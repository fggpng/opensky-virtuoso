package com.opensky.virtuoso.database.tycho2

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Convierte el Tycho-2 oficial del CDS (I/259: `tyc2.dat[.NN][.gz]` + `suppl_1.dat[.gz]`, ASCII
 * con campos separados por '|') al formato binario de [Tycho2Format].
 *
 * Usa arreglos primitivos (≈24 B/estrella → ~60 MB para 2.54 M estrellas), por lo que puede
 * ejecutarse en el PC (módulo tools:catalog-builder) o en el propio teléfono.
 *
 * - Registros con posición media (pflag ≠ 'X'): RA/Dec J2000 + movimiento propio.
 * - Registros 'X' (sin solución astrométrica): posición observada, μ = 0.
 * - Suplemento 1 (estrellas brillantes Hipparcos/Tycho-1, época J1991.25): se propaga a J2000.
 * - Magnitud V y B−V a partir de BT/VT: V = VT − 0.090·(BT−VT); B−V = 0.850·(BT−VT).
 */
class Tycho2Converter(private val onProgress: (phase: String, count: Int) -> Unit = { _, _ -> }) {

    data class Result(val stars: Int, val regions: Int, val hipReferences: Int, val skippedLines: Int)

    private var n = 0
    private var ra = IntArray(1 shl 16)
    private var dec = IntArray(1 shl 16)
    private var pmRa = ShortArray(1 shl 16)
    private var pmDec = ShortArray(1 shl 16)
    private var vmag = ShortArray(1 shl 16)
    private var bv = ShortArray(1 shl 16)
    private var tyc = IntArray(1 shl 16)
    private var hip = IntArray(1 shl 16)
    var skipped = 0
        private set

    val starCount: Int get() = n

    private fun ensureCapacity() {
        if (n < ra.size) return
        val cap = ra.size * 2
        ra = ra.copyOf(cap); dec = dec.copyOf(cap); pmRa = pmRa.copyOf(cap); pmDec = pmDec.copyOf(cap)
        vmag = vmag.copyOf(cap); bv = bv.copyOf(cap); tyc = tyc.copyOf(cap); hip = hip.copyOf(cap)
    }

    /** Añade un archivo (detecta .gz por la extensión). */
    fun addFile(file: File) = file.inputStream().use { addStream(it, gzip = file.name.endsWith(".gz")) }

    fun addStream(input: InputStream, gzip: Boolean = false) {
        val stream = if (gzip) GZIPInputStream(input, 1 shl 16) else input
        BufferedReader(InputStreamReader(stream, Charsets.US_ASCII), 1 shl 16).useLines { lines ->
            lines.forEach { line ->
                if (!addLine(line)) skipped++
                if (n % 100_000 == 0 && n > 0) onProgress("Leyendo", n)
            }
        }
    }

    private fun field(s: String): Double? = s.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    /** Parsea una línea de tyc2.dat (32 campos) o suppl_1.dat (18 campos). */
    fun addLine(line: String): Boolean {
        val f = line.split('|')
        val ids = f.getOrNull(0)?.trim()?.split(Regex("\\s+")) ?: return false
        if (ids.size != 3) return false
        val t1 = ids[0].toIntOrNull() ?: return false
        val t2 = ids[1].toIntOrNull() ?: return false
        val t3 = ids[2].toIntOrNull() ?: return false
        if (t1 !in 1..Tycho2Format.REGION_COUNT) return false

        val raDeg: Double; val decDeg: Double
        var pmA = 0.0; var pmD = 0.0
        val bt: Double?; val vt: Double?; val hipField: String
        when (f.size) {
            32 -> {
                val noMean = f[1].trim() == "X"
                if (noMean) {
                    raDeg = field(f[24]) ?: return false
                    decDeg = field(f[25]) ?: return false
                } else {
                    raDeg = field(f[2]) ?: return false
                    decDeg = field(f[3]) ?: return false
                    pmA = field(f[4]) ?: 0.0
                    pmD = field(f[5]) ?: 0.0
                }
                bt = field(f[17]); vt = field(f[19]); hipField = f[23]
            }
            18, 19 -> {
                val ra1991 = field(f[2]) ?: return false
                val dec1991 = field(f[3]) ?: return false
                pmA = field(f[4]) ?: 0.0
                pmD = field(f[5]) ?: 0.0
                val years = 2000.0 - 1991.25
                decDeg = dec1991 + pmD / 3.6e6 * years
                raDeg = ra1991 + pmA / 3.6e6 * years / cos(Math.toRadians(dec1991)).coerceAtLeast(1e-6)
                bt = field(f[11]); vt = field(f[13]); hipField = f[17]
            }
            else -> return false
        }
        val v: Double
        val bvValue: Double?
        when {
            bt != null && vt != null -> { v = vt - 0.090 * (bt - vt); bvValue = 0.850 * (bt - vt) }
            vt != null -> { v = vt; bvValue = null }
            bt != null -> { v = bt; bvValue = null }
            else -> return false
        }
        ensureCapacity()
        ra[n] = (((raDeg % 360.0 + 360.0) % 360.0) * 1e6).roundToLong().toInt() % 360_000_000
        dec[n] = (decDeg.coerceIn(-90.0, 90.0) * 1e6).roundToLong().toInt()
        pmRa[n] = pmA.roundToInt().coerceIn(-32767, 32767).toShort()
        pmDec[n] = pmD.roundToInt().coerceIn(-32767, 32767).toShort()
        vmag[n] = (v * 1000).roundToInt().coerceIn(-32767, 32767).toShort()
        bv[n] = bvValue?.let { (it * 1000).roundToInt().coerceIn(-32767, 32766).toShort() } ?: Tycho2Format.BV_UNKNOWN
        tyc[n] = Tycho2Format.packTyc(t1, t2, t3)
        hip[n] = hipField.take(6).trim().toIntOrNull() ?: 0
        n++
        return true
    }

