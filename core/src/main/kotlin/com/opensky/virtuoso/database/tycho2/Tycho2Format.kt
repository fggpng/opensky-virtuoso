package com.opensky.virtuoso.database.tycho2

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Formato binario propio de OpenSky para Tycho-2 (los archivos oficiales del CDS, I/259,
 * son ASCII: tyc2.dat + suppl_1.dat; el conversor genera estos tres binarios).
 *
 * tycho2.dat  — cabecera de 32 B + N registros de 20 B, agrupados por región GSC (TYC1)
 *               y ordenados por magnitud dentro de cada región:
 *                 int32 RA J2000 (µgrados) | int32 Dec J2000 (µgrados)
 *                 int16 μα·cosδ (mas/año) | int16 μδ (mas/año)
 *                 int16 V (milimag)        | int16 B−V (milimag, 0x7FFF = desconocido)
 *                 int32 TYC empaquetado: TYC1<<16 | TYC2<<2 | TYC3
 * tycho2.idx  — cabecera de 32 B + 9537 entradas de 28 B (una por región TYC1):
 *                 int32 primer registro | int32 cantidad | float32 RAmin, RAmax, Decmin, Decmax
 *                 (grados; RAmin > RAmax indica que la región cruza RA = 0°) | float32 Vmín
 * tycho2.ref  — cabecera de 32 B + M entradas de 8 B ordenadas por HIP:
 *                 int32 HIP | int32 índice de registro   (referencia cruzada para búsquedas)
 *
 * Todo en little-endian (orden nativo de ARM/x86 → lectura directa por mmap).
 */
object Tycho2Format {
    const val VERSION = 1
    const val HEADER_SIZE = 32
    const val RECORD_SIZE = 20
    const val INDEX_ENTRY_SIZE = 28
    const val REF_ENTRY_SIZE = 8
    const val REGION_COUNT = 9537
    const val BV_UNKNOWN: Short = 0x7FFF

    const val DAT_FILE = "tycho2.dat"
    const val IDX_FILE = "tycho2.idx"
    const val REF_FILE = "tycho2.ref"

    val MAGIC_DAT = "OSKYT2D\u0000".toByteArray(Charsets.US_ASCII)
    val MAGIC_IDX = "OSKYT2I\u0000".toByteArray(Charsets.US_ASCII)
    val MAGIC_REF = "OSKYT2R\u0000".toByteArray(Charsets.US_ASCII)

    val ORDER: ByteOrder = ByteOrder.LITTLE_ENDIAN

    fun packTyc(tyc1: Int, tyc2: Int, tyc3: Int): Int = (tyc1 shl 16) or (tyc2 shl 2) or (tyc3 and 0x3)
    fun tyc1(packed: Int) = packed ushr 16
    fun tyc2(packed: Int) = (packed ushr 2) and 0x3FFF
    fun tyc3(packed: Int) = packed and 0x3

    fun writeHeader(buf: ByteBuffer, magic: ByteArray, count: Int, entrySize: Int) {
        buf.order(ORDER)
        buf.put(magic)
        buf.putInt(VERSION)
        buf.putInt(count)
        buf.putInt(entrySize)
        while (buf.position() < HEADER_SIZE) buf.put(0)
    }

    /** Valida la cabecera y devuelve el número de entradas. */
    fun readHeader(buf: ByteBuffer, magic: ByteArray, entrySize: Int, fileName: String): Int {
        buf.order(ORDER)
        val m = ByteArray(8).also { buf.readBytesAt(0, it) }
        require(m.contentEquals(magic)) { "$fileName: firma inválida (¿archivo ASCII del CDS sin convertir?)" }
        require(buf.getInt(8) == VERSION) { "$fileName: versión de formato no soportada" }
        require(buf.getInt(16) == entrySize) { "$fileName: tamaño de entrada inesperado" }
        return buf.getInt(12)
    }

    // No usar ByteBuffer.get(int, byte[]): solo existe en Android 14+ (API 34).
    private fun ByteBuffer.readBytesAt(index: Int, dst: ByteArray) {
        for (i in dst.indices) dst[i] = this.get(index + i)
    }
}
