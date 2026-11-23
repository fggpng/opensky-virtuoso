package com.opensky.virtuoso.platform

import com.opensky.virtuoso.network.MountClient
import com.opensky.virtuoso.network.simulator.MountSimulator
import com.opensky.virtuoso.network.transport.InProcessMountTransport
import com.opensky.virtuoso.network.transport.UdpMountTransport
import com.opensky.virtuoso.testing.assertEquals
import com.opensky.virtuoso.testing.assertFalse
import com.opensky.virtuoso.testing.assertNull
import com.opensky.virtuoso.testing.assertTrue
import com.opensky.virtuoso.testing.fail
import com.opensky.virtuoso.testing.isoMillis
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test

/** Capa de plataforma común: debe comportarse igual en Android (JVM) y en iOS. */
class PlatformTest {

    @Test fun formatMatchesJavaConventions() {
        assertEquals("12.3", "%.1f".fmt(12.345))
        assertEquals("0.2", "%.1f".fmt(0.15))       // HALF_UP sobre la representación corta, como Java
        assertEquals("0.1", "%.1f".fmt(0.05))
        assertEquals("3", "%.0f".fmt(2.5))
        assertEquals("-3", "%.0f".fmt(-2.5))
        assertEquals("10.0", "%.1f".fmt(9.96))
        assertEquals("-0.0", "%.1f".fmt(-0.04))
        assertEquals("0.000010", "%f".fmt(1e-5))
        assertEquals("123456789.00", "%.2f".fmt(123456789.0))
        assertEquals("+1.50", "%+.2f".fmt(1.5))
        assertEquals("-01.50", "%06.2f".fmt(-1.5))
        assertEquals("05", "%02.0f".fmt(5.0))
        assertEquals("  -16.3", "%7.1f".fmt(-16.3))
        assertEquals("NaN", "%.1f".fmt(Double.NaN))
        assertEquals("-Infinity", "%.1f".fmt(Double.NEGATIVE_INFINITY))
        assertEquals("05", "%02d".fmt(5))
        assertEquals("-0042", "%05d".fmt(-42))
        assertEquals("+06", "%+03d".fmt(6))
        assertEquals("-05", "%+03d".fmt(-5))
        assertEquals("  42", "%4d".fmt(42L))
        assertEquals("2,539,913", "%,d".fmt(2_539_913))
        assertEquals("0F", "%02X".fmt(15))
        assertEquals("FFFFFFFF", "%X".fmt(-1))
        assertEquals("ab", "%x".fmt(171))
        assertEquals("+", "%c".fmt('+'))
        assertEquals("hola   |", "%-7s|".fmt("hola"))
        assertEquals("   hola", "%7s".fmt("hola"))
        assertEquals("100% listo", "%d%% listo".fmt(100))
        assertEquals("Az 1.000°  Alt 2.500°", "Az %.3f°  Alt %.3f°".fmt(1.0, 2.5))
        assertEquals("null", "%s".fmt(null))
    }

    @Test fun civilDatesRoundTrip() {
        val ms = isoMillis("2026-11-08T05:00:00Z")
        assertEquals(1_794_114_000_000L, ms)
        val c = CivilDateTime.fromEpochMillis(ms)
        assertEquals(CivilDateTime(2026, 11, 8, 5, 0, 0, 0), c)
        assertEquals("2026-11-08T05:00:00Z", c.isoUtc())
        assertEquals("Nov", c.monthAbbrevEn)
        assertEquals(0L, isoMillis("1970-01-01T00:00:00Z"))
        assertEquals(CivilDateTime(1969, 12, 31, 23, 59, 59, 999), CivilDateTime.fromEpochMillis(-1))
        assertEquals(CivilDateTime(2000, 2, 29, 12, 34, 56, 789), CivilDateTime.fromEpochMillis(isoMillis("2000-02-29T12:34:56.789Z")))
        for (k in 0 until 2000) {
            val t = -2_000_000_000_000L + k * 2_123_456_789_123L
            assertEquals(t, CivilDateTime.fromEpochMillis(t).toEpochMillis())
        }
    }

    @Test fun diacriticsAndUrlEncoding() {
        assertEquals("Andromeda Pinguino Nino Sao Paulo", TextUtils.stripDiacritics("Andrómeda Pingüino Niño São Paulo"))
        assertEquals("cafe", TextUtils.stripDiacritics("café"))
        assertEquals("ø æ ß", TextUtils.stripDiacritics("ø æ ß")) // sin descomposición canónica, como NFD
        assertEquals("141P%2FMachholz+1", TextUtils.urlEncode("141P/Machholz 1"))
        assertEquals("C%2F2023+A3+%28Tsuchinshan%29", TextUtils.urlEncode("C/2023 A3 (Tsuchinshan)"))
        assertEquals("%C3%B1", TextUtils.urlEncode("ñ"))
    }

