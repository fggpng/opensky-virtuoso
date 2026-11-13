package com.opensky.virtuoso.control

import com.opensky.virtuoso.astronomy.math.Angles
import kotlin.math.abs

/** De dónde leyó el usuario el rumbo que escribe a mano. */
enum class HeadingReference(val label: String, val northName: String) {
    /** Brújula física o de teléfono sin corregir: marca el norte magnético. */
    MAGNETIC("Brújula", "norte magnético"),
    /** Apps de mapa del cielo (Star Walk, Stellarium…): dan el acimut respecto al norte verdadero. */
    TRUE("App de mapa del cielo", "norte verdadero"),
}

/**
 * Posición de inicio escrita a mano: el usuario apunta el tubo a donde quiera, lee su rumbo en una
 * brújula o en una app de mapa del cielo y lo escribe. Aquí está toda la lógica que no depende de
 * Android, para poder probarla: interpretar el texto, corregir la declinación y comparar con la
 * brújula del teléfono.
 */
object ManualHeading {

    /** Diferencia con la brújula del teléfono a partir de la cual se pide revisar antes de aceptar. */
    const val PHONE_DISAGREEMENT_LIMIT_DEG = 20.0

    /**
     * Interpreta el rumbo escrito: acepta coma o punto decimal y el símbolo «°». Devuelve un valor
     * en [0°, 360°) o null si no es un número entre 0 y 360.
     */
    fun parseAzimuth(text: String): Double? {
        val clean = text.trim().removeSuffix("°").trim().replace(',', '.')
        if (clean.isEmpty()) return null
        val v = clean.toDoubleOrNull() ?: return null
        if (v.isNaN() || v < 0.0 || v > 360.0) return null
        return if (v == 360.0) 0.0 else v
    }

    /**
     * Acimut verdadero del tubo. Con brújula hay que sumar la declinación magnética del lugar
     * (positiva al este); sin ella (no hay ubicación) no se puede convertir y devuelve null.
     */
    fun trueAzimuth(inputDeg: Double, reference: HeadingReference, declinationDeg: Double?): Double? = when (reference) {
        HeadingReference.TRUE -> Angles.norm360(inputDeg)
        HeadingReference.MAGNETIC -> declinationDeg?.let { Angles.norm360(inputDeg + it) }
    }

    /** Diferencia angular más corta entre dos rumbos, de 0° a 180°. */
    fun difference(aDeg: Double, bDeg: Double): Double {
        val d = abs(Angles.norm360(aDeg) - Angles.norm360(bDeg))
        return if (d > 180.0) 360.0 - d else d
    }

    /** Nombre del punto cardinal (8 rumbos) en palabras, para que el usuario confirme de un vistazo. */
    fun cardinalName(azDeg: Double): String {
        val names = listOf("norte", "noreste", "este", "sureste", "sur", "suroeste", "oeste", "noroeste")
        return names[((Angles.norm360(azDeg) + 22.5) / 45.0).toInt() % 8]
    }
}

/**
 * Resumen que se le muestra al usuario antes de aplicar una posición escrita a mano.
 * @property phoneAzDeg acimut verdadero que mide la brújula del teléfono si se apoyó en el tubo.
 */
data class ManualStartPreview(
    val inputAzDeg: Double,
    val reference: HeadingReference,
    val declinationDeg: Double?,
    val trueAzDeg: Double,
    val altDeg: Double,
    val altitudeMeasured: Boolean,
    val phoneAzDeg: Double? = null,
    val phoneInterference: Boolean = false,
) {
    /** Diferencia con la brújula del teléfono, si se midió. */
    val phoneDisagreementDeg: Double? get() = phoneAzDeg?.let { ManualHeading.difference(trueAzDeg, it) }

    /** ¿Conviene revisar antes de aceptar? (el teléfono marca algo muy distinto de lo escrito). */
    val suspicious: Boolean get() = (phoneDisagreementDeg ?: 0.0) > ManualHeading.PHONE_DISAGREEMENT_LIMIT_DEG

    /** Línea para el registro de diagnóstico. */
    fun logLine(): String = buildString {
        append("Inicio escrito a mano: %.1f° (%s)".format(inputAzDeg, reference.northName))
        declinationDeg?.takeIf { reference == HeadingReference.MAGNETIC }?.let { append(", declinación %+.1f°".format(it)) }
        append(" → Az verdadero %.1f°, Alt %.1f° (%s)".format(trueAzDeg, altDeg, if (altitudeMeasured) "medida con el teléfono" else "horizontal"))
        phoneAzDeg?.let { append("; brújula del teléfono %.1f° (diferencia %.0f°%s)".format(it, phoneDisagreementDeg, if (phoneInterference) ", con interferencia" else "")) }
    }
}
