package com.opensky.virtuoso.control

import com.opensky.virtuoso.alignment.AlignmentManager
import com.opensky.virtuoso.alignment.AlignmentMode
import com.opensky.virtuoso.alignment.PointingModel
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystemBody
import com.opensky.virtuoso.astronomy.math.ARCMIN
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Prueba de extremo a extremo contra el simulador UDP: alineación SynMatrix de 4 estrellas sobre
 * una montura con trípode inclinado, error de índice, cono y no perpendicularidad; luego GoTo y
 * seguimiento a una quinta estrella. La "verdad física" la define [truth].
 */
class MountControllerTest {

    private val truth = PointingModel(Mat3.fromRotationVector(0.8 * DEG, -0.6 * DEG, 25.0 * DEG), 0.5 * DEG, 0.2 * DEG, 0.1 * DEG)
    private val site = Site(GeoLocation(28.6353, -106.0889, 1430.0))

    @Test fun synMatrixAlignmentThenGotoAndTrack() = runBlocking {
        val sim = MountSimulator(gotoTimeScale = 25.0).start()
        val client = MountClient(UdpMountTransport("127.0.0.1", sim.boundPort), MountClient.Config(timeoutMs = 200, maxAttempts = 5))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val clock = OffsetAstroClock()
        val alignment = AlignmentManager()
        var trackingMode = TrackingMode.VISUAL
        val controller = MountController(client, alignment, scope, clock, { site }, {
            ControllerConfig(minAltitudeDeg = -5.0, trackingUpdateMs = 400, pollIntervalMs = 300, gotoSpeedDegPerSec = 3.5 * 25,
                trackingMode = trackingMode)
        })
        val catalog = TargetCatalog()
        try {
            controller.connect()
            assertEquals(Activity.IDLE, controller.state.value.activity)

            // El Sol exige confirmación explícita
            val sun = controller.goto(SolarSystemTarget(SolarSystemBody.SUN))
            assertTrue(sun is GotoResult.Rejected && sun.needsConfirmation)

            suspend fun trueEncodersFor(target: Target): Pair<Double, Double> {
                val hz = TargetResolver.position(target, clock.now(), site.location, site.weather).horizontal
                return truth.skyToMount(hz)!!
            }

            /** Simula al usuario centrando la estrella en el ocular. */
            suspend fun centerOn(target: Target) {
                repeat(2) {
                    val (a, e) = trueEncodersFor(target)
                    val (az, _) = controller.readEncoders()
                    client.gotoSteps(Axis.AZIMUTH, controller.angleToSteps(Axis.AZIMUTH, az + Angles.normPi(a - az)))
                    client.gotoSteps(Axis.ALTITUDE, controller.angleToSteps(Axis.ALTITUDE, e))
                    client.waitUntilStopped(Axis.AZIMUTH, 20_000, 30); client.waitUntilStopped(Axis.ALTITUDE, 20_000, 30)
                }
            }

            alignment.begin(AlignmentMode.SYNMATRIX_AUTO)
            val stars = alignment.suggestStars(catalog, clock.now(), site.location, count = 5, minAltDeg = 15.0, maxAltDeg = 80.0)
            assertTrue("se necesitan 5 estrellas visibles, hay ${stars.size}", stars.size == 5)
            for ((star, _) in stars.take(4)) {
                centerOn(star)
                controller.sync(star, asAlignmentStar = true)
            }
            val q = alignment.state.value.quality
            assertTrue(alignment.state.value.isComplete)
            assertTrue("RMS=${q.rmsArcmin}", q.rmsArcmin!! < 2.0)

            val probe = stars[4].first
            val result = controller.goto(probe, trackAfter = true)
            assertTrue("$result", result is GotoResult.Success)
            assertEquals(Activity.TRACKING, controller.state.value.activity)
            delay(2500) // deja actuar el lazo de seguimiento
            val (az, alt) = controller.readEncoders()
            val (ta, te) = trueEncodersFor(probe)
            val errorArcmin = max(abs(Angles.normPi(az - ta)) * kotlin.math.cos(alt), abs(alt - te)) / ARCMIN
            // Incluye el error del modelo en una estrella NO usada para alinear (depende del cielo de la hora de la prueba).
            assertTrue("error de seguimiento = $errorArcmin′", errorArcmin < 5.0)

            // Centrado con el pad MIENTRAS se sigue el objeto (caso real: alineación con trackAfter = true).
            // 64× sidérea durante 3 s ≈ 48′ en el eje de acimut; el seguimiento no debe anularlo.
            suspend fun azOffsetFromTargetArcmin(): Double {
                val (a, e) = controller.readEncoders()
                val (t, _) = trueEncodersFor(probe)
                return Angles.normPi(a - t) * kotlin.math.cos(e) / ARCMIN
            }
            val before = azOffsetFromTargetArcmin()
            controller.manualStart(PadDirection.RIGHT, 5, PadMode.ALT_AZ)
            delay(3000)
            controller.manualStop()
            val moved = azOffsetFromTargetArcmin() - before
            val expected = 64 * 15.041 * 3 / 60 * kotlin.math.cos(controller.readEncoders().second)
            assertTrue("el pad movió $moved′ de ~$expected′ esperados", abs(moved) > 0.6 * expected)
            delay(2500) // tras soltar, el seguimiento conserva el nuevo centrado (no vuelve atrás)
            val kept = azOffsetFromTargetArcmin() - before
            assertTrue("tras soltar quedó $kept′ (movido $moved′)", abs(kept - moved) < 3.0)
            assertEquals(Activity.TRACKING, controller.state.value.activity)

            // Sentido contrario (invierte el motor de acimut mientras sigue)
            controller.manualStart(PadDirection.LEFT, 5, PadMode.ALT_AZ)
            delay(3000)
            controller.manualStop()
            val back = azOffsetFromTargetArcmin() - before
            assertTrue("al volver con ◀ quedó $back′ (antes $kept′)", abs(back) < 0.4 * abs(kept))

            // Modo cámara: mismo objetivo, seguimiento suave; debe mantenerlo sin reposicionar.
            trackingMode = TrackingMode.IMAGING
            val r2 = controller.goto(probe, trackAfter = true)
            assertTrue("$r2", r2 is GotoResult.Success)
            val samples = mutableListOf<Double>()
            repeat(8) {
                delay(500)
                val (a2, e2) = controller.readEncoders()
                val (ta2, te2) = trueEncodersFor(probe)
                samples += max(abs(Angles.normPi(a2 - ta2)) * kotlin.math.cos(e2), abs(e2 - te2)) / ARCMIN
            }
            assertEquals(Activity.TRACKING, controller.state.value.activity)
            assertTrue("errores en modo cámara: $samples", samples.all { it < 5.0 })
            // Estable: el error no salta entre muestras (sin reposicionamientos).
            assertTrue("saltos: $samples", samples.zipWithNext().all { (x, y) -> abs(x - y) < 0.5 })

            controller.stopAll()
            assertEquals(Activity.IDLE, controller.state.value.activity)
            assertTrue(!client.status(Axis.AZIMUTH).running && !client.status(Axis.ALTITUDE).running)
        } finally {
            controller.disconnect()
            scope.cancel()
            sim.close()
        }
    }

