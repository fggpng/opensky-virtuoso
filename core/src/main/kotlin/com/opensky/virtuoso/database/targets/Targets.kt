package com.opensky.virtuoso.database.targets

import com.opensky.virtuoso.astronomy.AstroEngine
import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.Weather
import com.opensky.virtuoso.astronomy.ephemeris.SmallBodyElements
import com.opensky.virtuoso.astronomy.ephemeris.SmallBodyType
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystem
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystemBody
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.database.tycho2.Tycho2Catalog
import com.opensky.virtuoso.database.tycho2.Tycho2Star
import java.text.Normalizer

enum class TargetCategory(val label: String) {
    SOLAR_SYSTEM("Sistema Solar"),
    DEEP_SKY("Cielo profundo"),
    NAMED_STAR("Estrellas con nombre"),
    DOUBLE_STAR("Estrellas dobles"),
    SMALL_BODY("Cometas y asteroides"),
    USER_OBJECT("Objetos de usuario"),
    CATALOG_STAR("Catálogo Tycho-2"),
}

/** Objeto definido por el usuario (coordenadas J2000). */
data class UserObject(
    val id: String,
    val name: String,
    val raHours: Double,
    val decDeg: Double,
    val magnitude: Double? = null,
    val notes: String = "",
) {
    val j2000: EquatorialCoord get() = EquatorialCoord.fromHoursDegrees(raHours, decDeg)
}

/** Objetivo seleccionable para GoTo. */
sealed interface Target {
    val id: String
    val name: String
    val subtitle: String
    val category: TargetCategory
    val magnitude: Double?
    val isSun: Boolean get() = this is SolarSystemTarget && body == SolarSystemBody.SUN
}

data class SolarSystemTarget(val body: SolarSystemBody) : Target {
    override val id = "ss:${body.name}"
    override val name = body.displayName
    override val subtitle = if (body == SolarSystemBody.SUN) "¡Solo con filtro solar certificado!" else "Sistema Solar"
    override val category = TargetCategory.SOLAR_SYSTEM
    override val magnitude: Double? = null
}

data class StarTarget(
    override val id: String,
    override val name: String,
    override val subtitle: String,
    val j2000: EquatorialCoord,
    val pmRaMasYr: Double,
    val pmDecMasYr: Double,
    override val magnitude: Double?,
    override val category: TargetCategory,
    val hip: Int? = null,
) : Target

data class DoubleStarTarget(val star: DoubleStar) : Target {
    override val id = "dbl:${star.designation}"
    override val name = star.name
    override val subtitle = "${star.designation} · ${"%.1f".format(star.magA)}/${"%.1f".format(star.magB)} · ${"%.1f".format(star.separationArcsec)}″"
    override val category = TargetCategory.DOUBLE_STAR
    override val magnitude: Double? = star.magA
}

data class SmallBodyTarget(val elements: SmallBodyElements) : Target {
    override val id = "sb:${elements.designation}"
    override val name = elements.displayName
    override val subtitle = if (elements.type == SmallBodyType.COMET) "Cometa · q=%.2f UA".format(elements.q) else "Asteroide"
    override val category = TargetCategory.SMALL_BODY
    override val magnitude: Double? = null
}

data class UserTarget(val obj: UserObject) : Target {
    override val id = "user:${obj.id}"
    override val name = obj.name
    override val subtitle = "AR ${Angles.formatHms(obj.raHours, 0)} · Dec ${Angles.formatDms(obj.decDeg)}"
    override val category = TargetCategory.USER_OBJECT
    override val magnitude = obj.magnitude
}

/** Posición calculada de un objetivo para un instante y lugar. */
data class TargetPosition(
    /** Aparente geocéntrica de la fecha. */
    val apparent: EquatorialCoord,
    /** Horizontal topocéntrica aparente (con refracción): lo que debe apuntar el tubo. */
    val horizontal: HorizontalCoord,
    val distanceKm: Double?,
    val magnitude: Double?,
    val illuminatedFraction: Double?,
) {
    val isAboveHorizon: Boolean get() = horizontal.alt > 0
}

