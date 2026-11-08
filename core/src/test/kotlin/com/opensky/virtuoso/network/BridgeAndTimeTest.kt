package com.opensky.virtuoso.network

import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.math.ARCSEC
import com.opensky.virtuoso.control.PadDirection
import com.opensky.virtuoso.control.Site
import com.opensky.virtuoso.network.bridge.CoordinateEpoch
import com.opensky.virtuoso.network.bridge.Lx200TcpServer
import com.opensky.virtuoso.network.bridge.StellariumBinaryProtocol
import com.opensky.virtuoso.network.bridge.StellariumTcpServer
import com.opensky.virtuoso.network.bridge.TelescopeBridge
import com.opensky.virtuoso.network.lx200.Lx200Format
import com.opensky.virtuoso.network.lx200.Lx200Session
import com.opensky.virtuoso.network.lx200.Lx200Tokenizer
import com.opensky.virtuoso.network.time.SntpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId
import java.util.Collections

class BridgeAndTimeTest {

    private class FakeBridge : TelescopeBridge {
        val gotos: MutableList<EquatorialCoord> = Collections.synchronizedList(mutableListOf())
        val syncs = mutableListOf<EquatorialCoord>()
        var stopped = false
        val moves = mutableListOf<PadDirection>()
        override fun now() = AstroTime.fromCalendarUtc(2026, 11, 8, 3, 0, 0.0)
        override fun site() = Site(GeoLocation(28.6353, -106.0889, 1430.0))
        override fun positionJ2000() = EquatorialCoord.fromHoursDegrees(18.615649, 38.783689) // Vega
        override fun horizontal() = HorizontalCoord.fromDegrees(301.5, 42.25)
        override fun isSlewing() = gotos.isNotEmpty()
        override fun precheckGoto(j2000: EquatorialCoord) = if (j2000.decDeg < -60) "Objeto bajo el horizonte" else null
        override suspend fun gotoJ2000(j2000: EquatorialCoord): String? { gotos += j2000; return null }
        override suspend fun syncJ2000(j2000: EquatorialCoord): String? { syncs += j2000; return null }
        override suspend fun stop() { stopped = true }
        override suspend fun move(direction: PadDirection, rateIndex: Int) { moves += direction }
        override suspend fun stopMove() {}
    }

    @Test fun lx200Formatting() {
        assertEquals("18:36:56#", Lx200Format.ra(18.615649, true))
        assertEquals("18:36.9#", Lx200Format.ra(18.615649, false))
        assertEquals("+38*47'01#", Lx200Format.dec(38.783689, true))
        assertEquals("-05*23#", Lx200Format.dec(-5.39, false))
        assertEquals("301*30'00#", Lx200Format.azimuth(301.5))
        assertEquals(12.5824, Lx200Format.parseRa("12:34:56.6")!!, 1e-4)
        assertEquals(12.575, Lx200Format.parseRa("12:34.5")!!, 1e-9)
        assertEquals(-45.5, Lx200Format.parseDec("-45*30:00")!!, 1e-9)
        assertEquals(45.5, Lx200Format.parseDec("+45ß30")!!, 1e-9)
        assertNull(Lx200Format.parseDec("+95*00"))
        assertNull(Lx200Format.parseRa("25:00:00"))
    }

    @Test fun tokenizerHandlesFragmentsAndAck() {
        val t = Lx200Tokenizer()
        assertEquals(listOf("\u0006"), t.feed("\u0006"))
        assertEquals(emptyList<String>(), t.feed(":G"))
        assertEquals(listOf(":GR", ":GD"), t.feed("R#:GD#"))
        assertEquals(listOf(":Sr 12:00:00"), t.feed("basura:Sr 12:00:00#"))
    }

    @Test fun lx200SessionGotoSyncAndSite() = runBlocking {
        val bridge = FakeBridge()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = Lx200Session(bridge, scope, CoordinateEpoch.J2000) { ZoneId.of("America/Mexico_City") }
        assertEquals("A", session.process("\u0006"))
        assertEquals("18:36:56#", session.process(":GR"))
        assertEquals("+38*47'01#", session.process(":GD"))
        assertEquals("1", session.process(":Sr 05:34:32"))
        assertEquals("1", session.process(":Sd +22*00:52"))
        assertEquals("0", session.process(":MS"))
        delay(100)
        assertEquals(1, bridge.gotos.size)
        assertEquals(5.575556, bridge.gotos[0].raHours, 1e-5)
        assertEquals("Coordinates matched#", session.process(":CM"))
        assertEquals(1, bridge.syncs.size)
        assertEquals("1", session.process(":Sd -75*00:00"))
        assertTrue(session.process(":MS")!!.startsWith("1"))
        assertEquals("106*05#", session.process(":Gg"))
        assertEquals("+28*38#", session.process(":Gt"))
        assertEquals("+06#", session.process(":GG"))
        assertEquals("11/07/26#", session.process(":GC")) // 2026-11-08 03:00 UTC = 7 nov 21:00 local
        assertNull(session.process(":Mn"))
        assertNull(session.process(":XYZ"))
        scope.cancel()
    }

