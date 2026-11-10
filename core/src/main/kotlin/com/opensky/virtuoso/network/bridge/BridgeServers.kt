package com.opensky.virtuoso.network.bridge

import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.TWO_PI
import com.opensky.virtuoso.network.lx200.Lx200Session
import com.opensky.virtuoso.network.lx200.Lx200Tokenizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import kotlin.math.PI

/** Servidor TCP genérico con un coroutine por cliente (pocos clientes simultáneos). */
abstract class TcpBridgeServer(val name: String, private val port: Int, protected val scope: CoroutineScope) {

    private val _clients = MutableStateFlow(0)
    val clients: StateFlow<Int> = _clients.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun start() {
        if (_running.value) return
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        } catch (e: IOException) {
            _lastError.value = "No se pudo abrir el puerto $port: ${e.message}"
            return
        }
        serverSocket = ss
        _running.value = true
        _lastError.value = null
        acceptJob = scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                val socket = try { ss.accept() } catch (_: SocketException) { break }
                if (!isLocalPeer(socket.inetAddress)) {
                    // Con datos móviles el teléfono puede tener una IPv6 pública: nadie de Internet
                    // debe poder mover el telescopio. Solo se aceptan redes locales.
                    _lastError.value = "Conexión rechazada desde ${socket.inetAddress?.hostAddress} (no es una red local)"
                    runCatching { socket.close() }
                    continue
                }
                launch(Dispatchers.IO) { handle(socket) }
            }
            _running.value = false
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        _running.value = false
    }

    private suspend fun handle(socket: Socket) {
        _clients.update { it + 1 }
        try {
            socket.use { s ->
                s.tcpNoDelay = true
                serve(s)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // cliente desconectado
        } finally {
            _clients.update { it - 1 }
        }
    }

    protected abstract suspend fun serve(socket: Socket)

    companion object {
        /**
         * Solo clientes de la propia red: el mismo teléfono (loopback), la Wi-Fi de la montura o de
         * casa (192.168.x, 10.x, 172.16–31.x), enlace local (169.254.x, fe80::) e IPv6 ULA (fc00::/7).
         */
        fun isLocalPeer(address: InetAddress?): Boolean {
            if (address == null) return false
            if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
            return address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
        }
    }
}

/**
 * Servidor LX200 sobre TCP (como un adaptador SkyFi): Stellarium Mobile/Plus, SkySafari y
 * similares se conectan a la IP del teléfono. Puerto típico 4030; configurable.
 */
class Lx200TcpServer(
    port: Int,
    scope: CoroutineScope,
    private val bridge: TelescopeBridge,
    private val epoch: () -> CoordinateEpoch = { CoordinateEpoch.JNOW },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : TcpBridgeServer("LX200", port, scope) {

    override suspend fun serve(socket: Socket) {
        val session = Lx200Session(bridge, scope, epoch(), zone)
        val tokenizer = Lx200Tokenizer()
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val buf = ByteArray(256)
        try {
            while (currentCoroutineContext().isActive) {
                val n = input.read(buf)
                if (n < 0) break
                for (cmd in tokenizer.feed(String(buf, 0, n, StandardCharsets.ISO_8859_1))) {
                    session.process(cmd)?.let { reply ->
                        output.write(reply.toByteArray(StandardCharsets.ISO_8859_1))
                        output.flush()
                    }
                }
            }
        } finally {
            // El cliente se fue (Wi-Fi caída, app cerrada) con un movimiento en marcha: se detiene.
            if (session.hasActiveMove) withContext(NonCancellable) { runCatching { bridge.stopMove() } }
        }
    }
}

/**
 * Protocolo binario del plugin "Telescope Control" de Stellarium (escritorio), opción
 * "External software or a remote computer". Puerto por defecto 10001.
 *
 * Cliente → servidor (GoTo, 20 B LE): u16 longitud=20 | u16 tipo=0 | i64 tiempo µs | u32 AR | i32 Dec
 * Servidor → cliente (posición, 24 B LE): u16 longitud=24 | u16 tipo=0 | i64 tiempo µs | u32 AR | i32 Dec | i32 estado
 * Escalas: AR 0x100000000 = 24 h; Dec 0x40000000 = 90°.
 */
object StellariumBinaryProtocol {
    const val GOTO_LENGTH = 20
    const val POSITION_LENGTH = 24

    fun encodePosition(eq: EquatorialCoord, timeMicros: Long, status: Int = 0): ByteArray {
        val raU = (Angles.norm2Pi(eq.ra) / TWO_PI * 4294967296.0).toLong() and 0xFFFFFFFFL
        val decI = (eq.dec / (PI / 2) * 0x40000000.toDouble()).toLong().coerceIn(-0x40000000L, 0x40000000L).toInt()
        return ByteBuffer.allocate(POSITION_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(POSITION_LENGTH.toShort()).putShort(0).putLong(timeMicros)
            .putInt(raU.toInt()).putInt(decI).putInt(status).array()
    }

    /** Decodifica el cuerpo (sin los 2 bytes de longitud) de un mensaje GoTo. */
    fun decodeGotoBody(body: ByteArray): EquatorialCoord? {
        if (body.size < GOTO_LENGTH - 2) return null
        val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getShort(0).toInt() != 0) return null
        val raU = bb.getInt(10).toLong() and 0xFFFFFFFFL
        val decI = bb.getInt(14)
        return EquatorialCoord(raU / 4294967296.0 * TWO_PI, decI / 0x40000000.toDouble() * (PI / 2))
    }
}

class StellariumTcpServer(
    port: Int,
    scope: CoroutineScope,
    private val bridge: TelescopeBridge,
    private val epoch: () -> CoordinateEpoch = { CoordinateEpoch.J2000 },
    private val positionIntervalMs: Long = 500,
) : TcpBridgeServer("Stellarium", port, scope) {

    override suspend fun serve(socket: Socket) {
        val out = socket.getOutputStream()
        val writer = scope.launch(Dispatchers.IO) { sendPositions(out) }
        try {
            val input = DataInputStream(socket.getInputStream())
            while (currentCoroutineContext().isActive) {
                val lenLo = input.read()
                val lenHi = input.read()
                if (lenLo < 0 || lenHi < 0) break
                val length = lenLo or (lenHi shl 8)
                if (length < 4 || length > 1024) continue
                val body = ByteArray(length - 2)
                try { input.readFully(body) } catch (_: EOFException) { break }
                if (length != StellariumBinaryProtocol.GOTO_LENGTH) continue
                val eq = StellariumBinaryProtocol.decodeGotoBody(body) ?: continue
                val j2000 = bridge.fromEpoch(eq, epoch())
                if (bridge.precheckGoto(j2000) == null) scope.launch { bridge.gotoJ2000(j2000) }
            }
        } finally {
            writer.cancel()
        }
    }

    private suspend fun sendPositions(out: OutputStream) {
        while (currentCoroutineContext().isActive) {
            val pos = bridge.positionJ2000()
            if (pos != null) {
                val msg = StellariumBinaryProtocol.encodePosition(bridge.toEpoch(pos, epoch()), bridge.now().epochMillis * 1000)
                try {
                    synchronized(out) { out.write(msg); out.flush() }
                } catch (_: IOException) {
                    return
                }
            }
            delay(positionIntervalMs)
        }
    }
}
