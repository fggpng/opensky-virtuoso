package com.opensky.virtuoso.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.opensky.virtuoso.control.OffsetAstroClock
import com.opensky.virtuoso.network.time.SntpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

data class LocationFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double,
    val accuracyM: Float?,
    val timeMillis: Long,
    val provider: String?,
)

enum class TimeSource(val label: String) {
    UNVERIFIED("Reloj del sistema (sin verificar)"),
    NTP("NTP"),
    NETWORK_CLOCK("Hora de red del sistema"),
    GNSS("GNSS (satélites)"),
}

/** Estado de la sincronización horaria. [offsetMillis] se suma al reloj del sistema. */
data class TimeStatus(
    val source: TimeSource = TimeSource.UNVERIFIED,
    val offsetMillis: Long = 0,
    val uncertaintyMillis: Long? = null,
    val lastSyncMillis: Long? = null,
    val detail: String = "Hora aún no verificada",
) {
    val verified: Boolean get() = source != TimeSource.UNVERIFIED
}

/**
 * GPS (Fused Location Provider) + verificación horaria.
 *
 * Tiempo: 1) SNTP directo contra varios servidores (se elige la muestra con menor ida y vuelta);
 * 2) en Android 13+, relojes del sistema `currentNetworkTimeClock()` y `currentGnssTimeClock()`
 * como respaldo sin Internet (útil en el campo). Un error de 1 s ≈ 15″ en el tiempo sidéreo.
 */
class LocationTimeManager(context: Context, private val clock: OffsetAstroClock) {

    private val appContext = context.applicationContext
    private val fused = LocationServices.getFusedLocationProviderClient(appContext)

    private val _location = MutableStateFlow<LocationFix?>(null)
    val location: StateFlow<LocationFix?> = _location.asStateFlow()
    private val _time = MutableStateFlow(TimeStatus())
    val time: StateFlow<TimeStatus> = _time.asStateFlow()

    private var updatesActive = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { _location.value = it.toFix() }
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (updatesActive || !hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        updatesActive = true
    }

    fun stopLocationUpdates() {
        if (!updatesActive) return
        fused.removeLocationUpdates(callback)
        updatesActive = false
    }

    /** Fix puntual de alta precisión (null si no hay permiso o no se obtuvo). */
    @SuppressLint("MissingPermission")
    suspend fun requestFreshFix(): LocationFix? {
        if (!hasLocationPermission()) return null
        val cts = CancellationTokenSource()
        return runCatching { fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await() }
            .getOrNull()?.toFix()?.also { _location.value = it }
    }

    private fun Location.toFix() = LocationFix(
        latitudeDeg = latitude, longitudeDeg = longitude,
        altitudeM = if (hasAltitude()) altitude else 0.0,
        accuracyM = if (hasAccuracy()) accuracy else null,
        timeMillis = time, provider = provider,
    )

    /** Verifica la hora. Devuelve el estado final y ajusta el [OffsetAstroClock] compartido. */
    suspend fun syncTime(primaryServer: String): TimeStatus {
        val servers = listOf(primaryServer, "time.google.com", "pool.ntp.org", "time.cloudflare.com").distinct()
        val samples = mutableListOf<com.opensky.virtuoso.network.time.SntpResult>()
        val errors = mutableListOf<String>()
        val client = SntpClient()
        for (server in servers) {
            repeat(2) {
                runCatching { client.query(server, timeoutMs = 2500) }
                    .onSuccess { samples += it }
                    .onFailure { e -> errors += "$server: ${e.message}" }
            }
            if (samples.size >= 3) break
        }
        val best = samples.minByOrNull { it.roundTripMillis }
        val status = if (best != null) {
            TimeStatus(TimeSource.NTP, best.offsetMillis, best.roundTripMillis / 2, System.currentTimeMillis(),
                "NTP ${best.server} (estrato ${best.stratum}), desfase del reloj ${formatOffset(best.offsetMillis)}, ±${best.roundTripMillis / 2} ms")
        } else systemClockFallback(errors.firstOrNull())
        clock.offsetMillis = status.offsetMillis
        _time.value = status
        return status
    }

    private fun systemClockFallback(ntpError: String?): TimeStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val gnss = SystemClock.currentGnssTimeClock().millis()
                val offset = gnss - System.currentTimeMillis()
                return TimeStatus(TimeSource.GNSS, offset, 50, System.currentTimeMillis(),
                    "Hora GNSS del receptor (sin Internet), desfase ${formatOffset(offset)}")
            }
            runCatching {
                val net = SystemClock.currentNetworkTimeClock().millis()
                val offset = net - System.currentTimeMillis()
                return TimeStatus(TimeSource.NETWORK_CLOCK, offset, 250, System.currentTimeMillis(),
                    "Última hora de red del sistema, desfase ${formatOffset(offset)}")
            }
        }
        return TimeStatus(detail = "No se pudo verificar la hora (${ntpError ?: "sin red"}). Se usa el reloj del teléfono: " +
            "activa datos móviles o espera un fix GPS.")
    }

    companion object {
        fun formatOffset(ms: Long): String = if (abs(ms) < 1000) "%+d ms".format(ms) else "%+.2f s".format(ms / 1000.0)
    }
}
