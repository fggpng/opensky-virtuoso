package com.opensky.virtuoso.control

import com.opensky.virtuoso.alignment.AlignmentManager
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.database.targets.TargetCatalog
import com.opensky.virtuoso.network.MountClient
import com.opensky.virtuoso.network.protocol.Axis
import com.opensky.virtuoso.network.simulator.MountSimulator
import com.opensky.virtuoso.network.transport.UdpMountTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Poka-yoke de la posición de inicio: sin confirmarla no hay GoTo. */
class StartPositionTest {

    private val site = Site(GeoLocation(28.5933, -106.1616, 1505.0))

    private fun withController(block: suspend (MountController, MountClient) -> Unit) = runBlocking {
        val sim = MountSimulator(gotoTimeScale = 25.0).start()
        val client = MountClient(UdpMountTransport("127.0.0.1", sim.boundPort), MountClient.Config(timeoutMs = 200, maxAttempts = 5))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val controller = MountController(client, AlignmentManager(), scope, OffsetAstroClock(), { site }, {
            ControllerConfig(minAltitudeDeg = -5.0, trackingUpdateMs = 400, pollIntervalMs = 300, gotoSpeedDegPerSec = 3.5 * 25)
        })
        try {
            controller.connect()
            block(controller, client)
        } finally {
            controller.disconnect(); scope.cancel(); sim.close()
        }
    }

    /** Polaris: siempre ~28.6° sobre el horizonte en esta latitud, sin importar la hora de la prueba. */
    private fun polaris() = TargetCatalog().search("Polaris").first()

    @Test fun gotoIsBlockedUntilStartIsConfirmed() = withController { controller, _ ->
        assertEquals(StartPosition.UNCONFIRMED, controller.state.value.startPosition)
        val r = controller.goto(polaris(), trackAfter = false)
        assertTrue("$r", r is GotoResult.Rejected && r.reason.contains("posición de inicio"))

        controller.confirmHomePosition()
        assertEquals(StartPosition.HOME, controller.state.value.startPosition)
        val (az, alt) = controller.readEncoders()
        assertEquals(0.0, az, 1e-6); assertEquals(0.0, alt, 1e-6)
    }

    @Test fun phoneAltitudeMismatchUnconfirmsAndBlocks() = withController { controller, client ->
        // Caso real: la montura no se reinició y el tubo ya no está donde cree la app (encoders a +60°).
        controller.confirmHomePosition()
        client.setPosition(Axis.ALTITUDE, controller.angleToSteps(Axis.ALTITUDE, 60.0 * DEG))
        val bad = controller.checkAltitude(measuredAltDeg = 0.5)   // el teléfono mide el tubo horizontal
        assertFalse(bad.ok)
        assertEquals(59.5, -bad.differenceDeg, 0.6)
        assertEquals(StartPosition.UNCONFIRMED, controller.state.value.startPosition)
        assertTrue(controller.goto(polaris(), trackAfter = false) is GotoResult.Rejected)

        controller.confirmHomePosition()
        val good = controller.checkAltitude(measuredAltDeg = 1.5)  // pequeño error del sensor: aceptable
        assertTrue(good.ok)
        assertEquals(StartPosition.HOME, controller.state.value.startPosition)
    }

    @Test fun phoneMeasuredStartAllowsGoto() = withController { controller, _ ->
        controller.setInitialOrientation(azDeg = 12.0, altDeg = 3.0)
        assertEquals(StartPosition.PHONE, controller.state.value.startPosition)
        val r = controller.goto(polaris(), trackAfter = false)
        assertFalse("$r", r is GotoResult.Rejected)
    }
}
