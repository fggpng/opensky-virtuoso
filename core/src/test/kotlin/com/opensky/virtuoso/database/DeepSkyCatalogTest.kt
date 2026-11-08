package com.opensky.virtuoso.database

import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.database.targets.DeepSkyCatalog
import com.opensky.virtuoso.database.targets.DeepSkyTarget
import com.opensky.virtuoso.database.targets.TargetCatalog
import com.opensky.virtuoso.database.targets.TargetCategory
import com.opensky.virtuoso.database.targets.TargetResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DeepSkyCatalogTest {

    private val catalog = TargetCatalog()

    @Test fun containsAllMessierObjectsOnce() {
        val numbers = DeepSkyCatalog.all.mapNotNull { it.messier }
        assertEquals((1..110).toList(), numbers.sorted())
        assertTrue("objetos: ${DeepSkyCatalog.all.size}", DeepSkyCatalog.all.size in 1500..2500)
    }

    @Test fun knownCoordinatesAndNames() {
        val m31 = DeepSkyCatalog.messier(31)!!
        assertEquals("NGC 224", m31.designation)
        assertEquals(10.6848, m31.j2000.raDeg, 0.01)      // 00h 42m 44s
        assertEquals(41.269, Math.toDegrees(m31.j2000.dec), 0.01)
        assertEquals("M31 · Galaxia de Andrómeda", m31.displayName)
        assertEquals(3.4, m31.magnitude!!, 0.1)

        val m42 = DeepSkyCatalog.messier(42)!!
        assertEquals(83.82, m42.j2000.raDeg, 0.02)
        assertEquals(-5.39, Math.toDegrees(m42.j2000.dec), 0.02)
        assertEquals("NGC 5866", DeepSkyCatalog.messier(102)!!.designation) // convención para M102
        assertEquals("Mel 22", DeepSkyCatalog.messier(45)!!.designation)     // Pléyades
    }

    @Test fun searchByDesignationAndName() {
        fun first(q: String) = catalog.search(q).firstOrNull() as? DeepSkyTarget
        listOf("M31", "m 31", "Messier 31", "NGC 224", "ngc0224", "ngc224").forEach { q ->
            assertEquals(q, 31, first(q)?.obj?.messier)
        }
        assertEquals("NGC 7000", first("NGC 7000")?.obj?.designation)
        assertEquals("IC 434", first("ic434")?.obj?.designation)
        assertEquals(45, first("pleyades")?.obj?.messier)              // sin acento
        assertEquals(45, first("Pleiades")?.obj?.messier)              // nombre en inglés
        assertEquals(31, first("Andrómeda")?.obj?.messier)
        assertTrue(catalog.search("nebulosa del velo").any { it is DeepSkyTarget })
    }

    @Test fun listedCategoryIncludesMessierAndBrightObjects() {
        val listed = catalog.list(TargetCategory.DEEP_SKY)
        assertTrue(listed.size in 400..700)
        assertEquals(110, listed.count { (it as DeepSkyTarget).obj.messier != null })
        assertTrue(listed.all { it.category == TargetCategory.DEEP_SKY })
    }

    @Test fun positionIsComputedForDeepSkyTargets() {
        // 8 nov 2026, 05:00 UTC (23:00 hora de México), Chihuahua: M42 debe estar sobre el horizonte, al este.
        val t = AstroTime.fromInstant(java.time.Instant.parse("2026-11-08T05:00:00Z"))
        val site = GeoLocation(28.6353, -106.0889, 1430.0)
        val p = TargetResolver.position(DeepSkyTarget(DeepSkyCatalog.messier(42)!!), t, site)
        assertTrue("alt=${p.horizontal.altDeg}", p.horizontal.altDeg in 5.0..60.0)
        assertTrue("az=${p.horizontal.azDeg}", p.horizontal.azDeg in 80.0..150.0)
        // Precesión J2000 → 2026.85: m + n·sen α·tan δ ≈ 3.075 − 0.125 = 2.95 s/año → 79 s ≈ 0.33°
        // (nutación y aberración añaden menos de 0.01°).
        val dRa = Math.toDegrees(p.apparent.ra) - DeepSkyCatalog.messier(42)!!.j2000.raDeg
        assertTrue("ΔAR=$dRa°", abs(dRa - 0.33) < 0.02)
        assertNotNull(p.magnitude)
    }
}
