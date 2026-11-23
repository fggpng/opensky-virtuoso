package com.opensky.virtuoso.astronomy.ephemeris

import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.astronomy.math.Vec3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

enum class SmallBodyType { COMET, ASTEROID }

/**
 * Elementos orbitales osculantes heliocéntricos (eclíptica y equinoccio J2000) en forma
 * canónica (q, e, i, Ω, ω, T). Cubre órbitas elípticas, parabólicas e hiperbólicas.
 *
 * @property perihelionJdTt instante del paso por el perihelio (JD TT).
 * @property absMag H (asteroides) o M1 (cometas). @property slope G (asteroides) o
 *  parámetro de pendiente del formato MPC de cometas (m = H + 5·log Δ + 2.5·G·log r).
 */
data class SmallBodyElements(
    val designation: String,
    val name: String,
    val type: SmallBodyType,
    val q: Double,
    val e: Double,
    val inclDeg: Double,
    val nodeDeg: Double,
    val argPeriDeg: Double,
    val perihelionJdTt: Double,
    val epochJdTt: Double,
    val absMag: Double?,
    val slope: Double?,
) {
    val displayName: String get() = name.ifBlank { designation }

    /** Posición heliocéntrica (UA) en el ecuador J2000 en el instante [jdTt]. */
    fun heliocentricEquatorial(jdTt: Double): Vec3 {
        val (xp, yp) = Kepler.orbitalPlanePosition(q, e, jdTt - perihelionJdTt)
        val ecl = Kepler.orbitalToEcliptic(xp, yp, argPeriDeg * DEG, nodeDeg * DEG, inclDeg * DEG)
        return Kepler.ECLIPTIC_TO_EQUATORIAL * ecl
    }

    /** Magnitud aparente aproximada dados r (Sol) y Δ (Tierra) en UA y el ángulo de fase (rad). */
    fun magnitude(r: Double, delta: Double, phaseAngle: Double): Double? {
        val h = absMag ?: return null
        return when (type) {
            SmallBodyType.COMET -> h + 5 * log10(delta) + 2.5 * (slope ?: 4.0) * log10(r)
            SmallBodyType.ASTEROID -> {
                val g = slope ?: 0.15
                val t = tan(phaseAngle / 2)
                val phi1 = exp(-3.33 * t.pow(0.63))
                val phi2 = exp(-1.87 * t.pow(1.22))
                h + 5 * log10(r * delta) - 2.5 * log10((1 - g) * phi1 + g * phi2)
            }
        }
    }

    companion object {
        /** Construye elementos canónicos a partir de a, e y anomalía media en la época (asteroides). */
        fun fromMeanAnomaly(
            designation: String, name: String, type: SmallBodyType,
            a: Double, e: Double, inclDeg: Double, nodeDeg: Double, argPeriDeg: Double,
            meanAnomalyDeg: Double, epochJdTt: Double, absMag: Double?, slope: Double?,
        ): SmallBodyElements {
            require(e < 1.0 && a > 0) { "Anomalía media solo válida para órbitas elípticas" }
            val nDegPerDay = Kepler.GAUSS_K / (a * sqrt(a)) / DEG
            val tp = epochJdTt - meanAnomalyDeg / nDegPerDay
            return SmallBodyElements(designation, name, type, a * (1 - e), e, inclDeg, nodeDeg, argPeriDeg, tp, epochJdTt, absMag, slope)
        }
    }
}

/**
 * Parsers de los formatos de una línea del Minor Planet Center:
 * - Cometas: "CometEls.txt" (https://www.minorplanetcenter.net/iau/MPCORB/CometEls.txt).
 * - Asteroides: formato MPCORB (MPCORB.DAT y sus subconjuntos).
 */
object MpcParser {

    const val COMET_ELEMENTS_URL = "https://www.minorplanetcenter.net/iau/MPCORB/CometEls.txt"

    private fun col(line: String, from: Int, to: Int): String =
        if (line.length < from) "" else line.substring(from - 1, minOf(to, line.length)).trim()

    private fun dbl(line: String, from: Int, to: Int): Double? = col(line, from, to).toDoubleOrNull()

