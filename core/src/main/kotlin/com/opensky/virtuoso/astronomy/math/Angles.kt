package com.opensky.virtuoso.astronomy.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

const val TWO_PI = 2.0 * PI
const val DEG = PI / 180.0
const val RAD = 180.0 / PI
const val ARCSEC = DEG / 3600.0
const val ARCMIN = DEG / 60.0
/** Una hora de ascensión recta en radianes. */
const val HOUR = PI / 12.0

/** Utilidades de ángulos: normalización, formato y parseo sexagesimal. */
object Angles {

    /** Normaliza radianes a [0, 2π). */
    fun norm2Pi(a: Double): Double {
        val r = a % TWO_PI
        return if (r < 0) r + TWO_PI else r
    }

    /** Normaliza radianes a (-π, π]. */
    fun normPi(a: Double): Double {
        var r = norm2Pi(a)
        if (r > PI) r -= TWO_PI
        return r
    }

    /** Normaliza grados a [0, 360). */
    fun norm360(d: Double): Double {
        val r = d % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Normaliza grados a (-180, 180]. */
    fun norm180(d: Double): Double {
        var r = norm360(d)
        if (r > 180.0) r -= 360.0
        return r
    }

    /** Formatea horas decimales como "HHh MMm SS.Ss". */
    fun formatHms(hours: Double, secDecimals: Int = 1): String {
        val (h, m, s) = splitSexagesimal(norm360(hours * 15.0) / 15.0, secDecimals)
        val hh = h % 24
        return "%02dh %02dm %0${3 + secDecimals}.${secDecimals}fs".format(hh, m, s)
    }

    /** Formatea grados decimales con signo como "+DD° MM′ SS″". */
    fun formatDms(degrees: Double, secDecimals: Int = 0, signed: Boolean = true): String {
        val sign = if (degrees < 0) "-" else if (signed) "+" else ""
        val (d, m, s) = splitSexagesimal(abs(degrees), secDecimals)
        val width = if (secDecimals > 0) 3 + secDecimals else 2
        return "%s%02d° %02d′ %0${width}.${secDecimals}f″".format(sign, d, m, s)
    }

    /**
     * Divide un valor positivo en grados/horas, minutos y segundos redondeando los segundos
     * a [secDecimals] decimales y propagando el acarreo (evita "59.99 → 60").
     */
    fun splitSexagesimal(value: Double, secDecimals: Int): Triple<Int, Int, Double> {
        val scale = Math.pow(10.0, secDecimals.toDouble())
        val totalUnits = (abs(value) * 3600.0 * scale).roundToLong()
        val unitsPerMin = (60 * scale).roundToLong()
        val unitsPerDeg = (3600 * scale).roundToLong()
        val d = (totalUnits / unitsPerDeg).toInt()
        val m = ((totalUnits % unitsPerDeg) / unitsPerMin).toInt()
        val s = (totalUnits % unitsPerMin) / scale
        return Triple(d, m, s)
    }

    /**
     * Parsea texto sexagesimal flexible: "12 34 56.7", "12:34:56", "-05°23′10″", "12h30m", "45.5".
     * Devuelve el valor decimal (grados u horas según el contexto del llamador) o null.
     */
    fun parseSexagesimal(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val negative = t.startsWith("-") || t.startsWith("−")
        val parts = Regex("[0-9]+(?:[.,][0-9]+)?").findAll(t).map { it.value.replace(',', '.').toDouble() }.toList()
        if (parts.isEmpty() || parts.size > 3) return null
        val v = parts[0] + (parts.getOrElse(1) { 0.0 } / 60.0) + (parts.getOrElse(2) { 0.0 } / 3600.0)
        return if (negative) -v else v
    }

    /** Parte fraccionaria siempre positiva. */
    fun frac(x: Double): Double = x - floor(x)
}
