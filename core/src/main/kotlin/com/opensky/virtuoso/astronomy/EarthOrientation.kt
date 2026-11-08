package com.opensky.virtuoso.astronomy

import com.opensky.virtuoso.astronomy.math.ARCSEC
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.astronomy.math.Mat3
import com.opensky.virtuoso.astronomy.math.TWO_PI
import kotlin.math.cos
import kotlin.math.sin

/** Nutación en longitud y oblicuidad (radianes). */
data class Nutation(val deltaPsi: Double, val deltaEpsilon: Double)

/**
 * Orientación de la Tierra: precesión (IAU 1976, Lieske), nutación (serie abreviada de
 * Meeus cap. 22, error < 0.5″), oblicuidad (IAU 2006) y tiempo sidéreo (ERA / IAU 2006).
 *
 * La precisión resultante (sub-segundo de arco) excede con creces la que puede aprovechar
 * una montura altacimutal con engranajes de tornillo sin fin.
 */
object EarthOrientation {

    /** Oblicuidad media de la eclíptica, IAU 2006 (radianes). t = siglos TT desde J2000. */
    fun meanObliquity(t: Double): Double {
        val arcsec = 84381.406 + t * (-46.836769 + t * (-0.0001831 + t * (0.00200340 + t * (-0.000000576 - t * 0.0000000434))))
        return arcsec * ARCSEC
    }

    fun nutation(t: Double): Nutation {
        val omega = (125.04452 - 1934.136261 * t + 0.0020708 * t * t + t * t * t / 450000.0) * DEG
        val lSun = (280.4665 + 36000.7698 * t) * DEG
        val lMoon = (218.3165 + 481267.8813 * t) * DEG
        val dPsi = -17.20 * sin(omega) - 1.32 * sin(2 * lSun) - 0.23 * sin(2 * lMoon) + 0.21 * sin(2 * omega)
        val dEps = 9.20 * cos(omega) + 0.57 * cos(2 * lSun) + 0.10 * cos(2 * lMoon) - 0.09 * cos(2 * omega)
        return Nutation(dPsi * ARCSEC, dEps * ARCSEC)
    }

    fun trueObliquity(t: Double): Double = meanObliquity(t) + nutation(t).deltaEpsilon

    /**
     * Matriz de precesión J2000 → ecuador/equinoccio medio de la fecha:
     * P = Rz(z) · Ry(−θ) · Rz(ζ) (rotaciones activas).
     */
    fun precessionMatrix(t: Double): Mat3 {
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * ARCSEC
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * ARCSEC
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * ARCSEC
        return Mat3.rotZ(z) * Mat3.rotY(-theta) * Mat3.rotZ(zeta)
    }

    /** Matriz de nutación: ecuador medio de la fecha → ecuador verdadero de la fecha. */
    fun nutationMatrix(t: Double): Mat3 {
        val eps = meanObliquity(t)
        val n = nutation(t)
        return Mat3.rotX(eps + n.deltaEpsilon) * Mat3.rotZ(n.deltaPsi) * Mat3.rotX(-eps)
    }

    /** J2000 (ICRS) → ecuador y equinoccio verdaderos de la fecha. */
    fun precessionNutationMatrix(t: Double): Mat3 = nutationMatrix(t) * precessionMatrix(t)

    /** Ángulo de rotación terrestre (ERA), radianes. */
    fun earthRotationAngle(jdUt1: Double): Double {
        val du = jdUt1 - AstroTime.J2000
        return Angles.norm2Pi(TWO_PI * (0.7790572732640 + 0.00273781191135448 * du + Angles.frac(du)))
    }

    /** Tiempo sidéreo medio de Greenwich (IAU 2006), radianes. */
    fun gmst(time: AstroTime): Double {
        val t = time.centuriesTt
        val poly = 0.014506 + t * (4612.156534 + t * (1.3915817 + t * (-0.00000044 + t * (-0.000029956 - t * 0.0000000368))))
        return Angles.norm2Pi(earthRotationAngle(time.jdUt1) + poly * ARCSEC)
    }

    /** Ecuación de los equinoccios (radianes). */
    fun equationOfEquinoxes(time: AstroTime): Double {
        val t = time.centuriesTt
        val n = nutation(t)
        val omega = (125.04452 - 1934.136261 * t) * DEG
        return n.deltaPsi * cos(meanObliquity(t)) + (0.00264 * sin(omega) + 0.000063 * sin(2 * omega)) * ARCSEC
    }

    /** Tiempo sidéreo aparente de Greenwich (GAST), radianes. */
    fun gast(time: AstroTime): Double = Angles.norm2Pi(gmst(time) + equationOfEquinoxes(time))

    /** Tiempo sidéreo aparente local (LAST), radianes. Longitud positiva al Este. */
    fun last(time: AstroTime, longitudeDeg: Double): Double = Angles.norm2Pi(gast(time) + longitudeDeg * DEG)
}
