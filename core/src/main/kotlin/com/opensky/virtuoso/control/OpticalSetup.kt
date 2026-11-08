package com.opensky.virtuoso.control

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** Cómo se usa el telescopio: decide tolerancias, seguimiento y velocidades por defecto. */
enum class ObservingMode(val label: String, val shortLabel: String) {
    VISUAL("Ocular", "Ocular"),
    CAMERA_PRIME("Canon R50 + adaptador (foco primario)", "R50"),
    CAMERA_BARLOW("Canon R50 + Barlow 2×", "R50 + Barlow"),
}

/**
 * VISUAL: corrige la posición con energía (el ojo no nota los ajustes).
 * IMAGING: correcciones suaves y acotadas, sin reposicionar con GoTo durante una toma.
 */
enum class TrackingMode { VISUAL, IMAGING }

data class Eyepiece(val id: String, val name: String, val focalMm: Double, val apparentFovDeg: Double)

data class CameraSensor(val name: String, val widthMm: Double, val heightMm: Double, val widthPx: Int, val heightPx: Int) {
    val pixelUm: Double get() = widthMm / widthPx * 1000.0
    /** Distancia del centro a una esquina, en píxeles. */
    val halfDiagonalPx: Double get() = hypot(widthPx.toDouble(), heightPx.toDouble()) / 2
}

/** Equipo del usuario. */
object Equipment {
    /** Sky-Watcher Virtuoso GTi 150P: 150 mm de apertura, f/5. */
    const val TELESCOPE_FOCAL_MM = 750.0
    const val TELESCOPE_APERTURE_MM = 150.0

    /** Campo aparente según los fabricantes (Sky-Watcher Super MA: 52°; Celestron X-Cel LX: 60°). */
    val EYEPIECES = listOf(
        Eyepiece("sw-super-25", "Sky-Watcher Super 25 mm", 25.0, 52.0),
        Eyepiece("sw-super-10", "Sky-Watcher Super 10 mm", 10.0, 52.0),
        Eyepiece("xcel-lx-25", "Celestron X-Cel LX 25 mm", 25.0, 60.0),
    )

    /** Canon EOS R50: APS-C 22.3 × 14.9 mm, 6000 × 4000 px (3.72 µm). */
    val CANON_R50 = CameraSensor("Canon EOS R50", 22.3, 14.9, 6000, 4000)

    fun eyepiece(id: String?): Eyepiece = EYEPIECES.firstOrNull { it.id == id } ?: EYEPIECES.first()
}

