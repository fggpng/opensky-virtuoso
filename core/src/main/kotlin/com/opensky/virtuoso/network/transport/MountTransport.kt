package com.opensky.virtuoso.network.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Canal de bajo nivel hacia la tarjeta de motores. Una petición ASCII produce exactamente una
 * respuesta ASCII terminada en '\r'. Los reintentos y la validación viven en MountClient.
 */
interface MountTransport : Closeable {
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
 *   usa `Network.bindSocket(socket)` para forzar la red SynScan (que no tiene Internet) sin
 *   desviar el resto del tráfico del teléfono.
 */
class UdpMountTransport(
    private val host: String,
    private val port: Int,
    private val socketConfigurator: (DatagramSocket) -> Unit = {},
) : MountTransport {

    private var socket: DatagramSocket? = null
    private val receiveBuffer = ByteArray(64)

    override val description: String get() = "UDP $host:$port"
    override val isOpen: Boolean get() = socket?.isClosed == false

    override suspend fun open() = withContext(Dispatchers.IO) {
        close()
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(0))
        socketConfigurator(s)
        s.connect(InetSocketAddress(host, port))
        socket = s
    }

    override suspend fun exchange(request: String, timeoutMs: Int): String = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("Transporte UDP cerrado")
        drainStaleDatagrams(s)
        val out = request.toByteArray(StandardCharsets.US_ASCII)
        s.send(DatagramPacket(out, out.size))
        s.soTimeout = timeoutMs
        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
        s.receive(packet)
        String(packet.data, 0, packet.length, StandardCharsets.US_ASCII)
    }

    /** Descarta respuestas tardías de peticiones anteriores (típico tras un reintento). */
    private fun drainStaleDatagrams(s: DatagramSocket) {
        s.soTimeout = 1
        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
        repeat(8) {
            try {
                s.receive(packet)
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
    private val socketConfigurator: (Socket) -> Unit = {},
) : MountTransport {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val description: String get() = "TCP $host:$port"
    override val isOpen: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true

    override suspend fun open() = withContext(Dispatchers.IO) {
        close()
        val s = Socket()
        socketConfigurator(s)
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override suspend fun exchange(request: String, timeoutMs: Int): String = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("Transporte TCP cerrado")
        val ins = input!!
        while (ins.available() > 0) ins.read() // descarta basura pendiente
        output!!.apply { write(request.toByteArray(StandardCharsets.US_ASCII)); flush() }
        s.soTimeout = timeoutMs
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) throw IOException("Conexión TCP cerrada por el puente")
            sb.append(b.toChar())
            if (b == '\r'.code) break
            if (sb.length > 64) throw IOException("Respuesta demasiado larga")
        }
        sb.toString()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}
