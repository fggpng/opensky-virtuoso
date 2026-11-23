package com.opensky.virtuoso.platform

/*
 * Lectura y escritura de enteros en arreglos de bytes (sustituye a java.nio.ByteBuffer en el
 * código común). «LE» = little-endian (archivos Tycho-2, Stellarium); «BE» = orden de red (NTP).
 */

fun ByteArray.getShortLE(offset: Int): Short =
    ((this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)).toShort()

fun ByteArray.getIntLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

fun ByteArray.getFloatLE(offset: Int): Float = Float.fromBits(getIntLE(offset))

fun ByteArray.getIntBE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

fun ByteArray.putShortLE(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
}

fun ByteArray.putIntLE(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
    this[offset + 2] = (value shr 16).toByte()
    this[offset + 3] = (value shr 24).toByte()
}

fun ByteArray.putLongLE(offset: Int, value: Long) {
    putIntLE(offset, value.toInt())
    putIntLE(offset + 4, (value ushr 32).toInt())
}

fun ByteArray.putFloatLE(offset: Int, value: Float) = putIntLE(offset, value.toRawBits())

fun ByteArray.putIntBE(offset: Int, value: Int) {
    this[offset] = (value shr 24).toByte()
    this[offset + 1] = (value shr 16).toByte()
    this[offset + 2] = (value shr 8).toByte()
    this[offset + 3] = value.toByte()
}

/** Bytes → texto, un carácter por byte (ISO-8859-1; ASCII es un subconjunto). */
fun ByteArray.latin1String(offset: Int = 0, length: Int = size - offset): String {
    val chars = CharArray(length)
    for (k in 0 until length) chars[k] = (this[offset + k].toInt() and 0xFF).toChar()
    return chars.concatToString()
}

/** Texto → bytes ISO-8859-1 (los caracteres fuera de rango se envían como «?»). */
fun String.latin1Bytes(): ByteArray = ByteArray(length) { k ->
    val c = this[k].code
    (if (c <= 0xFF) c else '?'.code).toByte()
}

/**
 * Bloque binario de solo lectura con acceso aleatorio (archivo mapeado en memoria en Android e
 * iOS, o un ByteArray en pruebas). Todas las lecturas son little-endian.
 */
interface BinaryBlob : AutoCloseable {
    val size: Long
    fun getByte(offset: Int): Byte
    fun getShortLE(offset: Int): Short
    fun getIntLE(offset: Int): Int
    fun getFloatLE(offset: Int): Float = Float.fromBits(getIntLE(offset))
    fun readBytes(offset: Int, length: Int): ByteArray = ByteArray(length) { getByte(offset + it) }
    override fun close() {}
}

class ByteArrayBlob(private val bytes: ByteArray) : BinaryBlob {
    override val size: Long get() = bytes.size.toLong()
    override fun getByte(offset: Int): Byte = bytes[offset]
    override fun getShortLE(offset: Int): Short = bytes.getShortLE(offset)
    override fun getIntLE(offset: Int): Int = bytes.getIntLE(offset)
}

/** Destino de escritura secuencial (archivo en disco o memoria). */
interface BinarySink : AutoCloseable {
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
    override fun close() {}
}

/** Sink en memoria (pruebas). */
class ByteArraySink : BinarySink {
    private var buf = ByteArray(1024)
    var size = 0
        private set

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (size + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + length))
        bytes.copyInto(buf, size, offset, offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
