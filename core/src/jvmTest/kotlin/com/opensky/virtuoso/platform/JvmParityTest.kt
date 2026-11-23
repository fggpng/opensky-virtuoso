package com.opensky.virtuoso.platform

import com.opensky.virtuoso.database.tycho2.Tycho2Catalog
import com.opensky.virtuoso.database.tycho2.Tycho2Converter
import com.opensky.virtuoso.database.tycho2.addFile
import com.opensky.virtuoso.database.tycho2.isInstalled
import com.opensky.virtuoso.database.tycho2.open
import com.opensky.virtuoso.database.tycho2.write
import com.opensky.virtuoso.testing.assertEquals
import com.opensky.virtuoso.testing.assertFalse
import com.opensky.virtuoso.testing.assertNotNull
import com.opensky.virtuoso.testing.assertTrue
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.Test

/** La versión común debe dar exactamente lo mismo que la de Java que usaba la app de Android. */
class JvmParityTest {

    @Test fun fmtMatchesStringFormatOnRandomValues() {
        val rnd = Random(2026)
        val patterns = listOf("%.0f", "%.1f", "%.2f", "%.3f", "%.5f", "%+.1f", "%+.2f", "%06.2f", "%7.1f", "%12.8f", "%02.0f", "%f")
        repeat(20_000) {
            val v = when (it % 5) {
                0 -> rnd.nextDouble(-400.0, 400.0)
                1 -> (rnd.nextInt(-20000, 20000) / 1000.0) // decimales exactos «redondos» (empates)
                2 -> rnd.nextDouble(-1.0, 1.0) * 1e-4
                3 -> rnd.nextDouble(0.0, 1e9)
                else -> rnd.nextInt(-999, 999) + 0.5 * rnd.nextInt(0, 2) + 0.05 * rnd.nextInt(0, 2)
            }
            for (p in patterns) assertEquals("$p de $v", String.format(Locale.US, p, v), p.fmt(v))
        }
        val ints = listOf("%d", "%02d", "%03d", "%05d", "%+03d", "%+d", "%6d", "%,d", "%X", "%02X", "%x")
        repeat(5_000) {
            val v = rnd.nextInt(-100_000, 3_000_000)
            for (p in ints) assertEquals("$p de $v", String.format(Locale.US, p, v), p.fmt(v))
        }
    }

    @Test fun textHelpersMatchJdk() {
        val samples = listOf("Andrómeda", "Pingüino", "Niño", "São Paulo", "Ångström", "Čech", "Łódź", "Ørsted", "ﬁn", "Ñandú", "Kraków")
        for (s in samples) {
            val nfd = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            assertEquals(s, nfd, TextUtils.stripDiacritics(s))
            assertEquals(s, URLEncoder.encode(s, "UTF-8"), TextUtils.urlEncode(s))
        }
        for (s in listOf("C/2023 A3 (Tsuchinshan-ATLAS)", "1P/Halley", "a+b=c&d", "~*._-")) {
            assertEquals(s, URLEncoder.encode(s, "UTF-8"), TextUtils.urlEncode(s))
        }
    }

    @Test fun tycho2RoundTripOnDiskWithGzip() {
        val dir = Files.createTempDirectory("tycho2-jvm").toFile()
        try {
            val line = MutableList(32) { "" }.apply {
                this[0] = "9001 00001 1"; this[1] = " "; this[2] = "279.23400000"; this[3] = " 38.78370000"
                this[4] = "  200.9"; this[5] = "  286.2"; this[17] = " 0.630"; this[19] = " 0.090"; this[23] = " 91262   "
                this[24] = "279.23400000"; this[25] = " 38.78370000"
            }.joinToString("|")
            val gz = File(dir, "tyc2.dat.00.gz")
            GZIPOutputStream(gz.outputStream()).bufferedWriter().use { it.write(line + "\n") }
            val conv = Tycho2Converter()
            conv.addFile(gz)
            assertEquals(1, conv.starCount)
            val out = File(dir, "bin")
            assertFalse(Tycho2Catalog.isInstalled(out))
            conv.write(out)
            assertTrue(Tycho2Catalog.isInstalled(out))
            Tycho2Catalog.open(out).use { cat ->
                val vega = cat.findByHip(91262)
                assertNotNull(vega)
                assertEquals(279.234, vega!!.raDeg, 1e-6)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
