package com.opensky.virtuoso.database.tycho2

import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.platform.BinarySink
import com.opensky.virtuoso.platform.putFloatLE
import com.opensky.virtuoso.platform.putIntLE
import com.opensky.virtuoso.platform.putShortLE
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
 * ejecutarse en el PC (módulo tools:catalog-builder) o en el propio teléfono (Android o iOS).
 * La lectura de archivos (y de .gz) la pone cada plataforma: ver `addFile`/`write` en jvmMain y
 * `Tycho2Files` en appleMain.
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

    /** Añade las líneas de un archivo del CDS (tyc2.dat o suppl_1.dat). */
    fun addLines(lines: Sequence<String>) {
        lines.forEach { addTextLine(it) }
    }

    /** Añade una línea contando las descartadas e informando del progreso. */
    fun addTextLine(line: String) {
        if (!addLine(line)) skipped++
        if (n % 100_000 == 0 && n > 0) onProgress("Leyendo", n)
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
                raDeg = ra1991 + pmA / 3.6e6 * years / cos(dec1991 * DEG).coerceAtLeast(1e-6)
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

    /**
     * Escribe tycho2.dat, tycho2.idx y tycho2.ref: [openSink] crea cada archivo por su nombre
     * (en disco en la app; en memoria en las pruebas).
     */
    fun write(openSink: (name: String) -> BinarySink): Result {
        require(n > 0) { "No se leyeron estrellas: ¿archivos correctos (tyc2.dat / suppl_1.dat)?" }
        onProgress("Ordenando", n)
        val keys = LongArray(n) { i ->
            (Tycho2Format.tyc1(tyc[i]).toLong() shl 48) or ((vmag[i] + 32768).toLong() shl 32) or i.toLong()
        }
        keys.sort()

        val regionFirst = IntArray(Tycho2Format.REGION_COUNT)
        val regionCount = IntArray(Tycho2Format.REGION_COUNT)
        val hipPairs = LongArray(n)
        var hipCount = 0

        openSink(Tycho2Format.DAT_FILE).use { sink ->
            sink.write(Tycho2Format.header(Tycho2Format.MAGIC_DAT, n, Tycho2Format.RECORD_SIZE))
            val chunk = ByteArray(Tycho2Format.RECORD_SIZE * 8192)
            var used = 0
            for (k in 0 until n) {
                val i = (keys[k] and 0xFFFFFFFFL).toInt()
                val region = Tycho2Format.tyc1(tyc[i]) - 1
                if (regionCount[region] == 0) regionFirst[region] = k
                regionCount[region]++
                if (hip[i] > 0) hipPairs[hipCount++] = (hip[i].toLong() shl 32) or k.toLong()
                chunk.putIntLE(used, ra[i]); chunk.putIntLE(used + 4, dec[i])
                chunk.putShortLE(used + 8, pmRa[i].toInt()); chunk.putShortLE(used + 10, pmDec[i].toInt())
                chunk.putShortLE(used + 12, vmag[i].toInt()); chunk.putShortLE(used + 14, bv[i].toInt())
                chunk.putIntLE(used + 16, tyc[i])
                used += Tycho2Format.RECORD_SIZE
                if (used == chunk.size) { sink.write(chunk, 0, used); used = 0 }
                if (k % 250_000 == 0) onProgress("Escribiendo", k)
            }
            if (used > 0) sink.write(chunk, 0, used)
        }

        openSink(Tycho2Format.IDX_FILE).use { it.write(buildIndex(keys, regionFirst, regionCount)) }
        val refs = buildReferences(hipPairs, hipCount, keys)
        openSink(Tycho2Format.REF_FILE).use { it.write(refs.second) }
        onProgress("Listo", n)
        return Result(n, regionCount.count { it > 0 }, refs.first, skipped)
    }

    private fun buildIndex(keys: LongArray, first: IntArray, count: IntArray): ByteArray {
        val buf = ByteArray(Tycho2Format.HEADER_SIZE + Tycho2Format.REGION_COUNT * Tycho2Format.INDEX_ENTRY_SIZE)
        Tycho2Format.header(Tycho2Format.MAGIC_IDX, Tycho2Format.REGION_COUNT, Tycho2Format.INDEX_ENTRY_SIZE).copyInto(buf, 0)
        for (r in 0 until Tycho2Format.REGION_COUNT) {
            val o = Tycho2Format.HEADER_SIZE + r * Tycho2Format.INDEX_ENTRY_SIZE
            val c = count[r]
            buf.putIntLE(o, if (c > 0) first[r] else 0)
            buf.putIntLE(o + 4, c)
            if (c == 0) {
                for (k in 0 until 5) buf.putFloatLE(o + 8 + 4 * k, Float.NaN)
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
                val raPad = pad / cos(maxAbsDec * DEG)
                val span = if (raLo <= raHi) raHi - raLo else raHi + 360 - raLo
                if (span + 2 * raPad >= 360.0) { raLo = 0.0; raHi = 360.0 } else {
                    raLo = (raLo - raPad + 360.0) % 360.0
                    raHi = (raHi + raPad) % 360.0
                }
            }
            buf.putFloatLE(o + 8, raLo.toFloat()); buf.putFloatLE(o + 12, raHi.toFloat())
            buf.putFloatLE(o + 16, dMin.toFloat()); buf.putFloatLE(o + 20, dMax.toFloat())
            buf.putFloatLE(o + 24, magMin.toFloat())
        }
        return buf
    }

    /** Referencia cruzada HIP → registro: (entradas escritas, bytes del archivo). */
    private fun buildReferences(pairs: LongArray, count: Int, keys: LongArray): Pair<Int, ByteArray> {
        val sorted = pairs.copyOf(count).also { it.sort() }
        // Para HIP duplicados (componentes CCDM) conserva la más brillante (= menor índice
        // dentro de su región; entre regiones, la de menor magnitud).
        val body = ByteArray(count * Tycho2Format.REF_ENTRY_SIZE)
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
            body.putIntLE(written * Tycho2Format.REF_ENTRY_SIZE, h)
            body.putIntLE(written * Tycho2Format.REF_ENTRY_SIZE + 4, best)
            written++
            k = j
        }
        val out = ByteArray(Tycho2Format.HEADER_SIZE + written * Tycho2Format.REF_ENTRY_SIZE)
        Tycho2Format.header(Tycho2Format.MAGIC_REF, written, Tycho2Format.REF_ENTRY_SIZE).copyInto(out, 0)
        body.copyInto(out, Tycho2Format.HEADER_SIZE, 0, written * Tycho2Format.REF_ENTRY_SIZE)
        return written to out
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
