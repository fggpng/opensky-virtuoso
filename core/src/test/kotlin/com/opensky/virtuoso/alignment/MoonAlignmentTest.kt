package com.opensky.virtuoso.alignment

import com.opensky.virtuoso.astronomy.AstroEngine
import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.database.targets.TargetResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Modo «Luna + 1 estrella»: cuándo se puede usar la Luna y cómo avanza el proceso. */
class MoonAlignmentTest {

    private val chihuahua = GeoLocation(28.5933, -106.1616, 1505.0)
    private val start = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()

    /** Primer instante (cada hora, durante 40 días) que cumple [cond]. */
    private fun firstTime(cond: (AstroTime) -> Boolean): AstroTime {
        var ms = start
        repeat(24 * 40) {
            val t = AstroTime.fromEpochMillis(ms)
            if (cond(t)) return t
            ms += 3_600_000L
        }
        error("no hubo ningún instante que cumpla la condición")
    }

    @Test fun moonModeStartsWithTheMoonAndThenNeedsOneStar() {
        val manager = AlignmentManager()
        manager.begin(AlignmentMode.MOON_ONE_STAR)
        assertTrue(manager.state.value.isMoonStep)
        assertEquals(2, manager.state.value.starsRemaining)

        manager.addAlignmentStar(AlignmentSample("Luna", HorizontalCoord(120 * DEG, 40 * DEG), 120 * DEG, 40 * DEG))
        assertFalse("tras la Luna toca una estrella", manager.state.value.isMoonStep)
        assertFalse(manager.state.value.isComplete)
        assertEquals(1, manager.state.value.starsRemaining)

        manager.addAlignmentStar(AlignmentSample("Vega", HorizontalCoord(300 * DEG, 50 * DEG), 300 * DEG, 50 * DEG))
        assertTrue(manager.state.value.isComplete)

        // Ningún otro modo pide la Luna.
        AlignmentMode.entries.filter { it != AlignmentMode.MOON_ONE_STAR }.forEach {
            manager.begin(it)
            assertFalse("$it", manager.state.value.isMoonStep)
        }
    }

    @Test fun explainsWhyTheMoonCannotBeUsed() {
        val newMoon = firstTime { MoonAlignment.reference(it, chihuahua).elongationDeg!! < 10.0 }
        val r1 = MoonAlignment.reference(newMoon, chihuahua)
        assertFalse(r1.usable)
        assertTrue(r1.unavailableReason!!, r1.unavailableReason!!.contains("luna nueva"))

        val below = firstTime { MoonAlignment.reference(it, chihuahua).let { r -> r.elongationDeg!! > 40 && r.horizontal!!.altDeg < -10 } }
        val r2 = MoonAlignment.reference(below, chihuahua)
        assertFalse(r2.usable)
        assertTrue(r2.unavailableReason!!, r2.unavailableReason!!.contains("bajo el horizonte"))

        val low = firstTime { MoonAlignment.reference(it, chihuahua).let { r -> r.elongationDeg!! > 40 && r.horizontal!!.altDeg in 2.0..8.0 } }
        assertTrue(MoonAlignment.reference(low, chihuahua).unavailableReason!!.contains("muy baja"))

        val good = firstTime { MoonAlignment.reference(it, chihuahua).let { r -> r.elongationDeg!! > 40 && r.horizontal!!.altDeg in 30.0..70.0 } }
        val r3 = MoonAlignment.reference(good, chihuahua)
        assertTrue(r3.unavailableReason ?: "", r3.usable)
        assertNotNull(r3.illuminatedPercent)
    }

    @Test fun missingLocationIsReportedNotGuessed() {
        val r = MoonReference.unavailable("Falta la ubicación")
        assertFalse(r.usable)
        assertEquals(null, r.horizontal)
    }

    /**
     * La Luna está tan cerca que vista desde Chihuahua aparece hasta ~1° más baja que desde el centro
     * de la Tierra. Si la app no lo corrigiera, ese grado entraría en la alineación.
     */
    @Test fun moonPositionIncludesTopocentricParallax() {
        val t = firstTime { MoonAlignment.reference(it, chihuahua).let { r -> r.elongationDeg!! > 40 && r.horizontal!!.altDeg in 30.0..50.0 } }
        val pos = TargetResolver.position(MoonAlignment.target, t, chihuahua)
        val geocentric = AstroEngine.apparentToHorizontal(pos.apparent, t, chihuahua, com.opensky.virtuoso.astronomy.Weather(), null)
        val shiftDeg = pos.horizontal.separation(geocentric) / DEG
        assertTrue("paralaje de %.2f°".format(shiftDeg), shiftDeg in 0.4..1.0)
        assertTrue("la paralaje baja la Luna", pos.horizontal.altDeg < geocentric.altDeg)
    }
}
