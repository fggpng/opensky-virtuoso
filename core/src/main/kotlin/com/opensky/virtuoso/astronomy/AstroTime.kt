package com.opensky.virtuoso.astronomy

import java.time.Instant
import kotlin.math.floor

/**
 * Instante astronómico con sus escalas de tiempo.
 *
 * - UTC: escala civil (la que entrega el reloj sincronizado por NTP/GNSS).
 * - UT1 = UTC + DUT1 (|DUT1| < 0.9 s; por defecto 0 → error máx. 13.5″ en AR).
 * - TT  = TAI + 32.184 s = UTC + (TAI−UTC) + 32.184 s (efemérides, precesión, nutación).
 *
 * @property jdUtc fecha juliana en UTC.
 */
class AstroTime private constructor(val jdUtc: Double, val dut1Seconds: Double) {

    /** Fecha juliana UT1 (rotación terrestre / tiempo sidéreo). */
    val jdUt1: Double get() = jdUtc + dut1Seconds / SECONDS_PER_DAY

    /** Fecha juliana TT (tiempo terrestre ≈ tiempo dinámico TD/TDB). */
    val jdTt: Double get() = jdUtc + (taiMinusUtc(jdUtc) + 32.184) / SECONDS_PER_DAY

    /** Siglos julianos TT desde J2000.0. */
    val centuriesTt: Double get() = (jdTt - J2000) / 36525.0

    /** Días TT desde J2000.0. */
    val daysTt: Double get() = jdTt - J2000

    /** Año juliano decimal (TT), útil para movimiento propio. */
    val julianYearTt: Double get() = 2000.0 + daysTt / 365.25

    val epochMillis: Long get() = Math.round((jdUtc - UNIX_EPOCH_JD) * MILLIS_PER_DAY)

    fun toInstant(): Instant = Instant.ofEpochMilli(epochMillis)

    fun plusSeconds(s: Double) = AstroTime(jdUtc + s / SECONDS_PER_DAY, dut1Seconds)

    override fun toString(): String = "AstroTime(${toInstant()}, JD_UTC=$jdUtc)"

    companion object {
        const val J2000 = 2451545.0
        const val SECONDS_PER_DAY = 86400.0
        const val MILLIS_PER_DAY = 86_400_000.0
        const val UNIX_EPOCH_JD = 2440587.5

        fun fromEpochMillis(ms: Long, dut1Seconds: Double = 0.0) =
            AstroTime(ms / MILLIS_PER_DAY + UNIX_EPOCH_JD, dut1Seconds)

        fun fromInstant(i: Instant, dut1Seconds: Double = 0.0) =
            AstroTime(i.epochSecond / SECONDS_PER_DAY + i.nano / 1e9 / SECONDS_PER_DAY + UNIX_EPOCH_JD, dut1Seconds)

        fun fromJdUtc(jd: Double, dut1Seconds: Double = 0.0) = AstroTime(jd, dut1Seconds)

        /** Construye a partir de una fecha juliana en TT (p. ej. ejemplos en "TD" de Meeus). */
        fun fromJdTt(jdTt: Double): AstroTime {
            var jdUtc = jdTt - 69.184 / SECONDS_PER_DAY
            repeat(2) { jdUtc = jdTt - (taiMinusUtc(jdUtc) + 32.184) / SECONDS_PER_DAY }
            return AstroTime(jdUtc, 0.0)
        }

        /** Fecha civil gregoriana UTC → AstroTime. */
        fun fromCalendarUtc(
            year: Int, month: Int, day: Int,
            hour: Int = 0, minute: Int = 0, second: Double = 0.0,
            dut1Seconds: Double = 0.0,
        ): AstroTime {
            val dayFrac = day + (hour + (minute + second / 60.0) / 60.0) / 24.0
            return AstroTime(julianDay(year, month, dayFrac), dut1Seconds)
        }

        /** Algoritmo de Meeus (7.1) para el calendario gregoriano. */
        fun julianDay(year: Int, month: Int, day: Double): Double {
            var y = year
            var m = month
            if (m <= 2) { y -= 1; m += 12 }
            val a = floor(y / 100.0)
            val b = 2 - a + floor(a / 4.0)
            return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
        }

        // Fechas (JD UTC) de entrada en vigor y valor de TAI−UTC (s). Sin segundos
        // intercalares nuevos desde 2017-01-01.
        private val LEAP_TABLE = doubleArrayOf(
            2441317.5, 10.0, 2441499.5, 11.0, 2441683.5, 12.0, 2442048.5, 13.0, 2442413.5, 14.0,
            2442778.5, 15.0, 2443144.5, 16.0, 2443509.5, 17.0, 2443874.5, 18.0, 2444239.5, 19.0,
            2444786.5, 20.0, 2445151.5, 21.0, 2445516.5, 22.0, 2446247.5, 23.0, 2447161.5, 24.0,
            2447892.5, 25.0, 2448257.5, 26.0, 2448804.5, 27.0, 2449169.5, 28.0, 2449534.5, 29.0,
            2450083.5, 30.0, 2450630.5, 31.0, 2451179.5, 32.0, 2453736.5, 33.0, 2454832.5, 34.0,
            2456109.5, 35.0, 2457204.5, 36.0, 2457754.5, 37.0,
        )

        /** TAI − UTC en segundos (antes de 1972 se aproxima con 10 s; suficiente para este uso). */
        fun taiMinusUtc(jdUtc: Double): Double {
            var value = 10.0
            var i = 0
            while (i < LEAP_TABLE.size && jdUtc >= LEAP_TABLE[i]) {
                value = LEAP_TABLE[i + 1]
                i += 2
            }
            return value
        }
    }
}
