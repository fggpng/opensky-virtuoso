package com.opensky.virtuoso.network.bridge

import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.TWO_PI
import com.opensky.virtuoso.network.lx200.Lx200Session
import com.opensky.virtuoso.network.lx200.Lx200Tokenizer
import com.opensky.virtuoso.platform.IOException
import com.opensky.virtuoso.platform.IpAddresses
import com.opensky.virtuoso.platform.SocketTimeoutException
import com.opensky.virtuoso.platform.TcpServerSocket
import com.opensky.virtuoso.platform.TcpSocket
import com.opensky.virtuoso.platform.getIntLE
import com.opensky.virtuoso.platform.getShortLE
import com.opensky.virtuoso.platform.ioDispatcher
import com.opensky.virtuoso.platform.latin1Bytes
import com.opensky.virtuoso.platform.latin1String
import com.opensky.virtuoso.platform.putIntLE
import com.opensky.virtuoso.platform.putLongLE
import com.opensky.virtuoso.platform.putShortLE
import com.opensky.virtuoso.platform.systemUtcOffsetSeconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.PI

/**
 * Servidor TCP genérico con un coroutine por cliente (pocos clientes simultáneos). Las esperas
 * (aceptar, leer) tienen plazos cortos para que [stop] cierre todo enseguida.
 */
abstract class TcpBridgeServer(val name: String, private val port: Int, protected val scope: CoroutineScope) {

    private val _clients = MutableStateFlow(0)
    val clients: StateFlow<Int> = _clients.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile private var serverSocket: TcpServerSocket? = null
    private var acceptJob: Job? = null

    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun start() {
        if (_running.value) return
        val ss = try {
            TcpServerSocket(port)
        } catch (e: Exception) {
            _lastError.value = "No se pudo abrir el puerto $port: ${e.message}"
            return
        }
        serverSocket = ss
        _running.value = true
        _lastError.value = null
        acceptJob = scope.launch(ioDispatcher) {
            try {
                while (currentCoroutineContext().isActive) {
                    val socket = try {
                        ss.accept(ACCEPT_POLL_MS) ?: continue
                    } catch (_: IOException) {
                        break // servidor cerrado
                    }
                    if (!IpAddresses.isLocalPeer(socket.remoteHost)) {
                        // Con datos móviles el teléfono puede tener una IPv6 pública: nadie de Internet
                        // debe poder mover el telescopio. Solo se aceptan redes locales.
                        _lastError.value = "Conexión rechazada desde ${socket.remoteHost} (no es una red local)"
                        runCatching { socket.close() }
                        continue
                    }
                    launch(ioDispatcher) { handle(socket) }
                }
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        _running.value = false
    }

    private suspend fun handle(socket: TcpSocket) {
        _clients.update { it + 1 }
        try {
            socket.use { s ->
                s.setNoDelay(true)
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

    protected abstract suspend fun serve(socket: TcpSocket)

    /**
     * Lee lo que haya llegado (o -1 si el cliente cerró). Si no llega nada en un rato devuelve 0
     * para que el bucle compruebe si el servidor sigue activo.
     */
    protected fun readSome(socket: TcpSocket, buffer: ByteArray): Int = try {
        socket.read(buffer, 0, buffer.size, READ_POLL_MS)
    } catch (_: SocketTimeoutException) {
        0
    }

    companion object {
        private const val ACCEPT_POLL_MS = 500
        internal const val READ_POLL_MS = 500

        /** Ver [IpAddresses.isLocalPeer]. */
        fun isLocalPeer(host: String?): Boolean = IpAddresses.isLocalPeer(host)
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
    private val utcOffsetSeconds: (epochMillis: Long) -> Int = ::systemUtcOffsetSeconds,
) : TcpBridgeServer("LX200", port, scope) {

    override suspend fun serve(socket: TcpSocket) {
        val session = Lx200Session(bridge, scope, epoch(), utcOffsetSeconds)
        val tokenizer = Lx200Tokenizer()
        val buf = ByteArray(256)
        try {
            while (currentCoroutineContext().isActive) {
                val n = readSome(socket, buf)
                if (n < 0) break
                if (n == 0) continue
                for (cmd in tokenizer.feed(buf.latin1String(0, n))) {
                    session.process(cmd)?.let { reply -> socket.write(reply.latin1Bytes()) }
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
        val out = ByteArray(POSITION_LENGTH)
        out.putShortLE(0, POSITION_LENGTH)
        out.putShortLE(2, 0)
        out.putLongLE(4, timeMicros)
        out.putIntLE(12, raU.toInt())
        out.putIntLE(16, decI)
        out.putIntLE(20, status)
        return out
    }

    /** Decodifica el cuerpo (sin los 2 bytes de longitud) de un mensaje GoTo. */
    fun decodeGotoBody(body: ByteArray): EquatorialCoord? {
        if (body.size < GOTO_LENGTH - 2) return null
        if (body.getShortLE(0).toInt() != 0) return null
        val raU = body.getIntLE(10).toLong() and 0xFFFFFFFFL
        val decI = body.getIntLE(14)
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

    override suspend fun serve(socket: TcpSocket) {
        val writer = scope.launch(ioDispatcher) { sendPositions(socket) }
        try {
            val reader = FrameReader(socket)
            while (currentCoroutineContext().isActive) {
                val lenLo = reader.next() ?: break
                val lenHi = reader.next() ?: break
                val length = lenLo or (lenHi shl 8)
                if (length < 4 || length > 1024) continue
                val body = ByteArray(length - 2)
                if (!reader.fill(body)) break
                if (length != StellariumBinaryProtocol.GOTO_LENGTH) continue
                val eq = StellariumBinaryProtocol.decodeGotoBody(body) ?: continue
                val j2000 = bridge.fromEpoch(eq, epoch())
                if (bridge.precheckGoto(j2000) == null) scope.launch { bridge.gotoJ2000(j2000) }
            }
        } finally {
            writer.cancel()
        }
    }

    /** Lectura byte a byte con búfer que sigue comprobando si el coroutine sigue activo. */
    private inner class FrameReader(private val socket: TcpSocket) {
        private val buf = ByteArray(256)
        private var pos = 0
        private var len = 0

        /** Siguiente byte (0–255) o null si el cliente cerró. */
        suspend fun next(): Int? {
            while (pos >= len) {
                if (!currentCoroutineContext().isActive) return null
                val n = readSome(socket, buf)
                if (n < 0) return null
                pos = 0
                len = n
            }
            return buf[pos++].toInt() and 0xFF
        }

        suspend fun fill(dst: ByteArray): Boolean {
            for (k in dst.indices) dst[k] = (next() ?: return false).toByte()
            return true
        }
    }

    private suspend fun sendPositions(socket: TcpSocket) {
        while (currentCoroutineContext().isActive) {
            val pos = bridge.positionJ2000()
            if (pos != null) {
                val msg = StellariumBinaryProtocol.encodePosition(bridge.toEpoch(pos, epoch()), bridge.now().epochMillis * 1000)
                try {
                    socket.write(msg)
                } catch (_: IOException) {
                    return
                }
            }
            delay(positionIntervalMs)
        }
    }
}
