package com.opensky.virtuoso.control

import com.opensky.virtuoso.alignment.AlignmentManager
import com.opensky.virtuoso.astronomy.AstroEngine
import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystemBody
import com.opensky.virtuoso.astronomy.math.ARCMIN
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.database.targets.SolarSystemTarget
import com.opensky.virtuoso.database.targets.StarTarget
import com.opensky.virtuoso.database.targets.TargetCatalog
import com.opensky.virtuoso.database.targets.TargetCategory
import com.opensky.virtuoso.database.targets.TargetResolver
import com.opensky.virtuoso.network.MountClient
import com.opensky.virtuoso.network.simulator.MountSimulator
import com.opensky.virtuoso.network.transport.UdpMountTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Cada prueba provoca a propósito un modo de falla en el simulador y comprueba el control:
 * que la app detecte la falla, detenga la montura y avise, en lugar de seguir como si nada.
 */
class FailureModesTest {

    private val site = Site(GeoLocation(28.5933, -106.1616, 1505.0)) // Chihuahua

    private class Rig(
        val sim: MountSimulator, val client: MountClient, val controller: MountController,
        val clock: OffsetAstroClock, val alignment: AlignmentManager, val scope: CoroutineScope,
    )

    private fun rig(
        siteKnown: () -> Boolean = { true },
        cfg: () -> ControllerConfig = { fastConfig() },
        block: suspend Rig.() -> Unit,
    ) = runBlocking {
        val sim = MountSimulator(gotoTimeScale = 25.0).start()
        val client = MountClient(UdpMountTransport("127.0.0.1", sim.boundPort), MountClient.Config(timeoutMs = 150, maxAttempts = 3))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val clock = OffsetAstroClock()
        val alignment = AlignmentManager()
        val controller = MountController(client, alignment, scope, clock, { site }, cfg, siteKnown)
        try {
            controller.connect()
            Rig(sim, client, controller, clock, alignment, scope).block()
        } finally {
            runCatching { controller.disconnect() }
            scope.cancel(); sim.close()
        }
    }

    private fun fastConfig(invertAlt: Boolean = false) = ControllerConfig(
        minAltitudeDeg = -5.0, trackingUpdateMs = 400, pollIntervalMs = 250, gotoSpeedDegPerSec = 3.5 * 25,
        stallWindowMs = 1_000, maxManualMs = 1_500, invertAltitude = invertAlt,
    )

    private fun star(name: String) = TargetCatalog().search(name).first()

