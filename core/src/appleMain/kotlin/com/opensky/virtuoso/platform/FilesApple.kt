@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class, BetaInteropApi::class)

package com.opensky.virtuoso.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.zlib.gzclose
import platform.zlib.gzgets
import platform.zlib.gzopen

/** Archivo mapeado en memoria (NSData con mmap): solo se leen del disco las páginas que se tocan. */
class MappedFileBlob(path: String) : BinaryBlob {
    private val data: NSData = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        NSData.dataWithContentsOfFile(path, NSDataReadingMappedIfSafe, error.ptr)
            ?: throw IOException("No se pudo abrir $path: ${error.value?.localizedDescription}")
    }
    private val bytes: CPointer<ByteVar> = data.bytes?.reinterpret() ?: throw IOException("$path está vacío")

    override val size: Long get() = data.length.toLong()
    override fun getByte(offset: Int): Byte = bytes[offset]
    override fun getShortLE(offset: Int): Short =
        ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
    override fun getIntLE(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

/** Escritura secuencial a un archivo. */
class FileSink(private val path: String) : BinarySink {
    private val file = fopen(path, "wb") ?: throw IOException("No se pudo crear $path")

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        val n = bytes.usePinned { fwrite(it.addressOf(offset), 1.convert(), length.convert(), file) }
        if (n.toLong() != length.toLong()) throw IOException("Error al escribir $path (¿disco lleno?)")
    }

    override fun close() {
        fclose(file)
    }
}

/** Operaciones de archivo mínimas para el catálogo y los datos de la app en iOS. */
object AppleFiles {
    fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    fun mkdirs(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)?.toString()

    /** Escribe texto de forma atómica (archivo temporal + renombrado). */
    fun writeText(path: String, text: String) {
        val tmp = "$path.tmp"
        FileSink(tmp).use { it.write(text.encodeToByteArray()) }
        val fm = NSFileManager.defaultManager
        fm.removeItemAtPath(path, error = null)
        if (!fm.moveItemAtPath(tmp, toPath = path, error = null)) throw IOException("No se pudo guardar $path")
    }

    /**
     * Recorre las líneas de un archivo de texto, comprimido con gzip o no (zlib lo detecta solo).
     * Para los archivos ASCII del Tycho-2 del CDS.
     */
    fun forEachLine(path: String, block: (String) -> Unit) {
        val gz = gzopen(path, "rb") ?: throw IOException("No se pudo abrir $path")
        try {
            val buf = ByteArray(4096)
            val pending = StringBuilder()
            buf.usePinned { pinned ->
                while (true) {
                    val got = gzgets(gz, pinned.addressOf(0), buf.size) ?: break
                    val chunk = got.toKString()
                    if (chunk.endsWith('\n')) {
                        pending.append(chunk, 0, chunk.length - 1)
                        var line = pending.toString()
                        if (line.endsWith('\r')) line = line.dropLast(1)
                        block(line)
                        pending.clear()
                    } else {
                        pending.append(chunk)
                    }
                }
            }
            if (pending.isNotEmpty()) block(pending.toString())
        } finally {
            gzclose(gz)
        }
    }
}
