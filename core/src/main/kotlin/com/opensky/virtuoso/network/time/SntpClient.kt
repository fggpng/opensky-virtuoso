package com.opensky.virtuoso.network.time

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Resultado de una consulta SNTP.
 * @property offsetMillis corrección a SUMAR al reloj del sistema para obtener UTC.
 */
data class SntpResult(val offsetMillis: Long, val roundTripMillis: Long, val stratum: Int, val server: String)

class SntpException(message: String) : Exception(message)

/**
 * Cliente SNTP (RFC 4330) mínimo para verificar el reloj del teléfono: un error de 1 s en el
 * tiempo equivale a 15″ de error en el tiempo sidéreo (y por tanto en el apuntado).
 *
 * offset = ((T2 − T1) + (T3 − T4)) / 2,  retardo = (T4 − T1) − (T3 − T2)
 */
class SntpClient(private val socketConfigurator: (DatagramSocket) -> Unit = {}) {

    suspend fun query(host: String = "pool.ntp.org", port: Int = 123, timeoutMs: Int = 3000): SntpResult =
        withContext(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socketConfigurator(socket)
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(host)
                val request = ByteArray(NTP_PACKET_SIZE)
                request[0] = 0x23 // LI=0, VN=4, modo=3 (cliente)
                val t1 = System.currentTimeMillis()
                writeTimestamp(request, TRANSMIT_OFFSET, t1)
                socket.send(DatagramPacket(request, request.size, address, port))
                val response = ByteArray(NTP_PACKET_SIZE)
                socket.receive(DatagramPacket(response, response.size))
                val t4 = System.currentTimeMillis()
                parseResponse(request, response, t1, t4, host)
            }
        }

    companion object {
        const val NTP_PACKET_SIZE = 48
        const val ORIGINATE_OFFSET = 24
        const val RECEIVE_OFFSET = 32
        const val TRANSMIT_OFFSET = 40
        /** Segundos entre 1900-01-01 (época NTP) y 1970-01-01 (época Unix). */
        const val NTP_UNIX_DELTA = 2_208_988_800L

        fun parseResponse(request: ByteArray, response: ByteArray, t1: Long, t4: Long, server: String): SntpResult {
            val leap = (response[0].toInt() shr 6) and 0x3
            val mode = response[0].toInt() and 0x7
            val stratum = response[1].toInt() and 0xFF
            if (mode != 4 && mode != 5) throw SntpException("Modo NTP inesperado: $mode")
            if (leap == 3 || stratum == 0 || stratum > 15) throw SntpException("Servidor NTP no sincronizado (estrato $stratum)")
            // El servidor debe devolver nuestro transmit timestamp como originate (anti-suplantación)
            for (i in 0 until 8) if (response[ORIGINATE_OFFSET + i] != request[TRANSMIT_OFFSET + i]) {
                throw SntpException("Respuesta NTP no corresponde a la petición")
            }
            val t2 = readTimestamp(response, RECEIVE_OFFSET)
            val t3 = readTimestamp(response, TRANSMIT_OFFSET)
            if (t3 == 0L) throw SntpException("Timestamp de transmisión vacío")
            val offset = ((t2 - t1) + (t3 - t4)) / 2
            val delay = (t4 - t1) - (t3 - t2)
            return SntpResult(offset, delay, stratum, server)
        }

        /** Timestamp NTP de 64 bits (32.32 punto fijo) → ms Unix. */
        fun readTimestamp(buf: ByteArray, offset: Int): Long {
            val bb = ByteBuffer.wrap(buf, offset, 8)
            val seconds = bb.int.toLong() and 0xFFFFFFFFL
            val fraction = bb.int.toLong() and 0xFFFFFFFFL
            if (seconds == 0L && fraction == 0L) return 0L
            return (seconds - NTP_UNIX_DELTA) * 1000L + fraction * 1000L / 0x100000000L
        }

        fun writeTimestamp(buf: ByteArray, offset: Int, unixMillis: Long) {
            val seconds = unixMillis / 1000L + NTP_UNIX_DELTA
            val fraction = (unixMillis % 1000L) * 0x100000000L / 1000L
            ByteBuffer.wrap(buf, offset, 8).putInt(seconds.toInt()).putInt(fraction.toInt())
        }
    }
}
