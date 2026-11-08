package com.opensky.virtuoso.database

import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.ephemeris.Kepler
import com.opensky.virtuoso.astronomy.ephemeris.MpcParser
import com.opensky.virtuoso.astronomy.ephemeris.SmallBodyType
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystem
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystemBody
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.database.targets.DoubleStarTarget
import com.opensky.virtuoso.database.targets.SolarSystemTarget
import com.opensky.virtuoso.database.targets.StarTarget
import com.opensky.virtuoso.database.targets.TargetCatalog
import com.opensky.virtuoso.database.tycho2.Tycho2Catalog
import com.opensky.virtuoso.database.tycho2.Tycho2Converter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

class CatalogTest {

    private data class Synthetic(val t1: Int, val t2: Int, val t3: Int, val ra: Double, val dec: Double, val v: Double, val hip: Int = 0)

    /** Línea con el formato real de tyc2.dat (32 campos separados por '|'). */
    private fun tyc2Line(s: Synthetic, noMeanPosition: Boolean = false): String {
        val f = MutableList(32) { "" }
        f[0] = "%04d %05d %d".format(s.t1, s.t2, s.t3)
        f[1] = if (noMeanPosition) "X" else " "
        if (!noMeanPosition) {
            f[2] = "%12.8f".format(s.ra); f[3] = "%12.8f".format(s.dec)
            f[4] = "%7.1f".format(-16.3); f[5] = "%7.1f".format(-9.0)
        }
        val bt = s.v + 0.6; val vt = s.v + 0.06
        f[17] = "%6.3f".format(bt); f[18] = "0.158"; f[19] = "%6.3f".format(vt); f[20] = "0.223"
        f[21] = "999"; f[22] = " "; f[23] = (if (s.hip > 0) "%6d".format(s.hip) else "      ") + "   "
        f[24] = "%12.8f".format(s.ra); f[25] = "%12.8f".format(s.dec)
        return f.joinToString("|")
    }

    private fun synthetic(): List<Synthetic> {
        val rnd = Random(7)
        val list = mutableListOf<Synthetic>()
        var t2 = 1
        repeat(3000) {
            val ra = rnd.nextDouble(0.0, 360.0)
            val dec = Math.toDegrees(kotlin.math.asin(rnd.nextDouble(-1.0, 1.0)))
            val region = 1 + (ra / 10).toInt() + 36 * ((dec + 90) / 10).toInt().coerceAtMost(17)
            list += Synthetic(region, t2++, 1, ra, dec, rnd.nextDouble(2.0, 11.5))
        }
        // Región que cruza AR = 0°
        repeat(40) { list += Synthetic(9000, t2++, 1, (359.0 + rnd.nextDouble(0.0, 2.0)) % 360.0, rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(3.0, 9.0)) }
        list += Synthetic(9001, 1, 1, 279.234, 38.7837, 0.03, hip = 91262) // "Vega"
        return list
    }

    private fun build(dir: File, stars: List<Synthetic>): Tycho2Catalog {
        val conv = Tycho2Converter()
        stars.forEach { assertTrue(conv.addLine(tyc2Line(it))) }
        assertTrue(conv.addLine(tyc2Line(Synthetic(9002, 5, 1, 10.0, 10.0, 8.0), noMeanPosition = true)))
        // Línea del suplemento 1 (18 campos, época J1991.25)
        val suppl = MutableList(18) { "" }.apply {
            this[0] = "9003 00001 1"; this[1] = "H"; this[2] = " 88.79293899"; this[3] = "  7.40706399"
            this[4] = "   27.3"; this[5] = "   10.9"; this[11] = " 2.312"; this[13] = " 0.444"; this[17] = " 27989   "
        }.joinToString("|")
        assertTrue(conv.addLine(suppl))
        assertTrue(!conv.addLine("basura|sin|formato"))
        val result = conv.write(dir)
        assertEquals(stars.size + 2, result.stars)
        return Tycho2Catalog.open(dir)
    }

