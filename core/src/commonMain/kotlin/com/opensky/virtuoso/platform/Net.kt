package com.opensky.virtuoso.platform

/*
 * Sockets mínimos y bloqueantes para la montura (UDP), los puentes serie-Wi-Fi (TCP), los
 * servidores LX200/Stellarium (TCP) y SNTP (UDP). Se usan siempre desde [ioDispatcher].
 * En JVM/Android envuelven java.net (los ganchos de Android acceden al socket original);
 * en iOS usan sockets POSIX.
 */

/** Error de E/S (java.io.IOException en JVM). */
expect open class IOException(message: String?) : Exception {
    constructor()
}

/** No llegó respuesta a tiempo (java.net.SocketTimeoutException en JVM). */
expect open class SocketTimeoutException(message: String?) : IOException {
    constructor()
}

/** Datagrama recibido con la dirección numérica de quien lo envió. */
class ReceivedDatagram(val length: Int, val host: String, val port: Int)

/** Socket UDP. [bindHost] null = todas las interfaces; [bindPort] 0 = puerto efímero. */
expect class UdpSocket(bindHost: String? = null, bindPort: Int = 0) : AutoCloseable {
    val localPort: Int
    val isClosed: Boolean

    /** Fija el par remoto (filtra lo que llega de otros orígenes). */
    fun connect(host: String, port: Int)

    /** Envía al par de [connect]. */
    fun send(data: ByteArray, length: Int = data.size)

    fun sendTo(data: ByteArray, length: Int, host: String, port: Int)

    /** Espera un datagrama hasta [timeoutMs]; lanza [SocketTimeoutException] si no llega. */
    fun receive(buffer: ByteArray, timeoutMs: Int): Int

    /** Como [receive], pero informa del origen (servidores). */
    fun receiveFrom(buffer: ByteArray, timeoutMs: Int): ReceivedDatagram

    override fun close()
}

/** Conexión TCP (cliente o aceptada por [TcpServerSocket]). */
expect class TcpSocket() : AutoCloseable {
    val isConnected: Boolean

    /** Dirección numérica del par (null si no está conectado). */
    val remoteHost: String?

    fun connect(host: String, port: Int, timeoutMs: Int)

    fun setNoDelay(on: Boolean)

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset)

    /**
     * Lee hasta [length] bytes. Devuelve los leídos (≥ 1) o -1 si el par cerró la conexión.
     * Con [timeoutMs] > 0 lanza [SocketTimeoutException] si no llega nada a tiempo.
     */
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset, timeoutMs: Int = 0): Int

    override fun close()
}

/** Servidor TCP en [port] (0 = efímero) de todas las interfaces, con SO_REUSEADDR. */
expect class TcpServerSocket(port: Int) : AutoCloseable {
    val localPort: Int

    /**
     * Espera un cliente hasta [timeoutMs]. Devuelve null si vence el plazo; lanza [IOException]
     * si el servidor se cerró.
     */
    fun accept(timeoutMs: Int): TcpSocket?

    override fun close()
}

/** Lee exactamente [length] bytes o lanza [IOException] si la conexión se cierra antes. */
fun TcpSocket.readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset, timeoutMs: Int = 0) {
    var done = 0
    while (done < length) {
        val n = read(buffer, offset + done, length - done, timeoutMs)
        if (n < 0) throw IOException("Conexión cerrada")
        done += n
    }
}

/** Utilidades de direcciones IP en texto. */
object IpAddresses {

    /** "192.168.4.2" → 4 bytes, o null si no es IPv4 literal. */
    fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (k in 0 until 4) {
            val p = parts[k]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[k] = v
        }
        return out
    }

    /** Literal IPv6 (admite «::», zona «%wlan0» e IPv4 incrustada) → 16 bytes, o null. */
    fun parseIpv6(text: String): IntArray? {
        var t = text.trim().removePrefix("[").removeSuffix("]")
        val zone = t.indexOf('%')
        if (zone >= 0) t = t.substring(0, zone)
        if (!t.contains(':')) return null
        val bytes = IntArray(16)
        val halves = t.split("::")
        if (halves.size > 2) return null
        fun groups(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(':')
        val head = groups(halves[0])
        val tail = if (halves.size == 2) groups(halves[1]) else emptyList()
        // Convierte una lista de grupos (el último puede ser IPv4) en bytes.
        fun toBytes(gs: List<String>): List<Int>? {
            val out = mutableListOf<Int>()
            for ((k, g) in gs.withIndex()) {
                if (k == gs.lastIndex && g.contains('.')) {
                    val v4 = parseIpv4(g) ?: return null
                    out += v4.toList()
                } else {
                    if (g.isEmpty() || g.length > 4) return null
                    val v = g.toIntOrNull(16) ?: return null
                    out += (v shr 8) and 0xFF
                    out += v and 0xFF
                }
            }
            return out
        }
        val hb = toBytes(head) ?: return null
        val tb = toBytes(tail) ?: return null
        if (halves.size == 1 && hb.size != 16) return null
        if (hb.size + tb.size > 16) return null
        hb.forEachIndexed { k, v -> bytes[k] = v }
        tb.forEachIndexed { k, v -> bytes[16 - tb.size + k] = v }
        return bytes
    }

    /**
     * Solo clientes de la propia red: el mismo teléfono (loopback), la Wi-Fi de la montura o de
     * casa (192.168.x, 10.x, 172.16–31.x), enlace local (169.254.x, fe80::) e IPv6 privada
     * (fc00::/7 y el antiguo fec0::/10).
     */
    fun isLocalPeer(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        parseIpv4(host)?.let { return isLocalIpv4(it) }
        val v6 = parseIpv6(host) ?: return false
        // IPv4 mapeada (::ffff:a.b.c.d)
        if ((0 until 10).all { v6[it] == 0 } && v6[10] == 0xFF && v6[11] == 0xFF) {
            return isLocalIpv4(intArrayOf(v6[12], v6[13], v6[14], v6[15]))
        }
        if ((0 until 15).all { v6[it] == 0 } && v6[15] == 1) return true // ::1
        if (v6[0] == 0xFE && (v6[1] and 0xC0) == 0x80) return true      // fe80::/10
        if (v6[0] == 0xFE && (v6[1] and 0xC0) == 0xC0) return true      // fec0::/10
        return (v6[0] and 0xFE) == 0xFC                                 // fc00::/7
    }

    private fun isLocalIpv4(b: IntArray): Boolean = when {
        b[0] == 127 -> true
        b[0] == 10 -> true
        b[0] == 172 && b[1] in 16..31 -> true
        b[0] == 192 && b[1] == 168 -> true
        b[0] == 169 && b[1] == 254 -> true
        else -> false
    }
}
