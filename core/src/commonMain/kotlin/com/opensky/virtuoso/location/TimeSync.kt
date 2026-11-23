package com.opensky.virtuoso.location

import com.opensky.virtuoso.network.time.SntpClient
import com.opensky.virtuoso.network.time.SntpResult
import com.opensky.virtuoso.platform.epochMillisNow
import com.opensky.virtuoso.platform.fmt
import kotlin.math.abs

/**
 * Verificación de la hora por SNTP contra varios servidores: se queda con la muestra de menor
 * ida y vuelta. Un error de 1 s ≈ 15″ en el tiempo sidéreo (y en el apuntado).
 */
object TimeSync {

    fun formatOffset(ms: Long): String = if (abs(ms) < 1000) "%+d ms".fmt(ms) else "%+.2f s".fmt(ms / 1000.0)

    /** Mejor muestra NTP (o null) y los errores de los servidores que fallaron. */
    suspend fun bestSample(primaryServer: String, client: SntpClient = SntpClient()): Pair<SntpResult?, List<String>> {
        val servers = listOf(primaryServer, "time.google.com", "pool.ntp.org", "time.cloudflare.com").filter { it.isNotBlank() }.distinct()
        val samples = mutableListOf<SntpResult>()
        val errors = mutableListOf<String>()
        for (server in servers) {
            repeat(2) {
                runCatching { client.query(server, timeoutMs = 2500) }
                    .onSuccess { samples += it }
                    .onFailure { e -> errors += "$server: ${e.message}" }
            }
            if (samples.size >= 3) break
        }
        return samples.minByOrNull { it.roundTripMillis } to errors
    }

    /** Estado final tras consultar NTP; sin red, se usa el reloj del teléfono (sin verificar). */
    suspend fun sync(primaryServer: String, client: SntpClient = SntpClient()): TimeStatus {
        val (best, errors) = bestSample(primaryServer, client)
        return if (best != null) {
            TimeStatus(
                TimeSource.NTP, best.offsetMillis, best.roundTripMillis / 2, epochMillisNow(),
                "NTP ${best.server} (estrato ${best.stratum}), desfase del reloj ${formatOffset(best.offsetMillis)}, ±${best.roundTripMillis / 2} ms",
            )
        } else {
            TimeStatus(
                detail = "No se pudo verificar la hora (${errors.firstOrNull() ?: "sin red"}). Se usa el reloj del teléfono " +
                    "(en iOS se sincroniza solo con la red del operador o la Wi-Fi con Internet).",
            )
        }
    }
}
