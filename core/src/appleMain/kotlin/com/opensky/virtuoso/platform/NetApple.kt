@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.opensky.virtuoso.platform

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.ECONNABORTED
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.EAGAIN
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_REUSEADDR
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.in_addr
import platform.posix.inet_pton
import platform.posix.listen
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror
import kotlin.concurrent.Volatile

actual open class IOException actual constructor(message: String?) : Exception(message) {
    actual constructor() : this(null)
}

actual open class SocketTimeoutException actual constructor(message: String?) : IOException(message) {
    actual constructor() : this(null)
}

// Constantes de Darwin que no siempre exporta platform.posix.
private const val IPPROTO_TCP_DARWIN = 6
private const val IPPROTO_UDP_DARWIN = 17
private const val TCP_NODELAY_DARWIN = 0x01
/** Sin esto, escribir en un socket que el cliente ya cerró mata la app con SIGPIPE. */
private const val SO_NOSIGPIPE_DARWIN = 0x1022

private fun posixError(what: String, code: Int = errno): Nothing {
    val text = strerror(code)?.toKString() ?: "errno $code"
    throw IOException("$what: $text")
}

private fun swap16(v: Int): Int = ((v and 0xFF) shl 8) or ((v shr 8) and 0xFF)

private fun setIntOption(fd: Int, level: Int, option: Int, value: Int) {
    memScoped {
        val v = alloc<IntVar>()
        v.value = value
        setsockopt(fd, level, option, v.ptr, sizeOf<IntVar>().convert())
    }
}

/** Dirección IPv4 (orden de red, tal como va en sin_addr) de un literal o de un nombre (DNS). */
private fun resolveIpv4(host: String): UInt = memScoped {
    val literal = alloc<in_addr>()
    if (inet_pton(AF_INET, host, literal.ptr) == 1) return literal.s_addr
    val hints = alloc<addrinfo>()
    memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
    hints.ai_family = AF_INET
    val result = alloc<CPointerVar<addrinfo>>()
    val rc = getaddrinfo(host, null, hints.ptr, result.ptr)
    if (rc != 0) throw IOException("No se pudo resolver $host: ${gai_strerror(rc)?.toKString()}")
    try {
        var p = result.value
        while (p != null) {
            val info = p.pointed
            val sa = info.ai_addr
            if (info.ai_family == AF_INET && sa != null) return sa.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr
            p = info.ai_next
        }
    } finally {
        freeaddrinfo(result.value)
    }
    throw IOException("$host no tiene dirección IPv4")
}

private fun ipv4Text(networkOrder: UInt): String {
    val v = networkOrder.toInt()
    return "${v and 0xFF}.${(v shr 8) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 24) and 0xFF}"
}

private fun MemScope.sockaddrIn(address: UInt, port: Int): sockaddr_in {
    val sa = alloc<sockaddr_in>()
    memset(sa.ptr, 0, sizeOf<sockaddr_in>().convert())
    sa.sin_len = sizeOf<sockaddr_in>().convert()
    sa.sin_family = AF_INET.convert()
    sa.sin_port = swap16(port).convert()
    sa.sin_addr.s_addr = address
    return sa
}

private val SOCKADDR_IN_SIZE: Int get() = sizeOf<sockaddr_in>().toInt()

private fun localPortOf(fd: Int): Int = memScoped {
    val sa = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>()
    len.value = SOCKADDR_IN_SIZE.convert()
    if (getsockname(fd, sa.ptr.reinterpret(), len.ptr) != 0) posixError("getsockname")
    swap16(sa.sin_port.toInt())
}

/** Espera datos (o un cliente) en [fd] hasta [timeoutMs]: true si hay algo que leer. */
private fun waitFor(fd: Int, events: Int, timeoutMs: Int): Boolean = memScoped {
    val p = alloc<pollfd>()
    p.fd = fd
    p.events = events.toShort()
    var rc: Int
    do {
        p.revents = 0
        rc = poll(p.ptr, 1.convert(), timeoutMs)
    } while (rc < 0 && errno == EINTR)
    if (rc < 0) posixError("poll")
    rc > 0
}

