package com.opensky.virtuoso.astronomy.ephemeris

import com.opensky.virtuoso.astronomy.EarthOrientation
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.Mat3
import com.opensky.virtuoso.astronomy.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Constantes y utilidades de mecánica celeste (unidades: UA, días, radianes). */
object Kepler {
    /** Constante gaussiana de gravitación (rad/día). */
    const val GAUSS_K = 0.01720209895

    /** Velocidad de la luz en UA/día. */
    const val C_AU_PER_DAY = 173.1446326847

    /** Tiempo de luz por UA en días. */
    const val LIGHT_TIME_DAYS_PER_AU = 1.0 / C_AU_PER_DAY

    /** Eclíptica J2000 → ecuador J2000 (ε0 IAU 2006 = 84381.406″). */
    val ECLIPTIC_TO_EQUATORIAL: Mat3 = Mat3.rotX(EarthOrientation.meanObliquity(0.0))

    /** Resuelve E − e·sin E = M (elíptica) por Newton con arranque de Danby. */
    fun solveElliptic(meanAnomaly: Double, e: Double): Double {
        val m = Angles.normPi(meanAnomaly)
        var ecc = m + 0.85 * e * sign(sin(m))
        repeat(50) {
            val f = ecc - e * sin(ecc) - m
            val d = f / (1 - e * cos(ecc))
            ecc -= d
            if (abs(d) < 1e-14) return ecc
        }
        return ecc
    }

    /** Resuelve e·sinh H − H = M (hiperbólica). */
    fun solveHyperbolic(meanAnomaly: Double, e: Double): Double {
        val m = meanAnomaly
        var h = sign(m) * ln(2 * abs(m) / e + 1.8)
        repeat(80) {
            val f = e * sinh(h) - h - m
            val d = f / (e * cosh(h) - 1)
            h -= d
            if (abs(d) < 1e-14) return h
        }
        return h
    }

    /**
     * Posición en el plano orbital (x hacia el perihelio) para una órbita definida por la
     * distancia al perihelio [q] (UA), excentricidad [e] y tiempo desde el perihelio [dtDays].
     * Cubre órbitas elípticas, parabólicas e hiperbólicas.
     */
    fun orbitalPlanePosition(q: Double, e: Double, dtDays: Double): Pair<Double, Double> {
        return when {
            abs(e - 1.0) < 1e-6 -> { // Parabólica: ecuación de Barker
                val w = 3.0 * GAUSS_K * dtDays / (sqrt(2.0) * q * sqrt(q))
                val y = cbrt(w / 2 + sqrt(w * w / 4 + 1))
                val s = y - 1 / y
                val nu = 2 * kotlin.math.atan(s)
                val r = q * (1 + s * s)
                r * cos(nu) to r * sin(nu)
            }
            e < 1.0 -> {
                val a = q / (1 - e)
                val n = GAUSS_K / (a * sqrt(a))
                val bigE = solveElliptic(n * dtDays, e)
                a * (cos(bigE) - e) to a * sqrt(1 - e * e) * sin(bigE)
            }
            else -> {
                val a = q / (e - 1)
                val n = GAUSS_K / (a * sqrt(a))
                val h = solveHyperbolic(n * dtDays, e)
                a * (e - cosh(h)) to a * sqrt(e * e - 1) * sinh(h)
            }
        }
    }

    /**
     * Rota coordenadas del plano orbital al marco eclíptico J2000.
     * @param argPeri ω, @param node Ω, @param incl i (radianes).
     */
    fun orbitalToEcliptic(xp: Double, yp: Double, argPeri: Double, node: Double, incl: Double): Vec3 {
        val cw = cos(argPeri); val sw = sin(argPeri)
        val cn = cos(node); val sn = sin(node)
        val ci = cos(incl); val si = sin(incl)
        return Vec3(
            (cw * cn - sw * sn * ci) * xp + (-sw * cn - cw * sn * ci) * yp,
            (cw * sn + sw * cn * ci) * xp + (-sw * sn + cw * cn * ci) * yp,
            (sw * si) * xp + (cw * si) * yp,
        )
    }

    /** Arranque útil para pruebas: anomalía verdadera desde E. */
    fun trueAnomalyFromEccentric(bigE: Double, e: Double): Double =
        2 * kotlin.math.atan(sqrt((1 + e) / (1 - e)) * tan(bigE / 2))

    internal fun wrapDegrees(d: Double) = Angles.norm180(d)

    internal const val PI2 = 2 * PI
}