    /** Escribe tycho2.dat, tycho2.idx y tycho2.ref en [outputDir]. */
    fun write(outputDir: File): Result {
        require(n > 0) { "No se leyeron estrellas: ¿archivos correctos (tyc2.dat / suppl_1.dat)?" }
        outputDir.mkdirs()
        onProgress("Ordenando", n)
        val keys = LongArray(n) { i ->
            (Tycho2Format.tyc1(tyc[i]).toLong() shl 48) or ((vmag[i] + 32768).toLong() shl 32) or i.toLong()
        }
        keys.sort()

        val regionFirst = IntArray(Tycho2Format.REGION_COUNT)
        val regionCount = IntArray(Tycho2Format.REGION_COUNT)
        val hipPairs = LongArray(n)
        var hipCount = 0

        val datFile = File(outputDir, Tycho2Format.DAT_FILE)
        RandomAccessFile(datFile, "rw").use { raf ->
            raf.setLength(0)
            val ch = raf.channel
            val header = ByteBuffer.allocate(Tycho2Format.HEADER_SIZE)
            Tycho2Format.writeHeader(header, Tycho2Format.MAGIC_DAT, n, Tycho2Format.RECORD_SIZE)
            header.flip(); ch.write(header)
            val buf = ByteBuffer.allocateDirect(Tycho2Format.RECORD_SIZE * 8192).order(Tycho2Format.ORDER)
            for (k in 0 until n) {
                val i = (keys[k] and 0xFFFFFFFFL).toInt()
                val region = Tycho2Format.tyc1(tyc[i]) - 1
                if (regionCount[region] == 0) regionFirst[region] = k
                regionCount[region]++
                if (hip[i] > 0) hipPairs[hipCount++] = (hip[i].toLong() shl 32) or k.toLong()
                buf.putInt(ra[i]).putInt(dec[i]).putShort(pmRa[i]).putShort(pmDec[i])
                    .putShort(vmag[i]).putShort(bv[i]).putInt(tyc[i])
                if (!buf.hasRemaining()) { buf.flip(); while (buf.hasRemaining()) ch.write(buf); buf.clear() }
                if (k % 250_000 == 0) onProgress("Escribiendo", k)
            }
            buf.flip(); while (buf.hasRemaining()) ch.write(buf)
        }

        writeIndex(File(outputDir, Tycho2Format.IDX_FILE), keys, regionFirst, regionCount)
        val refs = writeReferences(File(outputDir, Tycho2Format.REF_FILE), hipPairs, hipCount, keys)
        onProgress("Listo", n)
        return Result(n, regionCount.count { it > 0 }, refs, skipped)
    }

