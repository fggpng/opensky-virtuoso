package com.opensky.virtuoso.sensors

import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.astronomy.math.RAD
import com.opensky.virtuoso.platform.PlatformLock
import com.opensky.virtuoso.platform.epochMillisNow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Calidad de la brújula (magnetómetro calibrado). */
enum class CompassAccuracy(val label: String) {
    HIGH("Alta"),
    MEDIUM("Media"),
    LOW("Baja: calibra (figura en 8)"),
    UNRELIABLE("Sin fiabilidad: calibra (figura en 8)"),
}

/**
 * Hacia dónde apunta el tubo según el teléfono apoyado en él.
 * @property interference el campo magnético medido no es el de la Tierra (metal o motores cerca).
 */
data class TubeOrientation(
    val trueAzimuthDeg: Double,
    val altitudeDeg: Double,
    val magneticAzimuthDeg: Double,
    val declinationDeg: Double,
    val accuracy: CompassAccuracy,
    val fieldStrengthUt: Double?,
    val interference: Boolean,
) {
    val accuracyLabel: String get() = accuracy.label
}

/**
 * Orientación del tubo a partir de la gravedad y el campo magnético en ejes del teléfono (los da
 * CoreMotion en iOS; X a la derecha, Y hacia el borde superior, Z saliendo de la pantalla, igual
 * que en Android). Es la misma cuenta que SensorManager.getRotationMatrix de Android:
 * Este = B × Arriba, Norte = Arriba × Este.
 *
 * Precisión realista: altura ±0.5°; acimut ±2–10° (el tubo de acero desvía la brújula). Por eso
 * solo sirve como orientación INICIAL: la alineación con estrellas la corrige.
 */
class TubeOrientationEngine {

    private val lock = PlatformLock()
    private val _orientation = MutableStateFlow<TubeOrientation?>(null)
    val orientation: StateFlow<TubeOrientation?> = _orientation.asStateFlow()

    @Volatile var mounting: PhoneMounting = PhoneMounting.TOP_EDGE_ALONG_TUBE
    /** Declinación magnética del sitio (°, + al este); la da iOS (rumbo verdadero − magnético). */
    @Volatile var declinationDeg: Double = 0.0
    @Volatile var declinationKnown: Boolean = false

    private var fe = 0.0
    private var fn = 0.0
    private var fu = 0.0
    private var initialized = false

    /** Vuelve a empezar el filtrado (al abrir la pantalla de sensores). */
    fun reset() = lock.withLock { initialized = false }

    /**
     * Nueva muestra: [gx],[gy],[gz] gravedad (hacia el suelo, en g) y [mx],[my],[mz] campo
     * magnético calibrado (µT), ambos en ejes del teléfono.
     */
    fun onSample(gx: Double, gy: Double, gz: Double, mx: Double, my: Double, mz: Double, accuracy: CompassAccuracy) {
        val result = compute(gx, gy, gz, mx, my, mz, mounting) ?: return
        lock.withLock {
            val (e, n, u) = result
            val alpha = if (initialized) SMOOTHING else 1.0
            fe += alpha * (e - fe); fn += alpha * (n - fn); fu += alpha * (u - fu)
            initialized = true
            val norm = sqrt(fe * fe + fn * fn + fu * fu).coerceAtLeast(1e-9)
            val magAz = Angles.norm360(atan2(fe, fn) * RAD)
            val alt = asin((fu / norm).coerceIn(-1.0, 1.0)) * RAD
            val field = sqrt(mx * mx + my * my + mz * mz)
            _orientation.value = TubeOrientation(
                trueAzimuthDeg = Angles.norm360(magAz + declinationDeg),
                altitudeDeg = alt,
                magneticAzimuthDeg = magAz,
                declinationDeg = declinationDeg,
                accuracy = accuracy,
                fieldStrengthUt = field,
                interference = field < EARTH_FIELD_MIN_UT || field > EARTH_FIELD_MAX_UT,
            )
        }
    }

    /**
     * Promedia la orientación durante [durationMs] (el teléfono quieto sobre el tubo). Promedia
     * vectores, no ángulos, para que 359° y 1° den 0° y no 180°. Null si no hubo lecturas.
     */
    suspend fun captureAverage(durationMs: Long = 2500): TubeOrientation? {
        var se = 0.0; var sn = 0.0; var su = 0.0; var count = 0
        var last: TubeOrientation? = null
        val end = epochMillisNow() + durationMs
        while (epochMillisNow() < end) {
            _orientation.value?.let { o ->
                val az = o.trueAzimuthDeg * DEG
                val alt = o.altitudeDeg * DEG
                se += cos(alt) * sin(az); sn += cos(alt) * cos(az); su += sin(alt)
                count++; last = o
            }
            delay(50)
        }
        val l = last ?: return null
        if (count == 0) return null
        val az = Angles.norm360(atan2(se, sn) * RAD)
        val alt = atan2(su, sqrt(se * se + sn * sn)) * RAD
        return l.copy(trueAzimuthDeg = az, altitudeDeg = alt, magneticAzimuthDeg = Angles.norm360(az - l.declinationDeg))
    }

    companion object {
        private const val SMOOTHING = 0.15
        /** Intensidad del campo terrestre en superficie: ~22 µT (Atlántico Sur) a ~67 µT (polos). */
        const val EARTH_FIELD_MIN_UT = 20.0
        const val EARTH_FIELD_MAX_UT = 70.0

        /**
         * Componentes (Este, Norte magnético, Arriba) del eje del teléfono que va a lo largo del tubo.
         * Null si la muestra no sirve (caída libre o campo paralelo a la vertical).
         */
        fun compute(gx: Double, gy: Double, gz: Double, mx: Double, my: Double, mz: Double, mounting: PhoneMounting): Triple<Double, Double, Double>? {
            // Arriba = −gravedad
            var ux = -gx; var uy = -gy; var uz = -gz
            val un = sqrt(ux * ux + uy * uy + uz * uz)
            if (un < 0.1) return null
            ux /= un; uy /= un; uz /= un
            // Este = B × Arriba
            var ex = my * uz - mz * uy
            var ey = mz * ux - mx * uz
            var ez = mx * uy - my * ux
            val en = sqrt(ex * ex + ey * ey + ez * ez)
            if (en < 0.1) return null
            ex /= en; ey /= en; ez /= en
            // Norte = Arriba × Este
            val nx = uy * ez - uz * ey
            val ny = uz * ex - ux * ez
            val nz = ux * ey - uy * ex
            val (dx, dy, dz) = when (mounting) {
                PhoneMounting.TOP_EDGE_ALONG_TUBE -> Triple(0.0, 1.0, 0.0)
                PhoneMounting.BACK_CAMERA_ALONG_TUBE -> Triple(0.0, 0.0, -1.0)
            }
            return Triple(ex * dx + ey * dy + ez * dz, nx * dx + ny * dy + nz * dz, ux * dx + uy * dy + uz * dz)
        }
    }
}