    @Test fun tycho2ConeQueriesMatchBruteForce() {
        val dir = Files.createTempDirectory("tycho2").toFile()
        val stars = synthetic()
        build(dir, stars).use { cat ->
            assertEquals(stars.size + 2, cat.starCount)
            val centers = listOf(
                EquatorialCoord.fromDegrees(0.3, 0.0),     // cruza AR = 0°
                EquatorialCoord.fromDegrees(123.0, 45.0),
                EquatorialCoord.fromDegrees(200.0, 88.5),  // casquete polar
                EquatorialCoord.fromDegrees(300.0, -60.0),
            )
            for (c in centers) for (radius in listOf(3.0, 15.0, 60.0)) for (mag in listOf(6.0, 11.5)) {
                // V almacenada = VT − 0.090·(BT − VT) con BT = v + 0.6 y VT = v + 0.06
                val expected = stars.filter { s ->
                    s.v + 0.06 - 0.090 * 0.54 <= mag + 1e-9 && EquatorialCoord.fromDegrees(s.ra, s.dec).separation(c) <= radius * DEG
                }.map { "${it.t1}-${it.t2}" }.toSet()
                val got = cat.cone(c, radius, mag).filter { it.tyc1 < 9002 }.map { "${it.tyc1}-${it.tyc2}" }.toSet()
                assertEquals("centro=$c r=$radius mag=$mag", expected, got)
            }
            val bright = cat.cone(EquatorialCoord.fromDegrees(0.0, 0.0), 180.0, 20.0)
            assertTrue("ordenado por brillo", bright.zipWithNext().all { (a, b) -> a.vMag <= b.vMag })
        }
    }

    @Test fun tycho2LookupsAndSupplement() {
        val dir = Files.createTempDirectory("tycho2b").toFile()
        build(dir, synthetic()).use { cat ->
            val vega = cat.findByHip(91262)
            assertNotNull(vega); assertEquals(279.234, vega!!.raDeg, 1e-6)
            assertEquals(0.03 + 0.06 - 0.090 * 0.54, vega.vMag, 0.002)
            assertNotNull(cat.findByTyc(9000, 3001, 1))
            assertNull(cat.findByTyc(9000, 99999 % 12000, 3))
            // Suplemento: propagado de J1991.25 a J2000 con su movimiento propio
            val betel = cat.findByHip(27989)!!
            val expectedDec = 7.40706399 + 10.9 / 3.6e6 * 8.75
            assertEquals(expectedDec, betel.decDeg, 2e-6)
            assertEquals(0.444, betel.vMag + 0.090 * (2.312 - 0.444), 0.002)
            // Registro sin posición media (pflag X) usa la posición observada
            val x = cat.findByTyc(9002, 5, 1)!!
            assertEquals(10.0, x.raDeg, 1e-6); assertEquals(0.0, x.pmRaMasYr, 0.0)
        }
    }

    @Test fun circularRaRange() {
        assertEquals(359.0 to 1.0, Tycho2Converter.circularRange(doubleArrayOf(0.5, 359.0, 1.0, 359.5)))
        assertEquals(10.0 to 20.0, Tycho2Converter.circularRange(doubleArrayOf(20.0, 10.0, 15.0)))
        assertTrue(Tycho2Catalog.raIntervalsOverlap(350.0, 5.0, 2.0, 3.0))
        assertTrue(!Tycho2Catalog.raIntervalsOverlap(10.0, 20.0, 350.0, 5.0))
    }

    private fun cols(vararg parts: Pair<Int, String>): String {
        val sb = StringBuilder(" ".repeat(200))
        for ((col, text) in parts) for (i in text.indices) sb.setCharAt(col - 1 + i, text[i])
        return sb.toString().trimEnd()
    }

    @Test fun mpcCometAndAsteroidParsing() {
        val comet = cols(
            1 to "0001", 5 to "P", 15 to "2061", 20 to "07", 23 to "28.7090", 31 to " 0.586800",
            42 to "0.966740", 52 to "112.2524", 62 to " 59.4135", 72 to "162.1888", 82 to "20260523",
            92 to " 4.0", 97 to " 6.0", 103 to "1P/Halley", 160 to "MPC 12345",
        )
        val c = MpcParser.parseCometLine(comet)!!
        assertEquals(SmallBodyType.COMET, c.type)
        assertEquals("1P/Halley", c.designation)
        assertEquals(0.5868, c.q, 1e-9); assertEquals(0.96674, c.e, 1e-9); assertEquals(162.1888, c.inclDeg, 1e-9)
        assertEquals(AstroTime.julianDay(2061, 7, 28.709), c.perihelionJdTt, 1e-9)
        val pos = SolarSystem.smallBodyPosition(c, AstroTime.fromCalendarUtc(2026, 11, 7))
        assertTrue(pos.distanceAu in 30.0..40.0) // Halley cerca del afelio
        assertNotNull(pos.magnitude)

        val epoch = "K25BL"
        assertEquals(AstroTime.julianDay(2025, 11, 21.0), MpcParser.unpackEpoch(epoch)!!, 1e-9)
        val asteroid = cols(
            1 to "00004", 9 to " 3.25", 15 to " 0.32", 21 to epoch, 27 to " 26.80931", 38 to "151.53712",
            49 to "103.70232", 60 to "  7.14406", 71 to "0.0901662", 81 to " 0.27154800", 93 to "  2.3614000",
            167 to "(4) Vesta",
        )
        val a = MpcParser.parseAsteroidLine(asteroid)!!
        assertEquals("(4) Vesta", a.name)
        // La posición en la época debe coincidir con la calculada directamente desde M
        val e = 0.0901662; val aAu = 2.3614
        val bigE = Kepler.solveElliptic(26.80931 * DEG, e)
        val direct = Kepler.ECLIPTIC_TO_EQUATORIAL * Kepler.orbitalToEcliptic(
            aAu * (cos(bigE) - e), aAu * sqrt(1 - e * e) * kotlin.math.sin(bigE),
            151.53712 * DEG, 103.70232 * DEG, 7.14406 * DEG,
        )
        val viaElements = a.heliocentricEquatorial(MpcParser.unpackEpoch(epoch)!!)
        assertTrue((direct - viaElements).norm < 1e-9)
        assertEquals(2, MpcParser.parseAny(sequenceOf(comet, asteroid, "")).size)
    }

