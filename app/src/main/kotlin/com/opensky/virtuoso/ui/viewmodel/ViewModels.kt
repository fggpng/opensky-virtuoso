package com.opensky.virtuoso.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.opensky.virtuoso.alignment.AlignmentMode
import com.opensky.virtuoso.astronomy.EarthOrientation
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.HOUR
import com.opensky.virtuoso.control.FieldRotation
import com.opensky.virtuoso.control.GotoResult
import com.opensky.virtuoso.control.MountState
import com.opensky.virtuoso.control.PadDirection
import com.opensky.virtuoso.control.PadMode
import com.opensky.virtuoso.control.TrackingRate
import com.opensky.virtuoso.data.AppSettings
import com.opensky.virtuoso.database.targets.StarTarget
import com.opensky.virtuoso.database.targets.Target
import com.opensky.virtuoso.database.targets.TargetCategory
import com.opensky.virtuoso.database.targets.TargetResolver
import com.opensky.virtuoso.database.targets.UserObject
import com.opensky.virtuoso.di.AppContainer
import com.opensky.virtuoso.network.web.SbdbResult
import com.opensky.virtuoso.sensors.PhoneMounting
import com.opensky.virtuoso.service.ConnectionMode
import com.opensky.virtuoso.service.TelescopeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun appViewModelFactory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer { HomeViewModel(c) }
    initializer { SensorViewModel(c) }
    initializer { AlignmentViewModel(c) }
    initializer { TargetsViewModel(c) }
    initializer { ControlViewModel(c) }
    initializer { MoreViewModel(c) }
    initializer { UpdateViewModel(c) }
}

private fun ticker(periodMs: Long): Flow<Long> = flow {
    while (true) { emit(System.currentTimeMillis()); delay(periodMs) }
}

/** Base con acceso al estado de la montura y a mensajes de usuario. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class MountAwareViewModel(protected val c: AppContainer) : ViewModel() {
    val mount: StateFlow<MountState?> = c.session.controller
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = c.settings.settings

    protected val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    protected val controller get() = c.session.controller.value

    protected fun requireController() = controller ?: run { _message.value = "Conecta primero la montura (pestaña Inicio)"; null }

    protected fun describe(result: GotoResult): String = when (result) {
        is GotoResult.Success -> "GoTo completado"
        is GotoResult.Rejected -> result.reason
        is GotoResult.Failed -> "Error: ${result.error}"
    }
}

data class ClockInfo(val utc: String, val local: String, val lst: String, val zone: String)

class HomeViewModel(c: AppContainer) : MountAwareViewModel(c) {
    val session = c.session.state

    /** Cambia de configuración y ajusta la velocidad del pad a la recomendada para ella. */
    fun setObservingMode(mode: com.opensky.virtuoso.control.ObservingMode) = viewModelScope.launch {
        c.settings.update { s ->
            if (s.observingMode == mode) s
            else s.copy(observingMode = mode).let { n -> n.copy(padRateIndex = n.opticalSetup().defaultPadRateIndex) }
        }
    }

    fun setEyepiece(id: String) = viewModelScope.launch { c.settings.update { it.copy(eyepieceId = id) } }
    val wifi = c.wifi.state
    val location = c.locationTime.location
    val time = c.locationTime.time

    val clock: StateFlow<ClockInfo?> = ticker(1000).map {
        val s = c.settings.current
        val now = c.clock.now()
        val instant = Instant.ofEpochMilli(now.epochMillis)
        val zone: ZoneId = if (s.useSystemTimeZone) ZoneId.systemDefault()
        else ZoneOffset.ofTotalSeconds(((s.manualUtcOffsetHours + if (s.dstEnabled) 1.0 else 0.0) * 3600).toInt())
        val lon = c.session.siteOrNull()?.location?.longitudeDeg
        ClockInfo(
            utc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).format(instant) + " UTC",
            local = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone).format(instant),
            lst = lon?.let { Angles.formatHms(EarthOrientation.last(now, it) / HOUR, 1) } ?: "— (sin ubicación)",
            zone = zone.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun connect(mode: ConnectionMode) = viewModelScope.launch {
        if (c.session.siteOrNull() == null) c.locationTime.requestFreshFix()
        val result = c.session.connect(mode)
        result.onSuccess { info ->
            TelescopeService.start(c.appContext)
            _message.value = "Conectado: ${info.modelName}, firmware ${info.firmwareVersion}"
        }.onFailure { _message.value = "No se pudo conectar: ${it.message}" }
    }

    fun disconnect() = viewModelScope.launch {
        c.session.disconnect()
        TelescopeService.stop(c.appContext)
    }

    fun syncTime() = viewModelScope.launch {
        val st = c.locationTime.syncTime(c.settings.current.ntpServer)
        _message.value = st.detail
    }

    fun refreshLocation() = viewModelScope.launch {
        val fix = c.locationTime.requestFreshFix()
        _message.value = if (fix == null) "Sin fix GPS (¿permiso de ubicación concedido?)" else "Ubicación actualizada (±%.0f m)".format(fix.accuracyM ?: 0f)
    }

    fun onPermissionsResult() {
        c.locationTime.startLocationUpdates()
        syncTime()
    }
}

