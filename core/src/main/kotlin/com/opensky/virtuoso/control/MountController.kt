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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

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
    /** false mientras no haya ubicación real (GPS o manual): sin ella no se permite apuntar. */
    private val siteKnown: () -> Boolean = { true },
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

    // ---- Controles de modos de falla (ver comentarios en cada uso)
    /** Un solo GoTo a la vez: uno nuevo (app o Stellarium) cancela al anterior y espera a que pare. */
    private val gotoMutex = Mutex()
    /** Inicio del movimiento manual actual (hombre muerto). */
    @Volatile private var manualStartedAtMs = 0L
    /** Última lectura válida por eje (pasos, ms): detecta respuestas UDP tardías o cruzadas. */
    private val lastAxisRead = ConcurrentHashMap<Axis, Pair<Int, Long>>()
    /** Ambos encoders volvieron a 0 de golpe: la montura probablemente se reinició. */
    private val resetSuspected = AtomicBoolean(false)
    /** El firmware marca «inicializado» tras :F; si lo hace, se usa para detectar reinicios. */
    @Volatile private var initFlagReliable = false
    private var pollCount = 0L
    @Volatile private var lastAxisSigns: Pair<Boolean, Boolean>? = null
    /** Historial de (ms, pasos Az, pasos Alt, velocidad Az, velocidad Alt) para detectar atascos. */
    private val motionHistory = ArrayDeque<MotionSample>()
    private val repositionTimes = ArrayDeque<Long>()
    /** Hasta cuándo el usuario aceptó trabajar cerca del Sol (confirmó tener filtro). */
    @Volatile private var sunApprovedUntilMs = 0L

    private data class MotionSample(val ms: Long, val az: Double, val alt: Double, val rAz: Double, val rAlt: Double)

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
        lastAxisRead.clear()
        initFlagReliable = runCatching {
            client.status(Axis.AZIMUTH).initialized && client.status(Axis.ALTITUDE).initialized
        }.getOrDefault(false)
        lastAxisSigns = config().let { it.invertAzimuth to it.invertAltitude }
        _state.update {
            it.copy(info = info, activity = Activity.IDLE, lastError = null,
                message = "Conectado: ${info.modelName} (firmware ${info.firmwareVersion})")
        }
        Diagnostics.log("Detección de reinicio por bandera de inicialización: ${if (initFlagReliable) "sí" else "no (solo por encoders en cero)"}")
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
                    checkManualDeadman()
                    checkMotionStall()
                    checkAxisConfigChange()
                    if (resetSuspected.getAndSet(false)) handleMountReset("los dos encoders volvieron a cero")
                    if (initFlagReliable && pollCount % 4 == 0L) {
                        val ok = client.status(Axis.AZIMUTH).initialized && client.status(Axis.ALTITUDE).initialized
                        if (!ok) handleMountReset("la tarjeta de motores perdió su inicialización")
                    }
                    pollCount++
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

    suspend fun readEncoders(): Pair<Double, Double> {
        val hadHistory = lastAxisRead[Axis.AZIMUTH]?.first?.let { abs(it) } to lastAxisRead[Axis.ALTITUDE]?.first?.let { abs(it) }
        val azSteps = readAxisSteps(Axis.AZIMUTH)
        val altSteps = readAxisSteps(Axis.ALTITUDE)
        // Reinicio de la montura (p. ej. baterías bajas): los contadores vuelven exactamente a 0.
        val oneDegAz = stepsPerRadian(Axis.AZIMUTH) * DEG
        val oneDegAlt = stepsPerRadian(Axis.ALTITUDE) * DEG
        if (azSteps == 0 && altSteps == 0 &&
            ((hadHistory.first ?: 0) > oneDegAz || (hadHistory.second ?: 0) > oneDegAlt)
        ) resetSuspected.set(true)
        return stepsToAngle(Axis.AZIMUTH, azSteps) to stepsToAngle(Axis.ALTITUDE, altSteps)
    }

    /**
     * Lee un eje y comprueba que el salto respecto a la lectura anterior sea físicamente posible.
     * Por UDP, una respuesta tardía de otra petición (incluso del otro eje) tiene el mismo formato:
     * si el salto es imposible se vuelve a leer y se usa la segunda lectura.
     */
    private suspend fun readAxisSteps(axis: Axis): Int {
        val first = client.position(axis)
        val now = System.currentTimeMillis()
        val prev = lastAxisRead[axis]
        var value = first
        if (prev != null) {
            val dt = (now - prev.second).coerceAtLeast(1) / 1000.0
            val jumpDeg = abs(first - prev.first) / stepsPerRadian(axis) / DEG
            if (jumpDeg > PLAUSIBLE_RATE_DEG_S * dt + PLAUSIBLE_MARGIN_DEG) {
                value = client.position(axis)
                Diagnostics.log("Lectura inverosímil del eje ${axis.label}: salto de %.2f° en %.2f s; relectura = %+.3f°".format(
                    jumpDeg, dt, (value - prev.first) / stepsPerRadian(axis) / DEG))
            }
        }
        lastAxisRead[axis] = value to System.currentTimeMillis()
        return value
    }

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
    suspend fun setInitialOrientation(azDeg: Double, altDeg: Double, source: StartPosition = StartPosition.PHONE) {
        stopAll()
        client.setPosition(Axis.AZIMUTH, angleToSteps(Axis.AZIMUTH, Angles.normPi(azDeg * DEG)))
        client.setPosition(Axis.ALTITUDE, angleToSteps(Axis.ALTITUDE, altDeg * DEG))
        lastAxisRead.clear()
        refreshPointing()
        Diagnostics.log("POSICIÓN DE INICIO (${source.name}): Az %.2f°, Alt %.2f°".format(azDeg, altDeg))
        _state.update { it.copy(startPosition = source, message = "Orientación inicial: Az %.1f°, Alt %.1f°".format(azDeg, altDeg)) }
    }

    /**
     * «El tubo está horizontal y apunta al norte»: fija los encoders en Az 0°, Alt 0° sin tener que
     * apagar la montura. Se usa en cada sesión, en el sitio de observación.
     */
    suspend fun confirmHomePosition() = setInitialOrientation(0.0, 0.0, StartPosition.HOME)

    /**
     * Compara la altura que cree la app con la medida por el teléfono apoyado en el tubo (la altura
     * viene de la gravedad, fiable aunque haya metal cerca). Sin alineación con estrellas, un
     * desacuerdo vuelve a dejar la posición SIN CONFIRMAR, y el GoTo queda bloqueado.
     */
    suspend fun checkAltitude(measuredAltDeg: Double, toleranceDeg: Double = ALTITUDE_CHECK_TOLERANCE_DEG): AltitudeCheck {
        val believed = refreshPointing().pointing?.altDeg ?: throw MountException("Montura no conectada")
        val check = AltitudeCheck(believed, measuredAltDeg, toleranceDeg)
        val aligned = alignment.state.value.allSamples.isNotEmpty()
        _state.update {
            when {
                check.ok -> it.copy(message = "Altura verificada: la app cree %.1f°, el teléfono mide %.1f°".format(believed, measuredAltDeg))
                aligned -> it.copy(message = "¡Atención! La app cree %.1f° de altura y el teléfono mide %.1f°. Revisa la alineación.".format(believed, measuredAltDeg))
                else -> it.copy(startPosition = StartPosition.UNCONFIRMED,
                    message = "La app cree %.1f° de altura pero el teléfono mide %.1f°: confirma de nuevo la posición de inicio.".format(believed, measuredAltDeg))
            }
        }
        return check
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

    /** Posición del Sol si está sobre el horizonte (de día); null de noche. */
    private fun sunIfUp(now: AstroTime): HorizontalCoord? {
        if (!siteKnown()) return null // sin ubicación real la posición del Sol no es fiable (y el GoTo ya está bloqueado)
        val s = site()
        val sun = TargetResolver.position(SolarSystemTarget(SolarSystemBody.SUN), now, s.location, s.weather).horizontal
        return if (sun.altDeg < -2.0) null else sun
    }

    private fun sunApproved(): Boolean = System.currentTimeMillis() < sunApprovedUntilMs

    /**
     * Recorrido real de un GoTo: cada eje va a su máxima velocidad, así que primero se mueven los
     * dos y luego solo el que tiene más camino. Devuelve la mínima distancia al Sol en ese
     * recorrido (grados) si baja del límite; null si es seguro o de noche.
     */
    private fun sunOnPath(az0: Double, alt0: Double, azT: Double, altT: Double, now: AstroTime): Double? {
        val sun = sunIfUp(now) ?: return null
        val dAz = azT - az0; val dAlt = altT - alt0
        val span = max(abs(dAz), abs(dAlt))
        if (span <= 0.0) return null
        var minSep = Double.MAX_VALUE
        for (i in 0..PATH_SAMPLES) {
            val s = span * i / PATH_SAMPLES
            val az = az0 + sign(dAz) * min(s, abs(dAz))
            val alt = alt0 + sign(dAlt) * min(s, abs(dAlt))
            minSep = min(minSep, alignment.mountToSky(az, alt).separation(sun) / DEG)
        }
        return if (minSep < config().sunAvoidanceDeg) minSep else null
    }

    /** Elige el destino de acimut que no enrolla cables: camino corto salvo que pase de ±270°. */
    private fun chooseAzTarget(az0: Double, encAz: Double): Double {
        var t = az0 + Angles.normPi(encAz - az0)
        if (abs(t) > AZ_WRAP_LIMIT) t -= sign(t) * 2 * Math.PI
        return t
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
        if (!siteKnown()) return GotoResult.Rejected(NO_LOCATION_MESSAGE)
        val s = site(); val cfg = config()
        val now0 = clock.now()
        val first = TargetResolver.position(target, now0, s.location, s.weather)
        if (!allowNearSun) sunWarning(target, first.horizontal, now0)?.let { return GotoResult.Rejected(it, needsConfirmation = true) }
        altitudeViolation(target.name, first.horizontal.altDeg)?.let { return GotoResult.Rejected(it) }
        if (_state.value.startPosition == StartPosition.UNCONFIRMED && alignment.state.value.allSamples.isEmpty()) {
            return GotoResult.Rejected(START_UNCONFIRMED_MESSAGE)
        }
        // Un GoTo nuevo cancela el que esté en curso (por ejemplo uno pedido desde Stellarium) y
        // espera a que termine: nunca dos GoTo mandando órdenes a la vez.
        abortRequested.set(true)
        return gotoMutex.withLock { gotoLocked(target, allowNearSun, trackAfter, rate, s, cfg) }
    }

    private suspend fun gotoLocked(
        target: Target, allowNearSun: Boolean, trackAfter: Boolean, rate: TrackingRate?, s: Site, cfg: ControllerConfig,
    ): GotoResult {
        abortRequested.set(false)
        stopTracking(stopMotors = false)
        manualRates = 0.0 to 0.0
        centeringOffset = 0.0 to 0.0
        if (allowNearSun) sunApprovedUntilMs = System.currentTimeMillis() + SUN_APPROVAL_MS
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
                val azTarget = chooseAzTarget(az0, enc.first)
                if (!allowNearSun && !sunApproved()) {
                    sunOnPath(az0, alt0, azTarget, enc.second, now)?.let { sep ->
                        _state.update { it.copy(activity = Activity.IDLE) }
                        return GotoResult.Rejected("El recorrido del GoTo pasaría a %.0f° del Sol (mínimo %.0f°). Mueve primero el tubo con el pad para rodearlo.".format(sep, cfg.sunAvoidanceDeg))
                    }
                }
                Diagnostics.log("GoTo ${target.name} (pasada $pass): cielo Az %.2f° Alt %.2f° → encoders Az %.2f° Alt %.2f° desde Az %.2f° Alt %.2f°".format(
                    pos.horizontal.azDeg, pos.horizontal.altDeg, azTarget / DEG, enc.second / DEG, az0 / DEG, alt0 / DEG))
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
            Diagnostics.log("GoTo ${target.name} FALLÓ: ${e.message}")
            _state.update {
                it.copy(activity = Activity.IDLE, lastError = e.message,
                    alert = if (e is StallException) e.message else it.alert)
            }
            return GotoResult.Failed(e.message ?: e.toString())
        }
    }

    /**
     * Espera a que terminen ambos ejes. Además vigila que avancen: si un eje sigue «en marcha»
     * pero no cambia de posición durante [ControllerConfig.stallWindowMs] (algo frena el tubo,
     * embrague flojo, baterías bajas), detiene todo y lanza [StallException].
     */
    private suspend fun waitForGotoCompletion(timeoutMs: Long = 240_000): Boolean {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        val window = config().stallWindowMs
        var refMs = start
        var refPos: Pair<Int, Int>? = null
        while (true) {
            if (abortRequested.get()) {
                client.stopAll()
                return false
            }
            val azRunning = client.status(Axis.AZIMUTH).running
            val altRunning = client.status(Axis.ALTITUDE).running
            if (!azRunning && !altRunning) return true
            val now = System.currentTimeMillis()
            if (now > deadline) throw MountException("GoTo excedió el tiempo máximo")
            val pos = client.position(Axis.AZIMUTH) to client.position(Axis.ALTITUDE)
            val ref = refPos
            if (ref == null || now - refMs >= window) {
                if (ref != null && now - start > STALL_GRACE_MS) {
                    val movedAz = abs(pos.first - ref.first) / stepsPerRadian(Axis.AZIMUTH) / DEG
                    val movedAlt = abs(pos.second - ref.second) / stepsPerRadian(Axis.ALTITUDE) / DEG
                    val stuck = (azRunning && movedAz < STALL_MIN_DEG) || (altRunning && movedAlt < STALL_MIN_DEG)
                    if (stuck) {
                        client.stopAll()
                        throw StallException(stallMessage(if (azRunning && movedAz < STALL_MIN_DEG) Axis.AZIMUTH else Axis.ALTITUDE))
                    }
                }
                refPos = pos; refMs = now
            }
            delay(150)
        }
    }

    private fun stallMessage(axis: Axis) =
        "El motor de ${if (axis == Axis.AZIMUTH) "acimut" else "altura"} recibe órdenes pero el tubo no avanza. Se detuvo todo. " +
            "Revisa que nada lo frene (cables, cámara, embrague flojo) y las baterías."

    fun defaultRateFor(target: Target): TrackingRate = when (target) {
        is SolarSystemTarget, is SmallBodyTarget -> TrackingRate.EPHEMERIS
        else -> TrackingRate.SIDEREAL
    }

    /**
     * Detiene todo (botón STOP): cancela GoTo, seguimiento y pad. Devuelve false si la montura no
     * confirmó la parada ni al segundo intento: entonces se levanta una alarma para que el usuario
     * apague la montura con su interruptor (nunca se da por hecho que se detuvo).
     */
    suspend fun stopAll(): Boolean {
        abortRequested.set(true)
        stopTracking(stopMotors = false)
        manualRates = 0.0 to 0.0
        var ok = client.stopAll()
        if (!ok) {
            delay(300)
            ok = client.stopAll()
        }
        Diagnostics.log(if (ok) "DETENER: confirmado" else "DETENER: ¡NO CONFIRMADO por la montura!")
        _state.update {
            it.copy(activity = if (client.info != null) Activity.IDLE else Activity.DISCONNECTED, tracking = null,
                alert = if (ok) it.alert else STOP_NOT_CONFIRMED_ALERT)
        }
        return ok
    }

    /** El usuario leyó la alarma. */
    fun clearAlert() = _state.update { it.copy(alert = null) }

    // ---------------------------------------------------------------- seguimiento

    /**
     * Inicia el seguimiento. Con [target] = null sigue la posición actual del tubo a la
     * velocidad [rate] (sidérea, lunar o solar).
     */
    suspend fun startTracking(target: Target?, rate: TrackingRate = TrackingRate.SIDEREAL) {
        if (!siteKnown()) throw MountException(NO_LOCATION_MESSAGE)
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
                    endTrackingFromLoop("El objetivo bajó del límite de altura: seguimiento detenido")
                    return
                }
                if (hz0.altDeg > cfg.maxAltitudeDeg) {
                    endTrackingFromLoop("El objetivo pasó la altura máxima (%.0f°): seguimiento detenido para no forzar el tubo cerca del cenit".format(cfg.maxAltitudeDeg))
                    return
                }
                val e0 = alignment.skyToMount(hz0)
                val e1 = alignment.skyToMount(skyPosition(spec, now.plusSeconds(dt)))
                if (e0 == null || e1 == null) throw MountException("Objetivo inalcanzable")
                val (az, alt) = readEncoders()
                val errAz = Angles.normPi(e0.first - az)
                val errAlt = e0.second - alt
                val centering = holdFeedback || manualRates != (0.0 to 0.0)
                val imaging = cfg.trackingMode == TrackingMode.IMAGING
                val bigError = max(abs(errAz) * cos(alt), abs(errAlt)) > 1.0 * DEG
                if (bigError && imaging && !centering) {
                    // Con cámara no se reposiciona de golpe (arruinaría la toma): se avisa y la
                    // corrección suave lo va acercando.
                    if (_state.value.message == null) {
                        _state.update { it.copy(message = "Desviación de seguimiento > 1°: revisa el encuadre (haz GoTo de nuevo si hace falta)") }
                    }
                }
                if (!centering && !imaging && bigError) {
                    // Desviación grande (p. ej. tras un sync o un golpe): reposiciona y sigue, pero si
                    // pasa una y otra vez algo frena el tubo: se detiene en lugar de insistir.
                    val nowMs = System.currentTimeMillis()
                    repositionTimes.addLast(nowMs)
                    while (repositionTimes.isNotEmpty() && nowMs - repositionTimes.first() > 60_000) repositionTimes.removeFirst()
                    if (repositionTimes.size > MAX_REPOSITIONS_PER_MINUTE) {
                        endTrackingFromLoop(null, TRACKING_UNSTABLE_ALERT)
                        return
                    }
                    Diagnostics.log("Seguimiento: desviación de %.2f°, reposicionando".format(max(abs(errAz) * cos(alt), abs(errAlt)) / DEG))
                    client.gotoSteps(Axis.AZIMUTH, angleToSteps(Axis.AZIMUTH, chooseAzTarget(az, e1.first)))
                    client.gotoSteps(Axis.ALTITUDE, angleToSteps(Axis.ALTITUDE, e1.second))
                    waitForGotoCompletion()
                    continue
                }
                val ffAz = Angles.normPi(e1.first - e0.first) / dt
                val ffAlt = (e1.second - e0.second) / dt
                lastFeedForward = ffAz to ffAlt
                lastTrackRates = when {
                    centering -> ffAz to ffAlt
                    imaging -> {
                        // Corrección suave y acotada: como mucho ±[IMAGING_MAX_CORRECTION] sobre la
                        // velocidad prevista, para que las estrellas no se alarguen en la foto.
                        val lim = IMAGING_MAX_CORRECTION
                        (ffAz + (IMAGING_GAIN * errAz).coerceIn(-lim, lim)) to (ffAlt + (IMAGING_GAIN * errAlt).coerceIn(-lim, lim))
                    }
                    else -> (ffAz + TRACK_GAIN * errAz) to (ffAlt + TRACK_GAIN * errAlt)
                }
                applyRates()
                _state.update { if (it.lastError != null) it.copy(lastError = null) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StallException) {
                endTrackingFromLoop(null, e.message)
                return
            } catch (e: Exception) {
                _state.update { it.copy(lastError = "Seguimiento: ${e.message}") }
            }
            delay(cfg.trackingUpdateMs)
        }
    }

    /**
     * El propio bucle de seguimiento termina (límite de altura, atasco…): para los motores y deja
     * el estado limpio. Antes quedaba [trackSpec] puesto y el pad seguía sumando la última
     * velocidad de seguimiento aunque ya nadie la actualizara.
     */
    private suspend fun endTrackingFromLoop(message: String?, alert: String? = null) {
        trackSpec = null
        lastTrackRates = 0.0 to 0.0
        lastFeedForward = 0.0 to 0.0
        holdFeedback = false
        manualRates = 0.0 to 0.0
        runCatching { client.stopAll() }
        Diagnostics.log("Seguimiento terminado: ${message ?: alert}")
        _state.update {
            it.copy(activity = Activity.IDLE, tracking = null, message = message ?: it.message, alert = alert ?: it.alert)
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
        if (rates.second > 0 && (st.pointing?.altDeg ?: 0.0) >= config().maxAltitudeDeg) {
            _state.update { it.copy(message = "Límite de altura máxima alcanzado") }
            return
        }
        if (approachingSun(rates)) {
            _state.update { it.copy(message = SUN_APPROACH_MESSAGE) }
            return
        }
        manualStartedAtMs = System.currentTimeMillis()
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
        // Sin movimiento manual en curso no hay nada que soltar (p. ej. un cliente de Stellarium que
        // se desconecta): no se toca un GoTo o un seguimiento que estén en marcha.
        if (manualRates == (0.0 to 0.0) && _state.value.activity != Activity.MANUAL) return
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
        if (manualRates.second > 0 && alt > config().maxAltitudeDeg) {
            manualRates = manualRates.first to 0.0
            if (trackSpec != null) applyRates() else client.stop(Axis.ALTITUDE)
            _state.update { it.copy(message = "Límite de altura máxima alcanzado") }
        }
        if (manualRates != (0.0 to 0.0) && approachingSun(manualRates)) {
            Diagnostics.log("Pad detenido: se acercaba al Sol")
            manualStop()
            _state.update { it.copy(message = SUN_APPROACH_MESSAGE) }
        }
    }

    /**
     * De día, ¿este movimiento manual acerca el tubo al Sol por debajo del límite? Se permite
     * alejarse (para no dejar al usuario atrapado) y todo si confirmó usar filtro solar.
     */
    private fun approachingSun(rates: Pair<Double, Double>): Boolean {
        if (sunApproved()) return false
        val now = clock.now()
        val sun = sunIfUp(now) ?: return false
        val enc = _state.value.let { (it.encoderAzDeg ?: return false) * DEG to (it.encoderAltDeg ?: return false) * DEG }
        val here = alignment.mountToSky(enc.first, enc.second).separation(sun) / DEG
        val ahead = alignment.mountToSky(enc.first + rates.first * SUN_LOOKAHEAD_S, enc.second + rates.second * SUN_LOOKAHEAD_S).separation(sun) / DEG
        return ahead < config().sunAvoidanceDeg && ahead < here
    }

    /** Hombre muerto: ningún movimiento manual sigue indefinidamente (pad atorado, cliente LX200 caído). */
    private suspend fun checkManualDeadman() {
        if (manualRates == (0.0 to 0.0)) return
        val held = System.currentTimeMillis() - manualStartedAtMs
        if (held > config().maxManualMs) {
            Diagnostics.log("Hombre muerto: movimiento manual de ${held / 1000} s detenido")
            manualStop()
            _state.update { it.copy(message = "Movimiento manual detenido por seguridad tras %d s seguidos. Vuelve a pulsar si necesitas más.".format(config().maxManualMs / 1000)) }
        }
    }

    /**
     * Atasco durante pad o seguimiento rápido: se ordena mover a cierta velocidad pero el encoder
     * no avanza. Compara lo esperado (velocidad × tiempo) con lo medido en una ventana.
     */
    private suspend fun checkMotionStall() {
        val st = _state.value
        val az = (st.encoderAzDeg ?: return) * DEG
        val alt = (st.encoderAltDeg ?: return) * DEG
        val now = System.currentTimeMillis()
        val rAz = if (st.activity == Activity.MANUAL || st.activity == Activity.TRACKING) lastTrackRates.first + manualRates.first else 0.0
        val rAlt = if (st.activity == Activity.MANUAL || st.activity == Activity.TRACKING) lastTrackRates.second + manualRates.second else 0.0
        motionHistory.addLast(MotionSample(now, az, alt, rAz, rAlt))
        val window = config().stallWindowMs
        while (motionHistory.size > 2 && now - motionHistory.first().ms > window) motionHistory.removeFirst()
        val first = motionHistory.first()
        if (now - first.ms < window * 0.8) return
        // Solo si la orden fue constante en signo durante toda la ventana.
        fun steady(sel: (MotionSample) -> Double) = motionHistory.all { sel(it) != 0.0 && sign(sel(it)) == sign(sel(first)) }
        fun expected(sel: (MotionSample) -> Double): Double {
            var sum = 0.0
            motionHistory.zipWithNext { a, b -> sum += abs(sel(a)) * (b.ms - a.ms) / 1000.0 }
            return sum
        }
        val stalledAxis = when {
            steady { it.rAz } && expected { it.rAz } > STALL_CHECK_MIN_EXPECTED_DEG * DEG &&
                abs(Angles.normPi(az - first.az)) < 0.15 * expected { it.rAz } -> Axis.AZIMUTH
            steady { it.rAlt } && expected { it.rAlt } > STALL_CHECK_MIN_EXPECTED_DEG * DEG &&
                abs(alt - first.alt) < 0.15 * expected { it.rAlt } -> Axis.ALTITUDE
            else -> null
        } ?: return
        motionHistory.clear()
        Diagnostics.log("ATASCO detectado en ${stalledAxis.label} durante ${st.activity}")
        stopAll()
        _state.update { it.copy(alert = stallMessage(stalledAxis)) }
    }

    /**
     * Cambiar «Invertir acimut/altura» con la montura conectada invierte de golpe la lectura de
     * los encoders: la posición y la alineación dejan de valer. Se detiene todo y se pide
     * confirmar de nuevo la posición de inicio.
     */
    private suspend fun checkAxisConfigChange() {
        val signs = config().let { it.invertAzimuth to it.invertAltitude }
        val previous = lastAxisSigns
        lastAxisSigns = signs
        if (previous == null || previous == signs) return
        Diagnostics.log("Dirección de ejes cambiada en plena sesión: $previous → $signs")
        stopAll()
        alignment.reset()
        lastAxisRead.clear()
        _state.update { it.copy(startPosition = StartPosition.UNCONFIRMED, alert = AXIS_CHANGED_ALERT) }
    }

    /**
     * La montura se reinició (baterías, cable): sus contadores volvieron a cero y la posición que
     * creía la app ya no vale. Se detiene todo, se reinicializa y se exige confirmar el inicio.
     */
    private suspend fun handleMountReset(reason: String) {
        Diagnostics.log("REINICIO DE LA MONTURA detectado: $reason")
        stopAll()
        runCatching { client.initialize() }
        alignment.reset()
        lastAxisRead.clear()
        _state.update {
            it.copy(startPosition = StartPosition.UNCONFIRMED, activity = Activity.IDLE, target = null, tracking = null,
                alert = MOUNT_RESET_ALERT)
        }
    }

    // ---------------------------------------------------------------- sincronización

    /**
     * Toma una muestra: el objetivo está centrado en el ocular ahora mismo.
     * @param asAlignmentStar true para estrellas del proceso de alineación; false para "Sync Samples".
     */
    suspend fun sync(target: Target, asAlignmentStar: Boolean): AlignmentState {
        if (!siteKnown()) throw MountException(NO_LOCATION_MESSAGE)
        if (_state.value.activity == Activity.SLEWING) throw MountException("Espera a que termine el GoTo antes de confirmar la estrella")
        val (az, alt) = readEncoders()
        val now = clock.now(); val s = site()
        val pos = TargetResolver.position(target, now, s.location, s.weather)
        val sample = AlignmentSample(target.name, pos.horizontal, az, alt)
        val dev = deviationDeg(pos.horizontal, az, alt)
        Diagnostics.log("SYNC ${target.name} (${if (asAlignmentStar) "alineación" else "sync"}): cielo Az %.2f° Alt %.2f°, encoders Az %.2f° Alt %.2f°, desvío respecto al modelo %s".format(
            pos.horizontal.azDeg, pos.horizontal.altDeg, az / DEG, alt / DEG, dev?.let { "%.2f°".format(it) } ?: "—"))
        val result = if (asAlignmentStar) alignment.addAlignmentStar(sample) else alignment.addSyncSample(sample)
        centeringOffset = 0.0 to 0.0
        refreshPointing()
        _state.update { it.copy(message = "Sincronizado con ${target.name}") }
        return result
    }

    /**
     * ¿Cuánto difiere la posición actual del tubo de donde el modelo esperaba encontrar [target]?
     * Sirve para avisar ANTES de confirmar una estrella equivocada (el error humano más común).
     */
    suspend fun syncDeviationDeg(target: Target): Double? {
        val (az, alt) = readEncoders()
        val s = site()
        val pos = TargetResolver.position(target, clock.now(), s.location, s.weather)
        return deviationDeg(pos.horizontal, az, alt)
    }

    private fun deviationDeg(sky: HorizontalCoord, az: Double, alt: Double): Double? {
        val expected = alignment.skyToMount(sky) ?: return null
        return hypot(Angles.normPi(az - expected.first) * cos(alt), alt - expected.second) / DEG
    }

    /**
     * Desvío máximo razonable para confirmar una estrella, según lo que ya sabe la app: con 2 o más
     * estrellas el modelo es bueno (3°); con 1, aún no conoce la inclinación de la base (8°); sin
     * estrellas pero con posición de inicio confirmada, lo que da apuntar a ojo hacia el norte (20°).
     * null si no hay referencia para comparar.
     */
    fun syncDeviationLimitDeg(): Double? {
        val n = alignment.state.value.allSamples.size
        return when {
            n >= 2 -> 3.0
            n == 1 -> 8.0
            _state.value.startPosition != StartPosition.UNCONFIRMED -> 20.0
            else -> null
        }
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
        const val NO_LOCATION_MESSAGE =
            "Todavía no hay ubicación (GPS o coordenadas manuales). Sin ella la app calcularía las posiciones para otro " +
                "lugar del planeta: espera el GPS o escribe tus coordenadas en Ajustes."
        const val MOUNT_RESET_ALERT =
            "La montura se reinició (¿baterías bajas o cable flojo?) y perdió su posición. Se detuvo todo y se borró la " +
                "alineación. Revisa la alimentación, confirma de nuevo la posición de inicio y vuelve a alinear."
        const val STOP_NOT_CONFIRMED_ALERT =
            "¡La montura no confirmó la orden de DETENER! Puede seguir moviéndose. Apágala con su interruptor y revisa la conexión Wi-Fi."
        const val AXIS_CHANGED_ALERT =
            "Cambiaste la dirección de un eje con la montura conectada: la posición y la alineación anteriores ya no valen. " +
                "Confirma de nuevo la posición de inicio y vuelve a alinear."
        const val TRACKING_UNSTABLE_ALERT =
            "El seguimiento tuvo que reposicionar el tubo varias veces en un minuto: algo lo frena o la alineación es mala. " +
                "Se detuvo. Revisa cables, cámara y embrague, y repite la alineación si hace falta."
        const val SUN_APPROACH_MESSAGE =
            "Movimiento detenido: el tubo se acercaba al Sol. Aléjalo en otra dirección o haz GoTo al Sol confirmando que tienes filtro."
        /** Velocidad física máxima creíble de un eje (°/s) y margen para lecturas plausibles. */
        const val PLAUSIBLE_RATE_DEG_S = 8.0
        const val PLAUSIBLE_MARGIN_DEG = 1.0
        /** Un eje «en marcha» que avanza menos que esto en la ventana de vigilancia está atascado. */
        const val STALL_MIN_DEG = 0.02
        const val STALL_GRACE_MS = 1_500L
        const val STALL_CHECK_MIN_EXPECTED_DEG = 0.3
        const val MAX_REPOSITIONS_PER_MINUTE = 3
        /** Límite de vueltas en acimut desde la posición de inicio (protege cables). */
        val AZ_WRAP_LIMIT = Math.toRadians(270.0)
        const val PATH_SAMPLES = 40
        const val SUN_LOOKAHEAD_S = 2.0
        /** Tras confirmar el aviso del Sol (filtro puesto) se permite trabajar cerca de él este tiempo. */
        const val SUN_APPROVAL_MS = 3 * 3600_000L
        /** Diferencia máxima aceptada entre la altura creída y la medida por el teléfono. */
        const val ALTITUDE_CHECK_TOLERANCE_DEG = 5.0
        const val START_UNCONFIRMED_MESSAGE =
            "Primero confirma la posición de inicio en Inicio: deja el tubo horizontal hacia el norte y pulsa «El tubo está " +
                "horizontal hacia el norte», o mide con el teléfono sobre el tubo. Sin eso el GoTo podría ir a cualquier lado."
        /** Ganancia del modo cámara (1/s): corrige un error en ~10 s en lugar de ~2 s. */
        const val IMAGING_GAIN = 0.1
        /** Corrección máxima del modo cámara: 0.5× sidérea (≈7.5″/s). */
        const val IMAGING_MAX_CORRECTION = 0.5 * MountClient.SIDEREAL_RATE_RAD_S
    }
}

/** Un eje recibe órdenes de movimiento pero no avanza (obstáculo, embrague, baterías). */
class StallException(message: String) : MountException(message)
