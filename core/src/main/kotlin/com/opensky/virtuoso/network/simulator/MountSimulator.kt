package com.opensky.virtuoso.network.simulator

import com.opensky.virtuoso.network.protocol.SkyWatcherCodec
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Simulador UDP de la tarjeta de motores Sky-Watcher (AZ-GTi / Virtuoso GTi).
 *
 * Sirve para desarrollar y probar sin hardware: pruebas de integración, "modo demo" de la app
 * y reproducción de pérdidas de paquetes. Implementa los comandos usados por MountClient
 * con cinemática simplificada (aceleración instantánea).
 *
 * @param gotoTimeScale acelera solo los GOTO (útil en pruebas); el modo de velocidad constante
 *   (seguimiento/pad) siempre corre en tiempo real para validar las velocidades.
 * @param dropRate probabilidad de descartar una petición (simula pérdida UDP).
 */
class MountSimulator(
    private val requestedPort: Int = 0,
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    val countsPerRevolution: Int = 3_628_800,
    val timerFrequency: Int = 50_000,
    val highSpeedRatio: Int = 16,
    private val gotoFastDegPerSec: Double = 3.5,
    private val gotoSlowDegPerSec: Double = 0.25,
    @Volatile var gotoTimeScale: Double = 1.0,
    @Volatile var dropRate: Double = 0.0,
    private val firmware: String = "0322A5",
) : Closeable {

    private inner class AxisSim {
        var position = 0.0
        var running = false
        var gotoMode = false
        var fast = false
        var reverse = false
        var period = 0x1000
        var gotoIncrement = 0
        var absoluteTarget: Int? = null
        var target = 0.0
        var initialized = false
        /** Falla simulada: el motor recibe la orden pero el eje no avanza (obstáculo, baterías). */
        @Volatile var stalled = false
        private var lastNanos = System.nanoTime()

        @Synchronized
        fun advance() {
            val now = System.nanoTime()
            val dt = (now - lastNanos) * 1e-9
            lastNanos = now
            if (!running || dt <= 0 || stalled) return
            if (gotoMode) {
                val degPerSec = if (fast) gotoFastDegPerSec else gotoSlowDegPerSec
                val stepsPerSec = degPerSec * countsPerRevolution / 360.0
                val remaining = target - position
                val move = min(abs(remaining), stepsPerSec * dt * gotoTimeScale)
                position += Math.signum(remaining) * move
                if (abs(target - position) < 0.5) {
                    position = target
                    running = false
                }
            } else {
                val stepsPerSec = timerFrequency.toDouble() / period * (if (fast) highSpeedRatio else 1)
                position += (if (reverse) -1 else 1) * stepsPerSec * dt
            }
        }
    }

    private val axes = mapOf('1' to AxisSim(), '2' to AxisSim())
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(bindAddress, requestedPort))
    }
    private val random = Random(42)
    @Volatile private var closed = false
    private val thread = Thread(::serve, "MountSimulator").apply { isDaemon = true }

    /** Puerto UDP efectivo (útil cuando se pidió el puerto 0). */
    val boundPort: Int get() = socket.localPort

    fun start(): MountSimulator {
        thread.start()
        return this
    }

    /** Falla simulada: bloquea (o libera) un eje ('1' acimut, '2' altura). */
    fun setStalled(axis: Char, stalled: Boolean) { axes.getValue(axis).stalled = stalled }

    /** Falla simulada: la montura se reinicia (p. ej. baterías bajas): contadores a 0 y sin inicializar. */
    fun simulateReboot() {
        axes.values.forEach { it.advance(); it.running = false; it.position = 0.0; it.initialized = false }
    }

    /** ¿Está girando el eje? (para aserciones en pruebas). */
    fun isRunning(axis: Char): Boolean = axes.getValue(axis).also { it.advance() }.running

    /** Posición actual de un eje en pasos (para aserciones en pruebas). */
    fun positionOf(axis: Char): Double = axes.getValue(axis).also { it.advance() }.position

    private fun serve() {
        val buf = ByteArray(128)
        while (!closed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                break
            }
            if (dropRate > 0 && random.nextDouble() < dropRate) continue
            val request = String(packet.data, 0, packet.length, StandardCharsets.US_ASCII)
            val reply = handle(request).toByteArray(StandardCharsets.US_ASCII)
            runCatching { socket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
        }
    }

    /** Procesa una trama y devuelve la respuesta (visible para pruebas unitarias). */
    fun handle(request: String): String {
        if (request.length < 3 || request[0] != ':' || !request.endsWith("\r")) return "!1\r"
        val cmd = request[1]
        val axisChar = request[2]
        val data = request.substring(3, request.length - 1)
        val targets = when (axisChar) {
            '1', '2' -> listOf(axes.getValue(axisChar))
            '3' -> axes.values.toList()
            else -> return "!3\r"
        }
        targets.forEach { it.advance() }
        val a = targets.first()
        fun ok(payload: String = "") = "=$payload\r"
        return when (cmd) {
            'e' -> ok(firmware)
            'a' -> ok(SkyWatcherCodec.encode24(countsPerRevolution))
            'b' -> ok(SkyWatcherCodec.encode24(timerFrequency))
            'g' -> ok("%02X".format(highSpeedRatio))
            's' -> ok(SkyWatcherCodec.encode24(countsPerRevolution / 144))
            'F' -> { targets.forEach { it.initialized = true }; ok() }
            'f' -> {
                val n0 = (if (a.gotoMode) 0 else 1) or (if (a.reverse) 2 else 0) or (if (a.fast) 4 else 0)
                val n1 = if (a.running) 1 else 0
                val n2 = if (a.initialized) 1 else 0
                ok("%X%X%X".format(n0, n1, n2))
            }
            'j' -> ok(SkyWatcherCodec.encodePosition(a.position.roundToInt()))
            'E' -> if (a.running) "!2\r" else {
                a.position = SkyWatcherCodec.decodePosition(data).toDouble(); ok()
            }
            'G' -> if (a.running) "!2\r" else {
                if (data.length != 2) return "!1\r"
                val func = data[0].digitToInt(16)
                a.gotoMode = func and 0x1 == 0
                a.fast = if (a.gotoMode) func and 0x2 == 0 else func and 0x2 != 0
                a.reverse = data[1].digitToInt(16) and 0x1 != 0
                ok()
            }
            'H' -> { a.gotoIncrement = SkyWatcherCodec.decodeLittleEndian(data); a.absoluteTarget = null; ok() }
            'S' -> { a.absoluteTarget = SkyWatcherCodec.decodePosition(data); ok() }
            'M', 'P', 'O' -> ok()
            'I' -> if (a.running && a.gotoMode) "!2\r" else { a.period = SkyWatcherCodec.decodeLittleEndian(data).coerceAtLeast(1); ok() }
            'J' -> {
                targets.forEach { ax ->
                    if (ax.gotoMode) {
                        ax.target = ax.absoluteTarget?.toDouble()
                            ?: (ax.position + (if (ax.reverse) -1 else 1) * ax.gotoIncrement)
                    }
                    ax.running = true
                }
                ok()
            }
            'K', 'L' -> { targets.forEach { it.running = false }; ok() }
            else -> "!0\r"
        }
    }

    override fun close() {
        closed = true
        socket.close()
    }
}
