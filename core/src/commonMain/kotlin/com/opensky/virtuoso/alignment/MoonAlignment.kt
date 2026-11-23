package com.opensky.virtuoso.alignment

import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.Weather
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystem
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystemBody
import com.opensky.virtuoso.database.targets.SolarSystemTarget
import com.opensky.virtuoso.database.targets.TargetResolver
import com.opensky.virtuoso.platform.fmt

/**
 * La Luna como primera referencia del modo «Luna + 1 estrella».
 * @property horizontal posición aparente (topocéntrica, con refracción) ahora; null si falta la ubicación.
 * @property unavailableReason null si se puede usar; si no, explicación para el usuario.
 */
data class MoonReference(
    val horizontal: HorizontalCoord?,
    val illuminatedPercent: Double?,
    val elongationDeg: Double?,
    val unavailableReason: String?,
) {
    val usable: Boolean get() = unavailableReason == null && horizontal != null

    companion object {
        fun unavailable(reason: String) = MoonReference(null, null, null, reason)
    }
}

/**
 * Reglas para usar la Luna como punto de alineación. Su posición sale de la teoría lunar de Meeus
 * (≈10″) con paralaje topocéntrica: el límite real es centrar a ojo un disco de medio grado, por eso
 * el segundo punto es una estrella lejana, que además fija la inclinación del trípode.
 */
object MoonAlignment {
    /** Por debajo, la refracción es incierta y el tubo puede tropezar con la base. */
    const val MIN_ALT_DEG = 10.0

    /** Cerca del cenit, en una montura altacimutal el acimut queda mal definido. */
    const val MAX_ALT_DEG = 80.0

    /** Más cerca del Sol la Luna no se ve (luna nueva) y apuntar ahí es peligroso. */
    const val MIN_ELONGATION_DEG = 15.0

    val target: SolarSystemTarget = SolarSystemTarget(SolarSystemBody.MOON)

    fun reference(time: AstroTime, location: GeoLocation, weather: Weather = Weather()): MoonReference {
        val pos = TargetResolver.position(target, time, location, weather)
        val elongation = SolarSystem.position(SolarSystemBody.MOON, time).elongationDeg ?: 180.0
        val alt = pos.horizontal.altDeg
        val reason = when {
            elongation < MIN_ELONGATION_DEG ->
                "Es luna nueva: la Luna está a %.0f° del Sol y no se ve. Usa otro modo de alineación.".fmt(elongation)
            alt < 0.0 -> "La Luna está bajo el horizonte (%.0f°). Usa otro modo o espera a que salga.".fmt(alt)
            alt < MIN_ALT_DEG -> "La Luna está muy baja (%.0f°): espera a que pase de %.0f° o usa otro modo.".fmt(alt, MIN_ALT_DEG)
            alt > MAX_ALT_DEG -> "La Luna está casi en el cenit (%.0f°): ahí el acimut no se puede medir bien. Usa otro modo.".fmt(alt)
            else -> null
        }
        return MoonReference(pos.horizontal, (pos.illuminatedFraction ?: 1.0) * 100.0, elongation, reason)
    }
}