class SensorViewModel(c: AppContainer) : MountAwareViewModel(c) {
    val orientation = c.sensors.orientation
    val available = c.sensors.isAvailable
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun start() = c.sensors.start()
    fun stop() = c.sensors.stop()

    fun setMounting(m: PhoneMounting) = viewModelScope.launch { c.settings.update { it.copy(phoneMounting = m) } }

    /** Promedia la IMU y fija los encoders: sustituye a "nivelar y apuntar al Norte". */
    fun applyInitialOrientation() = viewModelScope.launch {
        val ctrl = requireController() ?: return@launch
        _busy.value = true
        try {
            val o = c.sensors.captureAverage(2500)
            if (o == null) { _message.value = "Sin lecturas del sensor de orientación"; return@launch }
            ctrl.setInitialOrientation(o.trueAzimuthDeg, o.altitudeDeg)
            c.alignment.reset()
            _message.value = "Encoders inicializados: Az %.1f°, Alt %.1f°%s".format(
                o.trueAzimuthDeg, o.altitudeDeg, if (o.interference) " (¡interferencia magnética: el acimut puede tener varios grados de error!)" else "",
            )
        } catch (e: Exception) {
            _message.value = "Error: ${e.message}"
        } finally {
            _busy.value = false
        }
    }
}

class AlignmentViewModel(c: AppContainer) : MountAwareViewModel(c) {
    val alignment = c.alignment.state
    private val _suggestions = MutableStateFlow<List<Pair<StarTarget, HorizontalCoord>>>(emptyList())
    val suggestions: StateFlow<List<Pair<StarTarget, HorizontalCoord>>> = _suggestions.asStateFlow()
    private val _current = MutableStateFlow<StarTarget?>(null)
    val current: StateFlow<StarTarget?> = _current.asStateFlow()
    private val _identified = MutableStateFlow<Pair<StarTarget, Double>?>(null)
    val identified: StateFlow<Pair<StarTarget, Double>?> = _identified.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val isBrightestMode get() = alignment.value.mode == AlignmentMode.BRIGHTEST_ONE || alignment.value.mode == AlignmentMode.BRIGHTEST_TWO

    fun begin(mode: AlignmentMode) {
        c.alignment.begin(mode)
        _current.value = null; _identified.value = null
        refreshSuggestions()
        if (mode == AlignmentMode.SYNMATRIX_AUTO) gotoNextSuggested()
    }

    fun reset() {
        c.alignment.reset()
        _current.value = null; _identified.value = null; _suggestions.value = emptyList()
        _message.value = "Alineación reiniciada: se usa la orientación inicial (IMU)"
    }

