package com.opensky.virtuoso.app

import com.opensky.virtuoso.alignment.AlignmentMode
import com.opensky.virtuoso.alignment.AlignmentState
import com.opensky.virtuoso.alignment.MoonAlignment
import com.opensky.virtuoso.alignment.MoonReference
import com.opensky.virtuoso.astronomy.AstroEngine
import com.opensky.virtuoso.astronomy.EarthOrientation
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.ephemeris.SmallBodyElements
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.astronomy.math.HOUR
import com.opensky.virtuoso.astronomy.math.RAD
import com.opensky.virtuoso.control.Diagnostics
import com.opensky.virtuoso.control.Equipment
import com.opensky.virtuoso.control.Eyepiece
import com.opensky.virtuoso.control.FieldRotation
import com.opensky.virtuoso.control.GotoResult
import com.opensky.virtuoso.control.HeadingReference
import com.opensky.virtuoso.control.ManualHeading
import com.opensky.virtuoso.control.ManualStartPreview
import com.opensky.virtuoso.control.MountController
import com.opensky.virtuoso.control.MountState
import com.opensky.virtuoso.control.ObservingMode
import com.opensky.virtuoso.control.PAD_RATES
import com.opensky.virtuoso.control.PadDirection
import com.opensky.virtuoso.control.PadMode
import com.opensky.virtuoso.control.StartPosition
import com.opensky.virtuoso.control.TrackingRate
import com.opensky.virtuoso.data.AppSettings
import com.opensky.virtuoso.data.SettingsCodec
import com.opensky.virtuoso.database.targets.StarTarget
import com.opensky.virtuoso.database.targets.Target
import com.opensky.virtuoso.database.targets.TargetCategory
import com.opensky.virtuoso.database.targets.TargetResolver
import com.opensky.virtuoso.database.targets.UserObject
import com.opensky.virtuoso.location.LocationFix
import com.opensky.virtuoso.location.TimeStatus
import com.opensky.virtuoso.network.web.SbdbResult
import com.opensky.virtuoso.platform.CivilDateTime
import com.opensky.virtuoso.platform.epochMillisNow
import com.opensky.virtuoso.platform.fmt
import com.opensky.virtuoso.platform.monotonicNanos
import com.opensky.virtuoso.platform.systemTimeZoneId
import com.opensky.virtuoso.platform.systemUtcOffsetSeconds
import com.opensky.virtuoso.platform.utcOffsetId
import com.opensky.virtuoso.sensors.PhoneMounting
import com.opensky.virtuoso.sensors.TubeOrientation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * Modelos de pantalla compartidos (versión común de los ViewModel de Android). Cada uno expone un
 * estado inmutable por pantalla ([StateWatcher]) y acciones sin valor de retorno: la UI nativa
 * (SwiftUI) solo dibuja el estado y llama a las acciones.
 */

/** Base: estado de la montura, ajustes y mensajes para el usuario. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ScreenModel internal constructor(protected val g: AppGraph) {
    protected val scope: CoroutineScope get() = g.scope

    val mount: StateFlow<MountState?> = g.session.controller
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(g.scope, SharingStarted.Eagerly, null)

    protected val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun clearMessage() { _message.value = null }

    protected val controller: MountController? get() = g.session.controller.value

    protected fun requireController(): MountController? =
        controller ?: run { _message.value = "Conecta primero la montura (pestaña Inicio)"; null }

    protected fun describe(result: GotoResult): String = when (result) {
        is GotoResult.Success -> "GoTo completado"
        is GotoResult.Rejected -> result.reason
        is GotoResult.Failed -> "Error: ${result.error}"
    }
}

// ------------------------------------------------------------------------------------- Inicio

/** Relojes de Inicio. (`zoneLabel` y no `zone`: en Swift `zone` es un método de NSObject.) */
data class ClockInfo(val utc: String, val local: String, val lst: String, val zoneLabel: String)

data class HomeState(
    val session: SessionState,
    val mount: MountState?,
    val settings: AppSettings,
    val location: LocationFix?,
    val time: TimeStatus,
    val clock: ClockInfo?,
    val message: String?,
    val alignmentSamples: Int,
    val checking: Boolean,
    val locationKnown: Boolean,
    val manualPreview: ManualStartPreview?,
    val manualError: String?,
    val eyepieces: List<Eyepiece>,
    val observingModes: List<ObservingMode>,
    val headingReferences: List<HeadingReference>,
    val connectionModes: List<ConnectionMode>,
    /** «Super MA 25 mm · 30× · campo 104′ (1.73°)» o el campo de la cámara. */
    val opticalSummary: String,
)

class HomeModel internal constructor(g: AppGraph) : ScreenModel(g) {
    private val checking = MutableStateFlow(false)
    private val manualPreview = MutableStateFlow<ManualStartPreview?>(null)
    private val manualError = MutableStateFlow<String?>(null)
    private val clockFlow = MutableStateFlow<ClockInfo?>(null)
    private val locationKnown = MutableStateFlow(false)