actual class UdpSocket actual constructor(bindHost: String?, bindPort: Int) : AutoCloseable {
    private val fd: Int = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP_DARWIN)
    @Volatile private var closed = false

    init {
        if (fd < 0) posixError("socket")
        try {
            setIntOption(fd, SOL_SOCKET, SO_REUSEADDR, 1)
            setIntOption(fd, SOL_SOCKET, SO_NOSIGPIPE_DARWIN, 1)
            memScoped {
                val sa = sockaddrIn(if (bindHost == null) 0u else resolveIpv4(bindHost), bindPort)
                if (bind(fd, sa.ptr.reinterpret(), SOCKADDR_IN_SIZE.convert()) != 0) posixError("bind")
            }
        } catch (e: Throwable) {
            platform.posix.close(fd)
            throw e
        }
    }

    actual val localPort: Int get() = localPortOf(fd)
    actual val isClosed: Boolean get() = closed

    private fun checkOpen() {
        if (closed) throw IOException("Socket UDP cerrado")
    }

    actual fun connect(host: String, port: Int) {
        checkOpen()
        memScoped {
            val sa = sockaddrIn(resolveIpv4(host), port)
            if (platform.posix.connect(fd, sa.ptr.reinterpret(), SOCKADDR_IN_SIZE.convert()) != 0) posixError("connect")
        }
    }

    actual fun send(data: ByteArray, length: Int) {
        checkOpen()
        if (length == 0) return
        val n = data.usePinned { platform.posix.send(fd, it.addressOf(0), length.convert(), 0) }
        if (n < 0) posixError("send")
    }

    actual fun sendTo(data: ByteArray, length: Int, host: String, port: Int) {
        checkOpen()
        if (length == 0) return
        memScoped {
            val sa = sockaddrIn(resolveIpv4(host), port)
            val n = data.usePinned { sendto(fd, it.addressOf(0), length.convert(), 0, sa.ptr.reinterpret(), SOCKADDR_IN_SIZE.convert()) }
            if (n < 0) posixError("sendto")
        }
    }

    actual fun receive(buffer: ByteArray, timeoutMs: Int): Int {
        checkOpen()
        if (!waitFor(fd, POLLIN, timeoutMs)) throw SocketTimeoutException("Sin respuesta en $timeoutMs ms")
        checkOpen()
        val n = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0) }
        if (n < 0) posixError("recv")
        return n.toInt()
    }

    actual fun receiveFrom(buffer: ByteArray, timeoutMs: Int): ReceivedDatagram {
        checkOpen()
        if (!waitFor(fd, POLLIN, timeoutMs)) throw SocketTimeoutException("Sin datos en $timeoutMs ms")
        checkOpen()
        return memScoped {
            val sa = alloc<sockaddr_in>()
            val len = alloc<socklen_tVar>()
            len.value = SOCKADDR_IN_SIZE.convert()
            val n = buffer.usePinned { recvfrom(fd, it.addressOf(0), buffer.size.convert(), 0, sa.ptr.reinterpret(), len.ptr) }
            if (n < 0) posixError("recvfrom")
            ReceivedDatagram(n.toInt(), ipv4Text(sa.sin_addr.s_addr), swap16(sa.sin_port.toInt()))
        }
    }

    actual override fun close() {
        if (closed) return
        closed = true
        platform.posix.close(fd)
    }
}

