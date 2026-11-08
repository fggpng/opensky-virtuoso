package com.opensky.virtuoso.astronomy

import com.opensky.virtuoso.astronomy.ephemeris.Moon
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystem
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystemBody
import com.opensky.virtuoso.astronomy.math.ARCSEC
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.astronomy.math.HOUR
import com.opensky.virtuoso.astronomy.math.RAD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Valida el motor astrométrico contra los ejemplos resueltos de
 * J. Meeus, "Astronomical Algorithms" (2.ª ed.).
 */
class AstronomyEngineTest {

    private fun hms(h: Int, m: Int, s: Double) = (h + m / 60.0 + s / 3600.0) * HOUR
    private fun dms(d: Int, m: Int, s: Double, neg: Boolean = false) = (if (neg) -1 else 1) * (d + m / 60.0 + s / 3600.0) * DEG
    private fun timeSeconds(rad: Double) = rad / HOUR * 3600.0

    @Test fun julianDay_meeus7a() {
        assertEquals(2436116.31, AstroTime.julianDay(1957, 10, 4.81), 1e-6)
        assertEquals(2451545.0, AstroTime.julianDay(2000, 1, 1.5), 1e-9)
    }

    @Test fun meanSiderealTime_meeus12a_12b() {
        val t0 = AstroTime.fromCalendarUtc(1987, 4, 10)
        assertEquals(timeSeconds(hms(13, 10, 46.3668)), timeSeconds(EarthOrientation.gmst(t0)), 0.005)
        val t1 = AstroTime.fromCalendarUtc(1987, 4, 10, 19, 21, 0.0)
        assertEquals(timeSeconds(hms(8, 34, 57.0896)), timeSeconds(EarthOrientation.gmst(t1)), 0.005)
    }

    @Test fun apparentSiderealTime_meeus12a() {
        val t0 = AstroTime.fromCalendarUtc(1987, 4, 10)
        assertEquals(timeSeconds(hms(13, 10, 46.1351)), timeSeconds(EarthOrientation.gast(t0)), 0.04)
    }

    @Test fun nutationAndObliquity_meeus22a() {
        val t = AstroTime.fromJdTt(2446895.5).centuriesTt
        val n = EarthOrientation.nutation(t)
        assertEquals(-3.788, n.deltaPsi / ARCSEC, 0.5)
        assertEquals(9.443, n.deltaEpsilon / ARCSEC, 0.1)
        assertEquals(dms(23, 26, 27.407) / ARCSEC, EarthOrientation.meanObliquity(t) / ARCSEC, 0.1)
    }

    @Test fun precession_meeus21b() {
        // θ Persei, J2000 → 2028 Nov 13.19 TD (posición media de la fecha).
        val time = AstroTime.fromJdTt(2462088.69)
        val years = time.julianYearTt - 2000.0
        val ra = hms(2, 44, 11.986) + 0.03425 * 15 * ARCSEC * years
        val dec = dms(49, 13, 42.48) - 0.0895 * ARCSEC * years
        val mean = EarthOrientation.precessionMatrix(time.centuriesTt) * EquatorialCoord(ra, dec).toVector()
        val result = EquatorialCoord.fromVector(mean)
        val expected = EquatorialCoord(hms(2, 46, 11.331), dms(49, 20, 54.54))
        assertTrue("sep=${result.separation(expected) / ARCSEC}″", result.separation(expected) < 0.1 * ARCSEC)
    }

    @Test fun apparentPlaceOfStar_meeus23a() {
        val time = AstroTime.fromJdTt(2462088.69)
        val j2000 = EquatorialCoord(hms(2, 44, 11.986), dms(49, 13, 42.48))
        val pmRa = 0.03425 * 15 * 1000 * kotlin.math.cos(j2000.dec) // mas/año (RA·cosδ)
        val apparent = AstroEngine.starApparent(j2000, time, pmRa, -89.5)
        val expected = EquatorialCoord(hms(2, 46, 14.390), dms(49, 21, 7.45))
        val sep = apparent.separation(expected) / ARCSEC
        assertTrue("sep=$sep″", sep < 1.5)
        // Ida y vuelta aparente ↔ J2000 sin movimiento propio
        val back = AstroEngine.apparentToJ2000(AstroEngine.j2000ToApparent(j2000, time), time)
        assertTrue(back.separation(j2000) < 0.01 * ARCSEC)
    }

