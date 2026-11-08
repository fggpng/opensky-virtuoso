package com.opensky.virtuoso.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalSetupTest {

    private fun visual(id: String) = OpticalSetup(ObservingMode.VISUAL, Equipment.eyepiece(id))

    @Test fun eyepieceFields() {
        val s25 = visual("sw-super-25")
        assertEquals(30.0, s25.magnification!!, 1e-9)
        assertEquals(104.0, s25.fieldWidthArcmin, 0.1)        // 52° / 30×
        assertEquals(52.0, s25.referenceRadiusArcmin, 0.1)     // igual que el valor fijo anterior
        assertEquals(75.0, visual("sw-super-10").magnification!!, 1e-9)
        assertEquals(41.6, visual("sw-super-10").fieldWidthArcmin, 0.1)
        assertEquals(120.0, visual("xcel-lx-25").fieldWidthArcmin, 0.1) // 60° / 30×
        assertNull(s25.arcsecPerPixel)
        assertEquals(TrackingMode.VISUAL, s25.trackingMode)
    }

    @Test fun cameraFields() {
        val prime = OpticalSetup(ObservingMode.CAMERA_PRIME)
        assertEquals(102.2, prime.fieldWidthArcmin, 0.2)   // 22.3 mm a 750 mm
        assertEquals(68.3, prime.fieldHeightArcmin, 0.2)
        assertEquals(1.02, prime.arcsecPerPixel!!, 0.01)   // 3.72 µm a 750 mm
        assertEquals(5.0, prime.focalRatio, 1e-9)
        assertEquals(34.2, prime.referenceRadiusArcmin, 0.2)

        val barlow = OpticalSetup(ObservingMode.CAMERA_BARLOW)
        assertEquals(1500.0, barlow.effectiveFocalMm, 1e-9)
        assertEquals(51.1, barlow.fieldWidthArcmin, 0.2)
        assertEquals(0.51, barlow.arcsecPerPixel!!, 0.01)
        assertEquals(10.0, barlow.focalRatio, 1e-9)
        assertEquals(1800.0, barlow.copy(barlowFactor = 2.4).effectiveFocalMm, 1e-9)
        assertEquals(TrackingMode.IMAGING, barlow.trackingMode)
        assertTrue(barlow.defaultPadRateIndex < prime.defaultPadRateIndex)
    }

    @Test fun fieldRotationLimitsExposure() {
        val lat = Math.toRadians(28.6)
        val prime = OpticalSetup(ObservingMode.CAMERA_PRIME)
        // Al sur a 45°: ω = 7.29e-5 · cos 28.6° / cos 45° ≈ 9.06e-5 rad/s; esquina a 3606 px → ≈ 3.1 s por píxel.
        val south = FieldRotation.maxExposureSec(prime, lat, Math.toRadians(180.0), Math.toRadians(45.0))!!
        assertEquals(3.06, south, 0.05)
        // Mismo límite con Barlow: depende de los píxeles del sensor, no de la focal.
        assertEquals(south, FieldRotation.maxExposureSec(OpticalSetup(ObservingMode.CAMERA_BARLOW), lat, Math.toRadians(180.0), Math.toRadians(45.0))!!, 1e-9)
        // Hacia el este u oeste la rotación casi desaparece.
        assertTrue(FieldRotation.maxExposureSec(prime, lat, Math.toRadians(90.0), Math.toRadians(45.0))!! > 600)
        // Cerca del cenit se dispara (exposiciones muy cortas).
        assertTrue(FieldRotation.maxExposureSec(prime, lat, Math.toRadians(180.0), Math.toRadians(85.0))!! < 0.5)
        // Con 3 píxeles de tolerancia, el triple.
        assertEquals(3 * south, FieldRotation.maxExposureSec(prime, lat, Math.toRadians(180.0), Math.toRadians(45.0), 3.0)!!, 1e-9)
        assertNull(FieldRotation.maxExposureSec(OpticalSetup(ObservingMode.VISUAL), lat, 0.0, 0.5))
        assertEquals("3.1 s", FieldRotation.format(south))
        assertEquals("> 10 min", FieldRotation.format(Double.POSITIVE_INFINITY))
    }
}
