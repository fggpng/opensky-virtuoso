package com.opensky.virtuoso.control

import com.opensky.virtuoso.alignment.AlignmentManager
import com.opensky.virtuoso.alignment.AlignmentSample
import com.opensky.virtuoso.alignment.AlignmentState
import com.opensky.virtuoso.astronomy.AstroEngine
import com.opensky.virtuoso.astronomy.AstroTime
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.ephemeris.SolarSystemBody
import com.opensky.virtuoso.astronomy.math.ARCMIN
import com.opensky.virtuoso.astronomy.math.ARCSEC
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.astronomy.math.DEG
import com.opensky.virtuoso.database.targets.SmallBodyTarget
import com.opensky.virtuoso.database.targets.SolarSystemTarget
import com.opensky.virtuoso.database.targets.StarTarget
import com.opensky.virtuoso.database.targets.Target
import com.opensky.virtuoso.database.targets.TargetCategory
import com.opensky.virtuoso.database.targets.TargetResolver
import com.opensky.virtuoso.network.LinkState
import com.opensky.virtuoso.network.MountClient
import com.opensky.virtuoso.network.protocol.Axis
import com.opensky.virtuoso.network.protocol.MountException
import com.opensky.virtuoso.network.protocol.MountInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Orquestador de la montura: convierte objetivos del cielo en movimientos de los ejes usando el
 * modelo de alineación, ejecuta GoTo en dos pasadas, seguimiento en lazo cerrado (prealimentación
 * + corrección proporcional), pad manual en Alt/Az o AR/Dec y sincronizaciones.
 *
 * Todo el tráfico con la montura se serializa en [MountClient], así que los distintos bucles
 * (sondeo, seguimiento, pad) pueden coexistir.
 */
