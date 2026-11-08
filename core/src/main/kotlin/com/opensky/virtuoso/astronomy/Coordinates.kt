package com.opensky.virtuoso.astronomy

import com.opensky.virtuoso.astronomy.math.ARCMIN
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.astronomy.math.HOUR
import com.opensky.virtuoso.astronomy.math.RAD
import com.opensky.virtuoso.astronomy.math.Vec3
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Coordenadas ecuatoriales (radianes). AR en [0, 2π). */
data class EquatorialCoord(val ra: Double, val dec: Double) {
    val raHours: Double get() = Angles.norm2Pi(ra) / HOUR
    val raDeg: Double get() = Angles.norm2Pi(ra) * RAD
    val decDeg: Double get() = dec * RAD

    fun toVector(): Vec3 = Vec3.fromSpherical(ra, dec)

    fun separation(o: EquatorialCoord): Double = toVector().angleTo(o.toVector())

    override fun toString() = "RA ${Angles.formatHms(raHours)}  Dec ${Angles.formatDms(decDeg)}"

    companion object {
        fun fromDegrees(raDeg: Double, decDeg: Double) = EquatorialCoord(Angles.norm2Pi(raDeg * DEG), decDeg * DEG)
        fun fromHoursDegrees(raHours: Double, decDeg: Double) = EquatorialCoord(Angles.norm2Pi(raHours * HOUR), decDeg * DEG)
        fun fromVector(v: Vec3) = EquatorialCoord(Angles.norm2Pi(v.lon), v.lat)
    }
}

/**
 * Coordenadas horizontales (radianes). Acimut medido desde el Norte hacia el Este
 * (0 = N, 90° = E, 180° = S, 270° = O).
 */
data class HorizontalCoord(val az: Double, val alt: Double) {
    val azDeg: Double get() = Angles.norm2Pi(az) * RAD
    val altDeg: Double get() = alt * RAD

    /**
     * Vector en el marco local Norte-Oeste-Cenit (dextrógiro): x=N, y=O, z=cenit.
     */
    fun toVector(): Vec3 = Vec3.fromSpherical(-az, alt)

    fun separation(o: HorizontalCoord): Double = toVector().angleTo(o.toVector())

    override fun toString() = "Az %.3f°  Alt %.3f°".format(azDeg, altDeg)

    companion object {
        fun fromDegrees(azDeg: Double, altDeg: Double) = HorizontalCoord(Angles.norm2Pi(azDeg * DEG), altDeg * DEG)
        fun fromVector(v: Vec3) = HorizontalCoord(Angles.norm2Pi(-v.lon), v.lat)
    }
}

/** Ubicación geodésica WGS84. Longitud positiva al Este. */
data class GeoLocation(val latitudeDeg: Double, val longitudeDeg: Double, val elevationM: Double = 0.0) {
    val latRad: Double get() = latitudeDeg * DEG
}

/** Condiciones atmosféricas para la refracción. */
data class Weather(val pressureHpa: Double = 1010.0, val temperatureC: Double = 10.0)

/** Conversión entre marcos ecuatorial (de la fecha) y horizontal. */
object CoordinateTransforms {

    /** Ecuatorial (de la fecha) → horizontal geométrico, dado el LAST (radianes). */
    fun equatorialToHorizontal(eq: EquatorialCoord, last: Double, latitude: Double): HorizontalCoord {
        val h = last - eq.ra
        val sinAlt = sin(latitude) * sin(eq.dec) + cos(latitude) * cos(eq.dec) * cos(h)
        val az = atan2(-cos(eq.dec) * sin(h), sin(eq.dec) * cos(latitude) - cos(eq.dec) * cos(h) * sin(latitude))
        return HorizontalCoord(Angles.norm2Pi(az), asin(sinAlt.coerceIn(-1.0, 1.0)))
    }

    /** Horizontal geométrico → ecuatorial de la fecha. */
    fun horizontalToEquatorial(hz: HorizontalCoord, last: Double, latitude: Double): EquatorialCoord {
        val sinDec = sin(latitude) * sin(hz.alt) + cos(latitude) * cos(hz.alt) * cos(hz.az)
        val h = atan2(-sin(hz.az) * cos(hz.alt), sin(hz.alt) * cos(latitude) - cos(hz.alt) * cos(hz.az) * sin(latitude))
        return EquatorialCoord(Angles.norm2Pi(last - h), asin(sinDec.coerceIn(-1.0, 1.0)))
    }

    /** Ángulo paraláctico (radianes) de un objeto. */
    fun parallacticAngle(eq: EquatorialCoord, last: Double, latitude: Double): Double {
        val h = last - eq.ra
        return atan2(sin(h), tan(latitude) * cos(eq.dec) - sin(eq.dec) * cos(h))
    }

    /**
     * Corrección topocéntrica (paralaje) de un objeto a [distanceKm] del geocentro.
     * Imprescindible para la Luna (hasta ~1°).
     */
    fun geocentricToTopocentric(
        eq: EquatorialCoord, distanceKm: Double, last: Double, location: GeoLocation,
    ): EquatorialCoord {
        val obs = observerGeocentricVector(last, location)
        val target = eq.toVector() * distanceKm
        return EquatorialCoord.fromVector(target - obs)
    }

    /** Vector geocéntrico del observador en km (marco ecuatorial de la fecha). */
    fun observerGeocentricVector(last: Double, location: GeoLocation): Vec3 {
        val a = 6378.137
        val ba = 0.99664719 // 1 − f (WGS84)
        val phi = location.latRad
        val u = atan(ba * tan(phi))
        val hRatio = location.elevationM / 1000.0 / a
        val rhoSin = ba * sin(u) + hRatio * sin(phi)
        val rhoCos = cos(u) + hRatio * cos(phi)
        return Vec3(a * rhoCos * cos(last), a * rhoCos * sin(last), a * rhoSin)
    }
}

/**
 * Refracción atmosférica. Fórmulas de Sæmundsson (verdadera → aparente) y Bennett
 * (aparente → verdadera), escaladas por presión y temperatura. Error < 0.1′ sobre 5° de altura.
 */
object Refraction {

    private fun factor(w: Weather) = (w.pressureHpa / 1010.0) * (283.0 / (273.0 + w.temperatureC))

    /** Refracción (radianes) a sumar a una altura verdadera [trueAlt] (radianes). */
    fun fromTrueAltitude(trueAlt: Double, weather: Weather = Weather()): Double {
        val h = (trueAlt * RAD).coerceAtLeast(-1.9)
        if (h > 89.9) return 0.0
        val r = 1.02 / tan((h + 10.3 / (h + 5.11)) * DEG)
        return (r * factor(weather)).coerceAtLeast(0.0) * ARCMIN
    }

    /** Refracción (radianes) a restar de una altura aparente [apparentAlt] (radianes). */
    fun fromApparentAltitude(apparentAlt: Double, weather: Weather = Weather()): Double {
        val h0 = (apparentAlt * RAD).coerceAtLeast(-1.7)
        if (h0 > 89.9) return 0.0
        val r = 1.0 / tan((h0 + 7.31 / (h0 + 4.4)) * DEG)
        return (r * factor(weather)).coerceAtLeast(0.0) * ARCMIN
    }

    fun trueToApparent(hz: HorizontalCoord, weather: Weather = Weather()) =
        hz.copy(alt = hz.alt + fromTrueAltitude(hz.alt, weather))

    fun apparentToTrue(hz: HorizontalCoord, weather: Weather = Weather()) =
        hz.copy(alt = hz.alt - fromApparentAltitude(hz.alt, weather))
}
