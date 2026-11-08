package com.opensky.virtuoso.alignment

import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.Weather
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.database.targets.BuiltInStars
import com.opensky.virtuoso.database.targets.StarTarget
import com.opensky.virtuoso.database.targets.TargetCatalog
import com.opensky.virtuoso.database.targets.TargetResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.exp

/** Modos de alineación (nombres equivalentes a los de SynScan). */
enum class AlignmentMode(val label: String, val starsRequired: Int, val description: String) {
    ONE_STAR("1-Star Alignment", 1, "Centra una estrella elegida de la lista. Supone el trípode nivelado."),
    BRIGHTEST_ONE("Brightest Star (1 estrella)", 1, "Apunta a mano a la estrella más brillante de una zona; la app la identifica."),
    BRIGHTEST_TWO("Brightest Star (2 estrellas)", 2, "Como la anterior pero con dos estrellas: corrige la inclinación del trípode."),
    TWO_STAR("2-Star Alignment", 2, "Dos estrellas elegidas: rotación completa (trípode no nivelado) + índice de altura."),
    THREE_STAR("3-Star Alignment", 3, "Añade el error de cono y permite estimar el error residual."),
    SYNMATRIX_AUTO("SynMatrix AutoAlign", 4, "La app elige 4 estrellas bien repartidas, hace GoTo a cada una y reajusta tras cada centrado."),
}

data class AlignmentState(
    val mode: AlignmentMode? = null,
    val alignmentSamples: List<AlignmentSample> = emptyList(),
    val syncSamples: List<AlignmentSample> = emptyList(),
    val fit: FitResult = AlignmentSolver.fit(emptyList()),
) {
    val allSamples: List<AlignmentSample> get() = alignmentSamples + syncSamples
    val isComplete: Boolean get() = mode != null && alignmentSamples.size >= mode.starsRequired
    val starsRemaining: Int get() = mode?.let { (it.starsRequired - alignmentSamples.size).coerceAtLeast(0) } ?: 0
    val model: PointingModel get() = fit.model
    val quality: AlignmentQuality get() = fit.quality
}

/**
 * Gestor de alineación: acumula estrellas de alineación y "Sync Samples", reajusta el modelo
 * global y aplica una corrección local ponderada por distancia (estilo PAE) cerca de cada muestra.
 */
class AlignmentManager(referenceFieldRadiusArcmin: Double = 52.0) {

    @Volatile var referenceFieldRadiusArcmin: Double = referenceFieldRadiusArcmin
        set(value) { field = value; refit(_state.value) }

    private val _state = MutableStateFlow(AlignmentState())
    val state: StateFlow<AlignmentState> = _state.asStateFlow()

    val model: PointingModel get() = _state.value.model

    /** Inicia un modo de alineación (descarta muestras anteriores). */
    fun begin(mode: AlignmentMode) {
        _state.value = AlignmentState(mode = mode)
    }

    /** Reset Alignment: vuelve al modelo identidad (orientación inicial por IMU/encoders). */
    fun reset() {
        _state.value = AlignmentState()
    }

    fun addAlignmentStar(sample: AlignmentSample): AlignmentState {
        val s = _state.value
        return refit(s.copy(alignmentSamples = s.alignmentSamples + sample))
    }

    fun addSyncSample(sample: AlignmentSample): AlignmentState {
        val s = _state.value
        return refit(s.copy(syncSamples = s.syncSamples + sample))
    }

    fun removeSample(sample: AlignmentSample): AlignmentState {
        val s = _state.value
        return refit(s.copy(alignmentSamples = s.alignmentSamples - sample, syncSamples = s.syncSamples - sample))
    }

    private fun refit(s: AlignmentState): AlignmentState {
        val fit = AlignmentSolver.fit(s.allSamples, referenceFieldRadiusArcmin)
        return s.copy(fit = fit).also { _state.value = it }
    }

    /** Cielo → encoders con el modelo global + corrección local de las muestras cercanas. */
    fun skyToMount(sky: HorizontalCoord): Pair<Double, Double>? {
        val base = model.skyToMount(sky) ?: return null
        val (dAz, dAlt) = localCorrection(sky)
        return Angles.normPi(base.first + dAz) to base.second + dAlt
    }

