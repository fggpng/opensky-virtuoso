package com.opensky.virtuoso.database.tycho2

import com.opensky.virtuoso.platform.BinaryBlob
import com.opensky.virtuoso.platform.putIntLE


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
 * Todo en little-endian (orden nativo de ARM/x86 → lectura directa por mmap en Android e iOS).
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

    val MAGIC_DAT = "OSKYT2D\u0000".encodeToByteArray()
    val MAGIC_IDX = "OSKYT2I\u0000".encodeToByteArray()
    val MAGIC_REF = "OSKYT2R\u0000".encodeToByteArray()

    fun packTyc(tyc1: Int, tyc2: Int, tyc3: Int): Int = (tyc1 shl 16) or (tyc2 shl 2) or (tyc3 and 0x3)
    fun tyc1(packed: Int) = packed ushr 16
    fun tyc2(packed: Int) = (packed ushr 2) and 0x3FFF
    fun tyc3(packed: Int) = packed and 0x3

    /** Cabecera de [HEADER_SIZE] bytes: firma, versión, número de entradas y tamaño de entrada. */
    fun header(magic: ByteArray, count: Int, entrySize: Int): ByteArray {
        val buf = ByteArray(HEADER_SIZE)
        magic.copyInto(buf, 0)
        buf.putIntLE(8, VERSION)
        buf.putIntLE(12, count)
        buf.putIntLE(16, entrySize)
        return buf
    }

    /** Valida la cabecera y devuelve el número de entradas. */
    fun readHeader(blob: BinaryBlob, magic: ByteArray, entrySize: Int, fileName: String): Int {
        require(blob.size >= HEADER_SIZE) { "$fileName: archivo truncado" }
        val m = blob.readBytes(0, 8)
        require(m.contentEquals(magic)) { "$fileName: firma inválida (¿archivo ASCII del CDS sin convertir?)" }
        require(blob.getIntLE(8) == VERSION) { "$fileName: versión de formato no soportada" }
        require(blob.getIntLE(16) == entrySize) { "$fileName: tamaño de entrada inesperado" }
        return blob.getIntLE(12)
    }
}
