package com.opensky.virtuoso.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.opensky.virtuoso.astronomy.math.Angles
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** Cómo se apoya el teléfono sobre el tubo del telescopio. */
enum class PhoneMounting(val label: String) {
    /** Teléfono plano sobre el tubo con el borde superior hacia la boca del tubo (eje +Y). */
    TOP_EDGE_ALONG_TUBE("Plano sobre el tubo, borde superior hacia la boca"),
    /** Cámara trasera mirando en la dirección del tubo (eje −Z). */
    BACK_CAMERA_ALONG_TUBE("Cámara trasera apuntando como el tubo"),
}

/**
 * Lectura de orientación.
 * @property trueAzimuthDeg acimut geográfico (magnético + declinación).
 * @property headingAccuracyDeg incertidumbre de rumbo estimada por el fusor (si el sensor la da).
 * @property interference campo magnético medido muy distinto del modelo WMM (metal/motores cerca).
 */
data class PhoneOrientation(
    val trueAzimuthDeg: Double,
    val altitudeDeg: Double,
    val magneticAzimuthDeg: Double,
    val declinationDeg: Double,
    val accuracy: Int,
    val headingAccuracyDeg: Double?,
    val fieldStrengthUt: Double?,
    val expectedFieldUt: Double?,
    val interference: Boolean,
) {
    val accuracyLabel: String get() = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "Alta"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Media"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Baja: calibra (figura en 8)"
        else -> "Sin fiabilidad: calibra (figura en 8)"
    }
}

/**
 * Motor de orientación: fusión de acelerómetro + giroscopio + magnetómetro mediante el sensor
 * virtual TYPE_ROTATION_VECTOR, con corrección de declinación magnética (modelo WMM de
 * [GeomagneticField]) y detección de interferencia magnética comparando |B| con el modelo.
 *
 * Precisión realista: altura ±0.5°; acimut ±2–10° (el tubo de acero y los motores desvían la
 * brújula). Por eso solo se usa como orientación INICIAL: la alineación por estrellas la corrige.
 */
class OrientationSensorEngine(context: Context) : SensorEventListener {

    private val sm = context.applicationContext.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val isAvailable: Boolean get() = rotationSensor != null

    private val _orientation = MutableStateFlow<PhoneOrientation?>(null)
    val orientation: StateFlow<PhoneOrientation?> = _orientation.asStateFlow()

    @Volatile var mounting: PhoneMounting = PhoneMounting.TOP_EDGE_ALONG_TUBE
    @Volatile private var declinationDeg = 0.0
    @Volatile private var expectedFieldUt: Double? = null
    @Volatile private var fieldStrengthUt: Double? = null
    @Volatile private var magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    private val rotation = FloatArray(9)
    // Vector de apuntado filtrado (Este, Norte, Arriba): promediar vectores evita el salto 359°→0°
    private var fe = 0.0; private var fn = 0.0; private var fu = 0.0
    private var initialized = false
    private var listeners = 0

    fun start() {
        if (listeners++ > 0) return
        rotationSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magneticSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        if (--listeners > 0) return
        listeners = 0
        sm.unregisterListener(this)
        initialized = false
    }

    /** Actualiza declinación y campo esperado con el modelo magnético mundial (WMM). */
    fun updateLocation(latitudeDeg: Double, longitudeDeg: Double, altitudeM: Double, timeMillis: Long = System.currentTimeMillis()) {
        val gf = GeomagneticField(latitudeDeg.toFloat(), longitudeDeg.toFloat(), altitudeM.toFloat(), timeMillis)
        declinationDeg = gf.declination.toDouble()
        expectedFieldUt = gf.fieldStrength / 1000.0 // nT → µT
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val (x, y, z) = Triple(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                fieldStrengthUt = sqrt(x * x + y * y + z * z)
                magAccuracy = event.accuracy
            }
            Sensor.TYPE_ROTATION_VECTOR -> handleRotation(event)
        }
    }

    private fun handleRotation(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Eje del teléfono que coincide con el tubo, en coordenadas del dispositivo
        val (dx, dy, dz) = when (mounting) {
            PhoneMounting.TOP_EDGE_ALONG_TUBE -> Triple(0.0, 1.0, 0.0)
            PhoneMounting.BACK_CAMERA_ALONG_TUBE -> Triple(0.0, 0.0, -1.0)
        }
        // Mundo = R · dispositivo, con mundo X=Este, Y=Norte magnético, Z=cenit
        val e = rotation[0] * dx + rotation[1] * dy + rotation[2] * dz
        val n = rotation[3] * dx + rotation[4] * dy + rotation[5] * dz
        val u = rotation[6] * dx + rotation[7] * dy + rotation[8] * dz
        val alpha = if (initialized) SMOOTHING else 1.0
        fe += alpha * (e - fe); fn += alpha * (n - fn); fu += alpha * (u - fu)
        initialized = true
        val norm = sqrt(fe * fe + fn * fn + fu * fu).coerceAtLeast(1e-9)
        val magAz = Angles.norm360(Math.toDegrees(atan2(fe, fn)))
        val alt = Math.toDegrees(asin((fu / norm).coerceIn(-1.0, 1.0)))
        val headingAcc = if (event.values.size > 4 && event.values[4] >= 0f) Math.toDegrees(event.values[4].toDouble()) else null
        val expected = expectedFieldUt
        val measured = fieldStrengthUt
        val interference = expected != null && measured != null && abs(measured - expected) / expected > 0.25
        _orientation.value = PhoneOrientation(
            trueAzimuthDeg = Angles.norm360(magAz + declinationDeg),
            altitudeDeg = alt,
            magneticAzimuthDeg = magAz,
            declinationDeg = declinationDeg,
            accuracy = minOf(event.accuracy, magAccuracy).takeIf { magneticSensor != null } ?: event.accuracy,
            headingAccuracyDeg = headingAcc,
            fieldStrengthUt = measured,
            expectedFieldUt = expected,
            interference = interference,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy
    }

    /**
     * Promedia la orientación durante [durationMs] (el teléfono debe estar quieto sobre el tubo).
     * Devuelve null si no hay lecturas.
     */
    suspend fun captureAverage(durationMs: Long = 2500): PhoneOrientation? {
        start()
        try {
            var se = 0.0; var sn = 0.0; var su = 0.0; var count = 0
            var last: PhoneOrientation? = null
            val end = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < end) {
                _orientation.value?.let { o ->
                    val az = Math.toRadians(o.trueAzimuthDeg); val alt = Math.toRadians(o.altitudeDeg)
                    se += kotlin.math.cos(alt) * kotlin.math.sin(az); sn += kotlin.math.cos(alt) * kotlin.math.cos(az); su += kotlin.math.sin(alt)
                    count++; last = o
                }
                delay(50)
            }
            val l = last ?: return null
            if (count == 0) return null
            val az = Angles.norm360(Math.toDegrees(atan2(se, sn)))
            val alt = Math.toDegrees(atan2(su, sqrt(se * se + sn * sn)))
            return l.copy(trueAzimuthDeg = az, altitudeDeg = alt, magneticAzimuthDeg = Angles.norm360(az - l.declinationDeg))
        } finally {
            stop()
        }
    }

    companion object {
        private const val SMOOTHING = 0.15
    }
}
