package com.opensky.virtuoso.astronomy.ephemeris

import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.astronomy.math.Vec3

/**
 * Planetas mayores con los elementos keplerianos aproximados de JPL
 * (E. M. Standish, "Keplerian Elements for Approximate Positions of the Major Planets",
 * Tabla 1, válida 1800–2050). Elementos y tasas por siglo, eclíptica y equinoccio J2000.
 *
 * Error típico: < 1′ para planetas interiores y Urano/Neptuno; hasta ~10′ para Júpiter y
 * Saturno — suficiente para un GoTo con ocular de campo amplio. Para mayor precisión se
 * puede sustituir por VSOP87 sin tocar el resto del motor.
 */
enum class Planet(
    val displayName: String,
    private val a0: Double, private val e0: Double, private val i0: Double,
    private val l0: Double, private val peri0: Double, private val node0: Double,
    private val aDot: Double, private val eDot: Double, private val iDot: Double,
    private val lDot: Double, private val periDot: Double, private val nodeDot: Double,
    /** Magnitud V(1,0) para la fórmula de magnitud aparente. */
    val absMag: Double,
) {
    MERCURY("Mercurio", 0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593,
        0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081, -0.42),
    VENUS("Venus", 0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255,
        0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418, -4.40),
    EARTH_MOON_BARY("Tierra-Luna", 1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0,
        0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0, 0.0),
    MARS("Marte", 1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891,
        0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343, -1.52),
    JUPITER("Júpiter", 5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909,
        -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106, -9.40),
    SATURN("Saturno", 9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448,
        -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794, -8.88),
    URANUS("Urano", 19.18916464, 0.04725744, 0.77263783, 313.23810451, 170.95427630, 74.01692503,
        -0.00196176, -0.00004397, -0.00242939, 428.48202785, 0.40805281, 0.04240589, -7.19),
    NEPTUNE("Neptuno", 30.06992276, 0.00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574,
        0.00026291, 0.00005105, 0.00035372, 218.45945325, -0.32241464, -0.00508664, -6.87),
    PLUTO("Plutón", 39.48211675, 0.24882730, 17.14001206, 238.92903833, 224.06891629, 110.30393684,
        -0.00031596, 0.00005170, 0.00004818, 145.20780515, -0.04062942, -0.01183482, -1.00);

    /**
     * Posición heliocéntrica en la eclíptica J2000 (UA).
     * @param t siglos julianos TT (≈TDB) desde J2000.
     */
    fun heliocentricEcliptic(t: Double): Vec3 {
        val a = a0 + aDot * t
        val e = e0 + eDot * t
        val incl = (i0 + iDot * t) * DEG
        val l = l0 + lDot * t
        val peri = peri0 + periDot * t
        val node = node0 + nodeDot * t
        val argPeri = (peri - node) * DEG
        val meanAnomaly = Kepler.wrapDegrees(l - peri) * DEG
        val bigE = Kepler.solveElliptic(meanAnomaly, e)
        val xp = a * (kotlin.math.cos(bigE) - e)
        val yp = a * kotlin.math.sqrt(1 - e * e) * kotlin.math.sin(bigE)
        return Kepler.orbitalToEcliptic(xp, yp, argPeri, node * DEG, incl)
    }

    /** Posición heliocéntrica en el ecuador J2000 (UA). */
    fun heliocentricEquatorial(t: Double): Vec3 = Kepler.ECLIPTIC_TO_EQUATORIAL * heliocentricEcliptic(t)
}

/** Estado de la Tierra (baricentro Tierra-Luna) en el ecuador J2000. */
object EarthState {

    /** Posición heliocéntrica (UA) del baricentro Tierra-Luna, ecuador J2000. */
    fun position(t: Double): Vec3 = Planet.EARTH_MOON_BARY.heliocentricEquatorial(t)

    /** Velocidad (UA/día) por diferencia central de ±0.5 días. */
    fun velocity(t: Double): Vec3 {
        val dt = 0.5 / 36525.0
        return (position(t + dt) - position(t - dt)) / 1.0
    }
}
