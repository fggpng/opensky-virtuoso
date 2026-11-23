package com.opensky.virtuoso.control

import com.opensky.virtuoso.alignment.AlignmentManager
import com.opensky.virtuoso.alignment.AlignmentMode
import com.opensky.virtuoso.alignment.MoonAlignment
import com.opensky.virtuoso.alignment.PointingModel
import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystemBody
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.astronomy.math.Mat3
import com.opensky.virtuoso.database.targets.SolarSystemTarget
import com.opensky.virtuoso.database.targets.Target
import com.opensky.virtuoso.database.targets.TargetCatalog
import com.opensky.virtuoso.database.targets.TargetResolver
import com.opensky.virtuoso.network.MountClient
import com.opensky.virtuoso.network.protocol.Axis
import com.opensky.virtuoso.network.simulator.MountSimulator
import com.opensky.virtuoso.network.transport.UdpMountTransport
import com.opensky.virtuoso.platform.epochMillisNow
import com.opensky.virtuoso.platform.fmt
import com.opensky.virtuoso.testing.assertFalse
import com.opensky.virtuoso.testing.assertTrue
import com.opensky.virtuoso.testing.isoMillis
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * «Luna + 1 estrella» de extremo a extremo contra el simulador: arranque escrito a mano con la
 * brújula equivocada 8°, trípode inclinado y error de índice. La Luna corrige el rumbo; la estrella
 * lejana, la inclinación; después un GoTo a una tercera estrella debe caer dentro del ocular.
 */
class MoonAlignmentFlowTest {

    private val site = Site(GeoLocation(28.5933, -106.1616, 1505.0))

    /** Verdad física respecto a los encoders tras el arranque manual: 8° de rumbo, 1.2°/0.9° de inclinación. */
    private val truth = PointingModel(Mat3.fromRotationVector(1.2 * DEG, -0.9 * DEG, 8.0 * DEG), 0.4 * DEG, 0.0, 0.0)

    /** Una noche cerrada con la Luna bien alta (para que la prueba no dependa del día en que se ejecute). */
    private fun moonlitNight(): Long {
        var ms = isoMillis("2026-11-20T00:00:00Z")
        val sun = SolarSystemTarget(SolarSystemBody.SUN)
        repeat(24 * 40) {
            val t = AstroTime.fromEpochMillis(ms)
            val moon = MoonAlignment.reference(t, site.location, site.weather)
            val sunAlt = TargetResolver.position(sun, t, site.location).horizontal.altDeg
            if (moon.usable && moon.horizontal!!.altDeg in 30.0..65.0 && sunAlt < -15.0) return ms
            ms += 3_600_000L
        }
        error("no hubo ninguna noche con Luna alta")
    }

    @Test fun moonThenOneStarAlignsATiltedMountStartedFromACompass() = runBlocking {
        val clock = OffsetAstroClock(offsetMillis = moonlitNight() - epochMillisNow())
        val sim = MountSimulator(gotoTimeScale = 25.0).start()
        val client = MountClient(UdpMountTransport("127.0.0.1", sim.boundPort), MountClient.Config(timeoutMs = 200, maxAttempts = 5))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val alignment = AlignmentManager()
        val controller = MountController(client, alignment, scope, clock, { site }, {
            ControllerConfig(minAltitudeDeg = -5.0, trackingUpdateMs = 400, pollIntervalMs = 300, gotoSpeedDegPerSec = 3.5 * 25)
        })
        val catalog = TargetCatalog()
        try {
            controller.connect()
            suspend fun trueEncodersFor(target: Target): Pair<Double, Double> =
                truth.skyToMount(TargetResolver.position(target, clock.now(), site.location, site.weather).horizontal)!!
            suspend fun errorDeg(target: Target): Double {
                val (az, alt) = controller.readEncoders()
                val (ta, te) = trueEncodersFor(target)
                return max(abs(Angles.normPi(az - ta)) * kotlin.math.cos(alt), abs(alt - te)) / DEG
            }
            suspend fun centerOn(target: Target) { // el usuario la centra con el pad
                repeat(2) {
                    val (a, e) = trueEncodersFor(target)
                    val (az, _) = controller.readEncoders()
                    client.gotoSteps(Axis.AZIMUTH, controller.angleToSteps(Axis.AZIMUTH, az + Angles.normPi(a - az)))
                    client.gotoSteps(Axis.ALTITUDE, controller.angleToSteps(Axis.ALTITUDE, e))
                    client.waitUntilStopped(Axis.AZIMUTH, 20_000, 30); client.waitUntilStopped(Axis.ALTITUDE, 20_000, 30)
                }
            }

            // «Escribir hacia dónde apunta el tubo»: 351° de brújula, tubo horizontal.
            controller.setInitialOrientation(351.0, 0.0, StartPosition.MANUAL)
            alignment.begin(AlignmentMode.MOON_ONE_STAR)
            assertTrue(alignment.state.value.isMoonStep)

            // 1) La Luna: el GoTo cae lejos por la brújula, pero se encuentra a simple vista.
            val moon = MoonAlignment.target
            assertTrue(controller.goto(moon, trackAfter = false) is GotoResult.Success)
            val moonError = errorDeg(moon)
            assertTrue("el GoTo a la Luna debía notar el error de brújula: $moonError°", moonError > 2.0)
            centerOn(moon)
            controller.sync(moon, asAlignmentStar = true)
            assertFalse(alignment.state.value.isMoonStep)

            // 2) La estrella sugerida queda lejos de la Luna, para medir la inclinación del trípode.
            val moonSky = TargetResolver.position(moon, clock.now(), site.location, site.weather).horizontal
            val (star, starSky) = alignment.suggestStars(catalog, clock.now(), site.location, site.weather, count = 4).first()
            val separation = starSky.separation(moonSky) / DEG
            assertTrue("estrella a $separation° de la Luna", separation > 45.0)
            assertTrue(controller.goto(star, trackAfter = false) is GotoResult.Success)
            val starError = errorDeg(star)
            assertTrue("con solo la Luna la estrella debía caer cerca: $starError°", starError < 4.0)
            centerOn(star)
            controller.sync(star, asAlignmentStar = true)
            assertTrue(alignment.state.value.isComplete)

            // 3) Comprobación: otra estrella cae dentro del campo del ocular.
            val third = alignment.suggestStars(catalog, clock.now(), site.location, site.weather, count = 6, exclude = setOf(star.name)).first().first
            assertTrue(controller.goto(third, trackAfter = false) is GotoResult.Success)
            val finalArcmin = errorDeg(third) * 60.0
            assertTrue("tras Luna + ${star.name}, ${third.name} cayó a $finalArcmin′", finalArcmin < 10.0)
            println("Luna: GoTo a %.1f°; %s (a %.0f° de la Luna) a %.2f°; %s tras alinear a %.1f′"
                .fmt(moonError, star.name, separation, starError, third.name, finalArcmin))
        } finally {
            runCatching { controller.disconnect() }
            scope.cancel()
            sim.close()
        }
    }
}
