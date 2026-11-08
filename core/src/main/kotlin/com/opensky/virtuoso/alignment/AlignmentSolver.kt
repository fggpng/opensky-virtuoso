package com.opensky.virtuoso.alignment

import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.math.ARCMIN
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.astronomy.math.Mat3
import com.opensky.virtuoso.astronomy.math.Vec3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Muestra de alineación: estrella centrada en el ocular.
 * @property sky posición horizontal APARENTE (con refracción) en el instante de confirmar.
 * @property azEncoder,altEncoder ángulos de encoder (rad) en ese mismo instante.
 */
data class AlignmentSample(
    val name: String,
    val sky: HorizontalCoord,
    val azEncoder: Double,
    val altEncoder: Double,
    val timestampMillis: Long = System.currentTimeMillis(),
)

/** Parámetros ajustables del modelo. */
enum class ModelTerm { TILT_X, TILT_Y, AZ_ZERO, INDEX_ALT, CONE, NON_PERP }

/**
 * Calidad de la alineación.
 * @property rmsArcmin error residual RMS (desviación estándar corregida por grados de libertad).
 * @property precisionPercent 100·(1 − RMS/R), con R = radio del campo del ocular de referencia.
 * @property fieldHitProbability probabilidad de que el objeto caiga dentro de ese campo
 *   (error 2D gaussiano: P = 1 − exp(−R²/RMS²)).
 */
data class AlignmentQuality(
    val sampleCount: Int,
    val degreesOfFreedom: Int,
    val rmsArcmin: Double?,
    val maxResidualArcmin: Double?,
    val residualsArcmin: List<Double>,
    val referenceFieldRadiusArcmin: Double,
    val method: String,
) {
    val precisionPercent: Double? get() = rmsArcmin?.let { (100.0 * (1.0 - it / referenceFieldRadiusArcmin)).coerceIn(0.0, 100.0) }
    val fieldHitProbability: Double? get() = rmsArcmin?.let {
        if (it <= 0.0) 1.0 else 1.0 - exp(-(referenceFieldRadiusArcmin * referenceFieldRadiusArcmin) / (it * it))
    }
}

data class FitResult(val model: PointingModel, val quality: AlignmentQuality, val freeTerms: List<ModelTerm>)

/**
 * Ajuste del modelo de apuntado por mínimos cuadrados no lineales (Levenberg–Marquardt) con
 * arranque por el método matricial de Taki (dos estrellas → tríada ortonormal).
 *
 * Términos libres según el número de estrellas N (se conserva al menos 1 grado de libertad
 * desde N = 3 para poder estimar el error):
 *   N=1 → cero de acimut + índice de altura (supone trípode nivelado)
 *   N=2 → rotación completa (inclinación del trípode) + índice de altura
 *   N=3 → + error de cono
 *   N≥4 → + no perpendicularidad de ejes
 */
object AlignmentSolver {

    /** Desviación típica de centrado supuesta (para ponderar las restricciones a priori). */
    private const val CENTERING_SIGMA = 2.0 * ARCMIN
    /** A priori débil sobre cono y no perpendicularidad (σ = 1°): estabiliza geometrías pobres. */
    private const val PRIOR_SIGMA = 1.0 * DEG

    fun freeTermsFor(n: Int): List<ModelTerm> = when {
        n <= 0 -> emptyList()
        n == 1 -> listOf(ModelTerm.AZ_ZERO, ModelTerm.INDEX_ALT)
        n == 2 -> listOf(ModelTerm.TILT_X, ModelTerm.TILT_Y, ModelTerm.AZ_ZERO, ModelTerm.INDEX_ALT)
        n == 3 -> listOf(ModelTerm.TILT_X, ModelTerm.TILT_Y, ModelTerm.AZ_ZERO, ModelTerm.INDEX_ALT, ModelTerm.CONE)
        else -> ModelTerm.entries.toList()
    }

    fun fit(samples: List<AlignmentSample>, referenceFieldRadiusArcmin: Double = 52.0): FitResult {
        val n = samples.size
        if (n == 0) return FitResult(PointingModel.IDENTITY, emptyQuality(referenceFieldRadiusArcmin, "Sin alinear"), emptyList())
        val terms = freeTermsFor(n)
        val r0 = initialRotation(samples)
        val ia0 = if (n == 1) samples[0].sky.alt - samples[0].altEncoder else 0.0

        fun build(p: DoubleArray): PointingModel {
            var rx = 0.0; var ry = 0.0; var rz = 0.0; var ia = ia0; var c = 0.0; var np = 0.0
            terms.forEachIndexed { k, t ->
                when (t) {
                    ModelTerm.TILT_X -> rx = p[k]
                    ModelTerm.TILT_Y -> ry = p[k]
                    ModelTerm.AZ_ZERO -> rz = p[k]
                    ModelTerm.INDEX_ALT -> ia = ia0 + p[k]
                    ModelTerm.CONE -> c = p[k]
                    ModelTerm.NON_PERP -> np = p[k]
                }
            }
            return PointingModel(Mat3.fromRotationVector(rx, ry, rz) * r0, ia, c, np)
        }

        fun residuals(p: DoubleArray): DoubleArray {
            val m = build(p)
            val out = DoubleArray(3 * n + 2)
            samples.forEachIndexed { i, s ->
                val d = m.mountToSky(s.azEncoder, s.altEncoder).toVector() - s.sky.toVector()
                out[3 * i] = d.x; out[3 * i + 1] = d.y; out[3 * i + 2] = d.z
            }
            out[3 * n] = m.cone * CENTERING_SIGMA / PRIOR_SIGMA
            out[3 * n + 1] = m.nonPerpendicularity * CENTERING_SIGMA / PRIOR_SIGMA
            return out
        }

        val p = levenbergMarquardt(DoubleArray(terms.size), ::residuals)
        val model = build(p)
        return FitResult(model, quality(samples, model, terms.size, referenceFieldRadiusArcmin), terms)
    }

