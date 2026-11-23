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
import com.opensky.virtuoso.platform.TcpSocket
import com.opensky.virtuoso.platform.epochMillisNow
import com.opensky.virtuoso.platform.ioDispatcher
import com.opensky.virtuoso.testing.SyncList
import com.opensky.virtuoso.testing.assertEquals
import com.opensky.virtuoso.testing.assertFalse
import com.opensky.virtuoso.testing.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class BridgeSafetyTest {

    /** Puente falso que registra el orden de las órdenes recibidas. */
    private class RecordingBridge : TelescopeBridge {
        val calls = SyncList<String>()
        override fun now(): AstroTime = AstroTime.fromEpochMillis(epochMillisNow())
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
        withContext(ioDispatcher) { TcpSocket().also { it.connect("127.0.0.1", server.boundPort, 2000) } }.use { s ->
            s.write(":Mn#".encodeToByteArray())
            withTimeout(3_000) { while ("move:UP" !in bridge.calls) delay(20) }
        } // el cliente se va sin mandar «:Qn#»
        withTimeout(3_000) { while ("stopMove" !in bridge.calls) delay(20) }
        server.stop(); scope.cancel()
    }

    @Test fun onlyLocalNetworksMayControlTheTelescope() {
        listOf(
            "127.0.0.1", "::1", "192.168.4.2", "10.0.0.7", "172.20.1.1", "169.254.3.4", "fe80::1", "fd12:3456::1",
            "fe80:0:0:0:1c2b:3dff:fe4a:5b6c%wlan0", "::ffff:192.168.4.3", "0:0:0:0:0:0:0:1",
        ).forEach { assertTrue(it, TcpBridgeServer.isLocalPeer(it)) }
        listOf("8.8.8.8", "100.72.1.9", "2001:4860:4860::8888", "200.57.1.1", "::ffff:8.8.8.8", "", "basura", "300.1.1.1")
            .forEach { assertFalse(it, TcpBridgeServer.isLocalPeer(it)) }
        assertFalse(TcpBridgeServer.isLocalPeer(null))
    }
}