actual class TcpSocket private constructor(fd: Int, peer: String?) : AutoCloseable {

    actual constructor() : this(-1, null)

    @Volatile private var fd: Int = fd
    @Volatile private var peer: String? = peer
    private var noDelay = false

    internal companion object {
        fun accepted(fd: Int, peer: String): TcpSocket = TcpSocket(fd, peer)
    }

    actual val isConnected: Boolean get() = fd >= 0
    actual val remoteHost: String? get() = peer

    actual fun connect(host: String, port: Int, timeoutMs: Int) {
        if (fd >= 0) throw IOException("Ya conectado")
        val s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP_DARWIN)
        if (s < 0) posixError("socket")
        try {
            setIntOption(s, SOL_SOCKET, SO_NOSIGPIPE_DARWIN, 1)
            if (noDelay) setIntOption(s, IPPROTO_TCP_DARWIN, TCP_NODELAY_DARWIN, 1)
            val flags = fcntl(s, F_GETFL, 0)
            fcntl(s, F_SETFL, flags or O_NONBLOCK)
            memScoped {
                val sa = sockaddrIn(resolveIpv4(host), port)
                if (platform.posix.connect(s, sa.ptr.reinterpret(), SOCKADDR_IN_SIZE.convert()) != 0) {
                    if (errno != EINPROGRESS) posixError("No se pudo conectar con $host:$port")
                    if (!waitFor(s, POLLOUT, timeoutMs)) throw SocketTimeoutException("Sin conexión con $host:$port en $timeoutMs ms")
                    val err = alloc<IntVar>()
                    val len = alloc<socklen_tVar>()
                    len.value = sizeOf<IntVar>().convert()
                    getsockopt(s, SOL_SOCKET, SO_ERROR, err.ptr, len.ptr)
                    if (err.value != 0) posixError("No se pudo conectar con $host:$port", err.value)
                }
            }
            fcntl(s, F_SETFL, flags)
            fd = s
            peer = host
        } catch (e: Throwable) {
            platform.posix.close(s)
            throw e
        }
    }

    actual fun setNoDelay(on: Boolean) {
        noDelay = on
        if (fd >= 0) setIntOption(fd, IPPROTO_TCP_DARWIN, TCP_NODELAY_DARWIN, if (on) 1 else 0)
    }

    private fun openFd(): Int = fd.takeIf { it >= 0 } ?: throw IOException("Socket TCP cerrado")

    actual fun write(data: ByteArray, offset: Int, length: Int) {
        val s = openFd()
        if (length == 0) return
        var done = 0
        data.usePinned { pinned ->
            while (done < length) {
                val n = platform.posix.send(s, pinned.addressOf(offset + done), (length - done).convert(), 0)
                if (n < 0) {
                    if (errno != EINTR) posixError("send")
                } else {
                    done += n.toInt()
                }
            }
        }
    }

    actual fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val s = openFd()
        if (length == 0) return 0
        if (timeoutMs > 0 && !waitFor(s, POLLIN, timeoutMs)) throw SocketTimeoutException("Sin datos en $timeoutMs ms")
        val n = buffer.usePinned { recv(s, it.addressOf(offset), length.convert(), 0) }
        if (n < 0) posixError("recv")
        return if (n == 0L) -1 else n.toInt()
    }

    actual override fun close() {
        val s = fd
        if (s < 0) return
        fd = -1
        platform.posix.shutdown(s, platform.posix.SHUT_RDWR)
        platform.posix.close(s)
    }
}

actual class TcpServerSocket actual constructor(port: Int) : AutoCloseable {
    private val fd: Int = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP_DARWIN)
    @Volatile private var closed = false

    init {
        if (fd < 0) posixError("socket")
        try {
            setIntOption(fd, SOL_SOCKET, SO_REUSEADDR, 1)
            memScoped {
                val sa = sockaddrIn(0u, port)
                if (bind(fd, sa.ptr.reinterpret(), SOCKADDR_IN_SIZE.convert()) != 0) posixError("No se pudo abrir el puerto $port")
            }
            if (listen(fd, 8) != 0) posixError("listen")
        } catch (e: Throwable) {
            platform.posix.close(fd)
            throw e
        }
    }

    actual val localPort: Int get() = localPortOf(fd)

    actual fun accept(timeoutMs: Int): TcpSocket? {
        if (closed) throw IOException("Servidor cerrado")
        if (!waitFor(fd, POLLIN, timeoutMs)) return null
        if (closed) throw IOException("Servidor cerrado")
        return memScoped {
            val sa = alloc<sockaddr_in>()
            val len = alloc<socklen_tVar>()
            len.value = SOCKADDR_IN_SIZE.convert()
            val c = platform.posix.accept(fd, sa.ptr.reinterpret(), len.ptr)
            if (c < 0) {
                val e = errno
                if (e == EINTR || e == EAGAIN || e == ECONNABORTED) null else posixError("accept", e)
            } else {
                setIntOption(c, SOL_SOCKET, SO_NOSIGPIPE_DARWIN, 1)
                TcpSocket.accepted(c, ipv4Text(sa.sin_addr.s_addr))
            }
        }
    }

    actual override fun close() {
        if (closed) return
        closed = true
        platform.posix.close(fd)
    }
}