    fun refreshSuggestions() = viewModelScope.launch {
        val site = c.session.siteOrNull() ?: run { _message.value = "Falta la ubicación (GPS o manual)"; return@launch }
        val remaining = alignment.value.starsRemaining.coerceAtLeast(1)
        _suggestions.value = withContext(Dispatchers.Default) {
            c.alignment.suggestStars(c.targets, c.clock.now(), site.location, site.weather, count = remaining + 3,
                exclude = alignment.value.alignmentSamples.map { it.name }.toSet())
        }
    }

    fun gotoStar(star: StarTarget) = viewModelScope.launch {
        val ctrl = requireController() ?: return@launch
        _current.value = star
        _busy.value = true
        try {
            val r = ctrl.goto(star, trackAfter = true)
            _message.value = if (r is GotoResult.Success) "Centra ${star.name} con el pad y pulsa «Centrada»" else describe(r)
        } finally { _busy.value = false }
    }

    private fun gotoNextSuggested() = viewModelScope.launch {
        refreshSuggestions().join()
        _suggestions.value.firstOrNull()?.let { gotoStar(it.first) } ?: run { _message.value = "No hay estrellas brillantes adecuadas sobre el horizonte" }
    }

    fun identifyBrightest() = viewModelScope.launch {
        val pointing = mount.value?.pointing ?: run { _message.value = "Sin posición de la montura"; return@launch }
        val site = c.session.siteOrNull() ?: return@launch
        _identified.value = c.alignment.identifyBrightest(pointing, c.targets, c.clock.now(), site.location, site.weather)
        if (_identified.value == null) _message.value = "No hay ninguna estrella brillante a menos de 25° de donde apunta el tubo"
    }

    fun selectStar(star: StarTarget) { _current.value = star }

    /** El usuario confirma que la estrella está centrada en el ocular. */
    fun confirmCentered() = viewModelScope.launch {
        val ctrl = requireController() ?: return@launch
        val star = if (isBrightestMode) _identified.value?.first else _current.value
        if (star == null) { _message.value = if (isBrightestMode) "Primero pulsa «Identificar»" else "Elige una estrella"; return@launch }
        _busy.value = true
        try {
            val state = ctrl.sync(star, asAlignmentStar = true)
            _identified.value = null; _current.value = null
            val q = state.quality
            _message.value = if (state.isComplete) {
                "Alineación completa. " + (q.rmsArcmin?.let { "Error RMS %.1f′ · precisión %.1f %%".format(it, q.precisionPercent ?: 0.0) } ?: q.method)
            } else "Estrella ${state.alignmentSamples.size} registrada. Faltan ${state.starsRemaining}."
            if (!state.isComplete && state.mode == AlignmentMode.SYNMATRIX_AUTO) gotoNextSuggested() else refreshSuggestions()
        } catch (e: Exception) {
            _message.value = "Error al sincronizar: ${e.message}"
        } finally { _busy.value = false }
    }
}

data class TargetRow(
    val target: Target, val altDeg: Double, val azDeg: Double, val magnitude: Double?,
    /** Exposición máxima por rotación de campo (s, 1 px en las esquinas); null con ocular. */
    val maxExposureSec: Double? = null,
)