    @Test fun hyperbolicAndParabolicOrbitsAreFinite() {
        for (e in listOf(1.0, 1.0000001, 1.2, 3.5)) {
            for (dt in listOf(-400.0, -10.0, 0.0, 25.0, 900.0)) {
                val (x, y) = Kepler.orbitalPlanePosition(0.8, e, dt)
                assertTrue("e=$e dt=$dt", x.isFinite() && y.isFinite())
                if (dt == 0.0) assertEquals(0.8, sqrt(x * x + y * y), 1e-9)
            }
        }
    }

    @Test fun targetSearch() {
        val catalog = TargetCatalog()
        assertEquals("Vega", catalog.search("vega").first().name)
        assertEquals("Vega", catalog.search("alpha lyr").first().name)
        assertEquals("Vega", catalog.search("HIP 91262").single().name)
        assertEquals(SolarSystemBody.MARS, (catalog.search("marte").first() as SolarSystemTarget).body)
        assertEquals(SolarSystemBody.MARS, (catalog.search("Mars").first() as SolarSystemTarget).body)
        assertEquals(SolarSystemBody.JUPITER, (catalog.search("jupiter").first() as SolarSystemTarget).body)
        assertTrue(catalog.search("doble doble").all { it is DoubleStarTarget })
        assertTrue(catalog.list(com.opensky.virtuoso.database.targets.TargetCategory.NAMED_STAR).size >= 100)
        val sirius = catalog.search("sirius").first() as StarTarget
        assertEquals(-1.46, sirius.magnitude!!, 1e-9)
    }
}

class SbdbParserTest {
    @Test fun parsesAsteroidAndAmbiguousResponses() {
        val json = """{"signature":{"version":"1.0"},"object":{"fullname":"4 Vesta (A807 FA)","des":"4","kind":"an"},
          "orbit":{"epoch":"2461000.5","elements":[
            {"name":"e","value":"0.0901662"},{"name":"a","value":"2.3614"},{"name":"q","value":"2.148478"},
            {"name":"i","value":"7.14406"},{"name":"om","value":"103.70232"},{"name":"w","value":"151.53712"},
            {"name":"ma","value":"26.80931"},{"name":"tp","value":"2460903.86"},{"name":"n","value":"0.271548"}]},
          "phys_par":[{"name":"H","value":"3.25"},{"name":"G","value":"0.32"}]}"""
        val r = com.opensky.virtuoso.network.web.SmallBodyService.parseSbdb(json)
        val el = (r as com.opensky.virtuoso.network.web.SbdbResult.Found).elements
        assertEquals("4", el.designation); assertEquals(2.148478, el.q, 1e-9); assertEquals(3.25, el.absMag!!, 1e-9)
        val amb = com.opensky.virtuoso.network.web.SmallBodyService.parseSbdb(
            """{"code":"300","message":"specified query matched more than one object","list":[{"pdes":"141P","name":"141P/Machholz"},{"pdes":"141P-A","name":"141P-A"}]}""")
        assertEquals(2, (amb as com.opensky.virtuoso.network.web.SbdbResult.Ambiguous).candidates.size)
        assertTrue(com.opensky.virtuoso.network.web.SmallBodyService.parseSbdb("{\"message\":\"x\"}") is com.opensky.virtuoso.network.web.SbdbResult.NotFound)
    }
}
