package com.opensky.virtuoso.network.protocol

/**
 * Sky-Watcher "Motor Controller Command Set" (protocolo de la tarjeta de motores usado por
 * AZ-GTi / Virtuoso GTi, también vía módulo Wi-Fi SynScan en UDP 192.168.4.1:11880).
 *
 * Trama de petición:  ':' + comando + eje + datos + '\r'      p. ej. ":j1\r"
 * Respuesta correcta: '=' + datos + '\r'                       p. ej. "=A1B2C3\r"
 * Respuesta de error: '!' + código + '\r'                      p. ej. "!2\r"
 *
 * Los enteros de 24 bits viajan como 6 dígitos hexadecimales en orden little-endian
 * (0x123456 → "563412"). Las posiciones llevan un desplazamiento de 0x800000 (= paso 0).
 *
 * Nota: comandos como ":GZ#" pertenecen a LX200 (ver paquete lx200), no a este protocolo.
 */
enum class Axis(val code: Char, val label: String) {
    /** Eje 1: acimut (AR en modo ecuatorial). */
    AZIMUTH('1', "Az"),
    /** Eje 2: altura (Dec en modo ecuatorial). */
    ALTITUDE('2', "Alt"),
    /** Ambos ejes (solo para algunos comandos, p. ej. :F3). */
    BOTH('3', "Az+Alt");

    companion object {
        val MOTION_AXES = listOf(AZIMUTH, ALTITUDE)
    }
}

/** Comandos soportados y longitud de datos esperada en la respuesta (para validar tramas UDP). */
enum class SwCommand(val code: Char, val responseDataLength: Int) {
    INITIALIZE('F', 0),
    INQUIRE_MOTOR_BOARD_VERSION('e', 6),
    INQUIRE_COUNTS_PER_REVOLUTION('a', 6),
    INQUIRE_TIMER_FREQUENCY('b', 6),
    INQUIRE_HIGH_SPEED_RATIO('g', 2),
    INQUIRE_PEC_PERIOD('s', 6),
    INSTANT_STOP('L', 0),
    STOP('K', 0),
    SET_POSITION('E', 0),
    GET_POSITION('j', 6),
    GET_STATUS('f', 3),
    SET_MOTION_MODE('G', 0),
    SET_GOTO_TARGET_INCREMENT('H', 0),
    SET_BREAK_POINT_INCREMENT('M', 0),
    SET_GOTO_TARGET('S', 0),
    SET_STEP_PERIOD('I', 0),
    START_MOTION('J', 0),
    SET_AUX_SWITCH('O', 0),
    SET_ST4_GUIDE_RATE('P', 0);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(c: Char): SwCommand? = byCode[c]
    }
}

/** Códigos de error "!n" documentados por Sky-Watcher. */
enum class MountErrorCode(val code: Int, val description: String) {
    UNKNOWN_COMMAND(0, "Comando desconocido"),
    COMMAND_LENGTH(1, "Longitud de comando incorrecta"),
    MOTOR_NOT_STOPPED(2, "El motor no está detenido"),
    INVALID_CHARACTER(3, "Carácter inválido"),
    NOT_INITIALIZED(4, "Controlador no inicializado"),
    DRIVER_SLEEPING(5, "Driver en reposo"),
    PEC_TRAINING(7, "Entrenamiento PEC en curso"),
    NO_PEC_DATA(8, "Sin datos PEC válidos"),
    UNRECOGNIZED(-1, "Error no reconocido");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: UNRECOGNIZED
    }
}

open class MountException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MountProtocolException(val error: MountErrorCode, val command: String) :
    MountException("La montura respondió error ${error.code} (${error.description}) a $command")

class MountTimeoutException(command: String, attempts: Int, cause: Throwable? = null) :
    MountException("Sin respuesta válida a $command tras $attempts intentos", cause)

/** Estado de un eje decodificado de la respuesta a ":f". */
data class AxisStatus(
    /** true = modo velocidad constante (slew/seguimiento); false = modo GOTO. */
    val trackingMode: Boolean,
    val reverse: Boolean,
    val fast: Boolean,
    val running: Boolean,
    val blocked: Boolean,
    val initialized: Boolean,
    val levelSwitchOn: Boolean,
) {
    val isGotoRunning: Boolean get() = running && !trackingMode
    val isSlewing: Boolean get() = running && trackingMode

    companion object {
        fun parse(data: String): AxisStatus {
            require(data.length >= 3) { "Estado inválido: '$data'" }
            val n0 = data[0].digitToInt(16)
            val n1 = data[1].digitToInt(16)
            val n2 = data[2].digitToInt(16)
            return AxisStatus(
                trackingMode = n0 and 0x1 != 0,
                reverse = n0 and 0x2 != 0,
                fast = n0 and 0x4 != 0,
                running = n1 and 0x1 != 0,
                blocked = n1 and 0x2 != 0,
                initialized = n2 and 0x1 != 0,
                levelSwitchOn = n2 and 0x2 != 0,
            )
        }
    }
}