    init {
        scope.launch {
            ticker(1000).collect {
                clockFlow.value = clockNow()
                locationKnown.value = g.session.siteKnown()
            }
        }
    }

    val stateFlow: StateFlow<HomeState> = scope.derived(
        g.session.state, mount, g.settings.settings, g.location.location, g.time.time, clockFlow, _message,
        g.alignment.state, checking, locationKnown, manualPreview, manualError,
    ) {
        HomeState(
            session = g.session.state.value, mount = mount.value, settings = g.settings.current,
            location = g.location.location.value, time = g.time.time.value, clock = clockFlow.value,
            message = _message.value, alignmentSamples = g.alignment.state.value.allSamples.size,
            checking = checking.value, locationKnown = locationKnown.value,
            manualPreview = manualPreview.value, manualError = manualError.value, eyepieces = Equipment.EYEPIECES,
            observingModes = ObservingMode.entries, headingReferences = HeadingReference.entries, connectionModes = ConnectionMode.entries,
            opticalSummary = g.settings.current.opticalSetup().summary,
        )
    }
    val state = StateWatcher(stateFlow)

    /** Cambia de configuración y ajusta la velocidad del pad a la recomendada para ella. */
    fun setObservingMode(mode: ObservingMode) = g.settings.update { s ->
        if (s.observingMode == mode) s else s.copy(observingMode = mode).let { n -> n.copy(padRateIndex = n.opticalSetup().defaultPadRateIndex) }
    }

    fun setEyepiece(id: String) = g.settings.update { it.copy(eyepieceId = id) }

    fun clearAlert() { controller?.clearAlert() }

