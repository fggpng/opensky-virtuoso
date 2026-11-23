package com.opensky.virtuoso.network.transport

import com.opensky.virtuoso.platform.IOException
import com.opensky.virtuoso.platform.SocketTimeoutException
import com.opensky.virtuoso.platform.TcpSocket
import com.opensky.virtuoso.platform.UdpSocket
import com.opensky.virtuoso.platform.ioDispatcher
import com.opensky.virtuoso.platform.latin1Bytes
import com.opensky.virtuoso.platform.latin1String
import kotlinx.coroutines.withContext

/**
 * Canal de bajo nivel hacia la tarjeta de motores. Una petición ASCII produce exactamente una
 * respuesta ASCII terminada en '\r'. Los reintentos y la validación viven en MountClient.
 */
interface MountTransport : AutoCloseable {
    val description: String
    val isOpen: Boolean

    suspend fun open()

    /**
     * Envía [request] y espera su respuesta durante [timeoutMs].
     * @throws SocketTimeoutException si no llega respuesta a tiempo.
     */
    suspend fun exchange(request: String, timeoutMs: Int): String
}

/**
 * Transporte UDP: el que usa el módulo Wi-Fi integrado de la Virtuoso GTi / AZ-GTi
 * (192.168.4.1:11880 en modo punto de acceso).
 *
 * @param socketConfigurator gancho para enlazar el socket a una red concreta; en Android se
 *   usa `Network.bindSocket(socket.javaSocket)` para forzar la red SynScan (que no tiene Internet)
 *   sin desviar el resto del tráfico del teléfono.
 */
class UdpMountTransport(
    private val host: String,
    private val port: Int,
    private val socketConfigurator: (UdpSocket) -> Unit = {},
) : MountTransport {

    private var socket: UdpSocket? = null
    private val receiveBuffer = ByteArray(64)

    override val description: String get() = "UDP $host:$port"
    override val isOpen: Boolean get() = socket?.isClosed == false

    override suspend fun open() = withContext(ioDispatcher) {
        close()
        val s = UdpSocket()
        try {
            socketConfigurator(s)
            s.connect(host, port)
        } catch (e: Exception) {
            s.close()
            throw e
        }
        socket = s
    }

    override suspend fun exchange(request: String, timeoutMs: Int): String = withContext(ioDispatcher) {
        val s = socket ?: throw IOException("Transporte UDP cerrado")
        drainStaleDatagrams(s)
        val out = request.latin1Bytes()
        s.send(out, out.size)
        val n = s.receive(receiveBuffer, timeoutMs)
        receiveBuffer.latin1String(0, n)
    }

    /** Descarta respuestas tardías de peticiones anteriores (típico tras un reintento). */
    private fun drainStaleDatagrams(s: UdpSocket) {
        repeat(8) {
            try {
                s.receive(receiveBuffer, 1)
            } catch (_: SocketTimeoutException) {
                return
            }
        }
    }

    override fun close() {
        socket?.close()
        socket = null
    }
}

/**
 * Transporte TCP para puentes serie↔Wi-Fi de terceros (p. ej. ESP32 conectado al puerto
 * del mando). La Virtuoso GTi de fábrica NO expone TCP: usar [UdpMountTransport].
 */
class TcpMountTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 3000,
    private val socketConfigurator: (TcpSocket) -> Unit = {},
) : MountTransport {

    private var socket: TcpSocket? = null
    private val one = ByteArray(64)

    override val description: String get() = "TCP $host:$port"
    override val isOpen: Boolean get() = socket?.isConnected == true

    override suspend fun open() = withContext(ioDispatcher) {
        close()
        val s = TcpSocket()
        try {
            socketConfigurator(s)
            s.setNoDelay(true)
            s.connect(host, port, connectTimeoutMs)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        socket = s
    }

    override suspend fun exchange(request: String, timeoutMs: Int): String = withContext(ioDispatcher) {
        val s = socket ?: throw IOException("Transporte TCP cerrado")
        drainPending(s)
        s.write(request.latin1Bytes())
        val sb = StringBuilder()
        while (true) {
            val n = s.read(one, 0, 1, timeoutMs)
            if (n < 0) throw IOException("Conexión TCP cerrada por el puente")
            val b = one[0].toInt() and 0xFF
            sb.append(b.toChar())
            if (b == '\r'.code) break
            if (sb.length > 64) throw IOException("Respuesta demasiado larga")
        }
        sb.toString()
    }

    /** Descarta bytes pendientes (basura de respuestas anteriores). */
    private fun drainPending(s: TcpSocket) {
        repeat(64) {
            try {
                if (s.read(one, 0, one.size, 1) < 0) throw IOException("Conexión TCP cerrada por el puente")
            } catch (_: SocketTimeoutException) {
                return
            }
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