    private fun writeIndex(file: File, keys: LongArray, first: IntArray, count: IntArray) {
        val buf = ByteBuffer.allocate(Tycho2Format.HEADER_SIZE + Tycho2Format.REGION_COUNT * Tycho2Format.INDEX_ENTRY_SIZE)
        Tycho2Format.writeHeader(buf, Tycho2Format.MAGIC_IDX, Tycho2Format.REGION_COUNT, Tycho2Format.INDEX_ENTRY_SIZE)
        for (r in 0 until Tycho2Format.REGION_COUNT) {
            val c = count[r]
            buf.putInt(if (c > 0) first[r] else 0).putInt(c)
            if (c == 0) {
                repeat(5) { buf.putFloat(Float.NaN) }
                continue
            }
            val ras = DoubleArray(c)
            var dMin = 90.0; var dMax = -90.0; var magMin = Double.MAX_VALUE
            for (j in 0 until c) {
                val i = (keys[first[r] + j] and 0xFFFFFFFFL).toInt()
                ras[j] = ra[i] / 1e6
                val d = dec[i] / 1e6
                dMin = min(dMin, d); dMax = max(dMax, d)
                magMin = min(magMin, vmag[i] / 1000.0)
            }
            val pad = BBOX_PAD_DEG
            dMin = (dMin - pad).coerceAtLeast(-90.0); dMax = (dMax + pad).coerceAtMost(90.0)
            var (raLo, raHi) = circularRange(ras)
            val maxAbsDec = max(abs(dMin), abs(dMax))
            if (maxAbsDec > 89.0) { raLo = 0.0; raHi = 360.0 } else {
                val raPad = pad / cos(Math.toRadians(maxAbsDec))
                val span = if (raLo <= raHi) raHi - raLo else raHi + 360 - raLo
                if (span + 2 * raPad >= 360.0) { raLo = 0.0; raHi = 360.0 } else {
                    raLo = (raLo - raPad + 360.0) % 360.0
                    raHi = (raHi + raPad) % 360.0
                }
            }
            buf.putFloat(raLo.toFloat()).putFloat(raHi.toFloat()).putFloat(dMin.toFloat()).putFloat(dMax.toFloat())
            buf.putFloat(magMin.toFloat())
        }
        file.writeBytes(buf.array())
    }

    private fun writeReferences(file: File, pairs: LongArray, count: Int, keys: LongArray): Int {
        val sorted = pairs.copyOf(count).also { it.sort() }
        // Para HIP duplicados (componentes CCDM) conserva la más brillante (= menor índice
        // dentro de su región; entre regiones, la de menor magnitud).
        val out = ByteBuffer.allocate(Tycho2Format.HEADER_SIZE + count * Tycho2Format.REF_ENTRY_SIZE)
        out.position(Tycho2Format.HEADER_SIZE)
        out.order(Tycho2Format.ORDER)
        var written = 0
        var k = 0
        while (k < count) {
            val h = (sorted[k] ushr 32).toInt()
            var best = (sorted[k] and 0xFFFFFFFFL).toInt()
            var j = k + 1
            while (j < count && (sorted[j] ushr 32).toInt() == h) {
                val cand = (sorted[j] and 0xFFFFFFFFL).toInt()
                if (magOf(keys, cand) < magOf(keys, best)) best = cand
                j++
            }
            out.putInt(h).putInt(best)
            written++
            k = j
        }
        val header = ByteBuffer.allocate(Tycho2Format.HEADER_SIZE)
        Tycho2Format.writeHeader(header, Tycho2Format.MAGIC_REF, written, Tycho2Format.REF_ENTRY_SIZE)
        val bytes = out.array().copyOf(Tycho2Format.HEADER_SIZE + written * Tycho2Format.REF_ENTRY_SIZE)
        System.arraycopy(header.array(), 0, bytes, 0, Tycho2Format.HEADER_SIZE)
        file.writeBytes(bytes)
        return written
    }

    private fun magOf(keys: LongArray, sortedIndex: Int): Short = vmag[(keys[sortedIndex] and 0xFFFFFFFFL).toInt()]

    companion object {
        /** Margen del recuadro de cada región (cubre ~3.6″/año de movimiento propio durante 50 años). */
        const val BBOX_PAD_DEG = 0.05

        /** Intervalo mínimo de AR (grados) que contiene todos los valores en el círculo. */
        fun circularRange(values: DoubleArray): Pair<Double, Double> {
            if (values.size == 1) return values[0] to values[0]
            val s = values.sortedArray()
            var maxGap = s[0] + 360.0 - s[s.size - 1]
            var gapEnd = 0
            for (i in 1 until s.size) {
                val g = s[i] - s[i - 1]
                if (g > maxGap) { maxGap = g; gapEnd = i }
            }
            val lo = s[gapEnd]
            val hi = s[(gapEnd - 1 + s.size) % s.size]
            return lo to hi
        }
    }
}
