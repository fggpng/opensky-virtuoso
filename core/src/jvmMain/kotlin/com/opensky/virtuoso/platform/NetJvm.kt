package com.opensky.virtuoso.platform

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

actual typealias IOException = java.io.IOException
actual typealias SocketTimeoutException = java.net.SocketTimeoutException

actual class UdpSocket actual constructor(bindHost: String?, bindPort: Int) : AutoCloseable {

    /** Socket original: Android lo enlaza a la red SynScan con Network.bindSocket(). */
    val javaSocket: DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(if (bindHost == null) InetSocketAddress(bindPort) else InetSocketAddress(InetAddress.getByName(bindHost), bindPort))
    }

    actual val localPort: Int get() = javaSocket.localPort
    actual val isClosed: Boolean get() = javaSocket.isClosed

    actual fun connect(host: String, port: Int) = javaSocket.connect(InetSocketAddress(host, port))

    actual fun send(data: ByteArray, length: Int) = javaSocket.send(DatagramPacket(data, length))

    actual fun sendTo(data: ByteArray, length: Int, host: String, port: Int) =
        javaSocket.send(DatagramPacket(data, length, InetAddress.getByName(host), port))

    actual fun receive(buffer: ByteArray, timeoutMs: Int): Int {
        javaSocket.soTimeout = timeoutMs.coerceAtLeast(1)
        val packet = DatagramPacket(buffer, buffer.size)
        javaSocket.receive(packet)
        return packet.length
    }

    actual fun receiveFrom(buffer: ByteArray, timeoutMs: Int): ReceivedDatagram {
        javaSocket.soTimeout = timeoutMs.coerceAtLeast(1)
        val packet = DatagramPacket(buffer, buffer.size)
        javaSocket.receive(packet)
        return ReceivedDatagram(packet.length, packet.address.hostAddress, packet.port)
    }

    actual override fun close() = javaSocket.close()
}

actual class TcpSocket internal constructor(
    /** Socket original: Android lo enlaza a la red SynScan con Network.bindSocket(). */
    val javaSocket: Socket,
) : AutoCloseable {

    actual constructor() : this(Socket())

    actual val isConnected: Boolean get() = javaSocket.isConnected && !javaSocket.isClosed
    actual val remoteHost: String? get() = javaSocket.inetAddress?.hostAddress

    actual fun connect(host: String, port: Int, timeoutMs: Int) =
        javaSocket.connect(InetSocketAddress(host, port), timeoutMs)

    actual fun setNoDelay(on: Boolean) {
        javaSocket.tcpNoDelay = on
    }

    actual fun write(data: ByteArray, offset: Int, length: Int) {
        val out = javaSocket.getOutputStream()
        out.write(data, offset, length)
        out.flush()
    }

    actual fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        javaSocket.soTimeout = timeoutMs.coerceAtLeast(0)
        return javaSocket.getInputStream().read(buffer, offset, length)
    }

    actual fun available(): Int = javaSocket.getInputStream().available()

    actual override fun close() = javaSocket.close()
}

actual class TcpServerSocket actual constructor(port: Int) : AutoCloseable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    actual val localPort: Int get() = server.localPort

    actual fun accept(timeoutMs: Int): TcpSocket? {
        if (server.isClosed) throw SocketException("Servidor cerrado")
        server.soTimeout = timeoutMs.coerceAtLeast(1)
        return try {
            TcpSocket(server.accept())
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
    }

    actual override fun close() = server.close()
}