    /**
     * Caso real reportado: la montura se enciende apuntando al SUR (no en la posición de inicio
     * hacia el norte) y sin orientación por sensores. El primer GoTo (Polaris) cae ~180° desviado;
     * tras centrarla a mano y confirmar, la segunda estrella ya debe caer cerca.
     */
    @Test fun firstStarFixesA180DegreeStartingOffset() = runBlocking {
        val upsideTruth = PointingModel(Mat3.fromRotationVector(0.4 * DEG, -0.3 * DEG, 180.0 * DEG), 0.3 * DEG, 0.0, 0.0)
        val sim = MountSimulator(gotoTimeScale = 25.0).start()
        val client = MountClient(UdpMountTransport("127.0.0.1", sim.boundPort), MountClient.Config(timeoutMs = 200, maxAttempts = 5))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val clock = OffsetAstroClock()
        val alignment = AlignmentManager()
        val controller = MountController(client, alignment, scope, clock, { site }, {
            ControllerConfig(minAltitudeDeg = -5.0, trackingUpdateMs = 400, pollIntervalMs = 300, gotoSpeedDegPerSec = 3.5 * 25)
        })
        val catalog = TargetCatalog()
        try {
            controller.connect()
            suspend fun trueEncodersFor(target: Target): Pair<Double, Double> =
                upsideTruth.skyToMount(TargetResolver.position(target, clock.now(), site.location, site.weather).horizontal)!!
            suspend fun errorDeg(target: Target): Double {
                val (az, alt) = controller.readEncoders()
                val (ta, te) = trueEncodersFor(target)
                return max(abs(Angles.normPi(az - ta)) * kotlin.math.cos(alt), abs(alt - te)) / DEG
            }
            suspend fun centerOn(target: Target) {
                repeat(2) {
                    val (a, e) = trueEncodersFor(target)
                    val (az, _) = controller.readEncoders()
                    client.gotoSteps(Axis.AZIMUTH, controller.angleToSteps(Axis.AZIMUTH, az + Angles.normPi(a - az)))
                    client.gotoSteps(Axis.ALTITUDE, controller.angleToSteps(Axis.ALTITUDE, e))
                    client.waitUntilStopped(Axis.AZIMUTH, 20_000, 30); client.waitUntilStopped(Axis.ALTITUDE, 20_000, 30)
                }
            }
            // El usuario confirma «horizontal hacia el norte» aunque en realidad el tubo apunta al sur.
            controller.confirmHomePosition()
            alignment.begin(AlignmentMode.SYNMATRIX_AUTO)
            val polaris = catalog.search("Polaris").first()
            assertTrue(controller.goto(polaris, trackAfter = true) is GotoResult.Success)
            val firstError = errorDeg(polaris)
            assertTrue("el primer GoTo debía caer muy lejos: $firstError°", firstError > 150)

            centerOn(polaris)                                   // el usuario la busca con el pad
            controller.sync(polaris, asAlignmentStar = true)    // «Estrella centrada ✓»

            val stars = alignment.suggestStars(catalog, clock.now(), site.location, count = 4, minAltDeg = 20.0, maxAltDeg = 75.0)
            val second = stars.first { (s, _) -> s.id != polaris.id }.first
            assertTrue(controller.goto(second, trackAfter = true) is GotoResult.Success)
            val secondError = errorDeg(second)
            // Con una sola estrella no se conoce la inclinación de la base: error de pocos grados como mucho.
            assertTrue("segundo GoTo tras corregir con Polaris: $secondError°", secondError < 3.0)
            println("Primer GoTo: %.1f° de error; segundo tras confirmar Polaris: %.2f°".format(firstError, secondError))
        } finally {
            controller.disconnect()
            scope.cancel()
            sim.close()
        }
    }
}
