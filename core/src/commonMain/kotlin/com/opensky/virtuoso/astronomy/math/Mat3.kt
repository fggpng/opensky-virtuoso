package com.opensky.virtuoso.astronomy.math

import kotlin.math.cos
import kotlin.math.sin

/**
 * Matriz 3x3 inmutable en orden por filas.
 *
 * Convención: las rotaciones [rotX], [rotY] y [rotZ] son ACTIVAS (giran el vector en sentido
 * antihorario alrededor del eje, vistas desde el extremo positivo del eje).
 */
class Mat3(private val m: DoubleArray) {

    init {
        require(m.size == 9)
    }

    operator fun get(row: Int, col: Int): Double = m[row * 3 + col]

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    operator fun times(o: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += this[i, k] * o[k, j]
            r[i * 3 + j] = s
        }
        return Mat3(r)
    }

    fun transpose() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    fun row(i: Int) = Vec3(m[i * 3], m[i * 3 + 1], m[i * 3 + 2])

    fun toArray(): DoubleArray = m.copyOf()

    override fun toString(): String = (0..2).joinToString(prefix = "[", postfix = "]") { row(it).toString() }

    companion object {
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun rotX(a: Double): Mat3 {
            val c = cos(a); val s = sin(a)
            return Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c))
        }

        fun rotY(a: Double): Mat3 {
            val c = cos(a); val s = sin(a)
            return Mat3(doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c))
        }

        fun rotZ(a: Double): Mat3 {
            val c = cos(a); val s = sin(a)
            return Mat3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        }

        /** Matriz cuyas filas son los vectores dados. */
        fun fromRows(r0: Vec3, r1: Vec3, r2: Vec3) =
            Mat3(doubleArrayOf(r0.x, r0.y, r0.z, r1.x, r1.y, r1.z, r2.x, r2.y, r2.z))

        /**
         * Rotación de ángulo pequeño (aproximación exacta por Rodrigues) a partir de un vector
         * de rotación (radianes). Útil para parametrizar el modelo de alineación.
         */
        fun fromRotationVector(rx: Double, ry: Double, rz: Double): Mat3 {
            val theta = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
            if (theta < 1e-15) return IDENTITY
            val kx = rx / theta; val ky = ry / theta; val kz = rz / theta
            val c = cos(theta); val s = sin(theta); val t = 1 - c
            return Mat3(
                doubleArrayOf(
                    t * kx * kx + c, t * kx * ky - s * kz, t * kx * kz + s * ky,
                    t * kx * ky + s * kz, t * ky * ky + c, t * ky * kz - s * kx,
                    t * kx * kz - s * ky, t * ky * kz + s * kx, t * kz * kz + c,
                ),
            )
        }
    }
}
