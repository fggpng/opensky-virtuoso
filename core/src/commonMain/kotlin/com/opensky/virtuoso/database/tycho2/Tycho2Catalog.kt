package com.opensky.virtuoso.database.tycho2

import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.platform.BinaryBlob
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Estrella del Tycho-2 (posición J2000/ICRS en época J2000.0). */
data class Tycho2Star(
    val index: Int,
    val tyc1: Int, val tyc2: Int, val tyc3: Int,
    val raDeg: Double, val decDeg: Double,
    val pmRaMasYr: Double, val pmDecMasYr: Double,
    val vMag: Double, val bv: Double?,
) {
    val designation: String get() = "TYC $tyc1-$tyc2-$tyc3"
    val j2000: EquatorialCoord get() = EquatorialCoord.fromDegrees(raDeg, decDeg)
}

/**
 * Lector de Tycho-2 sobre archivos mapeados en memoria (mmap): no carga los ~50 MB en el heap,
 * solo las páginas que tocan las consultas.
 *
 * Consultas típicas:
 * - [cone]: estrellas a menos de r grados de un punto con V ≤ límite (≈ ms para r = 2°).
 * - [box]: recuadro AR/Dec ("bounding box" del cielo visible o de la pantalla).
 * - [findByTyc] / [findByHip]: búsqueda por designación.
 *
 * Es seguro para lecturas concurrentes (solo se usan lecturas absolutas sobre el buffer).
 */