class TargetsViewModel(c: AppContainer) : MountAwareViewModel(c) {
    val category = MutableStateFlow(TargetCategory.SOLAR_SYSTEM)
    val query = MutableStateFlow("")
    private val _pendingConfirmation = MutableStateFlow<Pair<Target, String>?>(null)
    val pendingConfirmation: StateFlow<Pair<Target, String>?> = _pendingConfirmation.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val rows: StateFlow<List<TargetRow>> = combine(
        category, query, ticker(10_000), c.smallBodies.bodies, c.userObjects.objects,
    ) { cat, q, _, _, _ -> cat to q }
        .map { (cat, q) -> computeRows(cat, q) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun computeRows(cat: TargetCategory, q: String): List<TargetRow> {
        val site = c.session.site()
        val now = c.clock.now()
        val targets = if (q.isBlank()) c.targets.list(cat) else c.targets.search(q)
        val limit = if (cat == TargetCategory.DEEP_SKY && q.isBlank()) 700 else 400
        val setup = c.settings.current.opticalSetup()
        val latRad = Math.toRadians(site.location.latitudeDeg)
        val rows = targets.take(limit).mapNotNull { t ->
            runCatching {
                val p = TargetResolver.position(t, now, site.location, site.weather)
                val maxExp = if (p.horizontal.alt > 0) FieldRotation.maxExposureSec(setup, latRad, p.horizontal.az, p.horizontal.alt) else null
                TargetRow(t, p.horizontal.altDeg, p.horizontal.azDeg, p.magnitude ?: t.magnitude, maxExp)
            }.getOrNull()
        }
        return when {
            q.isNotBlank() -> rows
            cat == TargetCategory.SMALL_BODY -> rows.sortedBy { it.magnitude ?: 99.0 }
            // Cielo profundo: primero lo que está sobre el horizonte, del más brillante al más débil.
            cat == TargetCategory.DEEP_SKY -> rows.sortedWith(compareBy({ it.altDeg <= 0 }, { it.magnitude ?: 99.0 }))
            else -> rows
        }
    }

    fun goto(target: Target, confirmed: Boolean = false) = viewModelScope.launch {
        val ctrl = requireController() ?: return@launch
        _pendingConfirmation.value = null
        _busy.value = true
        try {
            when (val r = ctrl.goto(target, allowNearSun = confirmed)) {
                is GotoResult.Rejected -> if (r.needsConfirmation) _pendingConfirmation.value = target to r.reason else _message.value = r.reason
                else -> _message.value = describe(r)
            }
        } finally { _busy.value = false }
    }

    fun dismissConfirmation() { _pendingConfirmation.value = null }

    fun sync(target: Target) = viewModelScope.launch {
        val ctrl = requireController() ?: return@launch
        runCatching { ctrl.sync(target, asAlignmentStar = false) }
            .onSuccess { st -> _message.value = "Sync en ${target.name}: " + (st.quality.rmsArcmin?.let { "RMS %.1f′".format(it) } ?: "modelo actualizado") }
            .onFailure { _message.value = "Error: ${it.message}" }
    }

    fun downloadComets() = viewModelScope.launch {
        _busy.value = true
        runCatching { c.smallBodies.downloadComets(c.settings.current.cometUrl) }
            .onSuccess { _message.value = "$it cometas descargados del MPC" }
            .onFailure { _message.value = "Descarga fallida: ${it.message}" }
        _busy.value = false
    }

    fun importMpc(uri: Uri) = viewModelScope.launch {
        runCatching { c.smallBodies.importMpcFile(uri) }
            .onSuccess { _message.value = "$it objetos importados" }
            .onFailure { _message.value = "Archivo no válido: ${it.message}" }
    }

    fun lookupJpl(text: String) = viewModelScope.launch {
        _busy.value = true
        val r = runCatching { c.smallBodies.lookupJpl(text) }.getOrElse { SbdbResult.NotFound(it.message ?: "Error de red") }
        _message.value = when (r) {
            is SbdbResult.Found -> "Añadido: ${r.elements.displayName}"
            is SbdbResult.Ambiguous -> "Varias coincidencias: " + r.candidates.take(6).joinToString { it.first }
            is SbdbResult.NotFound -> r.message
        }
        _busy.value = false
    }

    fun saveUserObject(name: String, raText: String, decText: String, magText: String) = viewModelScope.launch {
        val ra = Angles.parseSexagesimal(raText)
        val dec = Angles.parseSexagesimal(decText)
        if (name.isBlank() || ra == null || dec == null || ra !in 0.0..24.0 || dec !in -90.0..90.0) {
            _message.value = "Datos no válidos: AR en horas (0–24) y Dec en grados (−90…+90)"; return@launch
        }
        c.userObjects.upsert(UserObject("", name.trim(), ra, dec, magText.replace(',', '.').toDoubleOrNull()))
        _message.value = "Objeto «$name» guardado"
    }

    fun deleteUserObject(id: String) = viewModelScope.launch { c.userObjects.delete(id) }
    fun removeSmallBody(designation: String) = viewModelScope.launch { c.smallBodies.remove(designation) }
}

class ControlViewModel(c: AppContainer) : MountAwareViewModel(c) {
    /** Exposición máxima por rotación de campo donde apunta ahora el tubo (null con ocular). */
    fun maxExposureAt(p: com.opensky.virtuoso.astronomy.HorizontalCoord): Double? {
        val lat = c.session.siteOrNull()?.location?.latitudeDeg ?: return null
        if (p.alt <= 0) return null
        return FieldRotation.maxExposureSec(c.settings.current.opticalSetup(), Math.toRadians(lat), p.az, p.alt)
    }