    private suspend fun Rig.waitFor(timeoutMs: Long = 6_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(50) }
    }

    @Test fun withoutLocationNothingPoints() = rig(siteKnown = { false }) {
        controller.confirmHomePosition()
        val r = controller.goto(star("Polaris"), trackAfter = false)
        assertTrue("$r", r is GotoResult.Rejected && r.reason.contains("ubicación"))
        val sync = runCatching { controller.sync(star("Polaris"), asAlignmentStar = true) }
        assertTrue(sync.isFailure)
    }

    @Test fun stalledMotorStopsGotoAndAlerts() = rig {
        controller.confirmHomePosition()
        sim.setStalled('2', true) // el tubo no puede subir (algo lo frena)
        val r = controller.goto(star("Polaris"), trackAfter = false)
        assertTrue("$r", r is GotoResult.Failed && r.error.contains("no avanza"))
        assertNotNull(controller.state.value.alert)
        assertFalse("el eje bloqueado debe quedar detenido", sim.isRunning('2'))
        assertFalse(sim.isRunning('1'))
    }

    @Test fun mountRebootIsDetectedAndPositionInvalidated() = rig {
        controller.confirmHomePosition()
        assertTrue(controller.goto(star("Polaris"), trackAfter = false) is GotoResult.Success)
        sim.simulateReboot() // baterías bajas: la montura se reinicia y sus contadores vuelven a 0
        waitFor { controller.state.value.alert == MountController.MOUNT_RESET_ALERT }
        assertEquals(StartPosition.UNCONFIRMED, controller.state.value.startPosition)
        // Y queda bloqueado el GoTo hasta confirmar de nuevo.
        assertTrue(controller.goto(star("Polaris"), trackAfter = false) is GotoResult.Rejected)
    }

    @Test fun manualMovementHasADeadmanTimeout() = rig {
        controller.confirmHomePosition()
        controller.manualStart(PadDirection.RIGHT, 5, PadMode.ALT_AZ) // y nunca llega el «soltar»
        assertTrue(sim.isRunning('1'))
        waitFor(5_000) { !sim.isRunning('1') }
        assertTrue(controller.state.value.message!!.contains("seguridad"))
    }

    @Test fun releaseWithoutPressDoesNotStopAGoto() = rig {
        controller.confirmHomePosition()
        val g = scope.async { controller.goto(star("Polaris"), trackAfter = false) }
        delay(150)
        controller.manualStop() // p. ej. un cliente de Stellarium que se desconecta sin haber movido nada
        assertTrue(g.await() is GotoResult.Success)
    }

    @Test fun twoGotosNeverRunAtTheSameTime() = rig {
        controller.confirmHomePosition()
        val first = scope.async { controller.goto(star("Kochab"), trackAfter = false) }
        delay(120)
        val second = controller.goto(star("Alderamin"), trackAfter = false)
        val firstResult = first.await()
        assertTrue("$second", second is GotoResult.Success)
        assertTrue("el primero debía cancelarse: $firstResult", firstResult is GotoResult.Rejected)
        val sky = TargetResolver.position(star("Alderamin"), clock.now(), site.location, site.weather).horizontal
        val err = controller.refreshPointing().pointing!!.separation(sky) / ARCMIN
        assertTrue("terminó a $err′ de Alderamin", err < 30)
    }

    @Test fun invertingAnAxisMidSessionInvalidatesEverything() {
        var invert = false
        rig(cfg = { fastConfig(invertAlt = invert) }) {
            controller.confirmHomePosition()
            invert = true
            waitFor { controller.state.value.alert == MountController.AXIS_CHANGED_ALERT }
            assertEquals(StartPosition.UNCONFIRMED, controller.state.value.startPosition)
        }
    }

    @Test fun confirmingTheWrongStarIsFlagged() = rig {
        controller.confirmHomePosition() // tubo horizontal al norte
        assertEquals(20.0, controller.syncDeviationLimitDeg()!!, 1e-9)
        // Sin mover el tubo, «confirmar» Polaris (28.6° más arriba) sería un error: debe detectarse.
        val wrong = controller.syncDeviationDeg(star("Polaris"))!!
        assertTrue("desvío $wrong°", wrong > 20)
        assertTrue(controller.goto(star("Polaris"), trackAfter = false) is GotoResult.Success)
        val right = controller.syncDeviationDeg(star("Polaris"))!!
        assertTrue("desvío $right°", right < 1)
    }

    @Test fun unconfirmedStopRaisesAlert() = rig {
        controller.confirmHomePosition()
        sim.close() // la Wi-Fi de la montura se cae: nadie confirma la parada
        val ok = controller.stopAll()
        assertFalse(ok)
        assertEquals(MountController.STOP_NOT_CONFIRMED_ALERT, controller.state.value.alert)
    }

    /** Coloca el reloj en el mediodía solar de Chihuahua (Sol alto al sur). */
    private fun Rig.setNoon(): HorizontalCoord {
        clock.offsetMillis = Instant.parse("2026-11-10T18:48:00Z").toEpochMilli() - System.currentTimeMillis()
        val sun = TargetResolver.position(SolarSystemTarget(SolarSystemBody.SUN), clock.now(), site.location, site.weather).horizontal
        assertTrue("el Sol debe estar alto: ${sun.altDeg}", sun.altDeg > 30)
        return sun
    }

    private fun Rig.fixedTarget(azDeg: Double, altDeg: Double, name: String): StarTarget {
        val now: AstroTime = clock.now()
        val apparent: EquatorialCoord = AstroEngine.horizontalToApparent(HorizontalCoord.fromDegrees(azDeg, altDeg), now, site.location, site.weather)
        val j2000 = AstroEngine.apparentToJ2000(apparent, now)
        return StarTarget("test:$name", name, "prueba", j2000, 0.0, 0.0, null, TargetCategory.USER_OBJECT)
    }

    @Test fun gotoPathCrossingTheSunIsRejected() = rig {
        val sun = setNoon()
        // Origen y destino a ~21° del Sol (permitidos), pero el camino pasa a ~10° de él.
        controller.setInitialOrientation(sun.azDeg - 25, sun.altDeg - 10)
        val target = fixedTarget(sun.azDeg + 25, sun.altDeg - 10, "lado opuesto del Sol")
        val r = controller.goto(target, trackAfter = false)
        assertTrue("$r", r is GotoResult.Rejected && r.reason.contains("Sol"))
        assertFalse(sim.isRunning('1'))
    }

    @Test fun padCannotApproachTheSunButCanMoveAway() = rig {
        val sun = setNoon()
        controller.setInitialOrientation(sun.azDeg - 12, sun.altDeg - 4) // ya a ~11° del Sol
        controller.refreshPointing()
        controller.manualStart(PadDirection.RIGHT, 5, PadMode.ALT_AZ) // hacia el Sol
        assertFalse("no debe moverse hacia el Sol", sim.isRunning('1'))
        assertTrue(controller.state.value.message!!.contains("Sol"))
        controller.manualStart(PadDirection.LEFT, 5, PadMode.ALT_AZ) // alejándose
        assertTrue("sí debe poder alejarse", sim.isRunning('1'))
        controller.manualStop()
        assertNull(controller.state.value.alert)
    }
}