    @Test fun equatorialToHorizontal_meeus13b() {
        val time = AstroTime.fromCalendarUtc(1987, 4, 10, 19, 21, 0.0)
        val lat = dms(38, 55, 17.0)
        val lonEast = -(77 + 3 / 60.0 + 56 / 3600.0)
        val venus = EquatorialCoord(hms(23, 9, 16.641), dms(6, 43, 11.61, neg = true))
        val hz = CoordinateTransforms.equatorialToHorizontal(venus, EarthOrientation.last(time, lonEast), lat)
        assertEquals(68.0337 + 180.0, hz.azDeg, 0.001) // Meeus mide el acimut desde el Sur
        assertEquals(15.1249, hz.altDeg, 0.001)
        val back = CoordinateTransforms.horizontalToEquatorial(hz, EarthOrientation.last(time, lonEast), lat)
        assertTrue(back.separation(venus) < 1e-9)
    }

    @Test fun sun_meeus25b() {
        val p = SolarSystem.position(SolarSystemBody.SUN, AstroTime.fromJdTt(2448908.5))
        val expected = EquatorialCoord.fromDegrees(198.378178, -7.783871)
        val sep = p.apparent.separation(expected) / ARCSEC
        assertTrue("Sol sep=$sep″", sep < 36.0)
        assertEquals(0.99766, p.distanceAu, 0.0002)
    }

    @Test fun moon_meeus47a() {
        val time = AstroTime.fromJdTt(2448724.5)
        val ecl = Moon.ecliptic(time.centuriesTt)
        assertEquals(133.162655, ecl.lambda * RAD, 0.0005)
        assertEquals(-3.229126, ecl.beta * RAD, 0.0005)
        assertEquals(368409.7, ecl.distanceKm, 5.0)
        val p = SolarSystem.position(SolarSystemBody.MOON, time)
        assertEquals(134.688470, p.apparent.raDeg, 0.001)
        assertEquals(13.768368, p.apparent.decDeg, 0.001)
    }

    @Test fun venus_meeus33a() {
        val p = SolarSystem.position(SolarSystemBody.VENUS, AstroTime.fromJdTt(2448976.5))
        val expected = EquatorialCoord(hms(21, 4, 41.454), dms(18, 53, 16.84, neg = true))
        val sep = p.apparent.separation(expected) / ARCSEC
        assertTrue("Venus sep=$sep″", sep < 60.0)
        assertEquals(0.910947, p.distanceAu, 0.001)
    }

    @Test fun refraction_reasonableAndInvertible() {
        assertEquals(34.5, Refraction.fromApparentAltitude(0.0) / DEG * 60, 0.6)
        assertEquals(1.0, Refraction.fromTrueAltitude(45 * DEG) / DEG * 60, 0.05)
        for (deg in listOf(2.0, 5.0, 10.0, 30.0, 60.0, 85.0)) {
            val trueAlt = deg * DEG
            val app = trueAlt + Refraction.fromTrueAltitude(trueAlt)
            val back = app - Refraction.fromApparentAltitude(app)
            assertTrue("alt=$deg", abs(back - trueAlt) / DEG * 60 < 0.15)
        }
    }

    @Test fun horizontalRoundTripWithRefraction() {
        val loc = GeoLocation(28.6353, -106.0889, 1430.0) // Chihuahua, MX
        val time = AstroTime.fromCalendarUtc(2026, 11, 7, 3, 0, 0.0)
        val star = EquatorialCoord.fromHoursDegrees(18.6156, 38.7837) // Vega
        val app = AstroEngine.j2000ToApparent(star, time)
        val hz = AstroEngine.apparentToHorizontal(app, time, loc)
        val back = AstroEngine.horizontalToApparent(hz, time, loc)
        assertTrue(back.separation(app) < 0.2 * ARCSEC * 60) // < 0.2′ (inversión de refracción)
    }

    @Test fun angleFormatting() {
        assertEquals("05h 30m 00.0s", com.opensky.virtuoso.astronomy.math.Angles.formatHms(5.5))
        assertEquals("-12° 30′ 00″", com.opensky.virtuoso.astronomy.math.Angles.formatDms(-12.5))
        assertEquals(-12.5, com.opensky.virtuoso.astronomy.math.Angles.parseSexagesimal("-12 30 00")!!, 1e-12)
        assertEquals(5.5, com.opensky.virtuoso.astronomy.math.Angles.parseSexagesimal("05h30m")!!, 1e-12)
    }
}