    /** Serializa pulsar/soltar: el "soltar" nunca puede adelantarse al "pulsar". */
    private val padMutex = Mutex()

    fun press(direction: PadDirection) {
        viewModelScope.launch {
            padMutex.withLock {
                val ctrl = requireController() ?: return@withLock
                val s = c.settings.current
                runCatching { ctrl.manualStart(direction, s.padRateIndex, s.padMode) }.onFailure { _message.value = it.message }
            }
        }
    }

    fun release() {
        viewModelScope.launch {
            padMutex.withLock { runCatching { controller?.manualStop() }.onFailure { _message.value = it.message } }
        }
    }

    fun setRate(index: Int) = viewModelScope.launch { c.settings.update { it.copy(padRateIndex = index) } }
    fun setMode(mode: PadMode) = viewModelScope.launch { c.settings.update { it.copy(padMode = mode) } }

    fun stopAll() = viewModelScope.launch { controller?.stopAll() }

    fun startTracking(rate: TrackingRate) = viewModelScope.launch {
        val ctrl = requireController() ?: return@launch
        val target = mount.value?.target
        runCatching { ctrl.startTracking(if (rate == TrackingRate.EPHEMERIS) target else target?.takeIf { rate == TrackingRate.SIDEREAL }, rate) }
            .onFailure { _message.value = it.message }
    }

    fun stopTracking() = viewModelScope.launch { controller?.stopTracking() }
}

class MoreViewModel(c: AppContainer) : MountAwareViewModel(c) {
    val servers = c.session.servers
    val catalogStatus = c.catalogs.status

    val ipAddresses: StateFlow<List<String>> = ticker(5_000).map { localIpv4() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { c.settings.update(transform) }

    fun restartServers() = viewModelScope.launch {
        delay(300) // espera a que DataStore propague los cambios
        c.session.restartServers()
        _message.value = "Servidores reiniciados"
    }

    fun importCatalog(uris: List<Uri>) = viewModelScope.launch { c.catalogs.import(uris) }
    fun deleteCatalog() = viewModelScope.launch { c.catalogs.delete() }

    /** Consulta de prueba: estrellas hasta V=9 a menos de 5° del cenit. */
    fun testCatalog() = viewModelScope.launch {
        val cat = c.catalogs.catalog.value ?: run { _message.value = "Catálogo no instalado"; return@launch }
        val site = c.session.site()
        val result = withContext(Dispatchers.Default) {
            val now = c.clock.now()
            val zenithApparent = com.opensky.virtuoso.astronomy.EquatorialCoord(EarthOrientation.last(now, site.location.longitudeDeg), site.location.latRad)
            val zenithJ2000 = com.opensky.virtuoso.astronomy.AstroEngine.apparentToJ2000(zenithApparent, now)
            val t0 = System.nanoTime()
            val stars = cat.cone(zenithJ2000, 5.0, 9.0)
            "${stars.size} estrellas V≤9 a <5° del cenit en %.1f ms; la más brillante: %s".format(
                (System.nanoTime() - t0) / 1e6, stars.firstOrNull()?.let { "${it.designation} (V=%.2f)".format(it.vMag) } ?: "—")
        }
        _message.value = result
    }

    private fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${ni.name})" }
        }
    }.getOrDefault(emptyList())
}
