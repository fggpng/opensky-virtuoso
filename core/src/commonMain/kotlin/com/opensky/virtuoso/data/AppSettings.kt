package com.opensky.virtuoso.data

import com.opensky.virtuoso.astronomy.ephemeris.MpcParser
import com.opensky.virtuoso.control.ControllerConfig
import com.opensky.virtuoso.control.Equipment
import com.opensky.virtuoso.control.ObservingMode
import com.opensky.virtuoso.control.OpticalSetup
import com.opensky.virtuoso.control.PadMode
import com.opensky.virtuoso.network.bridge.CoordinateEpoch
import com.opensky.virtuoso.sensors.PhoneMounting

enum class MountTransportType { UDP, TCP }

/** Configuración persistente de la app (DataStore en Android, UserDefaults en iOS). */
data class AppSettings(
    // Conexión
    val mountHost: String = "192.168.4.1",
    val mountPort: Int = 11880,
    val transport: MountTransportType = MountTransportType.UDP,
    val ssidPrefix: String = "SynScan_",
    val wifiPassphrase: String = "",
    // Seguridad y mecánica
    val minAltitudeDeg: Double = 0.0,
    val maxAltitudeDeg: Double = 89.0,
    val sunAvoidanceDeg: Double = 15.0,
    val maxSlewRateDegPerSec: Double = 3.5,
    val invertAzimuth: Boolean = false,
    val invertAltitude: Boolean = false,
    // Lugar y tiempo
    val useManualLocation: Boolean = false,
    val manualLatitude: Double = 0.0,
    val manualLongitude: Double = 0.0,
    val manualElevationM: Double = 0.0,
    val useSystemTimeZone: Boolean = true,
    val manualUtcOffsetHours: Double = -6.0,
    val dstEnabled: Boolean = false,
    val ntpServer: String = "time.google.com",
    val pressureHpa: Double = 1010.0,
    val temperatureC: Double = 10.0,
    // Sensores
    val phoneMounting: PhoneMounting = PhoneMounting.TOP_EDGE_ALONG_TUBE,
    // Equipo de observación: decide el campo de referencia (% de precisión), el modo de seguimiento
    // (ocular o cámara), la velocidad del pad por defecto y la exposición máxima por rotación de campo.
    val observingMode: ObservingMode = ObservingMode.VISUAL,
    val eyepieceId: String = Equipment.EYEPIECES.first().id,
    val barlowFactor: Double = 2.0,
    // Puente Stellarium
    val lx200Enabled: Boolean = true,
    val lx200Port: Int = 4030,
    val lx200Epoch: CoordinateEpoch = CoordinateEpoch.JNOW,
    val stellariumEnabled: Boolean = true,
    val stellariumPort: Int = 10001,
    val stellariumEpoch: CoordinateEpoch = CoordinateEpoch.J2000,
    // Interfaz
    val nightMode: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Efecto cristal (Liquid Glass) con el tema azul. Solo se aplica en Android 16 o superior. */
    val glassDay: Boolean = true,
    /** Efecto cristal con el modo nocturno rojo (apagado de fábrica: cuida batería y visión nocturna). */
    val glassNight: Boolean = false,
    val padRateIndex: Int = 5,
    val padMode: PadMode = PadMode.ALT_AZ,
    val cometUrl: String = MpcParser.COMET_ELEMENTS_URL,
) {
    fun opticalSetup() = OpticalSetup(observingMode, Equipment.eyepiece(eyepieceId), barlowFactor)

    fun controllerConfig() = ControllerConfig(
        minAltitudeDeg = minAltitudeDeg, maxAltitudeDeg = maxAltitudeDeg,
        invertAzimuth = invertAzimuth, invertAltitude = invertAltitude,
        sunAvoidanceDeg = sunAvoidanceDeg, maxRateDegPerSec = maxSlewRateDegPerSec,
        trackingMode = opticalSetup().trackingMode,
    )
}