    /** Una línea del formato de cometas del MPC; null si la línea no es válida. */
    fun parseCometLine(line: String): SmallBodyElements? = runCatching {
        if (line.length < 79) return null
        val year = col(line, 15, 18).toInt()
        val month = col(line, 20, 21).toInt()
        val day = col(line, 23, 29).toDouble()
        val q = dbl(line, 31, 39) ?: return null
        val e = dbl(line, 42, 49) ?: return null
        val w = dbl(line, 52, 59) ?: return null
        val node = dbl(line, 62, 69) ?: return null
        val incl = dbl(line, 72, 79) ?: return null
        val tp = AstroTime.julianDay(year, month, day)
        val epoch = runCatching {
            AstroTime.julianDay(col(line, 82, 85).toInt(), col(line, 86, 87).toInt(), col(line, 88, 89).toDouble())
        }.getOrDefault(tp)
        val number = col(line, 1, 4)
        val type = col(line, 5, 5)
        val packed = col(line, 6, 12)
        val name = col(line, 103, 158)
        val designation = when {
            name.isNotBlank() -> name.substringBefore(" (").ifBlank { name }
            number.isNotBlank() -> "${number.trimStart('0')}$type"
            else -> packed
        }
        SmallBodyElements(
            designation = designation, name = name, type = SmallBodyType.COMET,
            q = q, e = e, inclDeg = incl, nodeDeg = node, argPeriDeg = w,
            perihelionJdTt = tp, epochJdTt = epoch,
            absMag = dbl(line, 92, 95), slope = dbl(line, 97, 100),
        )
    }.getOrNull()

    /** Una línea del formato MPCORB (asteroides); null si no es válida. */
    fun parseAsteroidLine(line: String): SmallBodyElements? = runCatching {
        if (line.length < 103) return null
        val epoch = unpackEpoch(col(line, 21, 25)) ?: return null
        val m = dbl(line, 27, 35) ?: return null
        val w = dbl(line, 38, 46) ?: return null
        val node = dbl(line, 49, 57) ?: return null
        val incl = dbl(line, 60, 68) ?: return null
        val e = dbl(line, 71, 79) ?: return null
        val a = dbl(line, 93, 103) ?: return null
        val readable = col(line, 167, 194)
        SmallBodyElements.fromMeanAnomaly(
            designation = col(line, 1, 7), name = readable, type = SmallBodyType.ASTEROID,
            a = a, e = e, inclDeg = incl, nodeDeg = node, argPeriDeg = w,
            meanAnomalyDeg = m, epochJdTt = epoch,
            absMag = dbl(line, 9, 13), slope = dbl(line, 15, 19),
        )
    }.getOrNull()

    /** Época empaquetada del MPC (p. ej. "K25BL" = 2025-11-21.0 TT) → JD TT. */
    fun unpackEpoch(packed: String): Double? {
        if (packed.length != 5) return null
        val century = when (packed[0]) { 'I' -> 1800; 'J' -> 1900; 'K' -> 2000; 'L' -> 2100; else -> return null }
        val year = century + (packed.substring(1, 3).toIntOrNull() ?: return null)
        val month = unpackChar(packed[3]) ?: return null
        val day = unpackChar(packed[4]) ?: return null
        return AstroTime.julianDay(year, month, day.toDouble())
    }

    private fun unpackChar(c: Char): Int? = when (c) {
        in '1'..'9' -> c - '0'
        in 'A'..'V' -> c - 'A' + 10
        else -> null
    }

    /** Parsea un archivo completo detectando el formato línea por línea. */
    fun parseAny(lines: Sequence<String>): List<SmallBodyElements> = lines.mapNotNull { line ->
        if (line.isBlank()) null
        else if (looksLikeAsteroid(line)) parseAsteroidLine(line) else parseCometLine(line)
    }.toList()

    private fun looksLikeAsteroid(line: String): Boolean =
        line.length >= 103 && unpackEpoch(col(line, 21, 25)) != null && abs((dbl(line, 93, 103) ?: -1.0)) > 0
}
