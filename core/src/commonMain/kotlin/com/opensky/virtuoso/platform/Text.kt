package com.opensky.virtuoso.platform

/*
 * Utilidades de texto comunes (sustituyen a java.text.Normalizer y java.net.URLEncoder).
 */

object TextUtils {

    // Letras latinas con diacríticos → letra base (lo mismo que NFD + quitar marcas para los
    // alfabetos que aparecen en nombres de objetos y en las búsquedas: español, portugués,
    // francés, alemán, nórdicos, Europa central…). Como en NFD, «ø», «æ», «ß», «ł» o «đ» no se
    // tocan: no tienen descomposición canónica.
    private val DECOMPOSED: Map<Char, Char> = buildMap {
        fun add(base: Char, variants: String) = variants.forEach { put(it, base) }
        add('A', "ÀÁÂÃÄÅĀĂĄǍǞǠǺȀȂȦȺḀẠẢẤẦẨẪẬẮẰẲẴẶ")
        add('a', "àáâãäåāăąǎǟǡǻȁȃȧḁạảấầẩẫậắằẳẵặẚ")
        add('C', "ÇĆĈĊČḈ")
        add('c', "çćĉċčḉ")
        add('D', "ĎḊḌḎḐḒ")
        add('d', "ďḋḍḏḑḓ")
        add('E', "ÈÉÊËĒĔĖĘĚȄȆȨḔḖḘḚḜẸẺẼẾỀỂỄỆ")
        add('e', "èéêëēĕėęěȅȇȩḕḗḙḛḝẹẻẽếềểễệ")
        add('G', "ĜĞĠĢǦǴḠ")
        add('g', "ĝğġģǧǵḡ")
        add('H', "ĤȞḢḤḦḨḪ")
        add('h', "ĥȟḣḥḧḩḫẖ")
        add('I', "ÌÍÎÏĨĪĬĮİǏȈȊḬḮỈỊ")
        add('i', "ìíîïĩīĭįǐȉȋḭḯỉị")
        add('J', "Ĵ")
        add('j', "ĵǰ")
        add('K', "ĶǨḰḲḴ")
        add('k', "ķǩḱḳḵ")
        add('L', "ĹĻĽḶḸḺḼ")
        add('l', "ĺļľḷḹḻḽ")
        add('M', "ḾṀṂ")
        add('m', "ḿṁṃ")
        add('N', "ÑŃŅŇǸṄṆṈṊ")
        add('n', "ñńņňǹṅṇṉṋ")
        add('O', "ÒÓÔÕÖŌŎŐƠǑǪǬȌȎȪȬȮȰṌṎṐṒỌỎỐỒỔỖỘỚỜỞỠỢ")
        add('o', "òóôõöōŏőơǒǫǭȍȏȫȭȯȱṍṏṑṓọỏốồổỗộớờởỡợ")
        add('P', "ṔṖ")
        add('p', "ṕṗ")
        add('R', "ŔŖŘȐȒṘṚṜṞ")
        add('r', "ŕŗřȑȓṙṛṝṟ")
        add('S', "ŚŜŞŠȘṠṢṤṦṨ")
        add('s', "śŝşšșṡṣṥṧṩ")
        add('T', "ŢŤȚṪṬṮṰ")
        add('t', "ţťțṫṭṯṱẗ")
        add('U', "ÙÚÛÜŨŪŬŮŰŲƯǓǕǗǙǛȔȖṲṴṶṸṺỤỦỨỪỬỮỰ")
        add('u', "ùúûüũūŭůűųưǔǖǘǚǜȕȗṳṵṷṹṻụủứừửữự")
        add('V', "ṼṾ")
        add('v', "ṽṿ")
        add('W', "ŴẀẂẄẆẈ")
        add('w', "ŵẁẃẅẇẉẘ")
        add('X', "ẊẌ")
        add('x', "ẋẍ")
        add('Y', "ÝŶŸȲẎỲỴỶỸ")
        add('y', "ýÿŷȳẏẙỳỵỷỹ")
        add('Z', "ŹŻŽẐẒẔ")
        add('z', "źżžẑẓẕ")
    }

    private fun isCombiningMark(c: Char): Boolean {
        val code = c.code
        return code in 0x0300..0x036F || code in 0x1AB0..0x1AFF || code in 0x1DC0..0x1DFF ||
            code in 0x20D0..0x20FF || code in 0xFE20..0xFE2F
    }

    /** Quita acentos y diéresis: «Andrómeda» → «Andromeda», «Pingüino» → «Pinguino». */
    fun stripDiacritics(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (isCombiningMark(c)) continue
            sb.append(DECOMPOSED[c] ?: c)
        }
        return sb.toString()
    }

    /** Codificación application/x-www-form-urlencoded (como URLEncoder.encode(s, "UTF-8")). */
    fun urlEncode(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (b in text.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '*' || ch == '_' -> sb.append(ch)
                ch == ' ' -> sb.append('+')
                else -> {
                    sb.append('%')
                    sb.append(HEX[c shr 4])
                    sb.append(HEX[c and 0xF])
                }
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
