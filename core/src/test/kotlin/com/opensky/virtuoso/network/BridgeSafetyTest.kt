package com.opensky.virtuoso.network

import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.control.PadDirection
import com.opensky.virtuoso.control.Site
import com.opensky.virtuoso.network.bridge.Lx200TcpServer
import com.opensky.virtuoso.network.bridge.TcpBridgeServer
import com.opensky.virtuoso.network.bridge.TelescopeBridge
import com.opensky.virtuoso.network.lx200.Lx200Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.Collections

class BridgeSafetyTest {

    /** Puente falso que registra el orden de las órdenes recibidas. */
    private class RecordingBridge : TelescopeBridge {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun now(): AstroTime = AstroTime.fromEpochMillis(System.currentTimeMillis())
        override fun site() = Site(GeoLocation(28.6, -106.1, 1500.0))
        override fun positionJ2000(): EquatorialCoord? = null
        override fun horizontal(): HorizontalCoord? = null
        override fun isSlewing() = false
        override fun precheckGoto(j2000: EquatorialCoord): String? = null
        override suspend fun gotoJ2000(j2000: EquatorialCoord): String? = null.also { calls += "goto" }
        override suspend fun syncJ2000(j2000: EquatorialCoord): String? = null.also { calls += "sync" }
        override suspend fun stop() { calls += "stop" }
        override suspend fun move(direction: PadDirection, rateIndex: Int) { delay(30); calls += "move:$direction" } // lento a propósito
        override suspend fun stopMove() { calls += "stopMove" }
    }

    @Test fun moveAndStopAreExecutedInOrder() = runBlocking {
        val bridge = RecordingBridge()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = Lx200Session(bridge, scope)
        session.process(":Mn")
        assertTrue(session.hasActiveMove)
        session.process(":Qn")
        assertFalse(session.hasActiveMove)
        assertEquals(listOf("move:UP", "stopMove"), bridge.calls.toList())
        scope.cancel()
    }

    @Test fun clientDisconnectingMidMoveStopsTheMount() = runBlocking {
        val bridge = RecordingBridge()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val server = Lx200TcpServer(0, scope, bridge)
        server.start()
        Socket(InetAddress.getLoopbackAddress(), server.boundPort).use { s ->
            s.getOutputStream().apply { write(":Mn#".toByteArray()); flush() }
            withTimeout(3_000) { while ("move:UP" !in bridge.calls) delay(20) }
        } // el cliente se va sin mandar «:Qn#»
        withTimeout(3_000) { while ("stopMove" !in bridge.calls) delay(20) }
        server.stop(); scope.cancel()
    }

    @Test fun onlyLocalNetworksMayControlTheTelescope() {
        fun ip(s: String) = InetAddress.getByName(s)
        listOf("127.0.0.1", "::1", "192.168.4.2", "10.0.0.7", "172.20.1.1", "169.254.3.4", "fe80::1", "fd12:3456::1")
            .forEach { assertTrue(it, TcpBridgeServer.isLocalPeer(ip(it))) }
        listOf("8.8.8.8", "100.72.1.9", "2001:4860:4860::8888", "200.57.1.1")
            .forEach { assertFalse(it, TcpBridgeServer.isLocalPeer(ip(it))) }
    }
}
