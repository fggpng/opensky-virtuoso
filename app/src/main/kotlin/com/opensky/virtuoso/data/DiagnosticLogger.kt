package com.opensky.virtuoso.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Registro de diagnóstico en archivo rotativo (dos archivos de hasta [MAX_BYTES]): comandos a la
 * montura, GoTo, estrellas confirmadas, paradas y alarmas. Se escribe en un hilo propio para no
 * frenar nunca el control de la montura. «Exportar» junta todo en un .txt para compartirlo.
 */
class DiagnosticLogger(private val context: Context) {

    private val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val current = File(dir, "actual.log")
    private val previous = File(dir, "anterior.log")
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val pending = AtomicInteger(0)

    init {
        Thread(::writeLoop, "DiagnosticLogger").apply { isDaemon = true; start() }
    }

    fun log(message: String) {
        if (queue.offer("${stamp.format(Instant.now())} | $message")) pending.incrementAndGet()
    }

    private fun writeLoop() {
        val batch = ArrayList<String>(512)
        while (true) {
            batch.clear()
            batch += queue.take()
            queue.drainTo(batch, 511)
            runCatching {
                if (current.length() > MAX_BYTES) {
                    previous.delete()
                    current.renameTo(previous)
                }
                FileWriter(current, true).use { w -> batch.forEach { w.write(it); w.write("\n") } }
            }
            pending.addAndGet(-batch.size)
        }
    }

    /** Espera (como mucho 1 s) a que se escriba lo pendiente. */
    private fun flush() {
        val end = System.currentTimeMillis() + 1_000
        while (pending.get() > 0 && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    /** Une encabezado + registros en un archivo de exportación y abre «Compartir» de Android. */
    fun exportAndShare(header: String) {
        flush()
        val exports = File(context.cacheDir, "exports").apply { mkdirs() }
        exports.listFiles()?.forEach { it.delete() }
        val name = "opensky-diagnostico-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now()) + ".txt"
        val out = File(exports, name)
        out.bufferedWriter().use { w ->
            w.write(header)
            w.write("\n\n===== REGISTRO =====\n")
            listOf(previous, current).filter { it.exists() }.forEach { f -> f.bufferedReader().use { it.copyTo(w) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico de OpenSky Virtuoso")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Enviar diagnóstico").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val MAX_BYTES = 2_000_000L
        private const val QUEUE_CAPACITY = 20_000
    }
}
