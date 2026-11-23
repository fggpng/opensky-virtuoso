package com.opensky.virtuoso.platform

/*
 * Formato estilo printf (el subconjunto de java.util.Formatter que usa la app), común a Android e
 * iOS. Siempre con punto decimal y coma de miles, sea cual sea el idioma del teléfono: así los
 * protocolos (LX200) y las pruebas no dependen de la configuración regional.
 *
 * Soporta %d %s %c %x %X %f %% %n con banderas «-», «+», «0», « » y «,», ancho y precisión.
 * El redondeo de %f es el de Java: HALF_UP sobre la representación decimal más corta del Double
 * (por eso "%.1f" de 0.15 da "0.2", igual que en la versión de Android anterior).
 */

/** Equivalente común de `String.format`: `"%.1f°".fmt(x)`. */
fun String.fmt(vararg args: Any?): String = TextFormat.format(this, args)

internal object TextFormat {

    fun format(pattern: String, args: Array<out Any?>): String {
        val out = StringBuilder(pattern.length + 16)
        var next = 0
        var i = 0
        val n = pattern.length
        while (i < n) {
            val c = pattern[i]
            if (c != '%') {
                out.append(c); i++; continue
            }
            i++
            require(i < n) { "Formato incompleto: «$pattern»" }
            var left = false; var plus = false; var zero = false; var space = false; var group = false
            flags@ while (i < n) {
                when (pattern[i]) {
                    '-' -> left = true
                    '+' -> plus = true
                    '0' -> zero = true
                    ' ' -> space = true
                    ',' -> group = true
                    else -> break@flags
                }
                i++
            }
            var width = -1
            while (i < n && pattern[i] in '0'..'9') {
                width = (if (width < 0) 0 else width) * 10 + (pattern[i] - '0'); i++
            }
            var precision = -1
            if (i < n && pattern[i] == '.') {
                i++; precision = 0
                while (i < n && pattern[i] in '0'..'9') { precision = precision * 10 + (pattern[i] - '0'); i++ }
            }
            require(i < n) { "Formato incompleto: «$pattern»" }
            val conv = pattern[i++]
            val spec = Spec(left, plus, zero, space, group, width, precision)
            when (conv) {
                '%' -> out.append(pad("%", spec, allowZero = false))
                'n' -> out.append('\n')
                else -> {
                    require(next < args.size) { "Faltan argumentos para «$pattern»" }
                    val arg = args[next++]
                    out.append(
                        when (conv) {
                            'd' -> integer(arg, spec)
                            'x', 'X' -> hex(arg, spec, upper = conv == 'X')
                            'f' -> fixed(arg, spec)
                            's', 'S' -> {
                                val s = arg.toString().let { if (precision >= 0 && it.length > precision) it.take(precision) else it }
                                pad(if (conv == 'S') s.uppercase() else s, spec, allowZero = false)
                            }
                            'c' -> pad(
                                when (arg) {
                                    is Char -> arg.toString()
                                    is Int -> arg.toChar().toString()
                                    null -> "null"
                                    else -> throw IllegalArgumentException("%c requiere un carácter, no ${arg::class.simpleName}")
                                },
                                spec, allowZero = false,
                            )
                            else -> throw IllegalArgumentException("Conversión no soportada: %$conv en «$pattern»")
                        },
                    )
                }
            }
        }
        return out.toString()
    }

    private class Spec(
        val left: Boolean, val plus: Boolean, val zero: Boolean, val space: Boolean, val group: Boolean,
        val width: Int, val precision: Int,
    )

    private fun pad(s: String, spec: Spec, allowZero: Boolean): String {
        if (spec.width <= s.length) return s
        val fill = spec.width - s.length
        return when {
            spec.left -> s + " ".repeat(fill)
            allowZero && spec.zero -> {
                // Los ceros van entre el signo y las cifras: "%05d" de -42 → "-0042".
                val signLen = if (s.isNotEmpty() && (s[0] == '-' || s[0] == '+' || s[0] == ' ')) 1 else 0
                s.substring(0, signLen) + "0".repeat(fill) + s.substring(signLen)
            }
            else -> " ".repeat(fill) + s
        }
    }

    private fun signed(negative: Boolean, magnitude: String, spec: Spec): String = when {
        negative -> "-$magnitude"
        spec.plus -> "+$magnitude"
        spec.space -> " $magnitude"
        else -> magnitude
    }