    @Test fun ipAddressParsing() {
        assertEquals(listOf(192, 168, 4, 1), IpAddresses.parseIpv4("192.168.4.1")!!.toList())
        assertNull(IpAddresses.parseIpv4("192.168.4"))
        assertNull(IpAddresses.parseIpv4("256.1.1.1"))
        val v6 = IpAddresses.parseIpv6("fe80::1%en0")!!
        assertEquals(0xFE, v6[0]); assertEquals(0x80, v6[1]); assertEquals(1, v6[15])
        assertNull(IpAddresses.parseIpv6("1::2::3"))
    }

    @Test fun littleAndBigEndianHelpers() {
        val b = ByteArray(16)
        b.putIntLE(0, 0x12345678); b.putShortLE(4, -2); b.putFloatLE(6, 1.5f); b.putIntBE(10, 0x0A0B0C0D)
        assertEquals(0x78.toByte(), b[0])
        assertEquals(0x12345678, b.getIntLE(0))
        assertEquals((-2).toShort(), b.getShortLE(4))
        assertEquals(1.5f, b.getFloatLE(6))
        assertEquals(0x0A0B0C0D, b.getIntBE(10))
        assertEquals(0x0A.toByte(), b[10])
        val l = ByteArray(8).also { it.putLongLE(0, 0x0102030405060708L) }
        assertEquals(0x08.toByte(), l[0]); assertEquals(0x01.toByte(), l[7])
        assertEquals("ñA", byteArrayOf(0xF1.toByte(), 0x41).latin1String())
        assertEquals(listOf(0xF1.toByte(), 0x41.toByte()), "ñA".latin1Bytes().toList())
        val sink = ByteArraySink()
        repeat(300) { sink.write(byteArrayOf(it.toByte(), 1, 2), 0, 3) }
        assertEquals(900, sink.size)
        val blob = ByteArrayBlob(sink.toByteArray())
        assertEquals(299.toByte(), blob.getByte(897))
    }

    @Test fun udpRequestReplyAndTimeout() = runBlocking {
        withContext(ioDispatcher) {
            UdpSocket("127.0.0.1", 0).use { server ->
                UdpSocket().use { client ->
                    client.connect("127.0.0.1", server.localPort)
                    client.send(":e1\r".encodeToByteArray())
                    val buf = ByteArray(64)
                    val d = server.receiveFrom(buf, 2000)
                    assertEquals(":e1\r", buf.decodeToString(0, d.length))
                    assertEquals("127.0.0.1", d.host)
                    val reply = "=0322A5\r".encodeToByteArray()
                    server.sendTo(reply, reply.size, d.host, d.port)
                    val n = client.receive(buf, 2000)
                    assertEquals("=0322A5\r", buf.decodeToString(0, n))
                    try {
                        client.receive(buf, 50)
                        fail("debía vencer el plazo")
                    } catch (_: SocketTimeoutException) {
                        // esperado
                    }
                }
            }
        }
    }

    @Test fun tcpServerClientRoundTrip() = runBlocking {
        withContext(ioDispatcher) {
            TcpServerSocket(0).use { server ->
                assertNull(server.accept(20)) // nadie se conectó todavía
                val client = TcpSocket()
                client.setNoDelay(true)
                client.connect("127.0.0.1", server.localPort, 2000)
                val peer = server.accept(2000)!!
                assertTrue(IpAddresses.isLocalPeer(peer.remoteHost))
                client.write(":GR#".encodeToByteArray())
                val buf = ByteArray(4)
                peer.readFully(buf, timeoutMs = 2000)
                assertEquals(":GR#", buf.decodeToString())
                try {
                    peer.read(buf, 0, buf.size, 50)
                    fail("debía vencer el plazo")
                } catch (_: SocketTimeoutException) {
                    // esperado
                }
                client.close()
                assertEquals(-1, peer.read(buf, 0, buf.size, 2000)) // el cliente cerró
                assertFalse(client.isConnected)
                peer.close()
            }
        }
    }

    @Test fun mountClientOverUdpAndInProcessSimulator() = runBlocking {
        val sim = MountSimulator().start()
        try {
            val udp = MountClient(UdpMountTransport("127.0.0.1", sim.boundPort))
            assertEquals("3.34", udp.connect().firmwareVersion)
            udp.disconnect()
            val local = MountClient(InProcessMountTransport(MountSimulator()))
            val info = local.connect()
            assertEquals(3_628_800, info.azimuth.countsPerRevolution)
            local.disconnect()
        } finally {
            sim.close()
        }
    }

    @Test fun lockedPrimitives() {
        val flag = AtomicFlag(false)
        assertFalse(flag.getAndSet(true))
        assertTrue(flag.get())
        val map = LockedMap<String, Int>()
        map["a"] = 1
        assertEquals(1, map["a"])
        map.clear()
        assertNull(map["a"])
        val lock = PlatformLock()
        assertEquals(3, lock.withLock { lock.withLock { 3 } }) // reentrante
        assertTrue(epochMillisNow() > isoMillis("2025-01-01T00:00:00Z"))
        val t0 = monotonicNanos()
        assertTrue(monotonicNanos() >= t0)
    }
}