    @Test fun stellariumBinaryRoundTrip() {
        val eq = EquatorialCoord.fromHoursDegrees(5.919529, 7.407064)
        val msg = StellariumBinaryProtocol.encodePosition(eq, 123L)
        assertEquals(24, msg.size)
        val bb = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, bb.getShort(0).toInt())
        val body = msg.copyOfRange(2, 20)
        val decoded = StellariumBinaryProtocol.decodeGotoBody(body)!!
        assertTrue(decoded.separation(eq) < 0.01 * ARCSEC)
    }

    @Test fun tcpServersEndToEnd() = runBlocking {
        val bridge = FakeBridge()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val lx = Lx200TcpServer(0, scope, bridge, { CoordinateEpoch.J2000 })
        val st = StellariumTcpServer(0, scope, bridge, { CoordinateEpoch.J2000 }, positionIntervalMs = 100)
        lx.start(); st.start()
        try {
            Socket("127.0.0.1", lx.boundPort).use { s ->
                s.soTimeout = 2000
                s.getOutputStream().write(":GR#:GD#".toByteArray())
                val buf = ByteArray(64)
                val sb = StringBuilder()
                while (sb.count { it == '#' } < 2) { val n = s.getInputStream().read(buf); if (n < 0) break; sb.append(String(buf, 0, n)) }
                assertEquals("18:36:56#+38*47'01#", sb.toString())
            }
            Socket("127.0.0.1", st.boundPort).use { s ->
                s.soTimeout = 2000
                val input = DataInputStream(s.getInputStream())
                val pos = ByteArray(24).also { input.readFully(it) }
                val bb = ByteBuffer.wrap(pos).order(ByteOrder.LITTLE_ENDIAN)
                val ra = (bb.getInt(12).toLong() and 0xFFFFFFFFL) / 4294967296.0 * 24.0
                assertEquals(18.615649, ra, 1e-5)
                // GoTo desde Stellarium: Betelgeuse
                val target = EquatorialCoord.fromHoursDegrees(5.919529, 7.407064)
                val pkt = StellariumBinaryProtocol.encodePosition(target, 0L).copyOf(20)
                ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN).putShort(0, 20)
                s.getOutputStream().write(pkt)
                var waited = 0
                while (bridge.gotos.isEmpty() && waited < 2000) { delay(20); waited += 20 }
                assertEquals(1, bridge.gotos.size)
                assertTrue(bridge.gotos[0].separation(target) < 0.01 * ARCSEC)
            }
            assertNotNull(lx.running.value)
        } finally {
            lx.stop(); st.stop(); scope.cancel()
        }
    }

    @Test fun sntpOffsetComputation() {
        val request = ByteArray(48).also { it[0] = 0x23 }
        val t1 = 1_794_000_000_000L
        SntpClient.writeTimestamp(request, SntpClient.TRANSMIT_OFFSET, t1)
        val response = ByteArray(48)
        response[0] = 0x24 // LI=0 VN=4 modo=4 (servidor)
        response[1] = 2
        System.arraycopy(request, SntpClient.TRANSMIT_OFFSET, response, SntpClient.ORIGINATE_OFFSET, 8)
        // El servidor va 1500 ms adelantado; red con 40 ms de ida y vuelta.
        SntpClient.writeTimestamp(response, SntpClient.RECEIVE_OFFSET, t1 + 20 + 1500)
        SntpClient.writeTimestamp(response, SntpClient.TRANSMIT_OFFSET, t1 + 21 + 1500)
        val result = SntpClient.parseResponse(request, response, t1, t1 + 41, "test")
        assertEquals(1500.0, result.offsetMillis.toDouble(), 2.0)
        assertEquals(40.0, result.roundTripMillis.toDouble(), 2.0)
        assertEquals(t1, SntpClient.readTimestamp(request, SntpClient.TRANSMIT_OFFSET))
    }
}