object TargetResolver {

    fun position(target: Target, time: AstroTime, location: GeoLocation, weather: Weather = Weather()): TargetPosition {
        val (apparent, distanceKm, mag, illum) = when (target) {
            is SolarSystemTarget -> SolarSystem.position(target.body, time).let { Quad(it.apparent, it.distanceKm, it.magnitude, it.illuminatedFraction) }
            is SmallBodyTarget -> SolarSystem.smallBodyPosition(target.elements, time).let { Quad(it.apparent, it.distanceKm, it.magnitude, null) }
            is StarTarget -> Quad(AstroEngine.starApparent(target.j2000, time, target.pmRaMasYr, target.pmDecMasYr), null, target.magnitude, null)
            is DoubleStarTarget -> Quad(AstroEngine.starApparent(target.star.j2000, time, target.star.pmRaMasYr, target.star.pmDecMasYr), null, target.magnitude, null)
            is UserTarget -> Quad(AstroEngine.j2000ToApparent(target.obj.j2000, time), null, target.magnitude, null)
            is DeepSkyTarget -> Quad(AstroEngine.j2000ToApparent(target.obj.j2000, time), null, target.magnitude, null)
        }
        val hz = AstroEngine.apparentToHorizontal(apparent, time, location, weather, distanceKm)
        return TargetPosition(apparent, hz, distanceKm, mag, illum)
    }

    private data class Quad(val eq: EquatorialCoord, val km: Double?, val mag: Double?, val illum: Double?)
}

/**
 * Catálogo de objetivos agrupado en categorías con búsqueda por nombre o designación
 * ("Vega", "alpha lyr", "HIP 91262", "TYC 3105-2070-1", "C/2023 A3", "Marte"/"Mars").
 */
