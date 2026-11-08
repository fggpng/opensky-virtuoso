package com.opensky.virtuoso.network

import com.opensky.virtuoso.network.protocol.Axis
import com.opensky.virtuoso.network.protocol.AxisStatus
import com.opensky.virtuoso.network.protocol.MountErrorCode
import com.opensky.virtuoso.network.protocol.MountProtocolException
import com.opensky.virtuoso.network.protocol.SkyWatcherCodec
import com.opensky.virtuoso.network.protocol.SwCommand
import com.opensky.virtuoso.network.simulator.MountSimulator
import com.opensky.virtuoso.network.transport.UdpMountTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class SkyWatcherProtocolTest {

    @Test fun encodeDecode24_littleEndian() {
        assertEquals("563412", SkyWatcherCodec.encode24(0x123456))
        assertEquals(0x123456, SkyWatcherCodec.decodeLittleEndian("563412"))
        assertEquals(0x10, SkyWatcherCodec.decodeLittleEndian("10"))
        // Ejemplo real: ":S2D75D80" → objetivo 0x805DD7 → +24023 pasos
        assertEquals(24023, SkyWatcherCodec.decodePosition("D75D80"))
        assertEquals("000080", SkyWatcherCodec.encodePosition(0))
        assertEquals(-1000, SkyWatcherCodec.decodePosition(SkyWatcherCodec.encodePosition(-1000)))
    }

    @Test fun commandFramingAndMotionModes() {
        assertEquals(":j1\r", SkyWatcherCodec.buildCommand(SwCommand.GET_POSITION, Axis.AZIMUTH))
        assertEquals(":F3\r", SkyWatcherCodec.buildCommand(SwCommand.INITIALIZE, Axis.BOTH))
        assertEquals("00", SkyWatcherCodec.motionModeData(goto = true, fast = true, reverse = false))
        assertEquals("21", SkyWatcherCodec.motionModeData(goto = true, fast = false, reverse = true))
        assertEquals("10", SkyWatcherCodec.motionModeData(goto = false, fast = false, reverse = false))
        assertEquals("31", SkyWatcherCodec.motionModeData(goto = false, fast = true, reverse = true))
    }

    @Test fun responseParsing() {
        assertEquals("A1B2C3", SkyWatcherCodec.parseResponse("=A1B2C3\r", SwCommand.GET_POSITION, ":j1\r"))
        try {
            SkyWatcherCodec.parseResponse("!2\r", SwCommand.SET_MOTION_MODE, ":G100\r"); fail()
        } catch (e: MountProtocolException) {
            assertEquals(MountErrorCode.MOTOR_NOT_STOPPED, e.error)
        }
        // Una respuesta de otro comando (longitud distinta) se rechaza para forzar reintento
        try {
            SkyWatcherCodec.parseResponse("=101\r", SwCommand.GET_POSITION, ":j1\r"); fail()
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun statusDecoding() {
        val st = AxisStatus.parse("511") // modo slew, rápido, en marcha, inicializado
        assertTrue(st.trackingMode); assertTrue(st.fast); assertTrue(st.running); assertTrue(st.initialized)
        assertFalse(st.reverse)
        val g = AxisStatus.parse("201") // GOTO, reversa, detenido
        assertFalse(g.trackingMode); assertTrue(g.reverse); assertFalse(g.running)
    }

    private fun <T> withSimulator(gotoTimeScale: Double = 1.0, dropRate: Double = 0.0, block: suspend (MountSimulator, MountClient) -> T): T {
        val sim = MountSimulator(gotoTimeScale = gotoTimeScale, dropRate = dropRate).start()
        val client = MountClient(UdpMountTransport("127.0.0.1", sim.boundPort), MountClient.Config(timeoutMs = 150, maxAttempts = 6))
        try {
            return runBlocking { block(sim, client) }
        } finally {
            client.disconnect(); sim.close()
        }
    }

    @Test fun connectReadsMountParameters() = withSimulator { sim, client ->
        val info = client.connect()
        assertEquals(sim.countsPerRevolution, info.azimuth.countsPerRevolution)
        assertEquals(sim.timerFrequency, info.altitude.timerFrequency)
        assertEquals(16, info.azimuth.highSpeedRatio)
        assertEquals("AZ-GTi / Virtuoso GTi", info.modelName)
        assertEquals("3.34", info.firmwareVersion)
        assertTrue(client.status(Axis.AZIMUTH).initialized)
        assertEquals(LinkState.CONNECTED, client.linkState.value)
    }

    @Test fun gotoReachesTarget() = withSimulator(gotoTimeScale = 40.0) { sim, client ->
        client.connect()
        client.setPosition(Axis.AZIMUTH, 0)
        val target = sim.countsPerRevolution / 4 // 90°
        client.gotoSteps(Axis.AZIMUTH, target)
        assertTrue(client.status(Axis.AZIMUTH).isGotoRunning)
        client.waitUntilStopped(Axis.AZIMUTH, timeoutMs = 10_000, pollMs = 20)
        assertEquals(target, client.position(Axis.AZIMUTH))
        // GOTO corto en sentido inverso (modo lento)
        client.gotoSteps(Axis.AZIMUTH, target - 500)
        client.waitUntilStopped(Axis.AZIMUTH, timeoutMs = 10_000, pollMs = 20)
        assertEquals(target - 500, client.position(Axis.AZIMUTH))
    }

    @Test fun constantRateMatchesRequestedSpeed() = withSimulator { sim, client ->
        val info = client.connect()
        client.setPosition(Axis.ALTITUDE, 0)
        val rate = 20 * MountClient.SIDEREAL_RATE_RAD_S
        client.setRate(Axis.ALTITUDE, rate)
        val t0 = System.nanoTime(); val p0 = sim.positionOf('2')
        delay(1000)
        val dt = (System.nanoTime() - t0) * 1e-9; val p1 = sim.positionOf('2')
        val measured = (p1 - p0) / dt / info.altitude.stepsPerRadian
        assertTrue("medido=$measured esperado=$rate", abs(measured - rate) / rate < 0.02)
        // Cambio de sentido: debe detener y re-arrancar en reversa
        client.setRate(Axis.ALTITUDE, -rate)
        assertTrue(client.status(Axis.ALTITUDE).reverse)
        client.setRate(Axis.ALTITUDE, 0.0)
        assertFalse(client.status(Axis.ALTITUDE).running)
    }

    @Test fun survivesPacketLossWithRetries() = withSimulator(dropRate = 0.3) { _, client ->
        client.connect()
        repeat(30) { client.position(Axis.AZIMUTH) }
        assertTrue("se esperaban reintentos", client.stats.value.retries > 0)
        assertEquals(LinkState.CONNECTED, client.linkState.value)
    }
}
