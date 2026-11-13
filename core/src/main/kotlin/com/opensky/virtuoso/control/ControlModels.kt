package com.opensky.virtuoso.control

import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.Weather
import com.opensky.virtuoso.database.targets.Target
import com.opensky.virtuoso.network.LinkState
import com.opensky.virtuoso.network.protocol.MountInfo

/** Fuente de tiempo UTC corregida (NTP/GNSS). Nunca usar el reloj del sistema directamente. */
fun interface AstroClock {
    fun now(): AstroTime
}

/** Reloj del sistema + desfase medido contra NTP/GNSS (ms). */
class OffsetAstroClock(@Volatile var offsetMillis: Long = 0L, @Volatile var dut1Seconds: Double = 0.0) : AstroClock {
    override fun now(): AstroTime = AstroTime.fromEpochMillis(System.currentTimeMillis() + offsetMillis, dut1Seconds)
}

/** Lugar de observación y condiciones atmosféricas. */
data class Site(val location: GeoLocation, val weather: Weather = Weather())

/** Velocidad de seguimiento. EPHEMERIS: posición recalculada con efemérides (Luna, planetas, cometas). */
enum class TrackingRate(val label: String, val raDriftArcsecPerSec: Double) {
    SIDEREAL("Sidérea", 0.0),
    LUNAR("Lunar", 0.549),
    SOLAR("Solar", 0.0411),
    EPHEMERIS("Efemérides", 0.0),
}

enum class PadMode { ALT_AZ, RA_DEC }

/** Direcciones de la cruceta. En RA_DEC: UP=Norte, DOWN=Sur, LEFT=Este, RIGHT=Oeste. */
enum class PadDirection { UP, DOWN, LEFT, RIGHT }

enum class Activity { DISCONNECTED, IDLE, SLEWING, TRACKING, MANUAL }

data class ControllerConfig(
    val minAltitudeDeg: Double = 0.0,
    val maxAltitudeDeg: Double = 89.0,
    val invertAzimuth: Boolean = false,
    val invertAltitude: Boolean = false,
    /** Distancia mínima al Sol para un GoTo sin confirmación explícita. */
    val sunAvoidanceDeg: Double = 15.0,
    val trackingUpdateMs: Long = 1000,
    val pollIntervalMs: Long = 500,
    val gotoSpeedDegPerSec: Double = 3.5,
    val maxRateDegPerSec: Double = 3.5,
    /** Segunda pasada corta para compensar el movimiento del cielo durante el GoTo. */
    val refineGoto: Boolean = true,
    /** VISUAL (ocular) o IMAGING (cámara): ver [TrackingMode]. */
    val trackingMode: TrackingMode = TrackingMode.VISUAL,
    /** Hombre muerto: un movimiento manual continuo se detiene solo pasado este tiempo. */
    val maxManualMs: Long = 30_000,
    /** Ventana para detectar un motor que recibe órdenes pero no avanza (atasco, baterías). */
    val stallWindowMs: Long = 2_500,
)

/**
 * De dónde sale la posición de partida de los encoders. La montura solo cuenta pasos desde que
 * se enciende: si nadie confirma dónde estaba el tubo, el primer GoTo puede ir a cualquier lado.
 */
enum class StartPosition(val label: String) {
    UNCONFIRMED("Sin confirmar"),
    HOME("Horizontal hacia el norte (confirmado)"),
    PHONE("Medida con el teléfono sobre el tubo"),
    MANUAL("Escrita a mano (brújula o app de cielo)"),
}

/** Resultado de comparar la altura que cree la app con la que mide el teléfono sobre el tubo. */
data class AltitudeCheck(val believedDeg: Double, val measuredDeg: Double, val toleranceDeg: Double) {
    val differenceDeg: Double get() = measuredDeg - believedDeg
    val ok: Boolean get() = kotlin.math.abs(differenceDeg) <= toleranceDeg
}

data class MountState(
    val link: LinkState = LinkState.DISCONNECTED,
    /** Poka-yoke: sin posición de partida confirmada ni estrellas de alineación, no se permite GoTo. */
    val startPosition: StartPosition = StartPosition.UNCONFIRMED,
    val info: MountInfo? = null,
    val activity: Activity = Activity.DISCONNECTED,
    val encoderAzDeg: Double? = null,
    val encoderAltDeg: Double? = null,
    /** Dirección aparente (con refracción) del tubo según el modelo de alineación. */
    val pointing: HorizontalCoord? = null,
    val pointingApparent: EquatorialCoord? = null,
    val pointingJ2000: EquatorialCoord? = null,
    val target: Target? = null,
    val tracking: TrackingRate? = null,
    val message: String? = null,
    val lastError: String? = null,
    /** Alarma que el usuario debe leer y descartar (montura reiniciada, motor atascado, parada no confirmada…). */
    val alert: String? = null,
)

sealed class GotoResult {
    data class Success(val residualArcmin: Double) : GotoResult()
    /** Rechazado; si [needsConfirmation] el usuario puede reintentar aceptando el riesgo. */
    data class Rejected(val reason: String, val needsConfirmation: Boolean = false) : GotoResult()
    data class Failed(val error: String) : GotoResult()
}

/** Velocidades del pad manual (múltiplos de la sidérea), como el mando SynScan. */
val PAD_RATES: DoubleArray = doubleArrayOf(1.0, 2.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 400.0, 800.0)