class TargetCatalog(
    private val tycho2: () -> Tycho2Catalog? = { null },
    private val smallBodies: () -> List<SmallBodyElements> = { emptyList() },
    private val userObjects: () -> List<UserObject> = { emptyList() },
) {

    fun list(category: TargetCategory): List<Target> = when (category) {
        TargetCategory.SOLAR_SYSTEM -> SolarSystemBody.entries.map { SolarSystemTarget(it) }
        TargetCategory.DEEP_SKY -> DeepSkyCatalog.listed.map { DeepSkyTarget(it) }
        TargetCategory.NAMED_STAR -> BuiltInStars.namedStars.map { namedStarTarget(it) }
        TargetCategory.DOUBLE_STAR -> BuiltInStars.doubleStars.map { DoubleStarTarget(it) }
        TargetCategory.SMALL_BODY -> smallBodies().map { SmallBodyTarget(it) }
        TargetCategory.USER_OBJECT -> userObjects().map { UserTarget(it) }
        TargetCategory.CATALOG_STAR -> emptyList() // demasiado grande para listar: usar búsqueda/consultas
    }

    /** Estrella con nombre, refinada con Tycho-2 (HIP) si está disponible. */
    fun namedStarTarget(s: NamedStar): StarTarget {
        val refined = runCatching { tycho2()?.findByHip(s.hip) }.getOrNull()
        val (coord, pmA, pmD) = if (refined != null && refined.j2000.separation(s.j2000) < 0.01) {
            Triple(refined.j2000, refined.pmRaMasYr, refined.pmDecMasYr)
        } else Triple(s.j2000, s.pmRaMasYr, s.pmDecMasYr)
        return StarTarget("hip:${s.hip}", s.name, "${s.bayer} · HIP ${s.hip}", coord, pmA, pmD, s.vMag, TargetCategory.NAMED_STAR, s.hip)
    }

    fun tychoTarget(star: Tycho2Star, hip: Int? = null): StarTarget = StarTarget(
        id = "tyc:${star.tyc1}-${star.tyc2}-${star.tyc3}", name = star.designation,
        subtitle = "V %.2f%s".format(star.vMag, star.bv?.let { " · B−V %.2f".format(it) } ?: ""),
        j2000 = star.j2000, pmRaMasYr = star.pmRaMasYr, pmDecMasYr = star.pmDecMasYr,
        magnitude = star.vMag, category = TargetCategory.CATALOG_STAR, hip = hip,
    )

    fun search(query: String, limit: Int = 60): List<Target> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        HIP_PATTERN.matchEntire(q)?.let { m ->
            val hip = m.groupValues[1].toInt()
            BuiltInStars.namedStars.firstOrNull { it.hip == hip }?.let { return listOf(namedStarTarget(it)) }
            return listOfNotNull(tycho2()?.findByHip(hip)?.let { tychoTarget(it, hip) })
        }
        TYC_PATTERN.matchEntire(q)?.let { m ->
            val (a, b, c) = m.destructured
            return listOfNotNull(tycho2()?.findByTyc(a.toInt(), b.toInt(), c.toInt())?.let { tychoTarget(it) })
        }
        // «M31», «NGC 7000», «IC434»: coincidencia exacta del catálogo de cielo profundo.
        DeepSkyCatalog.findByDesignation(q)?.let { return listOf(DeepSkyTarget(it)) }
        val candidates = buildList {
            addAll(list(TargetCategory.SOLAR_SYSTEM))
            addAll(list(TargetCategory.NAMED_STAR))
            addAll(list(TargetCategory.DOUBLE_STAR))
            addAll(list(TargetCategory.SMALL_BODY))
            addAll(list(TargetCategory.USER_OBJECT))
        }
        val scored = candidates.mapNotNull { t ->
            val keys = searchKeys(t)
            val score = keys.minOfOrNull { k ->
                when {
                    k == q -> 0
                    k.startsWith(q) -> 1
                    k.contains(q) -> 2
                    else -> 99
                }
            } ?: 99
            if (score < 99) score to t else null
        } + DeepSkyCatalog.search(q).map { (score, o) -> score to DeepSkyTarget(o) }
        return scored.sortedWith(compareBy({ it.first }, { it.second.magnitude ?: 99.0 })).take(limit).map { it.second }
    }

    private fun searchKeys(t: Target): List<String> = buildList {
        add(normalize(t.name))
        add(normalize(t.subtitle))
        when (t) {
            is SolarSystemTarget -> add(t.body.name.lowercase())
            is StarTarget -> BuiltInStars.namedStars.firstOrNull { it.hip == t.hip }?.let { add(normalize(it.bayer)) }
            is DoubleStarTarget -> add(normalize(t.star.designation))
            is SmallBodyTarget -> { add(normalize(t.elements.designation)); add(normalize(t.elements.name)) }
            is UserTarget -> Unit
            is DeepSkyTarget -> addAll(t.obj.searchKeys)
        }
    }

    companion object {
        private val HIP_PATTERN = Regex("hip\\s*(\\d{1,6})")
        private val TYC_PATTERN = Regex("tyc\\s*(\\d{1,4})[-\\s](\\d{1,5})[-\\s](\\d)")

        private val GREEK = mapOf(
            'α' to "alpha", 'β' to "beta", 'γ' to "gamma", 'δ' to "delta", 'ε' to "epsilon", 'ζ' to "zeta",
            'η' to "eta", 'θ' to "theta", 'ι' to "iota", 'κ' to "kappa", 'λ' to "lambda", 'μ' to "mu",
            'ν' to "nu", 'ξ' to "xi", 'ο' to "omicron", 'π' to "pi", 'ρ' to "rho", 'σ' to "sigma",
            'τ' to "tau", 'υ' to "upsilon", 'φ' to "phi", 'χ' to "chi", 'ψ' to "psi", 'ω' to "omega",
        )

        /** Minúsculas, letras griegas transliteradas y sin diacríticos. */
        fun normalize(text: String): String {
            val sb = StringBuilder()
            for (ch in text.lowercase()) {
                val g = GREEK[ch]
                if (g != null) sb.append(g) else sb.append(ch)
            }
            return Normalizer.normalize(sb.toString(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace('¹', '1').replace('²', '2')
                .replace(Regex("\\s+"), " ").trim()
        }

    }
}