    /** Poka-yoke: «el tubo está horizontal y apunta al norte», en el sitio de esta sesión. */
    fun confirmHome() {
        scope.launch {
            val ctrl = requireController() ?: return@launch
            runCatching {
                ctrl.confirmHomePosition()
                g.alignment.reset()
            }.onSuccess { _message.value = "Posición de inicio confirmada (horizontal, norte). Ahora alinea con estrellas." }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    /** Apoya el teléfono sobre el tubo: compara su altura (gravedad) con la que cree la app. */
    fun checkAltitudeWithPhone() {
        scope.launch {
            val ctrl = requireController() ?: return@launch
            checking.value = true
            try {
                val o = g.measureOrientation(2000)
                if (o == null) { _message.value = "Sin lecturas del sensor del teléfono"; return@launch }
                val r = ctrl.checkAltitude(o.altitudeDeg)
                _message.value = if (r.ok) "✓ Altura correcta: la app cree %.1f°, el teléfono mide %.1f°".fmt(r.believedDeg, r.measuredDeg)
                else "✗ No coincide: la app cree %.1f° y el teléfono mide %.1f°. Confirma de nuevo la posición de inicio antes de cualquier GoTo."
                    .fmt(r.believedDeg, r.measuredDeg)
            } catch (e: Exception) {
                _message.value = "Error: ${e.message}"
            } finally {
                checking.value = false
            }
        }
    }

    /** Declinación magnética del sitio, o null sin ubicación (o si iOS aún no la conoce). */
    fun declinationHere(): Double? = if (!g.session.siteKnown()) null else g.platform.device.magneticDeclinationDeg()

    /**
     * Valida lo escrito y arma el resumen. Si [measureAltitude], el teléfono está apoyado en el tubo:
     * da la altura (fiable) y su brújula sirve para detectar un número mal escrito.
     */
    fun prepareManualStart(azText: String, reference: HeadingReference, measureAltitude: Boolean) {
        scope.launch {
            val az = ManualHeading.parseAzimuth(azText)
            if (az == null) { manualError.value = "Escribe el rumbo como un número entre 0 y 360."; return@launch }
            val decl = declinationHere()
            val trueAz = ManualHeading.trueAzimuth(az, reference, decl)
            if (trueAz == null) {
                manualError.value = "Sin ubicación (o sin brújula calibrada) no se puede corregir la declinación magnética. " +
                    "Espera el GPS o lee el rumbo en una app de mapa del cielo («verdadero»)."
                return@launch
            }
            var alt = 0.0
            var phoneAz: Double? = null
            var interference = false
            if (measureAltitude) {
                checking.value = true
                try {
                    val o = g.measureOrientation(2000)
                    if (o == null) { manualError.value = "Sin lecturas del sensor del teléfono."; return@launch }
                    alt = o.altitudeDeg; phoneAz = o.trueAzimuthDeg; interference = o.interference
                } finally {
                    checking.value = false
                }
            }
            manualError.value = null
            manualPreview.value = ManualStartPreview(az, reference, decl, trueAz, alt, measureAltitude, phoneAz, interference)
        }
    }

    fun editManualStart() { manualPreview.value = null }

    fun cancelManualStart() { manualPreview.value = null; manualError.value = null }

    fun applyManualStart() {
        scope.launch {
            val p = manualPreview.value ?: return@launch
            manualPreview.value = null
            val ctrl = requireController() ?: return@launch
            runCatching {
                Diagnostics.log(p.logLine())
                ctrl.setInitialOrientation(p.trueAzDeg, p.altDeg, StartPosition.MANUAL)
                g.alignment.reset()
            }.onSuccess {
                _message.value = "Posición de inicio: tubo hacia el %s (Az %.1f°, Alt %.1f°). Ahora alinea con estrellas."
                    .fmt(ManualHeading.cardinalName(p.trueAzDeg), p.trueAzDeg, p.altDeg)
            }.onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    private fun clockNow(): ClockInfo {
        val s = g.settings.current
        val now = g.clock.now()
        val ms = now.epochMillis
        val offsetSec = if (s.useSystemTimeZone) systemUtcOffsetSeconds(ms) else ((s.manualUtcOffsetHours + if (s.dstEnabled) 1.0 else 0.0) * 3600).toInt()
        val utc = CivilDateTime.fromEpochMillis(ms)
        val local = CivilDateTime.fromEpochMillis(ms + offsetSec * 1000L)
        val lon = g.session.siteOrNull()?.location?.longitudeDeg
        return ClockInfo(
            utc = "%04d-%02d-%02d %02d:%02d:%02d UTC".fmt(utc.year, utc.month, utc.day, utc.hour, utc.minute, utc.second),
            local = "%02d:%02d:%02d".fmt(local.hour, local.minute, local.second),
            lst = lon?.let { Angles.formatHms(EarthOrientation.last(now, it) / HOUR, 1) } ?: "— (sin ubicación)",
            zoneLabel = if (s.useSystemTimeZone) systemTimeZoneId() else utcOffsetId(offsetSec),
        )
    }

    fun connect(mode: ConnectionMode) {
        scope.launch {
            if (g.session.siteOrNull() == null) g.platform.device.requestLocation()
            g.session.connect(mode)
                .onSuccess { info -> _message.value = "Conectado: ${info.modelName}, firmware ${info.firmwareVersion}" }
                .onFailure { _message.value = "No se pudo conectar: ${g.session.state.value.error ?: it.message}" }
        }
    }

    fun disconnect() {
        scope.launch { g.session.disconnect() }
    }

    fun syncTime() {
        scope.launch { _message.value = g.time.sync(g.settings.current.ntpServer).detail }
    }

    fun refreshLocation() {
        g.platform.device.requestLocation()
        _message.value = "Buscando ubicación…"
    }
}

// -------------------------------------------------------------------------------- Sensores

data class SensorState(
    val orientation: TubeOrientation?,
    val available: Boolean,
    val busy: Boolean,
    val mounting: PhoneMounting,
    val mount: MountState?,
    val message: String?,
    val mountings: List<PhoneMounting>,
)

class SensorModel internal constructor(g: AppGraph) : ScreenModel(g) {
    private val busy = MutableStateFlow(false)

    val stateFlow: StateFlow<SensorState> = scope.derived(g.orientation.orientation, busy, g.settings.settings, mount, _message) {
        SensorState(
            g.orientation.orientation.value, g.platform.device.motionAvailable, busy.value, g.settings.current.phoneMounting,
            mount.value, _message.value, PhoneMounting.entries,
        )
    }
    val state = StateWatcher(stateFlow)

    fun start() = g.startMotion()
    fun stop() = g.stopMotion()

    fun setMounting(m: PhoneMounting) = g.settings.update { it.copy(phoneMounting = m) }

    /** Promedia los sensores y fija los encoders: sustituye a «nivelar y apuntar al norte». */
    fun applyInitialOrientation() {
        scope.launch {
            val ctrl = requireController() ?: return@launch
            busy.value = true
            try {
                val o = g.measureOrientation(2500)
                if (o == null) { _message.value = "Sin lecturas del sensor de orientación"; return@launch }
                ctrl.setInitialOrientation(o.trueAzimuthDeg, o.altitudeDeg)
                g.alignment.reset()
                _message.value = "Encoders inicializados: Az %.1f°, Alt %.1f°%s".fmt(
                    o.trueAzimuthDeg, o.altitudeDeg,
                    if (o.interference) " (¡interferencia magnética: el acimut puede tener varios grados de error!)" else "",
                )
            } catch (e: Exception) {
                _message.value = "Error: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }
}

// -------------------------------------------------------------------------------- Alinear

data class SuggestedStar(val star: StarTarget, val horizontal: HorizontalCoord)
data class IdentifiedStar(val star: StarTarget, val separationDeg: Double)
/** Estrella que parece equivocada: la UI pide confirmar antes de registrarla. */
data class DoubtfulStar(val star: StarTarget, val deviationDeg: Double, val limitDeg: Double)

data class AlignmentScreenState(
    val alignment: AlignmentState,
    val suggestions: List<SuggestedStar>,
    val current: StarTarget?,
    val identified: IdentifiedStar?,
    val doubtful: DoubtfulStar?,
    val moon: MoonReference?,
    val busy: Boolean,
    val mount: MountState?,
    val message: String?,
    val modes: List<AlignmentMode>,
)

class AlignmentModel internal constructor(g: AppGraph) : ScreenModel(g) {
    private val suggestions = MutableStateFlow<List<SuggestedStar>>(emptyList())
    private val current = MutableStateFlow<StarTarget?>(null)
    private val identified = MutableStateFlow<IdentifiedStar?>(null)
    private val doubtful = MutableStateFlow<DoubtfulStar?>(null)
    private val moon = MutableStateFlow<MoonReference?>(null)
    private val busy = MutableStateFlow(false)

    private val alignment get() = g.alignment.state

    private val isBrightestMode get() = alignment.value.mode == AlignmentMode.BRIGHTEST_ONE || alignment.value.mode == AlignmentMode.BRIGHTEST_TWO

    init {
        // La Luna solo mientras toca centrarla; se recalcula cada 15 s (altura y avisos al día).
        scope.launch {
            ticker(15_000).collect { moon.value = if (alignment.value.isMoonStep) withContext(Dispatchers.Default) { moonNow() } else null }
        }
        scope.launch {
            var wasMoonStep = alignment.value.isMoonStep
            alignment.collect {
                if (it.isMoonStep != wasMoonStep) {
                    wasMoonStep = it.isMoonStep
                    moon.value = if (it.isMoonStep) moonNow() else null
                }
            }
        }
    }

    val stateFlow: StateFlow<AlignmentScreenState> = scope.derived(alignment, suggestions, current, identified, doubtful, moon, busy, mount, _message) {
        AlignmentScreenState(
            alignment.value, suggestions.value, current.value, identified.value, doubtful.value, moon.value, busy.value,
            mount.value, _message.value, AlignmentMode.entries,
        )
    }
    val state = StateWatcher(stateFlow)

    fun dismissDoubtful() { doubtful.value = null }

    private fun moonNow(): MoonReference {
        val site = g.session.siteOrNull() ?: return MoonReference.unavailable("Falta la ubicación (GPS o manual) para saber dónde está la Luna.")
        return MoonAlignment.reference(g.clock.now(), site.location, site.weather)
    }

    /** GoTo a la Luna (primer punto del modo «Luna + 1 estrella»). */
    fun gotoMoon() {
        scope.launch {
            val ctrl = requireController() ?: return@launch
            val ref = moonNow()
            moon.value = ref
            if (!ref.usable) { _message.value = ref.unavailableReason; return@launch }
            busy.value = true
            try {
                val r = ctrl.goto(MoonAlignment.target, trackAfter = true)
                _message.value = if (r is GotoResult.Success) {
                    "Si no ves la Luna en el ocular, búscala a simple vista y llévala al centro con el pad; luego pulsa «Luna centrada ✓»"
                } else describe(r)
            } finally { busy.value = false }
        }
    }

    fun begin(mode: AlignmentMode) {
        g.alignment.begin(mode)
        current.value = null; identified.value = null
        moon.value = if (g.alignment.state.value.isMoonStep) moonNow() else null
        scope.launch {
            refreshSuggestionsNow()
            if (mode == AlignmentMode.SYNMATRIX_AUTO) gotoNextSuggested()
        }
    }

    fun reset() {
        g.alignment.reset()
        current.value = null; identified.value = null; suggestions.value = emptyList()
        _message.value = "Alineación reiniciada: se usa la orientación inicial"
    }

    fun refreshSuggestions() {
        scope.launch { refreshSuggestionsNow() }
    }

    private suspend fun refreshSuggestionsNow() {
        val site = g.session.siteOrNull() ?: run { _message.value = "Falta la ubicación (GPS o manual)"; return }
        val remaining = alignment.value.starsRemaining.coerceAtLeast(1)
        suggestions.value = withContext(Dispatchers.Default) {
            g.alignment.suggestStars(
                g.targets, g.clock.now(), site.location, site.weather, count = remaining + 3,
                exclude = alignment.value.alignmentSamples.map { it.name }.toSet(),
            ).map { (star, h) -> SuggestedStar(star, h) }
        }
    }

    fun gotoStar(star: StarTarget) {
        scope.launch { gotoStarNow(star) }
    }

    private suspend fun gotoStarNow(star: StarTarget) {
        val ctrl = requireController() ?: return
        current.value = star
        busy.value = true
        try {
            val r = ctrl.goto(star, trackAfter = true)
            _message.value = if (r is GotoResult.Success) "Centra ${star.name} con el pad y pulsa «Estrella centrada ✓»" else describe(r)
        } finally { busy.value = false }
    }

    private suspend fun gotoNextSuggested() {
        refreshSuggestionsNow()
        suggestions.value.firstOrNull()?.let { gotoStarNow(it.star) }
            ?: run { _message.value = "No hay estrellas brillantes adecuadas sobre el horizonte" }
    }

    fun identifyBrightest() {
        scope.launch {
            val pointing = mount.value?.pointing ?: run { _message.value = "Sin posición de la montura"; return@launch }
            val site = g.session.siteOrNull() ?: return@launch
            identified.value = g.alignment.identifyBrightest(pointing, g.targets, g.clock.now(), site.location, site.weather)
                ?.let { (star, sep) -> IdentifiedStar(star, sep) }
            if (identified.value == null) _message.value = "No hay ninguna estrella brillante a menos de 25° de donde apunta el tubo"
        }
    }

    fun selectStar(star: StarTarget) { current.value = star }

    /**
     * El usuario confirma que la estrella está centrada. Poka-yoke: si el tubo está lejos de donde
     * el modelo esperaba esa estrella (estrella equivocada, el error más común), se pide confirmar
     * antes de registrarla; [force] = true tras la confirmación.
     */
    fun confirmCentered(force: Boolean) {
        scope.launch {
            val ctrl = requireController() ?: return@launch
            if (alignment.value.isMoonStep) {
                confirmMoon(ctrl)
                return@launch
            }
            val star = if (isBrightestMode) identified.value?.star else current.value
            if (star == null) { _message.value = if (isBrightestMode) "Primero pulsa «Identificar»" else "Elige una estrella"; return@launch }
            doubtful.value = null
            if (!force) {
                val limit = ctrl.syncDeviationLimitDeg()
                val dev = runCatching { ctrl.syncDeviationDeg(star) }.getOrNull()
                if (limit != null && dev != null && dev > limit) {
                    doubtful.value = DoubtfulStar(star, dev, limit)
                    return@launch
                }
            }
            busy.value = true
            try {
                val st = ctrl.sync(star, asAlignmentStar = true)
                identified.value = null; current.value = null
                val q = st.quality
                _message.value = if (st.isComplete) {
                    "Alineación completa. " + (q.rmsArcmin?.let { "Error RMS %.1f′ · precisión %.1f %%".fmt(it, q.precisionPercent ?: 0.0) } ?: q.method)
                } else "Estrella ${st.alignmentSamples.size} registrada. Faltan ${st.starsRemaining}."
                if (!st.isComplete && st.mode == AlignmentMode.SYNMATRIX_AUTO) gotoNextSuggested() else refreshSuggestionsNow()
            } catch (e: Exception) {
                _message.value = "Error al sincronizar: ${e.message}"
            } finally { busy.value = false }
        }
    }

    /** Primer punto del modo «Luna + 1 estrella»: el desvío solo se informa (es el error de arranque). */
    private suspend fun confirmMoon(ctrl: MountController) {
        val ref = moonNow()
        if (!ref.usable) { _message.value = ref.unavailableReason; return }
        busy.value = true
        try {
            val dev = runCatching { ctrl.syncDeviationDeg(MoonAlignment.target) }.getOrNull()
            ctrl.sync(MoonAlignment.target, asAlignmentStar = true)
            current.value = null
            _message.value = "Luna registrada" + (dev?.let { " (estaba a %.1f° de donde la esperaba la app)".fmt(it) } ?: "") +
                ". Ahora una estrella lejos de la Luna: elígela en la lista."
            refreshSuggestionsNow()
        } catch (e: Exception) {
            _message.value = "Error al sincronizar: ${e.message}"
        } finally { busy.value = false }
    }
}

// ------------------------------------------------------------------------------- Objetivos

data class TargetRow(
    val target: Target,
    val altDeg: Double,
    val azDeg: Double,
    val magnitude: Double?,
    /** Exposición máxima por rotación de campo (s, 1 px en las esquinas); null con ocular. */
    val maxExposureSec: Double? = null,
) {
    val visible: Boolean get() = altDeg > 0
    val maxExposureLabel: String? get() = maxExposureSec?.let { FieldRotation.format(it) }
}

/** GoTo que pide confirmación (cerca del Sol). */
data class PendingGoto(val target: Target, val reason: String)

data class TargetsState(
    val category: TargetCategory,
    val query: String,
    val rows: List<TargetRow>,
    val pendingConfirmation: PendingGoto?,
    val busy: Boolean,
    val smallBodies: List<SmallBodyElements>,
    val userObjects: List<UserObject>,
    val mount: MountState?,
    val message: String?,
    val categories: List<TargetCategory>,
)

class TargetsModel internal constructor(g: AppGraph) : ScreenModel(g) {
    private val category = MutableStateFlow(TargetCategory.SOLAR_SYSTEM)
    private val query = MutableStateFlow("")
    private val rows = MutableStateFlow<List<TargetRow>>(emptyList())
    private val pending = MutableStateFlow<PendingGoto?>(null)
    private val busy = MutableStateFlow(false)

    init {
        scope.launch {
            scope.derived(category, query, ticker(10_000), g.smallBodies.bodies, g.userObjects.objects, g.catalogs.catalog) { category.value to query.value }
                .collect { (cat, q) -> rows.value = withContext(Dispatchers.Default) { computeRows(cat, q) } }
        }
    }

    val stateFlow: StateFlow<TargetsState> = scope.derived(category, query, rows, pending, busy, g.smallBodies.bodies, g.userObjects.objects, mount, _message) {
        TargetsState(
            category.value, query.value, rows.value, pending.value, busy.value, g.smallBodies.bodies.value,
            g.userObjects.objects.value, mount.value, _message.value, TargetCategory.entries,
        )
    }
    val state = StateWatcher(stateFlow)

    fun setCategory(c: TargetCategory) { category.value = c }
    fun setQuery(q: String) { query.value = q }

    private fun computeRows(cat: TargetCategory, q: String): List<TargetRow> {
        val site = g.session.site()
        val now = g.clock.now()
        val targets = if (q.isBlank()) g.targets.list(cat) else g.targets.search(q)
        val limit = if (cat == TargetCategory.DEEP_SKY && q.isBlank()) 700 else 400
        val setup = g.settings.current.opticalSetup()
        val latRad = site.location.latitudeDeg * DEG
        val result = targets.take(limit).mapNotNull { t ->
            runCatching {
                val p = TargetResolver.position(t, now, site.location, site.weather)
                val maxExp = if (p.horizontal.alt > 0) FieldRotation.maxExposureSec(setup, latRad, p.horizontal.az, p.horizontal.alt) else null
                TargetRow(t, p.horizontal.altDeg, p.horizontal.azDeg, p.magnitude ?: t.magnitude, maxExp)
            }.getOrNull()
        }
        return when {
            q.isNotBlank() -> result
            cat == TargetCategory.SMALL_BODY -> result.sortedBy { it.magnitude ?: 99.0 }
            // Cielo profundo: primero lo que está sobre el horizonte, del más brillante al más débil.
            cat == TargetCategory.DEEP_SKY -> result.sortedWith(compareBy({ it.altDeg <= 0 }, { it.magnitude ?: 99.0 }))
            else -> result
        }
    }

    fun goto(target: Target, confirmed: Boolean) {
        scope.launch {
            val ctrl = requireController() ?: return@launch
            pending.value = null
            busy.value = true
            try {
                when (val r = ctrl.goto(target, allowNearSun = confirmed)) {
                    is GotoResult.Rejected -> if (r.needsConfirmation) pending.value = PendingGoto(target, r.reason) else _message.value = r.reason
                    else -> _message.value = describe(r)
                }
            } finally { busy.value = false }
        }
    }

    fun dismissConfirmation() { pending.value = null }

    fun sync(target: Target) {
        scope.launch {
            val ctrl = requireController() ?: return@launch
            runCatching { ctrl.sync(target, asAlignmentStar = false) }
                .onSuccess { st -> _message.value = "Sync en ${target.name}: " + (st.quality.rmsArcmin?.let { "RMS %.1f′".fmt(it) } ?: "modelo actualizado") }
                .onFailure { _message.value = "Error: ${it.message}" }
        }
    }

    fun downloadComets() {
        scope.launch {
            busy.value = true
            runCatching { g.smallBodies.downloadComets(g.settings.current.cometUrl) }
                .onSuccess { _message.value = "$it cometas descargados del MPC" }
                .onFailure { _message.value = "Descarga fallida: ${it.message}" }
            busy.value = false
        }
    }

    /** Texto de un archivo MPC elegido por el usuario. */
    fun importMpcText(text: String) {
        scope.launch {
            runCatching { g.smallBodies.importMpcText(text) }
                .onSuccess { _message.value = if (it > 0) "$it objetos importados" else "El archivo no tiene elementos MPC reconocibles" }
                .onFailure { _message.value = "Archivo no válido: ${it.message}" }
        }
    }

    fun lookupJpl(text: String) {
        scope.launch {
            busy.value = true
            val r = runCatching { g.smallBodies.lookupJpl(text) }.getOrElse { SbdbResult.NotFound(it.message ?: "Error de red") }
            _message.value = when (r) {
                is SbdbResult.Found -> "Añadido: ${r.elements.displayName}"
                is SbdbResult.Ambiguous -> "Varias coincidencias: " + r.candidates.take(6).joinToString { it.first }
                is SbdbResult.NotFound -> r.message
            }
            busy.value = false
        }
    }

    fun saveUserObject(name: String, raText: String, decText: String, magText: String) {
        scope.launch {
            val ra = Angles.parseSexagesimal(raText)
            val dec = Angles.parseSexagesimal(decText)
            if (name.isBlank() || ra == null || dec == null || ra !in 0.0..24.0 || dec !in -90.0..90.0) {
                _message.value = "Datos no válidos: AR en horas (0–24) y Dec en grados (−90…+90)"; return@launch
            }
            g.userObjects.upsert(UserObject("", name.trim(), ra, dec, magText.replace(',', '.').toDoubleOrNull()))
            _message.value = "Objeto «$name» guardado"
        }
    }

    fun deleteUserObject(id: String) {
        scope.launch { g.userObjects.delete(id) }
    }

    fun removeSmallBody(designation: String) {
        scope.launch { g.smallBodies.remove(designation) }
    }
}

// ---------------------------------------------------------------------------------- Mando

data class ControlState(
    val mount: MountState?,
    val padRateIndex: Int,
    val padMode: PadMode,
    val padRates: List<Double>,
    val maxExposureSec: Double?,
    val message: String?,
    val trackingRates: List<TrackingRate>,
)

class ControlModel internal constructor(g: AppGraph) : ScreenModel(g) {

    val stateFlow: StateFlow<ControlState> = scope.derived(mount, g.settings.settings, _message) {
        val s = g.settings.current
        ControlState(
            mount.value, s.padRateIndex, s.padMode, PAD_RATES.toList(), mount.value?.pointing?.let(::maxExposureAt), _message.value,
            TrackingRate.entries,
        )
    }
    val state = StateWatcher(stateFlow)

    /** Exposición máxima por rotación de campo donde apunta ahora el tubo (null con ocular). */
    fun maxExposureAt(p: HorizontalCoord): Double? {
        val lat = g.session.siteOrNull()?.location?.latitudeDeg ?: return null
        if (p.alt <= 0) return null
        return FieldRotation.maxExposureSec(g.settings.current.opticalSetup(), lat * DEG, p.az, p.alt)
    }

    /** Serializa pulsar/soltar: el «soltar» nunca puede adelantarse al «pulsar». */
    private val padMutex = Mutex()

    /** Flecha del mando pulsada (en Swift `release` chocaría con NSObject: por eso «Pad»). */
    fun pressPad(direction: PadDirection) {
        scope.launch {
            padMutex.withLock {
                val ctrl = requireController() ?: return@withLock
                val s = g.settings.current
                Diagnostics.log("PAD pulsado: $direction a ${PAD_RATES[s.padRateIndex]}× (${s.padMode})")
                runCatching { ctrl.manualStart(direction, s.padRateIndex, s.padMode) }.onFailure { _message.value = it.message }
            }
        }
    }

    fun releasePad() {
        scope.launch {
            padMutex.withLock {
                Diagnostics.log("PAD soltado")
                runCatching { controller?.manualStop() }.onFailure { _message.value = it.message }
            }
        }
    }

    fun setRate(index: Int) = g.settings.update { it.copy(padRateIndex = index.coerceIn(PAD_RATES.indices)) }
    fun setMode(mode: PadMode) = g.settings.update { it.copy(padMode = mode) }

    /** DETENER: también lo usan el botón de Acción, Siri y el Centro de control. */
    fun stopAll() {
        scope.launch {
            Diagnostics.log("DETENER TODO pulsado")
            controller?.stopAll()
        }
    }

    fun startTracking(rate: TrackingRate) {
        scope.launch {
            val ctrl = requireController() ?: return@launch
            val target = mount.value?.target
            runCatching { ctrl.startTracking(if (rate == TrackingRate.EPHEMERIS) target else target?.takeIf { rate == TrackingRate.SIDEREAL }, rate) }
                .onFailure { _message.value = it.message }
        }
    }

    fun stopTracking() {
        scope.launch { controller?.stopTracking() }
    }
}

// ----------------------------------------------------------------------------------- Más

data class MoreState(
    val settings: AppSettings,
    val servers: List<ServerInfo>,
    val catalogStatus: CatalogStatus,
    val ipAddresses: List<String>,
    val appVersion: String,
    val exportedFile: String?,
    val busy: Boolean,
    val mount: MountState?,
    val message: String?,
)

class MoreModel internal constructor(g: AppGraph) : ScreenModel(g) {
    private val servers = MutableStateFlow<List<ServerInfo>>(emptyList())
    private val ips = MutableStateFlow<List<String>>(emptyList())
    private val exported = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)

    init {
        scope.launch {
            ticker(1_000).collect { servers.value = g.session.serverInfo() }
        }
        scope.launch {
            ticker(5_000).collect { ips.value = runCatching { g.platform.device.localIpv4Addresses() }.getOrDefault(emptyList()) }
        }
    }

    val stateFlow: StateFlow<MoreState> = scope.derived(g.settings.settings, servers, g.catalogs.status, ips, exported, busy, mount, _message) {
        MoreState(
            g.settings.current, servers.value, g.catalogs.status.value, ips.value, g.platform.appVersion,
            exported.value, busy.value, mount.value, _message.value,
        )
    }
    val state = StateWatcher(stateFlow)

    /**
     * Cambia un ajuste por su clave (las de [SettingsCodec]: "min_alt", "lx_port"…). Desde Swift es
     * más cómodo que copiar [AppSettings] entero.
     */
    fun setSetting(key: String, value: String) = g.settings.update { current ->
        val encoded = SettingsCodec.encode(current)
        if (key !in encoded) {
            Diagnostics.log("Ajuste desconocido: $key")
            current
        } else {
            SettingsCodec.decode { k -> if (k == key) value else encoded[k] }
        }
    }

    fun restartServers() {
        g.session.restartServers()
        _message.value = "Servidores reiniciados"
    }

    /** Rutas locales de los archivos elegidos (Swift los copia antes al contenedor de la app). */
    fun importCatalog(paths: List<String>) {
        scope.launch { g.catalogs.import(paths) }
    }

    fun deleteCatalog() {
        scope.launch { g.catalogs.delete() }
    }

    /** Consulta de prueba: estrellas hasta V=9 a menos de 5° del cenit. */
    fun testCatalog() {
        scope.launch {
            val cat = g.catalogs.catalog.value ?: run { _message.value = "Catálogo no instalado"; return@launch }
            val site = g.session.site()
            _message.value = withContext(Dispatchers.Default) {
                val now = g.clock.now()
                val zenithApparent = EquatorialCoord(EarthOrientation.last(now, site.location.longitudeDeg), site.location.latRad)
                val zenithJ2000 = AstroEngine.apparentToJ2000(zenithApparent, now)
                val t0 = monotonicNanos()
                val stars = cat.cone(zenithJ2000, 5.0, 9.0)
                "${stars.size} estrellas V≤9 a <5° del cenit en %.1f ms; la más brillante: %s".fmt(
                    (monotonicNanos() - t0) / 1e6, stars.firstOrNull()?.let { "${it.designation} (V=%.2f)".fmt(it.vMag) } ?: "—",
                )
            }
        }
    }

    /** Junta el registro con un resumen del equipo; la UI comparte [MoreState.exportedFile]. */
    fun exportDiagnostics() {
        scope.launch {
            busy.value = true
            try {
                val s = g.settings.current
                val m = g.session.controller.value?.state?.value
                val a = g.alignment.state.value
                val header = buildString {
                    appendLine("OpenSky Virtuoso — diagnóstico")
                    appendLine("App: ${g.platform.appVersion}")
                    appendLine("Teléfono: ${g.platform.deviceDescription}")
                    appendLine("Ubicación: ${g.session.siteOrNull()?.location ?: "desconocida"}")
                    appendLine("Equipo: ${s.observingMode}; ocular ${s.eyepieceId}; Barlow ${s.barlowFactor}")
                    appendLine("Ejes invertidos: Az=${s.invertAzimuth} Alt=${s.invertAltitude}; límites de altura ${s.minAltitudeDeg}°…${s.maxAltitudeDeg}°; Sol ${s.sunAvoidanceDeg}°")
                    appendLine("Montura: ${m?.info?.modelName ?: "desconectada"} ${m?.info?.firmwareVersion ?: ""}; posición de inicio ${m?.startPosition ?: "—"}")
                    appendLine("Encoders: Az ${m?.encoderAzDeg} Alt ${m?.encoderAltDeg}; apunta a ${m?.pointing}")
                    appendLine("Alineación: modo ${a.mode}; ${a.alignmentSamples.size} estrellas + ${a.syncSamples.size} sync; RMS ${a.quality.rmsArcmin}′")
                    a.allSamples.forEach {
                        appendLine("  · ${it.name}: cielo Az ${"%.2f".fmt(it.sky.azDeg)} Alt ${"%.2f".fmt(it.sky.altDeg)}; " +
                            "encoders Az ${"%.2f".fmt(it.azEncoder * RAD)} Alt ${"%.2f".fmt(it.altEncoder * RAD)}")
                    }
                }
                exported.value = g.diagnostics.export(header)
            } catch (e: Exception) {
                _message.value = "No se pudo exportar: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun clearExported() { exported.value = null }
}

/** Ayuda para la UI: hora local de un instante en ms («21:04»). */
fun localTimeLabel(epochMillis: Long): String =
    CivilDateTime.local(epochMillis).let { "%02d:%02d".fmt(it.hour, it.minute) }

/** Ahora en ms (para la UI de Swift sin importar Foundation en los modelos). */
fun nowMillis(): Long = epochMillisNow()

/** Descripción del modo (en Swift `description` choca con NSObject). */
val com.opensky.virtuoso.alignment.AlignmentMode.details: String get() = description
