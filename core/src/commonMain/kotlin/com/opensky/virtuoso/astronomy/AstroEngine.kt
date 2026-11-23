package com.opensky.virtuoso.astronomy

import com.opensky.virtuoso.astronomy.ephemeris.EarthState
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystem
import com.opensky.virtuoso.astronomy.math.ARCSEC
import kotlin.math.cos

/**
 * Motor astrométrico: encadena las transformaciones necesarias para apuntar el telescopio.
 *
 *  Catálogo J2000/ICRS ──movimiento propio──► época ──aberración──► precesión+nutación
 *     ──► aparente geocéntrica de la fecha ──paralaje──► topocéntrica ──LAST──► Alt/Az
 *     ──refracción──► Alt/Az aparente (lo que "ve" el tubo).
 *
 * Todas las operaciones son puras (sin estado) y seguras entre hilos.
 */
object AstroEngine {

    /** Posición aparente de la fecha de una estrella con coordenadas J2000 y movimiento propio. */
    fun starApparent(
        j2000: EquatorialCoord,
        time: AstroTime,
        pmRaCosDecMasPerYear: Double = 0.0,
        pmDecMasPerYear: Double = 0.0,
    ): EquatorialCoord {
        val years = time.julianYearTt - 2000.0
        val cosDec = cos(j2000.dec).coerceAtLeast(1e-9)
        val ra = j2000.ra + pmRaCosDecMasPerYear / 1000.0 * ARCSEC * years / cosDec
        val dec = j2000.dec + pmDecMasPerYear / 1000.0 * ARCSEC * years
        return j2000ToApparent(EquatorialCoord(ra, dec), time)
    }

    /** J2000 (astrométrica, sin movimiento propio) → aparente de la fecha. */
    fun j2000ToApparent(j2000: EquatorialCoord, time: AstroTime): EquatorialCoord {
        val t = time.centuriesTt
        val p = SolarSystem.applyAberration(j2000.toVector(), EarthState.velocity(t))
        return EquatorialCoord.fromVector(EarthOrientation.precessionNutationMatrix(t) * p)
    }

    /** Aparente de la fecha → J2000 astrométrica (inversa exacta a primer orden). */
    fun apparentToJ2000(apparent: EquatorialCoord, time: AstroTime): EquatorialCoord {
        val t = time.centuriesTt
        val mean = EarthOrientation.precessionNutationMatrix(t).transpose() * apparent.toVector()
        return EquatorialCoord.fromVector(SolarSystem.removeAberration(mean, EarthState.velocity(t)))
    }

    /**
     * Aparente geocéntrica de la fecha → horizontal topocéntrica.
     * @param distanceKm distancia del objeto para la paralaje (null = estrella, sin paralaje).
     * @param refraction si true devuelve la altura aparente refractada (la que debe apuntar el tubo).
     */
    fun apparentToHorizontal(
        apparent: EquatorialCoord,
        time: AstroTime,
        location: GeoLocation,
        weather: Weather = Weather(),
        distanceKm: Double? = null,
        refraction: Boolean = true,
    ): HorizontalCoord {
        val last = EarthOrientation.last(time, location.longitudeDeg)
        val topo = if (distanceKm != null && distanceKm < 1e9) {
            CoordinateTransforms.geocentricToTopocentric(apparent, distanceKm, last, location)
        } else apparent
        val geometric = CoordinateTransforms.equatorialToHorizontal(topo, last, location.latRad)
        return if (refraction) Refraction.trueToApparent(geometric, weather) else geometric
    }

    /** Horizontal aparente (refractada) → ecuatorial aparente de la fecha (topocéntrica). */
    fun horizontalToApparent(
        horizontal: HorizontalCoord,
        time: AstroTime,
        location: GeoLocation,
        weather: Weather = Weather(),
        refraction: Boolean = true,
    ): EquatorialCoord {
        val last = EarthOrientation.last(time, location.longitudeDeg)
        val geometric = if (refraction) Refraction.apparentToTrue(horizontal, weather) else horizontal
        return CoordinateTransforms.horizontalToEquatorial(geometric, last, location.latRad)
    }

    /** Atajo: J2000 → horizontal aparente. */
    fun j2000ToHorizontal(
        j2000: EquatorialCoord, time: AstroTime, location: GeoLocation, weather: Weather = Weather(),
    ): HorizontalCoord = apparentToHorizontal(j2000ToApparent(j2000, time), time, location, weather)
}
