package com.opensky.virtuoso.platform

import kotlin.time.TimeSource

/** Reloj del sistema en ms desde 1970-01-01 UTC (equivale a System.currentTimeMillis()). */
expect fun epochMillisNow(): Long

/** Desfase de la zona horaria del teléfono respecto a UTC, en segundos, en el instante dado. */
expect fun systemUtcOffsetSeconds(epochMillis: Long): Int

private val monotonicOrigin = TimeSource.Monotonic.markNow()

/** Reloj monótono en ns (solo para medir intervalos; no avanza hacia atrás). */
fun monotonicNanos(): Long = monotonicOrigin.elapsedNow().inWholeNanoseconds

/** Fecha y hora civil (calendario gregoriano proléptico). */
data class CivilDateTime(
    val year: Int, val month: Int, val day: Int,
    val hour: Int, val minute: Int, val second: Int, val millis: Int,
) {
    /** "2026-11-08T05:00:00Z" (con milisegundos solo si no son cero, como java.time.Instant). */
    fun isoUtc(): String = buildString {
        append("%04d-%02d-%02dT%02d:%02d:%02d".fmt(year, month, day, hour, minute, second))
        if (millis != 0) append(".%03d".fmt(millis))
        append('Z')
    }

    val monthAbbrevEn: String get() = MONTHS_EN[month - 1]

    /** Inverso de [fromEpochMillis] (algoritmo days_from_civil de Howard Hinnant). */
    fun toEpochMillis(): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * ((month + 9) % 12) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era.toLong() * 146_097 + doe - 719_468
        return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1000L + millis
    }

    companion object {
        private val MONTHS_EN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** Desglosa [epochMillis] (ya desplazado a la zona deseada) en fecha y hora. */
        fun fromEpochMillis(epochMillis: Long): CivilDateTime {
            val days = floorDiv(epochMillis, 86_400_000L)
            val msOfDay = (epochMillis - days * 86_400_000L).toInt()
            // Algoritmo civil_from_days de Howard Hinnant.
            val z = days + 719_468
            val era = floorDiv(z, 146_097L)
            val doe = (z - era * 146_097).toInt()
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            return CivilDateTime(
                y.toInt(), m, d,
                msOfDay / 3_600_000, (msOfDay / 60_000) % 60, (msOfDay / 1000) % 60, msOfDay % 1000,
            )
        }

        /** Fecha local del teléfono (zona del sistema) para [epochMillis]. */
        fun local(epochMillis: Long): CivilDateTime =
            fromEpochMillis(epochMillis + systemUtcOffsetSeconds(epochMillis) * 1000L)

        private fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
        }
    }
}