    /** Errores residuales angulares (arcmin) de cada muestra con un modelo dado. */
    fun residualsArcmin(samples: List<AlignmentSample>, model: PointingModel): List<Double> = samples.map {
        model.mountToSky(it.azEncoder, it.altEncoder).separation(it.sky) / ARCMIN
    }

    private fun quality(samples: List<AlignmentSample>, model: PointingModel, params: Int, fieldR: Double): AlignmentQuality {
        val res = residualsArcmin(samples, model)
        val n = samples.size
        val dof = 2 * n - params
        return when {
            n == 1 -> AlignmentQuality(1, 0, null, null, res, fieldR, "1 estrella: sin redundancia para estimar el error (supone trípode nivelado)")
            n == 2 -> AlignmentQuality(2, 0, null, null, res, fieldR,
                "2 estrellas: ajuste exacto sin redundancia. Añade una 3.ª estrella o un Sync para medir la precisión")
            else -> {
                val sumSq = res.sumOf { it * it }
                val rms = sqrt(sumSq / dof.coerceAtLeast(1))
                AlignmentQuality(n, dof, rms, res.maxOrNull(), res, fieldR, "$n estrellas: RMS de residuos (g.l. = $dof)")
            }
        }
    }

    private fun emptyQuality(fieldR: Double, method: String) = AlignmentQuality(0, 0, null, null, emptyList(), fieldR, method)

    /**
     * Rotación inicial (método de Taki). Usa el par de muestras con separación más cercana a 90°
     * para máxima estabilidad; con una sola muestra, solo un giro en acimut.
     */
    fun initialRotation(samples: List<AlignmentSample>): Mat3 {
        val ideal = samples.map { PointingModel.IDENTITY.mountToBase(it.azEncoder, it.altEncoder) }
        val sky = samples.map { it.sky.toVector() }
        if (samples.size == 1) return Mat3.rotZ(ideal[0].lon - sky[0].lon)
        var best = 0 to 1
        var bestScore = Double.MAX_VALUE
        for (i in samples.indices) for (j in i + 1 until samples.size) {
            val score = abs(sky[i].angleTo(sky[j]) - Math.PI / 2)
            if (score < bestScore) { bestScore = score; best = i to j }
        }
        val (i, j) = best
        fun triad(a: Vec3, b: Vec3): Triple<Vec3, Vec3, Vec3> {
            val t1 = a.normalized()
            val t2 = (a cross b).normalized()
            return Triple(t1, t2, t1 cross t2)
        }
        val (s1, s2, s3) = triad(sky[i], sky[j])
        val (m1, m2, m3) = triad(ideal[i], ideal[j])
        return Mat3.fromRows(m1, m2, m3).transpose() * Mat3.fromRows(s1, s2, s3)
    }

    /** Levenberg–Marquardt con Jacobiano por diferencias centrales. */
    fun levenbergMarquardt(start: DoubleArray, f: (DoubleArray) -> DoubleArray, maxIter: Int = 60): DoubleArray {
        val k = start.size
        if (k == 0) return start
        var p = start.copyOf()
        var r = f(p)
        var cost = r.sumOf { it * it }
        var lambda = 1e-3
        val h = 1e-7
        repeat(maxIter) {
            val m = r.size
            val jac = Array(m) { DoubleArray(k) }
            for (c in 0 until k) {
                val pp = p.copyOf(); pp[c] += h
                val pm = p.copyOf(); pm[c] -= h
                val rp = f(pp); val rm = f(pm)
                for (row in 0 until m) jac[row][c] = (rp[row] - rm[row]) / (2 * h)
            }
            val a = Array(k) { DoubleArray(k) }
            val g = DoubleArray(k)
            for (row in 0 until m) for (c1 in 0 until k) {
                g[c1] += jac[row][c1] * r[row]
                for (c2 in 0 until k) a[c1][c2] += jac[row][c1] * jac[row][c2]
            }
            var improved = false
            while (!improved && lambda < 1e12) {
                val aa = Array(k) { i -> DoubleArray(k) { j -> a[i][j] + if (i == j) lambda * (a[i][i] + 1e-12) else 0.0 } }
                val delta = solve(aa, DoubleArray(k) { -g[it] }) ?: break
                val pNew = DoubleArray(k) { p[it] + delta[it] }
                val rNew = f(pNew)
                val costNew = rNew.sumOf { it * it }
                if (costNew < cost) {
                    val step = sqrt(delta.sumOf { it * it })
                    p = pNew; r = rNew; cost = costNew; lambda = (lambda / 3).coerceAtLeast(1e-12); improved = true
                    if (step < 1e-12) return p
                } else lambda *= 4
            }
            if (!improved) return p
        }
        return p
    }

    /** Eliminación gaussiana con pivoteo parcial (sistemas pequeños). */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
        for (col in 0 until n) {
            val piv = (col until n).maxByOrNull { abs(m[it][col]) }!!
            if (abs(m[piv][col]) < 1e-300) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (row in col + 1 until n) {
                val factor = m[row][col] / m[col][col]
                for (c in col..n) m[row][c] -= factor * m[col][c]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = m[i][n]
            for (j in i + 1 until n) s -= m[i][j] * x[j]
            x[i] = s / m[i][i]
        }
        return x
    }
}
