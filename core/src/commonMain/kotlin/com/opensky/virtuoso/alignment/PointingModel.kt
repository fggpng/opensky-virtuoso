package com.opensky.virtuoso.alignment

import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.Mat3
import com.opensky.virtuoso.astronomy.math.Vec3
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Modelo geométrico de apuntado para una montura altacimutal (marco local Norte-Oeste-Cenit).
 *
 * Cadena directa (ángulos de encoder → dirección en el cielo):
 *   w(e)  = (cos c·cos e, sin c, cos c·sin e)        eje óptico con error de cono c
 *   u     = Rx(n) · w                               eje de altura no perpendicular (n)
 *   base  = Rz(−a) · u                              giro en acimut del encoder a
 *   cielo = Rᵀ · base                               R: inclinación del trípode + cero de acimut
 * con e = altEncoder + IE (error de índice en altura).
 *
 * Parámetros (equivalentes TPOINT): R ≈ {AN, AW, IA}, [indexAlt] = IE, [cone] = CA,
 * [nonPerpendicularity] = NPAE. Todos en radianes.
 */
data class PointingModel(
    val rotation: Mat3 = Mat3.IDENTITY,
    val indexAlt: Double = 0.0,
    val cone: Double = 0.0,
    val nonPerpendicularity: Double = 0.0,
) {
    private val rotationT: Mat3 by lazy { rotation.transpose() }

    /** Dirección del cielo (horizontal aparente) a la que apunta el tubo con esos encoders. */
    fun mountToSky(azEncoder: Double, altEncoder: Double): HorizontalCoord =
        HorizontalCoord.fromVector(rotationT * mountToBase(azEncoder, altEncoder))

    fun mountToBase(azEncoder: Double, altEncoder: Double): Vec3 {
        val e = altEncoder + indexAlt
        val w = Vec3(cos(cone) * cos(e), sin(cone), cos(cone) * sin(e))
        return Mat3.rotZ(-azEncoder) * (Mat3.rotX(nonPerpendicularity) * w)
    }

    /**
     * Ángulos de encoder (acimut en (−π, π], altura) que apuntan a [sky]. Solución cerrada.
     * @return null si la dirección es inalcanzable por la geometría (cerca del cenit con c/n grandes).
     */
    fun skyToMount(sky: HorizontalCoord): Pair<Double, Double>? {
        val v = rotation * sky.toVector()
        val sinE = (v.z - sin(nonPerpendicularity) * sin(cone)) / (cos(nonPerpendicularity) * cos(cone))
        if (sinE < -1.0000001 || sinE > 1.0000001) return null
        val e = asin(sinE.coerceIn(-1.0, 1.0))
        val w = Vec3(cos(cone) * cos(e), sin(cone), cos(cone) * sin(e))
        val u = Mat3.rotX(nonPerpendicularity) * w
        val a = Angles.normPi(u.lon - v.lon)
        return a to (e - indexAlt)
    }

    companion object {
        val IDENTITY = PointingModel()
    }
}
