package com.opensky.virtuoso.network.lx200

import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.math.Angles
import com.opensky.virtuoso.control.PadDirection
import com.opensky.virtuoso.network.bridge.CoordinateEpoch
import com.opensky.virtuoso.network.bridge.TelescopeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Formato y parseo de coordenadas del protocolo Meade LX200. */
object Lx200Format {

    fun ra(hours: Double, high: Boolean): String {
        val h = Angles.norm360(hours * 15.0) / 15.0
        return if (high) {
            val (hh, mm, ss) = Angles.splitSexagesimal(h, 0)
            "%02d:%02d:%02d#".format(hh % 24, mm, ss.roundToInt())
        } else {
            val totalTenths = (h * 600).roundToInt() % (24 * 600)
            "%02d:%02d.%d#".format(totalTenths / 600, (totalTenths % 600) / 10, totalTenths % 10)
        }
    }

    fun dec(deg: Double, high: Boolean): String {
        val sign = if (deg < 0) '-' else '+'
        return if (high) {
            val (d, m, s) = Angles.splitSexagesimal(abs(deg).coerceAtMost(90.0), 0)
            "%c%02d*%02d'%02d#".format(sign, d, m, s.roundToInt())
        } else {
            val totalMin = (abs(deg) * 60).roundToInt()
            "%c%02d*%02d#".format(sign, totalMin / 60, totalMin % 60)
        }
    }

    /** Acimut (0–360) "DDD*MM'SS#". */
    fun azimuth(deg: Double): String {
        val (d, m, s) = Angles.splitSexagesimal(Angles.norm360(deg), 0)
        return "%03d*%02d'%02d#".format(d % 360, m, s.roundToInt())
    }

    /** "HH:MM:SS", "HH:MM.T" o "HH MM SS" → horas. */
    fun parseRa(text: String): Double? {
        val t = text.trim()
        Regex("""^(\d{1,2})[:\s](\d{1,2})\.(\d)$""").matchEntire(t)?.let { m ->
            val (h, mm, tenth) = m.destructured
            return h.toInt() + (mm.toInt() + tenth.toInt() / 10.0) / 60.0
        }
        return Regex("""^(\d{1,2})[:\s](\d{1,2})[:\s](\d{1,2}(?:\.\d+)?)$""").matchEntire(t)?.let { m ->
            val (h, mm, ss) = m.destructured
            h.toInt() + mm.toInt() / 60.0 + ss.toDouble() / 3600.0
        }?.takeIf { it < 24.0 }
    }

    /** "sDD*MM:SS", "sDD*MM'SS", "sDD:MM:SS", "sDD*MM" (también con º/ß) → grados. */
    fun parseDec(text: String): Double? {
        val t = text.trim().replace('ß', '*').replace('º', '*').replace('°', '*')
        val m = Regex("""^([+\-]?)(\d{1,2})[*:\s](\d{1,2})(?:[:'\s](\d{1,2}(?:\.\d+)?))?$""").matchEntire(t) ?: return null
        val (sign, d, mm, ss) = m.destructured
        val v = d.toInt() + mm.toInt() / 60.0 + (ss.ifEmpty { "0" }.toDouble()) / 3600.0
        return (if (sign == "-") -v else v).takeIf { abs(it) <= 90.0 }
    }

    /** "sDD*MM" → grados (latitud del sitio). */
    fun parseSiteAngle(text: String): Double? = parseDec(text) ?: Regex("""^([+\-]?)(\d{1,3})[*:](\d{1,2})$""")
        .matchEntire(text.trim())?.destructured?.let { (s, d, m) -> (d.toInt() + m.toInt() / 60.0) * if (s == "-") -1 else 1 }
}

/** Separa el flujo de bytes en comandos ":...#" y ACK (0x06). */
class Lx200Tokenizer {
    private val buffer = StringBuilder()

    fun feed(chunk: String): List<String> {
        val out = mutableListOf<String>()
        for (c in chunk) {
            when {
                c == '\u0006' && buffer.isEmpty() -> out += "\u0006"
                // ':' abre comando solo fuera de uno: dentro aparece en coordenadas (":Sr 12:00:00#")
                c == ':' && buffer.isEmpty() -> buffer.append(c)
                c == '#' && buffer.isNotEmpty() -> { out += buffer.toString(); buffer.setLength(0) }
                buffer.isNotEmpty() -> if (buffer.length < 64) buffer.append(c) else buffer.setLength(0)
            }
        }
        return out
    }
}

/**
 * Sesión LX200 de un cliente (Stellarium Mobile, SkySafari, etc.). Responde exactamente con los
 * bytes que espera el protocolo. Los GoTo se lanzan en segundo plano para no bloquear al cliente.
 */