    /** Encoders → cielo (inversa aproximada de [skyToMount]). */
    fun mountToSky(azEncoder: Double, altEncoder: Double): HorizontalCoord {
        val first = model.mountToSky(azEncoder, altEncoder)
        val (dAz, dAlt) = localCorrection(first)
        return model.mountToSky(azEncoder - dAz, altEncoder - dAlt)
    }

    private fun localCorrection(sky: HorizontalCoord): Pair<Double, Double> {
        val samples = _state.value.allSamples
        if (samples.size < 3) return 0.0 to 0.0 // con ≤2 muestras el ajuste es exacto
        val m = model
        var wSum = LOCAL_W0
        var az = 0.0
        var alt = 0.0
        for (s in samples) {
            val d = sky.separation(s.sky)
            if (d > LOCAL_MAX_DISTANCE) continue
            val pred = m.skyToMount(s.sky) ?: continue
            val w = exp(-(d / LOCAL_SIGMA) * (d / LOCAL_SIGMA))
            az += w * Angles.normPi(s.azEncoder - pred.first)
            alt += w * (s.altEncoder - pred.second)
            wSum += w
        }
        return az / wSum to alt / wSum
    }

    /**
     * Sugiere estrellas brillantes de alineación bien repartidas en acimut, entre 20° y 75° de altura.
     */
    fun suggestStars(
        catalog: TargetCatalog, time: AstroTime, location: GeoLocation, weather: Weather = Weather(),
        count: Int = 3, maxMagnitude: Double = 2.6, exclude: Set<String> = emptySet(),
        minAltDeg: Double = 20.0, maxAltDeg: Double = 75.0,
    ): List<Pair<StarTarget, HorizontalCoord>> {
        val usedSky = _state.value.alignmentSamples.map { it.sky }
        val candidates = BuiltInStars.namedStars.asSequence()
            .filter { it.vMag <= maxMagnitude }
            .map { catalog.namedStarTarget(it) }
            .filter { it.name !in exclude && it.id !in exclude }
            .map { it to TargetResolver.position(it, time, location, weather).horizontal }
            .filter { (_, hz) -> hz.altDeg in minAltDeg..maxAltDeg }
            .filter { (_, hz) -> usedSky.none { it.separation(hz) < 20 * DEG } }
            .toMutableList()
        val chosen = mutableListOf<Pair<StarTarget, HorizontalCoord>>()
        while (chosen.size < count && candidates.isNotEmpty()) {
            val reference = usedSky + chosen.map { it.second }
            val next = if (reference.isEmpty()) candidates.minByOrNull { it.first.magnitude ?: 9.0 }!!
            else candidates.maxByOrNull { (star, hz) ->
                val minSep = reference.minOf { it.separation(hz) } / DEG
                minSep.coerceAtMost(90.0) - 6.0 * (star.magnitude ?: 3.0) // reparto + brillo
            }!!
            chosen += next
            candidates.remove(next)
        }
        return chosen
    }

    /**
     * "Brightest Star": identifica la estrella brillante (V ≤ [maxMagnitude]) más probable cerca
     * de donde apunta el tubo, combinando cercanía y brillo.
     */
    fun identifyBrightest(
        pointing: HorizontalCoord, catalog: TargetCatalog, time: AstroTime, location: GeoLocation,
        weather: Weather = Weather(), maxSeparationDeg: Double = 25.0, maxMagnitude: Double = 2.2,
    ): Pair<StarTarget, Double>? = BuiltInStars.namedStars.asSequence()
        .filter { it.vMag <= maxMagnitude }
        .map { catalog.namedStarTarget(it) }
        .map { it to TargetResolver.position(it, time, location, weather).horizontal }
        .filter { (_, hz) -> hz.altDeg > 5.0 }
        .map { (star, hz) -> Triple(star, hz.separation(pointing) / DEG, star.magnitude ?: 3.0) }
        .filter { it.second <= maxSeparationDeg }
        .minByOrNull { (_, sep, mag) -> sep + 4.0 * mag }
        ?.let { it.first to it.second }

    companion object {
        private const val LOCAL_SIGMA = 20.0 * DEG
        private const val LOCAL_MAX_DISTANCE = 45.0 * DEG
        private const val LOCAL_W0 = 0.05
    }
}