/** Parámetros mecánicos de un eje leídos de la montura. */
data class AxisParameters(
    /** Micropasos por revolución completa del eje (":a"). */
    val countsPerRevolution: Int,
    /** Frecuencia de la interrupción del temporizador en Hz (":b"). */
    val timerFrequency: Int,
    /** Multiplicador de velocidad en modo rápido (":g"). */
    val highSpeedRatio: Int,
) {
    val stepsPerRadian: Double get() = countsPerRevolution / (2 * Math.PI)
    val stepsPerDegree: Double get() = countsPerRevolution / 360.0
}

/** Información de la tarjeta de motores. */
data class MountInfo(
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val mountCode: Int,
    val azimuth: AxisParameters,
    val altitude: AxisParameters,
) {
    val firmwareVersion: String get() = "%d.%02d".format(firmwareMajor, firmwareMinor)
    val modelName: String get() = MOUNT_MODELS[mountCode] ?: "Desconocida (0x%02X)".format(mountCode)

    fun axis(axis: Axis): AxisParameters = if (axis == Axis.ALTITUDE) altitude else azimuth

    companion object {
        /** Códigos de montura conocidos (según el driver INDI de Sky-Watcher). */
        val MOUNT_MODELS = mapOf(
            0x00 to "EQ6", 0x01 to "HEQ5", 0x02 to "EQ5", 0x03 to "EQ3", 0x04 to "EQ8",
            0x05 to "AZ-EQ6", 0x06 to "AZ-EQ5", 0x0A to "Star Adventurer", 0x80 to "GT (Alt-Az)",
            0x81 to "MF", 0x82 to "114GT", 0x90 to "Dobson", 0xA2 to "AZ-GTe",
            0xA5 to "AZ-GTi / Virtuoso GTi",
        )
    }
}

/** Codificación/decodificación de tramas del protocolo. */
object SkyWatcherCodec {
    const val POSITION_OFFSET = 0x800000
    private const val HEX = "0123456789ABCDEF"

    fun buildCommand(command: SwCommand, axis: Axis, data: String = ""): String =
        ":${command.code}${axis.code}$data\r"

    /** Entero de 24 bits → 6 dígitos hex little-endian. */
    fun encode24(value: Int): String {
        require(value in 0..0xFFFFFF) { "Valor fuera de 24 bits: $value" }
        val sb = StringBuilder(6)
        for (byteIndex in 0..2) {
            val b = (value shr (8 * byteIndex)) and 0xFF
            sb.append(HEX[b shr 4]).append(HEX[b and 0xF])
        }
        return sb.toString()
    }

    /** 2, 4 o 6 dígitos hex little-endian → entero. */
    fun decodeLittleEndian(hex: String): Int {
        require(hex.length % 2 == 0 && hex.isNotEmpty()) { "Longitud hex inválida: '$hex'" }
        var value = 0
        for (i in hex.length / 2 - 1 downTo 0) {
            value = (value shl 8) or hex.substring(2 * i, 2 * i + 2).toInt(16)
        }
        return value
    }

    fun encodePosition(steps: Int): String = encode24((steps + POSITION_OFFSET) and 0xFFFFFF)

    fun decodePosition(hex: String): Int = decodeLittleEndian(hex) - POSITION_OFFSET

    /** Datos del modo de movimiento ":G". */
    fun motionModeData(goto: Boolean, fast: Boolean, reverse: Boolean): String {
        val func = when {
            goto && fast -> '0'   // GOTO rápido
            !goto && !fast -> '1' // velocidad constante lenta (seguimiento / pad)
            goto && !fast -> '2'  // GOTO lento
            else -> '3'           // velocidad constante rápida
        }
        return "$func${if (reverse) '1' else '0'}"
    }

    /**
     * Valida y separa una respuesta cruda.
     * @return los datos (sin '=' ni '\r'); lanza [MountProtocolException] si es "!n".
     * @throws IllegalArgumentException si la trama está mal formada o no coincide la longitud.
     */
    fun parseResponse(raw: String, command: SwCommand, request: String): String {
        val body = raw.trimEnd('\r', '\n')
        require(body.isNotEmpty()) { "Respuesta vacía" }
        return when (body[0]) {
            '=' -> {
                val data = body.substring(1)
                require(data.length == command.responseDataLength) {
                    "Longitud inesperada para ${command.code}: '$data'"
                }
                require(data.all { it in HEX }) { "Datos no hexadecimales: '$data'" }
                data
            }
            '!' -> throw MountProtocolException(
                MountErrorCode.fromCode(body.substring(1).toIntOrNull() ?: -1), request.trim(),
            )
            else -> throw IllegalArgumentException("Trama inválida: '$body'")
        }
    }
}
