package com.opensky.virtuoso.network

import com.opensky.virtuoso.control.Diagnostics
import com.opensky.virtuoso.network.protocol.Axis
import com.opensky.virtuoso.network.protocol.AxisParameters
import com.opensky.virtuoso.network.protocol.AxisStatus
import com.opensky.virtuoso.network.protocol.MountException
import com.opensky.virtuoso.network.protocol.MountInfo
import com.opensky.virtuoso.network.protocol.MountProtocolException
import com.opensky.virtuoso.network.protocol.MountTimeoutException
import com.opensky.virtuoso.network.protocol.SkyWatcherCodec
import com.opensky.virtuoso.network.protocol.SwCommand
import com.opensky.virtuoso.network.transport.MountTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.abs
import kotlin.math.roundToLong

/** Estado del enlace con la montura. */
enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, RETRYING, LOST }

/**
 * Cliente del protocolo Sky-Watcher con reintentos, revalidación de tramas, reconexión
 * automática del socket y serialización de comandos (una petición en vuelo a la vez).
 */
class MountClient(
    private val transport: MountTransport,
    private val config: Config = Config(),
) {
    data class Config(
        val timeoutMs: Int = 350,
        val maxAttempts: Int = 4,
        val reconnectBackoffMs: Long = 200,
        /** Pasos de frenado antes del objetivo en GOTO (valor del SDK de Sky-Watcher). */
        val breakSteps: Int = 3500,
    )

    data class Stats(val commands: Long = 0, val retries: Long = 0, val reconnects: Long = 0)

    private val mutex = Mutex()
    private val _linkState = MutableStateFlow(LinkState.DISCONNECTED)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    @Volatile var info: MountInfo? = null
        private set

    /** Último modo de velocidad constante enviado por eje (evita tráfico redundante). */
    private val rateCache = mutableMapOf<Axis, RateCommand>()

    private data class RateCommand(val period: Int, val fast: Boolean, val reverse: Boolean)

    val transportDescription: String get() = transport.description

    /** Abre el transporte, lee los parámetros de la montura e inicializa los motores. */
    suspend fun connect(): MountInfo {
        _linkState.value = LinkState.CONNECTING
        try {
            transport.open()
            val mountInfo = readInfo()
            send(SwCommand.INITIALIZE, Axis.BOTH)
            info = mountInfo
            // Si una sesión anterior terminó mal (app cerrada a la fuerza, Wi-Fi caída) los motores
            // pueden seguir girando con la última orden: se detienen antes de nada.
            stopAll()
            _linkState.value = LinkState.CONNECTED
            Diagnostics.log("CONEXIÓN ${transport.description}: firmware ${mountInfo.firmwareVersion}, modelo ${mountInfo.modelName}, " +
                "pasos/vuelta Az=${mountInfo.azimuth.countsPerRevolution} Alt=${mountInfo.altitude.countsPerRevolution}, " +
                "timer Az=${mountInfo.azimuth.timerFrequency} Alt=${mountInfo.altitude.timerFrequency}")
            return mountInfo
        } catch (e: Exception) {
            _linkState.value = LinkState.LOST
            throw e
        }
    }

    /** Reenvía la inicialización de motores (tras detectar que la montura se reinició). */
    suspend fun initialize() {
        send(SwCommand.INITIALIZE, Axis.BOTH)
        rateCache.clear()
    }

    fun disconnect() {
        transport.close()
        rateCache.clear()
        info = null
        _linkState.value = LinkState.DISCONNECTED
    }

    /**
     * Envía un comando y devuelve los datos de la respuesta.
     * Reintenta ante timeouts o tramas corruptas; reabre el socket ante errores de E/S.
     * Los errores "!n" de la montura NO se reintentan (son deterministas).
     */
    suspend fun send(command: SwCommand, axis: Axis, data: String = ""): String = mutex.withLock {
        val request = SkyWatcherCodec.buildCommand(command, axis, data)
        var lastError: Exception? = null
        for (attempt in 1..config.maxAttempts) {
            try {
                if (!transport.isOpen) {
                    transport.open()
                    _stats.value = _stats.value.copy(reconnects = _stats.value.reconnects + 1)
                }
                val raw = transport.exchange(request, config.timeoutMs)
                val result = SkyWatcherCodec.parseResponse(raw, command, request)
                Diagnostics.traffic("→ ${request.trim()} ← ${raw.trim()}")
                _stats.value = _stats.value.copy(commands = _stats.value.commands + 1)
                if (_linkState.value != LinkState.CONNECTING) _linkState.value = LinkState.CONNECTED
                return@withLock result
            } catch (e: MountProtocolException) {
                Diagnostics.log("ERROR de la montura a ${request.trim()}: ${e.message}")
                throw e
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: IllegalArgumentException) {
                lastError = e // trama corrupta o desfasada: se reintenta
            } catch (e: IOException) {
                lastError = e
                transport.close()
                delay(config.reconnectBackoffMs * attempt)
            }
            if (attempt < config.maxAttempts) {
                _stats.value = _stats.value.copy(retries = _stats.value.retries + 1)
                if (_linkState.value == LinkState.CONNECTED) _linkState.value = LinkState.RETRYING
            }
        }
        _linkState.value = LinkState.LOST
        Diagnostics.log("SIN RESPUESTA a ${request.trim()} tras ${config.maxAttempts} intentos (${lastError?.javaClass?.simpleName}: ${lastError?.message})")
        throw MountTimeoutException(request.trim(), config.maxAttempts, lastError)
    }

    suspend fun readInfo(): MountInfo {
        val version = send(SwCommand.INQUIRE_MOTOR_BOARD_VERSION, Axis.AZIMUTH)
        suspend fun params(axis: Axis) = AxisParameters(
            countsPerRevolution = SkyWatcherCodec.decodeLittleEndian(send(SwCommand.INQUIRE_COUNTS_PER_REVOLUTION, axis)),
            timerFrequency = SkyWatcherCodec.decodeLittleEndian(send(SwCommand.INQUIRE_TIMER_FREQUENCY, axis)),
            highSpeedRatio = SkyWatcherCodec.decodeLittleEndian(send(SwCommand.INQUIRE_HIGH_SPEED_RATIO, axis)).coerceAtLeast(1),
        )
        return MountInfo(
            firmwareMajor = version.substring(0, 2).toInt(16),
            firmwareMinor = version.substring(2, 4).toInt(16),
            mountCode = version.substring(4, 6).toInt(16),
            azimuth = params(Axis.AZIMUTH),
            altitude = params(Axis.ALTITUDE),
        )
    }

    private fun requireInfo(): MountInfo = info ?: throw MountException("Montura no inicializada")

    suspend fun status(axis: Axis): AxisStatus = AxisStatus.parse(send(SwCommand.GET_STATUS, axis))

    suspend fun position(axis: Axis): Int = SkyWatcherCodec.decodePosition(send(SwCommand.GET_POSITION, axis))

    /** Fija el contador de posición (solo con el eje detenido). */
    suspend fun setPosition(axis: Axis, steps: Int) {
        stopAndWait(axis)
        send(SwCommand.SET_POSITION, axis, SkyWatcherCodec.encodePosition(steps))
    }

    suspend fun stop(axis: Axis, instant: Boolean = false) {
        rateCache.remove(axis)
        send(if (instant) SwCommand.INSTANT_STOP else SwCommand.STOP, axis)
    }

    /**
     * Detiene ambos ejes. Devuelve false si alguna orden de parada no fue confirmada por la
     * montura (enlace caído): quien llama debe avisar al usuario, nunca suponer que se detuvo.
     */
    suspend fun stopAll(instant: Boolean = false): Boolean {
        var ok = true
        Axis.MOTION_AXES.forEach { axis ->
            runCatching { stop(axis, instant) }.onFailure { ok = false }
        }
        return ok
    }

    suspend fun stopAndWait(axis: Axis, timeoutMs: Long = 20_000) {
        if (status(axis).running) {
            stop(axis)
            waitUntilStopped(axis, timeoutMs)
        }
    }

    suspend fun waitUntilStopped(axis: Axis, timeoutMs: Long = 120_000, pollMs: Long = 150) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (status(axis).running) {
            if (System.currentTimeMillis() > deadline) throw MountException("El eje ${axis.label} no se detuvo a tiempo")
            delay(pollMs)
        }
    }

    /**
     * GOTO absoluto de un eje a [targetSteps] usando incremento relativo (":H"), compatible
     * con todas las versiones de firmware. Elige GOTO rápido o lento según la distancia.
     */
    suspend fun gotoSteps(axis: Axis, targetSteps: Int) {
        val p = requireInfo().axis(axis)
        stopAndWait(axis)
        rateCache.remove(axis)
        val delta = targetSteps - position(axis)
        if (delta == 0) return
        val steps = abs(delta)
        val lowSpeedMargin = (640 * SIDEREAL_RATE_RAD_S * p.stepsPerRadian).toInt()
        val fast = steps > lowSpeedMargin
        send(SwCommand.SET_MOTION_MODE, axis, SkyWatcherCodec.motionModeData(goto = true, fast = fast, reverse = delta < 0))
        send(SwCommand.SET_GOTO_TARGET_INCREMENT, axis, SkyWatcherCodec.encode24(steps.coerceAtMost(0xFFFFFF)))
        send(SwCommand.SET_BREAK_POINT_INCREMENT, axis, SkyWatcherCodec.encode24(config.breakSteps))
        send(SwCommand.START_MOTION, axis)
    }

    /**
     * Movimiento a velocidad constante (pad manual y seguimiento). [radPerSec] con signo:
     * positivo = sentido de pasos crecientes. 0 detiene el eje.
     */
    suspend fun setRate(axis: Axis, radPerSec: Double) {
        val p = requireInfo().axis(axis)
        val speed = abs(radPerSec)
        if (speed < MIN_RATE_RAD_S) {
            if (rateCache.containsKey(axis) || status(axis).running) stop(axis)
            return
        }
        val reverse = radPerSec < 0
        val fast = speed > HIGH_SPEED_THRESHOLD_RAD_S
        val effective = if (fast) speed / p.highSpeedRatio else speed
        val period = (p.timerFrequency / (effective * p.stepsPerRadian)).roundToLong()
            .coerceIn(MIN_STEP_PERIOD.toLong(), 0xFFFFFFL).toInt()
        val wanted = RateCommand(period, fast, reverse)
        val cached = rateCache[axis]
        if (cached == wanted) return

        if (cached != null && cached.fast == fast && cached.reverse == reverse) {
            // Mismo modo y sentido: basta con actualizar el periodo al vuelo.
            send(SwCommand.SET_STEP_PERIOD, axis, SkyWatcherCodec.encode24(period))
        } else {
            val st = status(axis)
            if (st.running) {
                stop(axis)
                waitUntilStopped(axis, 10_000, 60)
            }
            send(SwCommand.SET_MOTION_MODE, axis, SkyWatcherCodec.motionModeData(goto = false, fast = fast, reverse = reverse))
            send(SwCommand.SET_STEP_PERIOD, axis, SkyWatcherCodec.encode24(period))
            send(SwCommand.START_MOTION, axis)
        }
        rateCache[axis] = wanted
    }

    /** Invalida la caché de velocidades (p. ej. tras un GOTO o un cambio externo). */
    fun invalidateRateCache() = rateCache.clear()

    companion object {
        /** Velocidad sidérea en rad/s (15.041″/s). */
        const val SIDEREAL_RATE_RAD_S = 7.292115855e-5
        const val HIGH_SPEED_THRESHOLD_RAD_S = 128 * SIDEREAL_RATE_RAD_S
        const val MIN_RATE_RAD_S = 0.01 * SIDEREAL_RATE_RAD_S
        const val MIN_STEP_PERIOD = 6
    }
}
