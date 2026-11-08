package com.opensky.virtuoso.astronomy.math

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Vector cartesiano 3D inmutable (doble precisión). */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun div(k: Double) = Vec3(x / k, y / k, z / k)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val norm: Double get() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val n = norm
        require(n > 0.0) { "No se puede normalizar el vector cero" }
        return this / n
    }

    /** Longitud esférica en radianes, rango (-π, π]. */
    val lon: Double get() = atan2(y, x)

    /** Latitud esférica en radianes, rango [-π/2, π/2]. */
    val lat: Double get() = asin((z / norm).coerceIn(-1.0, 1.0))

    /** Ángulo entre dos vectores (radianes), numéricamente estable para ángulos pequeños. */
    fun angleTo(o: Vec3): Double = atan2((this cross o).norm, this dot o)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)

        /** Vector unitario a partir de longitud y latitud esféricas (radianes). */
        fun fromSpherical(lon: Double, lat: Double, r: Double = 1.0): Vec3 {
            val cl = cos(lat)
            return Vec3(r * cl * cos(lon), r * cl * sin(lon), r * sin(lat))
        }
    }
}
