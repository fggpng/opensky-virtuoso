package com.opensky.virtuoso.control

import com.opensky.virtuoso.alignment.AlignmentManager
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.math.ARCMIN
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.database.targets.TargetCatalog
import com.opensky.virtuoso.database.targets.TargetResolver
import com.opensky.virtuoso.network.MountClient
import com.opensky.virtuoso.network.simulator.MountSimulator
import com.opensky.virtuoso.network.transport.UdpMountTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Posición de inicio escrita a mano (rumbo leído en una brújula o en una app de mapa del cielo). */
class ManualStartTest {

    @Test fun parsesWhatPeopleType() {
        assertEquals(351.0, ManualHeading.parseAzimuth("351")!!, 1e-9)
        assertEquals(12.5, ManualHeading.parseAzimuth(" 12,5 ")!!, 1e-9)
        assertEquals(90.0, ManualHeading.parseAzimuth("90°")!!, 1e-9)
        assertEquals(0.0, ManualHeading.parseAzimuth("360")!!, 1e-9)
        listOf("", "abc", "-5", "361", "NaN", "12.5.3").forEach { assertNull(it, ManualHeading.parseAzimuth(it)) }
    }

    @Test fun compassReadingIsCorrectedByDeclination() {
        // Chihuahua: declinación ≈ +8° (este). Brújula 355° → norte verdadero 3°.
        assertEquals(3.0, ManualHeading.trueAzimuth(355.0, HeadingReference.MAGNETIC, 8.0)!!, 1e-9)
        assertEquals(357.0, ManualHeading.trueAzimuth(2.0, HeadingReference.MAGNETIC, -5.0)!!, 1e-9)
        // Las apps de cielo ya dan el norte verdadero: no se corrige.
        assertEquals(351.0, ManualHeading.trueAzimuth(351.0, HeadingReference.TRUE, 8.0)!!, 1e-9)
        // Sin ubicación no hay declinación: la brújula no se puede usar.
        assertNull(ManualHeading.trueAzimuth(10.0, HeadingReference.MAGNETIC, null))
        assertEquals(10.0, ManualHeading.trueAzimuth(10.0, HeadingReference.TRUE, null)!!, 1e-9)
    }

    @Test fun differenceAndCardinals() {
        assertEquals(3.0, ManualHeading.difference(359.0, 2.0), 1e-9)
        assertEquals(180.0, ManualHeading.difference(10.0, 190.0), 1e-9)
        assertEquals("norte", ManualHeading.cardinalName(351.0))
        assertEquals("noroeste", ManualHeading.cardinalName(315.0))
        assertEquals("sur", ManualHeading.cardinalName(180.0))
    }

    @Test fun phoneDisagreementIsFlagged() {
        val ok = ManualStartPreview(351.0, HeadingReference.TRUE, null, 351.0, 0.5, true, phoneAzDeg = 356.0)
        assertFalse(ok.suspicious)
        // El error típico: escribir el rumbo con el tubo apuntando al lado contrario.
        val wrong = ok.copy(phoneAzDeg = 170.0)
        assertTrue(wrong.suspicious)
        assertTrue(wrong.logLine().contains("diferencia"))
        // Sin medición del teléfono no hay con qué comparar.
        assertFalse(ok.copy(phoneAzDeg = null).suspicious)
    }

    private val site = Site(GeoLocation(28.5933, -106.1616, 1505.0))

    @Test fun manualStartUnlocksGotoAndPointsTheShortWay() = runBlocking {
        val sim = MountSimulator(gotoTimeScale = 25.0).start()
        val client = MountClient(UdpMountTransport("127.0.0.1", sim.boundPort), MountClient.Config(timeoutMs = 200, maxAttempts = 5))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val clock = OffsetAstroClock()
        val controller = MountController(client, AlignmentManager(), scope, clock, { site }, {
            ControllerConfig(minAltitudeDeg = -5.0, trackingUpdateMs = 400, pollIntervalMs = 300, gotoSpeedDegPerSec = 3.5 * 25)
        })
        try {
            controller.connect()
            // La noche de Polaris: Star Walk marcaba 351° con el tubo casi horizontal.
            val preview = ManualStartPreview(351.0, HeadingReference.TRUE, null, 351.0, 0.0, altitudeMeasured = false)
            controller.setInitialOrientation(preview.trueAzDeg, preview.altDeg, StartPosition.MANUAL)
            assertEquals(StartPosition.MANUAL, controller.state.value.startPosition)
            val (az0, _) = controller.readEncoders()

            val polaris = TargetCatalog().search("Polaris").first()
            val r = controller.goto(polaris, trackAfter = false)
            assertTrue("$r", r is GotoResult.Success)

            // Llega a Polaris girando unos grados hacia el norte, no media vuelta hacia el sur.
            val (az1, _) = controller.readEncoders()
            assertTrue("giró ${(az1 - az0) / DEG}°", abs(az1 - az0) / DEG < 20.0)
            val sky = TargetResolver.position(polaris, clock.now(), site.location, site.weather).horizontal
            val err = controller.refreshPointing().pointing!!.separation(sky) / ARCMIN
            assertTrue("quedó a $err′ de Polaris", err < 30)
        } finally {
            runCatching { controller.disconnect() }
            scope.cancel(); sim.close()
        }
    }
}
