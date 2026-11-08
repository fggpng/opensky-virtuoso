package com.opensky.virtuoso.astronomy.ephemeris

import com.opensky.virtuoso.astronomy.EarthOrientation
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.DEG
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Posición eclíptica geocéntrica de la Luna (eclíptica y equinoccio medios de la fecha). */
data class MoonEcliptic(val lambda: Double, val beta: Double, val distanceKm: Double)

/**
 * Teoría lunar de Meeus (Astronomical Algorithms, cap. 47; ELP-2000/82 truncada).
 * Precisión ≈ 10″ en longitud y 4″ en latitud: muy por debajo del tamaño aparente de la Luna.
 */
object Moon {

    // D, M, M', F, Σl (1e-6 °), Σr (1e-3 km) — Tabla 47.A
    private val LR = intArrayOf(
        0, 0, 1, 0, 6288774, -20905355, 2, 0, -1, 0, 1274027, -3699111,
        2, 0, 0, 0, 658314, -2955968, 0, 0, 2, 0, 213618, -569925,
        0, 1, 0, 0, -185116, 48888, 0, 0, 0, 2, -114332, -3149,
        2, 0, -2, 0, 58793, 246158, 2, -1, -1, 0, 57066, -152138,
        2, 0, 1, 0, 53322, -170733, 2, -1, 0, 0, 45758, -204586,
        0, 1, -1, 0, -40923, -129620, 1, 0, 0, 0, -34720, 108743,
        0, 1, 1, 0, -30383, 104755, 2, 0, 0, -2, 15327, 10321,
        0, 0, 1, 2, -12528, 0, 0, 0, 1, -2, 10980, 79661,
        4, 0, -1, 0, 10675, -34782, 0, 0, 3, 0, 10034, -23210,
        4, 0, -2, 0, 8548, -21636, 2, 1, -1, 0, -7888, 24208,
        2, 1, 0, 0, -6766, 30824, 1, 0, -1, 0, -5163, -8379,
        1, 1, 0, 0, 4987, -16675, 2, -1, 1, 0, 4036, -12831,
        2, 0, 2, 0, 3994, -10445, 4, 0, 0, 0, 3861, -11650,
        2, 0, -3, 0, 3665, 14403, 0, 1, -2, 0, -2689, -7003,
        2, 0, -1, 2, -2602, 0, 2, -1, -2, 0, 2390, 10056,
        1, 0, 1, 0, -2348, 6322, 2, -2, 0, 0, 2236, -9884,
        0, 1, 2, 0, -2120, 5751, 0, 2, 0, 0, -2069, 0,
        2, -2, -1, 0, 2048, -4950, 2, 0, 1, -2, -1773, 4130,
        2, 0, 0, 2, -1595, 0, 4, -1, -1, 0, 1215, -3958,
        0, 0, 2, 2, -1110, 0, 3, 0, -1, 0, -892, 3258,
        2, 1, 1, 0, -810, 2616, 4, -1, -2, 0, 759, -1897,
        0, 2, -1, 0, -713, -2117, 2, 2, -1, 0, -700, 2354,
        2, 1, -2, 0, 691, 0, 2, -1, 0, -2, 596, 0,
        4, 0, 1, 0, 549, -1423, 0, 0, 4, 0, 537, -1117,
        4, -1, 0, 0, 520, -1571, 1, 0, -2, 0, -487, -1739,
        2, 1, 0, -2, -399, 0, 0, 0, 2, -2, -381, -4421,
        1, 1, 1, 0, 351, 0, 3, 0, -2, 0, -340, 0,
        4, 0, -3, 0, 330, 0, 2, -1, 2, 0, 327, 0,
        0, 2, 1, 0, -323, 1165, 1, 1, -1, 0, 299, 0,
        2, 0, 3, 0, 294, 0, 2, 0, -1, -2, 0, 8752,
    )