    private fun grouped(digits: String): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder(digits.length + digits.length / 3)
        val first = digits.length % 3
        if (first > 0) sb.append(digits, 0, first)
        var k = first
        while (k < digits.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(digits, k, k + 3)
            k += 3
        }
        return sb.toString()
    }

    private fun integer(arg: Any?, spec: Spec): String {
        val v: Long = when (arg) {
            is Int -> arg.toLong()
            is Long -> arg
            is Short -> arg.toLong()
            is Byte -> arg.toLong()
            null -> return pad("null", spec, allowZero = false)
            else -> throw IllegalArgumentException("%d requiere un entero, no ${arg::class.simpleName}")
        }
        val negative = v < 0
        // Long.MIN_VALUE no tiene opuesto: su texto ya trae el signo.
        var digits = if (v == Long.MIN_VALUE) "9223372036854775808" else (if (negative) -v else v).toString()
        if (spec.group) digits = grouped(digits)
        return pad(signed(negative, digits, spec), spec, allowZero = true)
    }

    private fun hex(arg: Any?, spec: Spec, upper: Boolean): String {
        val s = when (arg) {
            is Int -> arg.toUInt().toString(16)
            is Long -> arg.toULong().toString(16)
            is Short -> arg.toUShort().toString(16)
            is Byte -> arg.toUByte().toString(16)
            null -> return pad("null", spec, allowZero = false)
            else -> throw IllegalArgumentException("%x requiere un entero, no ${arg::class.simpleName}")
        }
        return pad(if (upper) s.uppercase() else s, spec, allowZero = true)
    }

    private fun fixed(arg: Any?, spec: Spec): String {
        val v: Double = when (arg) {
            is Double -> arg
            is Float -> arg.toDouble()
            null -> return pad("null", spec, allowZero = false)
            else -> throw IllegalArgumentException("%f requiere un número decimal, no ${arg::class.simpleName}")
        }
        val precision = if (spec.precision < 0) 6 else spec.precision
        if (v.isNaN()) return pad("NaN", spec, allowZero = false)
        val negative = v < 0.0 || (v == 0.0 && 1.0 / v < 0.0)
        if (v.isInfinite()) return pad(signed(negative, "Infinity", spec), spec, allowZero = false)
        var body = fixedMagnitude(if (negative) -v else v, precision)
        if (spec.group) {
            val dot = body.indexOf('.')
            body = if (dot < 0) grouped(body) else grouped(body.substring(0, dot)) + body.substring(dot)
        }
        return pad(signed(negative, body, spec), spec, allowZero = true)
    }

    /**
     * |v| con [precision] decimales. Parte de las cifras de la representación más corta
     * (`toString`), como hace el Formatter de Java, y redondea HALF_UP.
     */
    internal fun fixedMagnitude(v: Double, precision: Int): String {
        val text = v.toString()
        val ePos = text.indexOfFirst { it == 'E' || it == 'e' }
        val mantissa = if (ePos >= 0) text.substring(0, ePos) else text
        val exponent = if (ePos >= 0) text.substring(ePos + 1).toInt() else 0
        val dot = mantissa.indexOf('.')
        val intPart = if (dot >= 0) mantissa.substring(0, dot) else mantissa
        val fracPart = if (dot >= 0) mantissa.substring(dot + 1) else ""
        var digits = (intPart + fracPart)
        // Valor = 0.d1d2d3… × 10^point
        var point = intPart.length + exponent
        val lead = digits.indexOfFirst { it != '0' }
        if (lead < 0) {
            digits = ""
            point = 0
        } else {
            digits = digits.substring(lead).trimEnd('0')
            point -= lead
        }
        val chars = digits.toCharArray().toMutableList()
        val keep = point + precision
        if (chars.isNotEmpty() && keep < chars.size) {
            if (keep < 0) {
                chars.clear()
            } else {
                val roundUp = chars[keep] >= '5'
                while (chars.size > keep) chars.removeAt(chars.size - 1)
                if (roundUp) {
                    var k = keep - 1
                    while (k >= 0 && chars[k] == '9') { chars[k] = '0'; k-- }
                    if (k >= 0) chars[k] = chars[k] + 1 else { chars.add(0, '1'); point++ }
                }
            }
        }
        val all = chars.joinToString("")
        val intDigits = when {
            point <= 0 || all.isEmpty() -> "0"
            point >= all.length -> all + "0".repeat(point - all.length)
            else -> all.substring(0, point)
        }
        if (precision == 0) return intDigits
        val frac = StringBuilder(precision)
        for (k in 0 until precision) {
            val idx = point + k
            frac.append(if (idx in all.indices && idx >= 0) all[idx] else '0')
        }
        return "$intDigits.$frac"
    }
}