class MountController(
    private val client: MountClient,
    val alignment: AlignmentManager,
    private val scope: CoroutineScope,
    private val clock: AstroClock,
    private val site: () -> Site,
    private val config: () -> ControllerConfig,
) {
    private val _state = MutableStateFlow(MountState())
    val state: StateFlow<MountState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var trackJob: Job? = null
    private val abortRequested = AtomicBoolean(false)

    private sealed class TrackSpec {
        data class OfTarget(val target: Target) : TrackSpec()
        data class Fixed(val apparent: EquatorialCoord, val epoch: AstroTime, val rate: TrackingRate) : TrackSpec()
    }

    @Volatile private var trackSpec: TrackSpec? = null
    /** Desplazamiento de centrado manual (ΔAR, ΔDec aparentes, rad) aplicado al objetivo seguido. */
    @Volatile private var centeringOffset = 0.0 to 0.0
    /** Velocidades del pad (rad/s en ángulo de encoder) sumadas al seguimiento. */
    @Volatile private var manualRates = 0.0 to 0.0
    @Volatile private var lastTrackRates = 0.0 to 0.0
    /**
     * true mientras el usuario centra con el pad (o justo al soltar, hasta fijar el nuevo centrado):
     * el seguimiento aplica solo la velocidad prevista y no corrige el error de posición, que en ese
     * momento es intencionado. Sin esto el lazo «tiraba» del tubo hacia el objetivo y anulaba el pad.
     */
    @Volatile private var holdFeedback = false
    @Volatile private var lastFeedForward = 0.0 to 0.0

    init {
        scope.launch {
            client.linkState.collect { link ->
                _state.update {
                    it.copy(link = link, activity = if (link == LinkState.DISCONNECTED) Activity.DISCONNECTED else it.activity)
                }
            }
        }
    }

    val mountInfo: MountInfo? get() = client.info

    // ---------------------------------------------------------------- conexión

    suspend fun connect(): MountInfo {
        val info = client.connect()
        _state.update {
            it.copy(info = info, activity = Activity.IDLE, lastError = null,
                message = "Conectado: ${info.modelName} (firmware ${info.firmwareVersion})")
        }
        startPolling()
        return info
    }

    suspend fun disconnect() {
        trackJob?.cancelAndJoin(); trackJob = null
        pollJob?.cancelAndJoin(); pollJob = null
        runCatching { client.stopAll() }
        client.disconnect()
        _state.value = MountState()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    refreshPointing()
                    enforceManualLimits()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(lastError = e.message) }
                }
                delay(config().pollIntervalMs)
            }
        }
    }

    // ---------------------------------------------------------------- encoders

    private fun axisSign(axis: Axis): Int {
        val c = config()
        return if ((axis == Axis.AZIMUTH && c.invertAzimuth) || (axis == Axis.ALTITUDE && c.invertAltitude)) -1 else 1
    }

    private fun stepsPerRadian(axis: Axis): Double =
        client.info?.axis(axis)?.stepsPerRadian ?: throw MountException("Montura no conectada")

    fun stepsToAngle(axis: Axis, steps: Int): Double = axisSign(axis) * steps / stepsPerRadian(axis)

    fun angleToSteps(axis: Axis, angle: Double): Int = (axisSign(axis) * angle * stepsPerRadian(axis)).roundToInt()

    suspend fun readEncoders(): Pair<Double, Double> =
        stepsToAngle(Axis.AZIMUTH, client.position(Axis.AZIMUTH)) to stepsToAngle(Axis.ALTITUDE, client.position(Axis.ALTITUDE))

    /** Lee encoders y actualiza la posición del cielo (Alt/Az, AR/Dec de la fecha y J2000). */
    suspend fun refreshPointing(): MountState {
        val (az, alt) = readEncoders()
        val now = clock.now()
        val s = site()
        val hz = alignment.mountToSky(az, alt)
        val apparent = AstroEngine.horizontalToApparent(hz, now, s.location, s.weather)
        val j2000 = AstroEngine.apparentToJ2000(apparent, now)
        _state.update {
            it.copy(encoderAzDeg = az / DEG, encoderAltDeg = alt / DEG, pointing = hz,
                pointingApparent = apparent, pointingJ2000 = j2000)
        }
        return _state.value
    }

    /**
     * Inicializa los encoders con la orientación medida por la IMU del teléfono
     * (acimut verdadero y altura, en grados). Sustituye al "punto de inicio" nivelado al Norte.
     */
    suspend fun setInitialOrientation(azDeg: Double, altDeg: Double) {
        stopAll()
        client.setPosition(Axis.AZIMUTH, angleToSteps(Axis.AZIMUTH, Angles.normPi(azDeg * DEG)))
        client.setPosition(Axis.ALTITUDE, angleToSteps(Axis.ALTITUDE, altDeg * DEG))
        refreshPointing()
        _state.update { it.copy(message = "Orientación inicial: Az %.1f°, Alt %.1f°".format(azDeg, altDeg)) }
    }

    // ---------------------------------------------------------------- seguridad

    /** Devuelve un aviso si el objetivo es el Sol o está demasiado cerca de él. */
    fun sunWarning(target: Target, hz: HorizontalCoord, now: AstroTime): String? {
        if (target.isSun) {
            return "PELIGRO: mirar el Sol sin un filtro solar certificado de apertura completa causa " +
                "ceguera permanente al instante y daña el telescopio. Tapa también el buscador. " +
                "Confirma solo si el filtro está bien colocado."
        }
        val s = site()
        val sun = TargetResolver.position(SolarSystemTarget(SolarSystemBody.SUN), now, s.location, s.weather)
        if (sun.horizontal.altDeg < -2.0) return null
        val sep = hz.separation(sun.horizontal) / DEG
        return if (sep < config().sunAvoidanceDeg) {
            "%s está a %.1f° del Sol (mínimo configurado %.0f°). Riesgo de que el Sol entre en el tubo.".format(target.name, sep, config().sunAvoidanceDeg)
        } else null
    }

    private fun altitudeViolation(name: String, altDeg: Double): String? {
        val c = config()
        return when {
            altDeg < c.minAltitudeDeg -> "%s está bajo el límite de altura (%.1f° < %.1f°)".format(name, altDeg, c.minAltitudeDeg)
            altDeg > c.maxAltitudeDeg -> "%s supera el límite máximo de altura (%.1f° > %.1f°)".format(name, altDeg, c.maxAltitudeDeg)
            else -> null
        }
    }

    // ---------------------------------------------------------------- GoTo

    /**
     * GoTo a un objetivo. Primera pasada rápida apuntando a la posición futura estimada; segunda
     * pasada corta para compensar el desplazamiento del cielo. Termina en seguimiento si [trackAfter].
     */
    suspend fun goto(
        target: Target, allowNearSun: Boolean = false, trackAfter: Boolean = true, rate: TrackingRate? = null,
    ): GotoResult {
        if (client.info == null) return GotoResult.Failed("Montura no conectada")
        val s = site(); val cfg = config()
        val now0 = clock.now()
        val first = TargetResolver.position(target, now0, s.location, s.weather)
        if (!allowNearSun) sunWarning(target, first.horizontal, now0)?.let { return GotoResult.Rejected(it, needsConfirmation = true) }
        altitudeViolation(target.name, first.horizontal.altDeg)?.let { return GotoResult.Rejected(it) }

        abortRequested.set(false)
        stopTracking(stopMotors = false)
        centeringOffset = 0.0 to 0.0
        _state.update { it.copy(activity = Activity.SLEWING, target = target, tracking = null, message = "GoTo → ${target.name}", lastError = null) }
        try {
            val passes = if (cfg.refineGoto) 2 else 1
            for (pass in 1..passes) {
                val (az0, alt0) = readEncoders()
                val now = clock.now()
                val encNow = alignment.skyToMount(TargetResolver.position(target, now, s.location, s.weather).horizontal)
                    ?: return GotoResult.Rejected("Posición inalcanzable con el modelo actual")
                val distDeg = max(abs(Angles.normPi(encNow.first - az0)), abs(encNow.second - alt0)) / DEG
                val lead = if (pass == 1) distDeg / cfg.gotoSpeedDegPerSec + 2.0 else 1.0
                val pos = TargetResolver.position(target, now.plusSeconds(lead), s.location, s.weather)
                altitudeViolation(target.name, pos.horizontal.altDeg)?.let { return GotoResult.Rejected(it) }
                val enc = alignment.skyToMount(pos.horizontal) ?: return GotoResult.Rejected("Posición inalcanzable")
                val azTarget = az0 + Angles.normPi(enc.first - az0) // camino más corto, ángulo continuo
                client.gotoSteps(Axis.AZIMUTH, angleToSteps(Axis.AZIMUTH, azTarget))
                client.gotoSteps(Axis.ALTITUDE, angleToSteps(Axis.ALTITUDE, enc.second))
                if (!waitForGotoCompletion()) {
                    _state.update { it.copy(activity = Activity.IDLE, message = "GoTo cancelado") }
                    return GotoResult.Rejected("Cancelado por el usuario")
                }
            }
            val st = refreshPointing()
            val now = clock.now()
            val residual = st.pointing?.let {
                it.separation(TargetResolver.position(target, now, s.location, s.weather).horizontal) / ARCMIN
            } ?: Double.NaN
            if (trackAfter) startTracking(target, rate ?: defaultRateFor(target))
            else _state.update { it.copy(activity = Activity.IDLE) }
            _state.update { it.copy(message = "En ${target.name} (error de apuntado del lazo %.1f′)".format(residual)) }
            return GotoResult.Success(residual)
        } catch (e: CancellationException) {
            runCatching { client.stopAll() }
            throw e
        } catch (e: Exception) {
            runCatching { client.stopAll() }
            _state.update { it.copy(activity = Activity.IDLE, lastError = e.message) }
            return GotoResult.Failed(e.message ?: e.toString())
        }
    }

    private suspend fun waitForGotoCompletion(timeoutMs: Long = 240_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (abortRequested.get()) {
                client.stopAll()
                return false
            }
            val azRunning = client.status(Axis.AZIMUTH).running
            val altRunning = client.status(Axis.ALTITUDE).running
            if (!azRunning && !altRunning) return true
            if (System.currentTimeMillis() > deadline) throw MountException("GoTo excedió el tiempo máximo")
            delay(150)
        }
    }

    fun defaultRateFor(target: Target): TrackingRate = when (target) {
        is SolarSystemTarget, is SmallBodyTarget -> TrackingRate.EPHEMERIS
        else -> TrackingRate.SIDEREAL
    }

    /** Detiene todo (botón STOP): cancela GoTo, seguimiento y pad. */
    suspend fun stopAll() {
        abortRequested.set(true)
        stopTracking(stopMotors = false)
        manualRates = 0.0 to 0.0
        runCatching { client.stopAll() }
        _state.update { it.copy(activity = if (client.info != null) Activity.IDLE else Activity.DISCONNECTED, tracking = null) }
    }

    // ---------------------------------------------------------------- seguimiento

    /**
     * Inicia el seguimiento. Con [target] = null sigue la posición actual del tubo a la
     * velocidad [rate] (sidérea, lunar o solar).
     */
    suspend fun startTracking(target: Target?, rate: TrackingRate = TrackingRate.SIDEREAL) {
        val spec: TrackSpec = if (target == null || (rate == TrackingRate.LUNAR || rate == TrackingRate.SOLAR) && target !is SolarSystemTarget && target !is SmallBodyTarget) {
            val app = refreshPointing().pointingApparent ?: throw MountException("Sin posición actual")
            TrackSpec.Fixed(app, clock.now(), if (rate == TrackingRate.EPHEMERIS) TrackingRate.SIDEREAL else rate)
        } else TrackSpec.OfTarget(target)
        trackJob?.cancelAndJoin()
        abortRequested.set(false)
        trackSpec = spec
        val effective = when (spec) { is TrackSpec.Fixed -> spec.rate; is TrackSpec.OfTarget -> rate }
        _state.update { it.copy(activity = Activity.TRACKING, tracking = effective, target = target ?: it.target) }
        trackJob = scope.launch { trackingLoop(spec) }
    }

    suspend fun stopTracking(stopMotors: Boolean = true) {
        trackJob?.cancelAndJoin()
        trackJob = null
        trackSpec = null
        lastTrackRates = 0.0 to 0.0
        lastFeedForward = 0.0 to 0.0
        holdFeedback = false
        if (stopMotors) runCatching { client.stopAll() }
        _state.update { if (it.activity == Activity.TRACKING) it.copy(activity = Activity.IDLE, tracking = null) else it.copy(tracking = null) }
    }

    private fun skyPosition(spec: TrackSpec, t: AstroTime): HorizontalCoord {
        val s = site()
        val (dRa, dDec) = centeringOffset
        return when (spec) {
            is TrackSpec.OfTarget -> {
                val p = TargetResolver.position(spec.target, t, s.location, s.weather)
                if (dRa == 0.0 && dDec == 0.0) p.horizontal
                else AstroEngine.apparentToHorizontal(EquatorialCoord(p.apparent.ra + dRa, p.apparent.dec + dDec), t, s.location, s.weather, p.distanceKm)
            }
            is TrackSpec.Fixed -> {
                val dtSec = (t.jdUtc - spec.epoch.jdUtc) * AstroTime.SECONDS_PER_DAY
                val ra = spec.apparent.ra + spec.rate.raDriftArcsecPerSec * ARCSEC * dtSec + dRa
                AstroEngine.apparentToHorizontal(EquatorialCoord(ra, spec.apparent.dec + dDec), t, s.location, s.weather)
            }
        }
    }

    private suspend fun trackingLoop(spec: TrackSpec) {
        while (currentCoroutineContext().isActive) {
            val cfg = config()
            try {
                val dt = cfg.trackingUpdateMs / 1000.0
                val now = clock.now()
                val hz0 = skyPosition(spec, now)
                if (hz0.altDeg < cfg.minAltitudeDeg) {
                    client.stopAll()
                    _state.update { it.copy(activity = Activity.IDLE, tracking = null, message = "El objetivo bajó del límite de altura: seguimiento detenido") }
                    return
                }
                val e0 = alignment.skyToMount(hz0)
                val e1 = alignment.skyToMount(skyPosition(spec, now.plusSeconds(dt)))
                if (e0 == null || e1 == null) throw MountException("Objetivo inalcanzable")
                val (az, alt) = readEncoders()
                val errAz = Angles.normPi(e0.first - az)
                val errAlt = e0.second - alt
                val centering = holdFeedback || manualRates != (0.0 to 0.0)
                if (!centering && max(abs(errAz) * cos(alt), abs(errAlt)) > 1.0 * DEG) {
                    // Desviación grande (p. ej. tras un sync o un golpe): reposiciona y sigue.
                    client.gotoSteps(Axis.AZIMUTH, angleToSteps(Axis.AZIMUTH, az + Angles.normPi(e1.first - az)))
                    client.gotoSteps(Axis.ALTITUDE, angleToSteps(Axis.ALTITUDE, e1.second))
                    waitForGotoCompletion()
                    continue
                }
                val ffAz = Angles.normPi(e1.first - e0.first) / dt
                val ffAlt = (e1.second - e0.second) / dt
                lastFeedForward = ffAz to ffAlt
                lastTrackRates = if (centering) ffAz to ffAlt
                else (ffAz + TRACK_GAIN * errAz) to (ffAlt + TRACK_GAIN * errAlt)
                applyRates()
                _state.update { if (it.lastError != null) it.copy(lastError = null) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(lastError = "Seguimiento: ${e.message}") }
            }
            delay(cfg.trackingUpdateMs)
        }
    }

    private suspend fun applyRates() {
        val max = config().maxRateDegPerSec * DEG
        val rAz = (lastTrackRates.first + manualRates.first).coerceIn(-max, max)
        val rAlt = (lastTrackRates.second + manualRates.second).coerceIn(-max, max)
        client.setRate(Axis.AZIMUTH, axisSign(Axis.AZIMUTH) * rAz)
        client.setRate(Axis.ALTITUDE, axisSign(Axis.ALTITUDE) * rAlt)
    }

    // ---------------------------------------------------------------- pad manual

    /** Pulsa una flecha del pad a la velocidad [rateIndex] de [PAD_RATES]. */
    suspend fun manualStart(direction: PadDirection, rateIndex: Int, mode: PadMode) {
        if (client.info == null) return
        val r = PAD_RATES[rateIndex.coerceIn(PAD_RATES.indices)] * MountClient.SIDEREAL_RATE_RAD_S
        val rates = when (mode) {
            PadMode.ALT_AZ -> when (direction) {
                PadDirection.UP -> 0.0 to r
                PadDirection.DOWN -> 0.0 to -r
                PadDirection.RIGHT -> r to 0.0
                PadDirection.LEFT -> -r to 0.0
            }
            PadMode.RA_DEC -> equatorialRates(direction, r)
        }
        val st = _state.value
        if (rates.second < 0 && (st.pointing?.altDeg ?: 90.0) <= config().minAltitudeDeg) {
            _state.update { it.copy(message = "Límite de altura mínima alcanzado") }
            return
        }
        manualRates = rates
        if (trackSpec != null) {
            holdFeedback = true
            lastTrackRates = lastFeedForward
            applyRates()
        } else {
            applyRates()
            _state.update { it.copy(activity = Activity.MANUAL) }
        }
    }

    suspend fun manualStop() {
        val spec = trackSpec
        if (spec != null) {
            // 1) Deja de mover a mano pero sin corregir posición; 2) mide dónde quedó; 3) lo fija como
            // nuevo centrado; 4) reactiva la corrección. Así el seguimiento no deshace el centrado.
            holdFeedback = true
            manualRates = 0.0 to 0.0
            try {
                applyRates()
                // Conserva el centrado manual como desplazamiento respecto al objetivo seguido.
                val st = refreshPointing()
                val now = clock.now()
                val pointing = st.pointingApparent
                if (pointing != null) {
                    val s = site()
                    val base = when (spec) {
                        is TrackSpec.OfTarget -> TargetResolver.position(spec.target, now, s.location, s.weather).apparent
                        is TrackSpec.Fixed -> EquatorialCoord(
                            spec.apparent.ra + spec.rate.raDriftArcsecPerSec * ARCSEC * (now.jdUtc - spec.epoch.jdUtc) * AstroTime.SECONDS_PER_DAY,
                            spec.apparent.dec,
                        )
                    }
                    centeringOffset = Angles.normPi(pointing.ra - base.ra) to (pointing.dec - base.dec)
                }
            } finally {
                holdFeedback = false
            }
        } else {
            manualRates = 0.0 to 0.0
            client.stopAll()
            refreshPointing()
            _state.update { it.copy(activity = Activity.IDLE) }
        }
    }

    /** Velocidades de encoder para moverse a [r] rad/s en AR o Dec (Jacobiano numérico). */
    private suspend fun equatorialRates(direction: PadDirection, r: Double): Pair<Double, Double> {
        val st = refreshPointing()
        val app = st.pointingApparent ?: return 0.0 to 0.0
        val now = clock.now(); val s = site()
        val h = 60 * ARCSEC
        val cosDec = cos(app.dec).coerceAtLeast(1e-3)
        val (dRa, dDec) = when (direction) {
            PadDirection.UP -> 0.0 to h
            PadDirection.DOWN -> 0.0 to -h
            PadDirection.LEFT -> h / cosDec to 0.0
            PadDirection.RIGHT -> -h / cosDec to 0.0
        }
        val e0 = alignment.skyToMount(AstroEngine.apparentToHorizontal(app, now, s.location, s.weather)) ?: return 0.0 to 0.0
        val e1 = alignment.skyToMount(AstroEngine.apparentToHorizontal(EquatorialCoord(app.ra + dRa, app.dec + dDec), now, s.location, s.weather))
            ?: return 0.0 to 0.0
        return Angles.normPi(e1.first - e0.first) / h * r to (e1.second - e0.second) / h * r
    }

    private suspend fun enforceManualLimits() {
        val st = _state.value
        val alt = st.pointing?.altDeg ?: return
        if (manualRates.second < 0 && alt < config().minAltitudeDeg) {
            manualRates = manualRates.first to 0.0
            if (trackSpec != null) applyRates() else client.stop(Axis.ALTITUDE)
            _state.update { it.copy(message = "Límite de altura mínima alcanzado") }
        }
    }

    // ---------------------------------------------------------------- sincronización

    /**
     * Toma una muestra: el objetivo está centrado en el ocular ahora mismo.
     * @param asAlignmentStar true para estrellas del proceso de alineación; false para "Sync Samples".
     */
    suspend fun sync(target: Target, asAlignmentStar: Boolean): AlignmentState {
        val (az, alt) = readEncoders()
        val now = clock.now(); val s = site()
        val pos = TargetResolver.position(target, now, s.location, s.weather)
        val sample = AlignmentSample(target.name, pos.horizontal, az, alt)
        val result = if (asAlignmentStar) alignment.addAlignmentStar(sample) else alignment.addSyncSample(sample)
        centeringOffset = 0.0 to 0.0
        refreshPointing()
        _state.update { it.copy(message = "Sincronizado con ${target.name}") }
        return result
    }

    /** GoTo a coordenadas J2000 externas (Stellarium). Nunca permite el entorno del Sol. */
    suspend fun gotoJ2000(j2000: EquatorialCoord, name: String = "Objetivo de Stellarium"): GotoResult =
        goto(externalTarget(j2000, name), allowNearSun = false)

    suspend fun syncJ2000(j2000: EquatorialCoord, name: String = "Sync de Stellarium"): AlignmentState =
        sync(externalTarget(j2000, name), asAlignmentStar = false)

    private fun externalTarget(j2000: EquatorialCoord, name: String) =
        StarTarget("ext:${j2000.raDeg}:${j2000.decDeg}", name, "Coordenadas externas", j2000, 0.0, 0.0, null, TargetCategory.USER_OBJECT)

    companion object {
        /** Ganancia proporcional del seguimiento (1/s): corrige la mitad del error por segundo. */
        const val TRACK_GAIN = 0.5
    }
}
