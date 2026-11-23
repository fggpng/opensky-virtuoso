package com.opensky.virtuoso.astronomy

import java.time.Instant

/** Conversión con java.time (solo JVM/Android). */
fun AstroTime.toInstant(): Instant = Instant.ofEpochMilli(epochMillis)

fun AstroTime.Companion.fromInstant(i: Instant, dut1Seconds: Double = 0.0): AstroTime =
    fromEpochSeconds(i.epochSecond, i.nano, dut1Seconds)
