package com.opensky.virtuoso.alignment

import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.math.ARCMIN
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.astronomy.math.Mat3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class AlignmentTest {

    /** Montura "real": trípode inclinado 1.5°, acimut desplazado 37°, índice, cono y NP. */
    private val truth = PointingModel(
        rotation = Mat3.fromRotationVector(1.2 * DEG, -0.9 * DEG, 37.0 * DEG),
        indexAlt = 0.8 * DEG, cone = 0.35 * DEG, nonPerpendicularity = 0.2 * DEG,
    )

    private fun sky(azDeg: Double, altDeg: Double) = HorizontalCoord.fromDegrees(azDeg, altDeg)

    private fun sample(model: PointingModel, hz: HorizontalCoord, noiseArcmin: Double = 0.0, rnd: Random = Random(1)): AlignmentSample {
        val (a, e) = model.skyToMount(hz)!!
        return AlignmentSample("s", hz, a + rnd.nextDouble(-1.0, 1.0) * noiseArcmin * ARCMIN, e + rnd.nextDouble(-1.0, 1.0) * noiseArcmin * ARCMIN)
    }

    private fun maxErrorArcmin(fitted: PointingModel, reference: PointingModel, points: List<HorizontalCoord>): Double =
        points.maxOf { hz ->
            val (a1, e1) = fitted.skyToMount(hz)!!
            val (a2, e2) = reference.skyToMount(hz)!!
            fitted.mountToSky(a1, e1).separation(fitted.mountToSky(a2, e2)) / ARCMIN
        }

    private val testPoints = listOf(sky(10.0, 30.0), sky(100.0, 50.0), sky(200.0, 20.0), sky(300.0, 70.0), sky(45.0, 82.0))

    @Test fun modelInverseIsExact() {
        val rnd = Random(3)
        repeat(200) {
            val hz = sky(rnd.nextDouble(0.0, 360.0), rnd.nextDouble(-5.0, 85.0))
            val (a, e) = truth.skyToMount(hz)!!
            assertTrue(truth.mountToSky(a, e).separation(hz) < 1e-9)
        }
        val (a, e) = PointingModel.IDENTITY.skyToMount(sky(123.0, 45.0))!!
        assertEquals(123.0, Angles.norm360(a / DEG), 1e-9); assertEquals(45.0, e / DEG, 1e-9)
    }

    @Test fun twoStarRecoversTiltedTripodWithoutConeErrors() {
        val simple = truth.copy(cone = 0.0, nonPerpendicularity = 0.0)
        val fit = AlignmentSolver.fit(listOf(sample(simple, sky(80.0, 35.0)), sample(simple, sky(190.0, 55.0))))
        assertTrue(maxErrorArcmin(fit.model, simple, testPoints) < 0.05)
        assertNull(fit.quality.rmsArcmin) // ajuste exacto: sin grados de libertad para estimar el error
    }

    @Test fun fourStarsRecoverFullModel() {
        val stars = listOf(sky(20.0, 25.0), sky(110.0, 60.0), sky(205.0, 40.0), sky(290.0, 75.0), sky(330.0, 15.0))
        val fit = AlignmentSolver.fit(stars.map { sample(truth, it) })
        assertTrue("error=${maxErrorArcmin(fit.model, truth, testPoints)}", maxErrorArcmin(fit.model, truth, testPoints) < 0.5)
        assertTrue(fit.quality.rmsArcmin!! < 0.2)
        assertTrue(fit.quality.precisionPercent!! > 99.5)
    }

    @Test fun noisyAlignmentReportsRealisticPrecision() {
        val rnd = Random(11)
        val stars = (0 until 8).map { sky(it * 45.0 + 7, 20.0 + (it % 4) * 15.0) }
        val fit = AlignmentSolver.fit(stars.map { sample(truth, it, noiseArcmin = 3.0, rnd = rnd) }, referenceFieldRadiusArcmin = 52.0)
        val q = fit.quality
        assertEquals(10, q.degreesOfFreedom)
        assertTrue("rms=${q.rmsArcmin}", q.rmsArcmin!! in 0.5..4.0)
        assertTrue(q.precisionPercent!! in 90.0..99.5)
        assertTrue(q.fieldHitProbability!! > 0.99)
        assertTrue(maxErrorArcmin(fit.model, truth, testPoints) < 6.0)
    }

    @Test fun singleStarHasNoErrorEstimate() {
        val level = PointingModel(rotation = Mat3.rotZ(5 * DEG), indexAlt = 0.5 * DEG)
        val fit = AlignmentSolver.fit(listOf(sample(level, sky(150.0, 40.0))))
        assertNull(fit.quality.rmsArcmin)
        assertNull(fit.quality.precisionPercent)
        assertTrue(maxErrorArcmin(fit.model, level, testPoints) < 0.05)
    }

    @Test fun syncSamplesLocallyImproveNearbyPointing() {
        val rnd = Random(5)
        val manager = AlignmentManager()
        manager.begin(AlignmentMode.THREE_STAR)
        listOf(sky(30.0, 30.0), sky(150.0, 50.0), sky(260.0, 35.0)).forEach { manager.addAlignmentStar(sample(truth, it, 2.0, rnd)) }
        assertTrue(manager.state.value.isComplete)
        val probe = sky(95.0, 62.0)
        val syncSample = sample(truth, probe, 0.0)
        val before = abs(manager.skyToMount(probe)!!.second - syncSample.altEncoder) / ARCMIN
        manager.addSyncSample(syncSample)
        val after = manager.skyToMount(probe)!!
        val errAfter = manager.model.mountToSky(after.first, after.second).separation(truth.mountToSky(after.first, after.second))
        val (ta, te) = truth.skyToMount(probe)!!
        val residualAfter = maxOf(abs(Angles.normPi(after.first - ta)), abs(after.second - te)) / ARCMIN
        assertTrue("antes=$before después=$residualAfter (${errAfter / ARCMIN})", residualAfter < 1.0)
        manager.reset()
        assertEquals(PointingModel.IDENTITY, manager.model)
    }
}
