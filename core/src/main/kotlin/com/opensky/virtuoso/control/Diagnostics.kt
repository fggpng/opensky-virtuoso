package com.opensky.virtuoso.control

/**
 * Registro de diagnóstico: comandos a la montura, GoTo, alineación, paradas y alarmas.
 * El núcleo solo escribe líneas; la app decide dónde guardarlas (archivo rotativo exportable).
 * Sin [sink] configurado (pruebas) no hace nada.
 */
object Diagnostics {
    @Volatile var sink: ((String) -> Unit)? = null

    /** Tráfico de bajo nivel (comandos y respuestas): muy frecuente. */
    @Volatile var logTraffic: Boolean = true

    fun log(message: String) {
        sink?.let { runCatching { it(message) } }
    }

    fun traffic(message: String) {
        if (logTraffic) log(message)
    }
}
