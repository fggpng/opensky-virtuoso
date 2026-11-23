package com.opensky.virtuoso.platform

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Archivo mapeado en memoria (mmap): no carga el archivo en el heap, solo las páginas que se leen. */
class MappedFileBlob(file: File) : BinaryBlob {
    private val raf = RandomAccessFile(file, "r")
    private val buffer: MappedByteBuffer = try {
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).also { it.order(ByteOrder.LITTLE_ENDIAN) }
    } catch (e: Exception) {
        raf.close()
        throw e
    }

    override val size: Long get() = buffer.capacity().toLong()
    override fun getByte(offset: Int): Byte = buffer.get(offset)
    override fun getShortLE(offset: Int): Short = buffer.getShort(offset)
    override fun getIntLE(offset: Int): Int = buffer.getInt(offset)
    override fun getFloatLE(offset: Int): Float = buffer.getFloat(offset)
    override fun close() = raf.close()
}

/** Escritura secuencial a un archivo (con búfer). */
class FileSink(file: File) : BinarySink {
    private val out = FileOutputStream(file).buffered(1 shl 16)
    override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
    override fun close() = out.close()
}
