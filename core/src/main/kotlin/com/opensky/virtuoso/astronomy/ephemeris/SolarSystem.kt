package com.opensky.virtuoso.astronomy.ephemeris

import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.EarthOrientation
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.math.RAD
import com.opensky.virtuoso.astronomy.math.Vec3
import kotlin.math.acos
import kotlin.math.log10

/** Cuerpos del Sistema Solar disponibles en el catálogo de objetivos. */
enum class SolarSystemBody(val displayName: String, val planet: Planet?) {
    SUN("Sol", null),
    MOON("Luna", null),
    MERCURY("Mercurio", Planet.MERCURY),
    VENUS("Venus", Planet.VENUS),
    MARS("Marte", Planet.MARS),
    JUPITER("Júpiter", Planet.JUPITER),
    SATURN("Saturno", Planet.SATURN),
    URANUS("Urano", Planet.URANUS),
    NEPTUNE("Neptuno", Planet.NEPTUNE),
    PLUTO("Plutón", Planet.PLUTO),
}

/**
 * Posición aparente geocéntrica (ecuador y equinoccio verdaderos de la fecha).
 * @property distanceAu distancia a la Tierra (necesaria para la paralaje topocéntrica).
 */
data class BodyPosition(
    val apparent: EquatorialCoord,
    val distanceAu: Double,
    val magnitude: Double?,
    /** Fracción iluminada (0..1). */
    val illuminatedFraction: Double?,
    /** Elongación respecto al Sol (grados). */
    val elongationDeg: Double?,
) {
    val distanceKm: Double get() = distanceAu * AU_KM

    companion object {
        const val AU_KM = 149_597_870.7
    }
}

/** Fachada de efemérides: Sol, Luna, planetas y cuerpos menores. */
object SolarSystem {

    fun position(body: SolarSystemBody, time: AstroTime): BodyPosition {
        val t = time.centuriesTt
        return when (body) {
            SolarSystemBody.SUN -> {
                val (eq, dist) = apparentFromHeliocentric({ Vec3.ZERO }, time)
                BodyPosition(eq, dist, -26.74, 1.0, 0.0)
            }
            SolarSystemBody.MOON -> {
                val (eq, km) = Moon.apparentEquatorial(t)
                val sun = position(SolarSystemBody.SUN, time)
                val elong = eq.separation(sun.apparent)
                // Ángulo de fase ≈ 180° − elongación (la Luna está muy cerca de la Tierra)
                val phase = Math.PI - elong
                val k = (1 + kotlin.math.cos(phase)) / 2
                val phaseDeg = phase * RAD
                val mag = -12.73 + 0.026 * kotlin.math.abs(phaseDeg) + 4e-9 * Math.pow(phaseDeg, 4.0)
                BodyPosition(eq, km / BodyPosition.AU_KM, mag, k, elong * RAD)
            }
            else -> {
                val planet = body.planet!!
                val (eq, delta) = apparentFromHeliocentric({ planet.heliocentricEquatorial(it) }, time)
                val r = planet.heliocentricEquatorial(t).norm
                val bigR = EarthState.position(t).norm
                val phase = phaseAngle(r, delta, bigR)
                val phaseDeg = phase * RAD
                val base = planet.absMag + 5 * log10(r * delta)
                val mag = when (planet) {
                    Planet.MERCURY -> base + 0.0380 * phaseDeg - 0.000273 * phaseDeg * phaseDeg + 0.000002 * phaseDeg * phaseDeg * phaseDeg
                    Planet.VENUS -> base + 0.0009 * phaseDeg + 0.000239 * phaseDeg * phaseDeg - 0.00000065 * phaseDeg * phaseDeg * phaseDeg
                    Planet.MARS -> base + 0.016 * phaseDeg
                    Planet.JUPITER -> base + 0.005 * phaseDeg
                    else -> base // Saturno sin término de anillos: aproximación ±0.5 mag
                }
                val sun = position(SolarSystemBody.SUN, time)
                BodyPosition(eq, delta, mag, (1 + kotlin.math.cos(phase)) / 2, eq.separation(sun.apparent) * RAD)
            }
        }
    }

    fun smallBodyPosition(el: SmallBodyElements, time: AstroTime): BodyPosition {
        val (eq, delta) = apparentFromHeliocentric({ t -> el.heliocentricEquatorial(AstroTime.J2000 + t * 36525.0) }, time)
        val t = time.centuriesTt
        val r = el.heliocentricEquatorial(time.jdTt).norm
        val bigR = EarthState.position(t).norm
        val phase = phaseAngle(r, delta, bigR)
        val sun = position(SolarSystemBody.SUN, time)
        return BodyPosition(eq, delta, el.magnitude(r, delta, phase), (1 + kotlin.math.cos(phase)) / 2, eq.separation(sun.apparent) * RAD)
    }

    private fun phaseAngle(r: Double, delta: Double, bigR: Double): Double =
        acos(((r * r + delta * delta - bigR * bigR) / (2 * r * delta)).coerceIn(-1.0, 1.0))

    /**
     * Posición aparente a partir de una función heliocéntrica (ecuador J2000, UA) dependiente
     * del tiempo en siglos TT. Incluye tiempo de luz, aberración anual, precesión y nutación.
     */
    fun apparentFromHeliocentric(helio: (Double) -> Vec3, time: AstroTime): Pair<EquatorialCoord, Double> {
        val t = time.centuriesTt
        val earth = EarthState.position(t)
        var geo = helio(t) - earth
        repeat(3) {
            val tau = geo.norm * Kepler.LIGHT_TIME_DAYS_PER_AU
            geo = helio(t - tau / 36525.0) - earth
        }
        val distance = geo.norm
        val aberrated = applyAberration(geo.normalized(), EarthState.velocity(t))
        val ofDate = EarthOrientation.precessionNutationMatrix(t) * aberrated
        return EquatorialCoord.fromVector(ofDate) to distance
    }

    /** Aberración anual (primer orden): p' = normalizar(p + v/c). */
    fun applyAberration(p: Vec3, earthVelocityAuPerDay: Vec3): Vec3 =
        (p + earthVelocityAuPerDay / Kepler.C_AU_PER_DAY).normalized()

    fun removeAberration(p: Vec3, earthVelocityAuPerDay: Vec3): Vec3 =
        (p - earthVelocityAuPerDay / Kepler.C_AU_PER_DAY).normalized()
}
