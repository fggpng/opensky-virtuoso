package com.opensky.virtuoso.database.targets

import com.opensky.virtuoso.astronomy.EquatorialCoord

/**
 * Objeto de cielo profundo (galaxia, cúmulo, nebulosa…). Coordenadas J2000.
 *
 * Datos derivados de OpenNGC (https://github.com/mattiaverga/OpenNGC), © Mattia Verga y
 * colaboradores, licencia CC-BY-SA-4.0. Se generan con tools/deepsky/build_deepsky.py.
 */
data class DeepSkyObject(
    /** Identificador de OpenNGC: "NGC0224", "IC0434", "Mel022"… */
    val key: String,
    val messier: Int?,
    /** Designación legible: "NGC 224", "IC 434", "Mel 22", "Caldwell 9"… */
    val designation: String,
    val typeCode: String,
    val typeLabel: String,
    val j2000: EquatorialCoord,
    val magnitude: Double?,
    val majorAxisArcmin: Double?,
    val minorAxisArcmin: Double?,
    val constellation: String,
    /** Nombres comunes: primero el español (si existe) y luego los de OpenNGC en inglés. */
    val names: List<String>,
) {
    val messierLabel: String? get() = messier?.let { "M$it" }

    /** Nombre principal: "M31 · Galaxia de Andrómeda", "NGC 7000 · Nebulosa Norteamérica", "NGC 891". */
    val displayName: String
        get() {
            val id = messierLabel ?: designation
            return names.firstOrNull()?.let { "$id · $it" } ?: id
        }

    val sizeLabel: String?
        get() {
            val a = majorAxisArcmin ?: return null
            val b = minorAxisArcmin
            return if (b != null && b > 0 && b != a) "${fmt(a)}′×${fmt(b)}′" else "${fmt(a)}′"
        }

    /** Claves de búsqueda ya normalizadas (se calculan una vez). */
    internal val searchKeys: List<String> by lazy {
        buildList {
            messier?.let { add("m$it"); add("m $it"); add("messier $it") }
            val d = TargetCatalog.normalize(designation)
            add(d)
            add(d.replace(" ", ""))
            names.forEach { add(TargetCatalog.normalize(it)) }
        }
    }

    private fun fmt(v: Double) = if (v >= 10) "%.0f".format(v) else "%.1f".format(v)
}

data class DeepSkyTarget(val obj: DeepSkyObject) : Target {
    override val id = "dso:${obj.key}"
    override val name = obj.displayName
    override val subtitle = buildList {
        if (obj.messier != null) add(obj.designation)
        add(obj.typeLabel)
        if (obj.constellation.isNotBlank()) add(obj.constellation)
        obj.sizeLabel?.let { add(it) }
    }.joinToString(" · ")
    override val category = TargetCategory.DEEP_SKY
    override val magnitude: Double? = obj.magnitude
}

/** Catálogo integrado (recurso catalogs/deepsky.tsv), cargado una sola vez y bajo demanda. */
object DeepSkyCatalog {

    /** Magnitud límite de la lista por defecto (los Messier siempre aparecen). */
    const val LIST_MAG_LIMIT = 10.0

    val all: List<DeepSkyObject> by lazy { load() }

    private val byMessier: Map<Int, DeepSkyObject> by lazy { all.filter { it.messier != null }.associateBy { it.messier!! } }
    private val byDesignation: Map<String, DeepSkyObject> by lazy {
        all.associateBy { TargetCatalog.normalize(it.designation).replace(" ", "") }
    }

    /** Lista navegable: los 110 Messier y los demás objetos de magnitud ≤ [LIST_MAG_LIMIT]. */
    val listed: List<DeepSkyObject> by lazy {
        all.filter { it.messier != null || (it.magnitude ?: 99.0) <= LIST_MAG_LIMIT }
    }

    fun messier(n: Int): DeepSkyObject? = byMessier[n]

    /** Búsqueda exacta por designación: "m31", "m 31", "messier 31", "ngc 224", "ngc0224", "ic434", "caldwell 9". */
    fun findByDesignation(normalizedQuery: String): DeepSkyObject? {
        MESSIER.matchEntire(normalizedQuery)?.let { return messier(it.groupValues[2].toInt()) }
        CATALOG.matchEntire(normalizedQuery)?.let { m ->
            val (prefix, number, suffix) = m.destructured
            return byDesignation["$prefix${number.trimStart('0').ifEmpty { "0" }}$suffix"]
        }
        return null
    }

    fun search(normalizedQuery: String): List<Pair<Int, DeepSkyObject>> = all.mapNotNull { o ->
        val score = o.searchKeys.minOfOrNull { k ->
            when {
                k == normalizedQuery -> 0
                k.startsWith(normalizedQuery) -> 1
                k.contains(normalizedQuery) -> 2
                else -> 99
            }
        } ?: 99
        if (score < 99) score to o else null
    }

    private val MESSIER = Regex("""(m|messier)\s*(\d{1,3})""")
    private val CATALOG = Regex("""(ngc|ic|mel|caldwell|barnard)\s*(\d{1,4})\s*([a-z]?)""")

    private fun load(): List<DeepSkyObject> {
        val stream = DeepSkyCatalog::class.java.getResourceAsStream("/catalogs/deepsky.tsv")
            ?: error("Falta el recurso catalogs/deepsky.tsv")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val f = line.split('\t')
                DeepSkyObject(
                    key = f[0],
                    messier = f[1].toIntOrNull(),
                    designation = f[2],
                    typeCode = f[3],
                    typeLabel = f[4],
                    j2000 = EquatorialCoord.fromDegrees(f[5].toDouble(), f[6].toDouble()),
                    magnitude = f[7].toDoubleOrNull(),
                    majorAxisArcmin = f[8].toDoubleOrNull(),
                    minorAxisArcmin = f[9].toDoubleOrNull(),
                    constellation = f[10],
                    names = f.getOrNull(11)?.split('|')?.filter { it.isNotBlank() }.orEmpty(),
                )
            }.toList()
        }
    }
}