class Lx200Session(
    private val bridge: TelescopeBridge,
    private val scope: CoroutineScope,
    private val epoch: CoordinateEpoch = CoordinateEpoch.JNOW,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private var highPrecision = true
    private var targetRaHours: Double? = null
    private var targetDecDeg: Double? = null
    private var rateIndex = 5 // 64x por defecto ("find")

    /** true si este cliente dejó un movimiento manual en marcha (se detiene si se desconecta). */
    @Volatile var hasActiveMove = false
        private set

    private fun current(): EquatorialCoord? = bridge.positionJ2000()?.let { bridge.toEpoch(it, epoch) }

    /** @return respuesta a enviar (puede ser vacía) o null si el comando no lleva respuesta. */
    suspend fun process(command: String): String? {
        if (command == "\u0006") return "A" // montura en modo Alt-Az
        val body = command.removePrefix(":")
        return when {
            body == "GR" -> current()?.let { Lx200Format.ra(it.raHours, highPrecision) } ?: "00:00:00#"
            body == "GD" -> current()?.let { Lx200Format.dec(it.decDeg, highPrecision) } ?: "+00*00'00#"
            body == "GA" -> bridge.horizontal()?.let { Lx200Format.dec(it.altDeg, true) } ?: "+00*00'00#"
            body == "GZ" -> bridge.horizontal()?.let { Lx200Format.azimuth(it.azDeg) } ?: "000*00'00#"
            body == "U" -> { highPrecision = !highPrecision; null }
            body == "P" -> { highPrecision = !highPrecision; if (highPrecision) "HIGH PRECISION" else "LOW PRECISION" }
            body.startsWith("Sr") -> Lx200Format.parseRa(body.substring(2))?.let { targetRaHours = it; "1" } ?: "0"
            body.startsWith("Sd") -> Lx200Format.parseDec(body.substring(2))?.let { targetDecDeg = it; "1" } ?: "0"
            body == "MS" -> slew()
            body == "CM" || body == "CMR" -> sync()
            // Mover y parar se ejecutan EN ORDEN (sin lanzarlos en paralelo): si «parar» llegara a
            // procesarse antes que «mover», el telescopio seguiría girando.
            body == "Q" -> { hasActiveMove = false; bridge.stop(); null }
            body in setOf("Qn", "Qs", "Qe", "Qw") -> { hasActiveMove = false; bridge.stopMove(); null }
            body in setOf("Mn", "Ms", "Me", "Mw") -> {
                val dir = when (body) { "Mn" -> PadDirection.UP; "Ms" -> PadDirection.DOWN; "Me" -> PadDirection.LEFT; else -> PadDirection.RIGHT }
                hasActiveMove = true
                bridge.move(dir, rateIndex); null
            }
            body == "RG" -> { rateIndex = 0; null }
            body == "RC" -> { rateIndex = 2; null }
            body == "RM" -> { rateIndex = 5; null }
            body == "RS" -> { rateIndex = 9; null }
            body == "D" -> if (bridge.isSlewing()) "\u007F#" else "#"
            body == "GVP" -> "OpenSky Virtuoso#"
            body == "GVN" -> "1.0#"
            body == "GVD" -> DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.US).format(localNow()) + "#"
            body == "GVT" -> DateTimeFormatter.ofPattern("HH:mm:ss").format(localNow()) + "#"
            body == "Gt" -> Lx200Format.dec(bridge.site().location.latitudeDeg, false)
            body == "Gg" -> {
                // LX200: longitud positiva hacia el OESTE, 0–360°
                val west = Angles.norm360(-bridge.site().location.longitudeDeg)
                val totalMin = (west * 60).roundToInt() % (360 * 60)
                "%03d*%02d#".format(totalMin / 60, totalMin % 60)
            }
            body == "GC" -> DateTimeFormatter.ofPattern("MM/dd/yy").format(localNow()) + "#"
            body == "GL" -> DateTimeFormatter.ofPattern("HH:mm:ss").format(localNow()) + "#"
            body == "Gc" -> "24#"
            body == "GG" -> {
                // Horas a SUMAR a la hora local para obtener UTC (México centro → +06)
                val offsetH = -localNow().offset.totalSeconds / 3600.0
                "%+03d#".format(offsetH.roundToInt())
            }
            body.startsWith("St") || body.startsWith("Sg") || body.startsWith("SL") ||
                body.startsWith("SG") || body.startsWith("Sw") -> "1" // el GPS del teléfono manda
            body.startsWith("SC") -> "1Updating Planetary Data#                              #"
            body == "GW" -> if (bridge.isSlewing()) "AN0#" else "AT1#"
            else -> null // LX200 ignora comandos desconocidos
        }
    }

    private fun localNow(): ZonedDateTime = Instant.ofEpochMilli(bridge.now().epochMillis).atZone(zoneProvider())

    private fun pendingTarget(): EquatorialCoord? {
        val ra = targetRaHours ?: return null
        val dec = targetDecDeg ?: return null
        return bridge.fromEpoch(EquatorialCoord.fromHoursDegrees(ra, dec), epoch)
    }

    private fun slew(): String {
        val target = pendingTarget() ?: return "2Sin objetivo#"
        bridge.precheckGoto(target)?.let { return "1$it#" }
        scope.launch { bridge.gotoJ2000(target) }
        return "0"
    }

    private suspend fun sync(): String {
        val target = pendingTarget() ?: return "Sin objetivo#"
        val error = bridge.syncJ2000(target)
        return if (error == null) "Coordinates matched#" else "$error#"
    }
}
