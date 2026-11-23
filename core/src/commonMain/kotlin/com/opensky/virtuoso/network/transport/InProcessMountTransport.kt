package com.opensky.virtuoso.network.transport

import com.opensky.virtuoso.network.simulator.MountSimulator
import com.opensky.virtuoso.platform.IOException
import com.opensky.virtuoso.platform.SocketTimeoutException
import kotlinx.coroutines.delay

/**
 * Transporte sin red hacia un [MountSimulator] del propio proceso: el modo simulador funciona sin
 * permisos de red local (iOS) y sin abrir puertos. Respeta la pérdida de paquetes simulada.
 */
class InProcessMountTransport(private val simulator: MountSimulator) : MountTransport {
    private var opened = false

    override val description: String get() = "Simulador interno"
    override val isOpen: Boolean get() = opened

    override suspend fun open() {
        opened = true
    }

    override suspend fun exchange(request: String, timeoutMs: Int): String {
        if (!opened) throw IOException("Simulador cerrado")
        if (simulator.shouldDrop()) {
            delay(timeoutMs.toLong())
            throw SocketTimeoutException("Paquete perdido (simulado)")
        }
        return simulator.handle(request)
    }

    override fun close() {
        opened = false
    }
}