class Tycho2Catalog private constructor(
    private val blobs: List<BinaryBlob>,
    private val dat: BinaryBlob,
    private val ref: BinaryBlob,
    private val regionFirst: IntArray,
    private val regionCount: IntArray,
    private val raMin: FloatArray, private val raMax: FloatArray,
    private val decMin: FloatArray, private val decMax: FloatArray,
    private val minMag: FloatArray,
    val starCount: Int,
    private val refCount: Int,
) : AutoCloseable {

    /** Lee el registro [index] (orden del archivo). */
    fun star(index: Int): Tycho2Star {
        val o = Tycho2Format.HEADER_SIZE + index * Tycho2Format.RECORD_SIZE
        val packed = dat.getIntLE(o + 16)
        val bvRaw = dat.getShortLE(o + 14)
        return Tycho2Star(
            index = index,
            tyc1 = Tycho2Format.tyc1(packed), tyc2 = Tycho2Format.tyc2(packed), tyc3 = Tycho2Format.tyc3(packed),
            raDeg = dat.getIntLE(o) / 1e6, decDeg = dat.getIntLE(o + 4) / 1e6,
            pmRaMasYr = dat.getShortLE(o + 8).toDouble(), pmDecMasYr = dat.getShortLE(o + 10).toDouble(),
            vMag = dat.getShortLE(o + 12) / 1000.0,
            bv = if (bvRaw == Tycho2Format.BV_UNKNOWN) null else bvRaw / 1000.0,
        )
    }

    private fun magMilliAt(index: Int) = dat.getShortLE(Tycho2Format.HEADER_SIZE + index * Tycho2Format.RECORD_SIZE + 12)

    /**
     * Estrellas con V ≤ [magLimit] dentro de [radiusDeg] de [center] (J2000), ordenadas por brillo.
     */
    fun cone(center: EquatorialCoord, radiusDeg: Double, magLimit: Double, limit: Int = 20_000): List<Tycho2Star> {
        val c = center.toVector()
        val cosR = cos(radiusDeg * DEG)
        val decC = center.decDeg
        val dLo = decC - radiusDeg
        val dHi = decC + radiusDeg
        val (raLo, raHi) = if (dLo <= -90.0 || dHi >= 90.0) 0.0 to 360.0 else {
            val half = asin((sin(radiusDeg * DEG) / cos(center.dec)).coerceIn(-1.0, 1.0)) / DEG
            if (half >= 180.0 || radiusDeg >= 90.0) 0.0 to 360.0
            else ((center.raDeg - half + 360.0) % 360.0) to ((center.raDeg + half) % 360.0)
        }
        return scan(raLo, raHi, dLo, dHi, magLimit, limit) { o ->
            val ra = dat.getIntLE(o) / 1e6 * DEG
            val dec = dat.getIntLE(o + 4) / 1e6 * DEG
            val cd = cos(dec)
            cd * cos(ra) * c.x + cd * sin(ra) * c.y + sin(dec) * c.z >= cosR
        }
    }

    /**
     * Recuadro AR/Dec en grados (J2000). Si [raMinDeg] > [raMaxDeg] el recuadro cruza AR = 0°.
     */
    fun box(raMinDeg: Double, raMaxDeg: Double, decMinDeg: Double, decMaxDeg: Double, magLimit: Double, limit: Int = 20_000): List<Tycho2Star> =
        scan(raMinDeg, raMaxDeg, decMinDeg, decMaxDeg, magLimit, limit) { o ->
            val ra = dat.getIntLE(o) / 1e6
            val dec = dat.getIntLE(o + 4) / 1e6
            dec in decMinDeg..decMaxDeg && inRaRange(ra, raMinDeg, raMaxDeg)
        }

    private inline fun scan(
        raLo: Double, raHi: Double, dLo: Double, dHi: Double, magLimit: Double, limit: Int,
        accept: (offset: Int) -> Boolean,
    ): List<Tycho2Star> {
        val magMilli = (magLimit * 1000).toInt()
        // Clave (magnitud, índice) en un LongArray: sin objetos intermedios aunque haya
        // cientos de miles de coincidencias.
        var hits = LongArray(1024)
        var n = 0
        for (r in regionFirst.indices) {
            val count = regionCount[r]
            if (count == 0 || minMag[r] > magLimit) continue
            if (decMax[r] < dLo || decMin[r] > dHi) continue
            if (!raIntervalsOverlap(raMin[r].toDouble(), raMax[r].toDouble(), raLo, raHi)) continue
            val first = regionFirst[r]
            for (i in first until first + count) {
                val m = magMilliAt(i)
                if (m > magMilli) break // la región está ordenada por magnitud
                if (accept(Tycho2Format.HEADER_SIZE + i * Tycho2Format.RECORD_SIZE)) {
                    if (n == hits.size) hits = hits.copyOf(n * 2)
                    hits[n++] = ((m + 32768).toLong() shl 32) or i.toLong()
                }
            }
        }
        val sorted = hits.copyOf(n).also { it.sort() }
        val take = minOf(limit, n)
        return List(take) { star((sorted[it] and 0xFFFFFFFFL).toInt()) }
    }

    /** Busca por designación TYC1-TYC2-TYC3 (recorre solo la región TYC1). */
    fun findByTyc(tyc1: Int, tyc2: Int, tyc3: Int): Tycho2Star? {
        if (tyc1 !in 1..regionCount.size) return null
        val packed = Tycho2Format.packTyc(tyc1, tyc2, tyc3)
        val first = regionFirst[tyc1 - 1]
        for (i in first until first + regionCount[tyc1 - 1]) {
            if (dat.getIntLE(Tycho2Format.HEADER_SIZE + i * Tycho2Format.RECORD_SIZE + 16) == packed) return star(i)
        }
        return null
    }

    /** Búsqueda binaria en la referencia cruzada HIP → registro. */
    fun findByHip(hip: Int): Tycho2Star? {
        var lo = 0
        var hi = refCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = ref.getIntLE(Tycho2Format.HEADER_SIZE + mid * Tycho2Format.REF_ENTRY_SIZE)
            when {
                v < hip -> lo = mid + 1
                v > hip -> hi = mid - 1
                else -> return star(ref.getIntLE(Tycho2Format.HEADER_SIZE + mid * Tycho2Format.REF_ENTRY_SIZE + 4))
            }
        }
        return null
    }

    override fun close() {
        blobs.forEach { runCatching { it.close() } }
    }

    companion object {
        val FILE_NAMES = listOf(Tycho2Format.DAT_FILE, Tycho2Format.IDX_FILE, Tycho2Format.REF_FILE)

        /**
         * Abre el catálogo a partir de sus tres archivos (cada plataforma los mapea en memoria:
         * ver `Tycho2Catalog.open(File)` en Android y `Tycho2Files.open(ruta)` en iOS).
         * Si algo falla, cierra los tres.
         */
        fun open(openFile: (name: String) -> BinaryBlob): Tycho2Catalog {
            val blobs = mutableListOf<BinaryBlob>()
            try {
                val dat = openFile(Tycho2Format.DAT_FILE).also { blobs += it }
                val idx = openFile(Tycho2Format.IDX_FILE).also { blobs += it }
                val ref = openFile(Tycho2Format.REF_FILE).also { blobs += it }
                val stars = Tycho2Format.readHeader(dat, Tycho2Format.MAGIC_DAT, Tycho2Format.RECORD_SIZE, Tycho2Format.DAT_FILE)
                require(dat.size >= Tycho2Format.HEADER_SIZE + stars.toLong() * Tycho2Format.RECORD_SIZE) { "tycho2.dat truncado" }
                val regions = Tycho2Format.readHeader(idx, Tycho2Format.MAGIC_IDX, Tycho2Format.INDEX_ENTRY_SIZE, Tycho2Format.IDX_FILE)
                require(idx.size >= Tycho2Format.HEADER_SIZE + regions.toLong() * Tycho2Format.INDEX_ENTRY_SIZE) { "tycho2.idx truncado" }
                val refs = Tycho2Format.readHeader(ref, Tycho2Format.MAGIC_REF, Tycho2Format.REF_ENTRY_SIZE, Tycho2Format.REF_FILE)
                require(ref.size >= Tycho2Format.HEADER_SIZE + refs.toLong() * Tycho2Format.REF_ENTRY_SIZE) { "tycho2.ref truncado" }
                val first = IntArray(regions); val count = IntArray(regions)
                val raLo = FloatArray(regions); val raHi = FloatArray(regions)
                val dLo = FloatArray(regions); val dHi = FloatArray(regions); val mag = FloatArray(regions)
                for (r in 0 until regions) {
                    val o = Tycho2Format.HEADER_SIZE + r * Tycho2Format.INDEX_ENTRY_SIZE
                    first[r] = idx.getIntLE(o); count[r] = idx.getIntLE(o + 4)
                    raLo[r] = idx.getFloatLE(o + 8); raHi[r] = idx.getFloatLE(o + 12)
                    dLo[r] = idx.getFloatLE(o + 16); dHi[r] = idx.getFloatLE(o + 20); mag[r] = idx.getFloatLE(o + 24)
                }
                return Tycho2Catalog(blobs, dat, ref, first, count, raLo, raHi, dLo, dHi, mag, stars, refs)
            } catch (e: Exception) {
                blobs.forEach { runCatching { it.close() } }
                throw e
            }
        }

        fun inRaRange(ra: Double, lo: Double, hi: Double): Boolean =
            if (lo <= hi) ra in lo..hi else ra >= lo || ra <= hi

        /** Solapamiento de dos intervalos de AR en el círculo (cada uno puede cruzar 0°). */
        fun raIntervalsOverlap(aLo: Double, aHi: Double, bLo: Double, bHi: Double): Boolean {
            fun parts(lo: Double, hi: Double) = if (lo <= hi) listOf(lo to hi) else listOf(lo to 360.0, 0.0 to hi)
            return parts(aLo, aHi).any { (a0, a1) -> parts(bLo, bHi).any { (b0, b1) -> a0 <= b1 && b0 <= a1 } }
        }
    }
}