/** Configuración óptica activa y todo lo que se deriva de ella. */
data class OpticalSetup(
    val mode: ObservingMode,
    val eyepiece: Eyepiece = Equipment.EYEPIECES.first(),
    /** Factor real del Barlow con la cámara (suele ser 2.2–2.5 en un «2×» por la distancia al sensor). */
    val barlowFactor: Double = 2.0,
    val camera: CameraSensor = Equipment.CANON_R50,
    val telescopeFocalMm: Double = Equipment.TELESCOPE_FOCAL_MM,
) {
    val usesCamera: Boolean get() = mode != ObservingMode.VISUAL

    val effectiveFocalMm: Double get() = if (mode == ObservingMode.CAMERA_BARLOW) telescopeFocalMm * barlowFactor else telescopeFocalMm
    val focalRatio: Double get() = effectiveFocalMm / Equipment.TELESCOPE_APERTURE_MM

    /** Aumentos (solo ocular). */
    val magnification: Double? get() = if (usesCamera) null else telescopeFocalMm / eyepiece.focalMm

    /** Campo real en minutos de arco (ancho × alto; en ocular es circular, ancho = alto). */
    val fieldWidthArcmin: Double
        get() = if (usesCamera) sensorAngleArcmin(camera.widthMm) else eyepiece.apparentFovDeg / magnification!! * 60.0
    val fieldHeightArcmin: Double
        get() = if (usesCamera) sensorAngleArcmin(camera.heightMm) else fieldWidthArcmin

    /** Escala de imagen en segundos de arco por píxel (solo cámara). */
    val arcsecPerPixel: Double? get() = if (usesCamera) 206.265 * camera.pixelUm / effectiveFocalMm else null

    /**
     * Radio de referencia para el «% de precisión» y la probabilidad de que el GoTo caiga en el
     * campo: medio campo del ocular, o media altura del sensor (el lado corto) con cámara.
     */
    val referenceRadiusArcmin: Double get() = minOf(fieldWidthArcmin, fieldHeightArcmin) / 2

    val trackingMode: TrackingMode get() = if (usesCamera) TrackingMode.IMAGING else TrackingMode.VISUAL

    /** Velocidad del pad por defecto (índice de [PAD_RATES]): 64× ocular, 32× foco primario, 16× con Barlow. */
    val defaultPadRateIndex: Int
        get() = when (mode) {
            ObservingMode.VISUAL -> 5
            ObservingMode.CAMERA_PRIME -> 4
            ObservingMode.CAMERA_BARLOW -> 3
        }

    /** Dónde centrar al alinear o hacer Sync. */
    val centeringHint: String
        get() = if (usesCamera) "en el centro de la pantalla de la cámara (amplía la vista en vivo)" else "en el centro del ocular"

    /** Resumen de una línea para la pantalla de Inicio. */
    val summary: String
        get() = if (usesCamera) {
            "%s · %.0f mm f/%.1f · campo %.0f′×%.0f′ · %.2f″/px".format(
                camera.name.removePrefix("Canon EOS "), effectiveFocalMm, focalRatio, fieldWidthArcmin, fieldHeightArcmin, arcsecPerPixel,
            )
        } else {
            "%s · %.0f× · campo %.0f′ (%.2f°)".format(eyepiece.name, magnification, fieldWidthArcmin, fieldWidthArcmin / 60)
        }

    private fun sensorAngleArcmin(mm: Double) = Math.toDegrees(mm / effectiveFocalMm) * 60.0
}

/**
 * Rotación de campo de una montura altazimutal: la imagen gira alrededor del centro a
 * ω = Ω⊕ · cos φ · cos A / cos h (A desde el norte). Limita la exposición de cada foto.
 */
object FieldRotation {
    /** Velocidad de rotación de la Tierra respecto a las estrellas (rad/s). */
    const val EARTH_RAD_PER_SEC = 7.292115855e-5

    fun rateRadPerSec(latitudeRad: Double, azimuthRad: Double, altitudeRad: Double): Double {
        val ch = cos(altitudeRad)
        return if (abs(ch) < 1e-6) Double.POSITIVE_INFINITY else EARTH_RAD_PER_SEC * cos(latitudeRad) * cos(azimuthRad) / ch
    }

    /**
     * Exposición máxima (s) para que las esquinas del sensor se desplacen como mucho [tolerancePx]
     * píxeles. No depende de la distancia focal: solo del tamaño del sensor en píxeles.
     * null con ocular; infinito donde la rotación es nula (justo al este u oeste).
     */
    fun maxExposureSec(setup: OpticalSetup, latitudeRad: Double, azimuthRad: Double, altitudeRad: Double, tolerancePx: Double = 1.0): Double? {
        if (!setup.usesCamera) return null
        val rate = abs(rateRadPerSec(latitudeRad, azimuthRad, altitudeRad))
        if (rate == 0.0) return Double.POSITIVE_INFINITY
        return tolerancePx / setup.camera.halfDiagonalPx / rate
    }

    fun format(seconds: Double?): String = when {
        seconds == null -> "—"
        seconds.isInfinite() || seconds > 600 -> "> 10 min"
        seconds >= 60 -> "%.0f min %02.0f s".format(Math.floor(seconds / 60), seconds % 60)
        seconds >= 10 -> "%.0f s".format(seconds)
        else -> "%.1f s".format(seconds)
    }
}