    // D, M, M', F, Σb (1e-6 °) — Tabla 47.B
    private val B = intArrayOf(
        0, 0, 0, 1, 5128122, 0, 0, 1, 1, 280602, 0, 0, 1, -1, 277693, 2, 0, 0, -1, 173237,
        2, 0, -1, 1, 55413, 2, 0, -1, -1, 46271, 2, 0, 0, 1, 32573, 0, 0, 2, 1, 17198,
        2, 0, 1, -1, 9266, 0, 0, 2, -1, 8822, 2, -1, 0, -1, 8216, 2, 0, -2, -1, 4324,
        2, 0, 1, 1, 4200, 2, 1, 0, -1, -3359, 2, -1, -1, 1, 2463, 2, -1, 0, 1, 2211,
        2, -1, -1, -1, 2065, 0, 1, -1, -1, -1870, 4, 0, -1, -1, 1828, 0, 1, 0, 1, -1794,
        0, 0, 0, 3, -1749, 0, 1, -1, 1, -1565, 1, 0, 0, 1, -1491, 0, 1, 1, 1, -1475,
        0, 1, 1, -1, -1410, 0, 1, 0, -1, -1344, 1, 0, 0, -1, -1335, 0, 0, 3, 1, 1107,
        4, 0, 0, -1, 1021, 4, 0, -1, 1, 833, 0, 0, 1, -3, 777, 4, 0, -2, 1, 671,
        2, 0, 0, -3, 607, 2, 0, 2, -1, 596, 2, -1, 1, -1, 491, 2, 0, -2, 1, -451,
        0, 0, 3, -1, 439, 2, 0, 2, 1, 422, 2, 0, -3, -1, 421, 2, 1, -1, 1, -366,
        2, 1, 0, 1, -351, 4, 0, 0, 1, 331, 2, -1, 1, 1, 315, 2, -2, 0, -1, 302,
        0, 0, 1, 3, -283, 2, 1, 1, -1, -229, 1, 1, 0, -1, 223, 1, 1, 0, 1, 223,
        0, 1, -2, -1, -220, 2, 1, -1, -1, -220, 1, 0, 1, 1, -185, 2, -1, -2, -1, 181,
        0, 1, 2, 1, -177, 4, 0, -2, -1, 176, 4, -1, -1, -1, 166, 1, 0, 1, -1, -164,
        4, 0, 1, -1, 132, 1, 0, -1, -1, -119, 4, -1, 0, -1, 115, 2, -2, 0, 1, 107,
    )

    /** @param t siglos julianos TT desde J2000. */
    fun ecliptic(t: Double): MoonEcliptic {
        val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t
        val lp = Angles.norm360(218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0)
        val d = Angles.norm360(297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0) * DEG
        val m = Angles.norm360(357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0) * DEG
        val mp = Angles.norm360(134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0) * DEG
        val f = Angles.norm360(93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0) * DEG
        val a1 = Angles.norm360(119.75 + 131.849 * t) * DEG
        val a2 = Angles.norm360(53.09 + 479264.290 * t) * DEG
        val a3 = Angles.norm360(313.45 + 481266.484 * t) * DEG
        val e = 1 - 0.002516 * t - 0.0000074 * t2
        val lpr = lp * DEG

        var sl = 0.0; var sr = 0.0; var sb = 0.0
        var i = 0
        while (i < LR.size) {
            val cm = LR[i + 1]
            val arg = LR[i] * d + cm * m + LR[i + 2] * mp + LR[i + 3] * f
            val ef = when (abs(cm)) { 1 -> e; 2 -> e * e; else -> 1.0 }
            sl += LR[i + 4] * ef * sin(arg)
            sr += LR[i + 5] * ef * cos(arg)
            i += 6
        }
        i = 0
        while (i < B.size) {
            val cm = B[i + 1]
            val arg = B[i] * d + cm * m + B[i + 2] * mp + B[i + 3] * f
            val ef = when (abs(cm)) { 1 -> e; 2 -> e * e; else -> 1.0 }
            sb += B[i + 4] * ef * sin(arg)
            i += 5
        }
        sl += 3958 * sin(a1) + 1962 * sin(lpr - f) + 318 * sin(a2)
        sb += -2235 * sin(lpr) + 382 * sin(a3) + 175 * sin(a1 - f) + 175 * sin(a1 + f) +
            127 * sin(lpr - mp) - 115 * sin(lpr + mp)

        return MoonEcliptic(
            lambda = Angles.norm360(lp + sl / 1e6) * DEG,
            beta = (sb / 1e6) * DEG,
            distanceKm = 385000.56 + sr / 1000.0,
        )
    }

    /** Ascensión recta y declinación aparentes geocéntricas (ecuador verdadero de la fecha). */
    fun apparentEquatorial(t: Double): Pair<EquatorialCoord, Double> {
        val ecl = ecliptic(t)
        val nut = EarthOrientation.nutation(t)
        val eps = EarthOrientation.meanObliquity(t) + nut.deltaEpsilon
        val lam = ecl.lambda + nut.deltaPsi
        val ra = atan2(sin(lam) * cos(eps) - tan(ecl.beta) * sin(eps), cos(lam))
        val dec = asin(sin(ecl.beta) * cos(eps) + cos(ecl.beta) * sin(eps) * sin(lam))
        return EquatorialCoord(Angles.norm2Pi(ra), dec) to ecl.distanceKm
    }
}
