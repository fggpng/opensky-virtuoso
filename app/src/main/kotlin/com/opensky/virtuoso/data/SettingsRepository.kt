package com.opensky.virtuoso.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opensky.virtuoso.astronomy.ephemeris.MpcParser
import com.opensky.virtuoso.control.ControllerConfig
import com.opensky.virtuoso.control.Equipment
import com.opensky.virtuoso.control.ObservingMode
import com.opensky.virtuoso.control.OpticalSetup
import com.opensky.virtuoso.control.PadMode
import com.opensky.virtuoso.network.bridge.CoordinateEpoch
import com.opensky.virtuoso.sensors.PhoneMounting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class MountTransportType { UDP, TCP }

/** Configuración persistente de la app (DataStore Preferences). */
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

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {

    private val store = context.applicationContext.settingsStore

    val settings: StateFlow<AppSettings> = store.data
        .catch { emit(emptyPreferences()) } // archivo corrupto → valores por defecto
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val current: AppSettings get() = settings.value

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }

    private object K {
        val host = stringPreferencesKey("mount_host")
        val port = intPreferencesKey("mount_port")
        val transport = stringPreferencesKey("transport")
        val ssid = stringPreferencesKey("ssid_prefix")
        val pass = stringPreferencesKey("wifi_pass")
        val minAlt = doublePreferencesKey("min_alt")
        val maxAlt = doublePreferencesKey("max_alt")
        val sun = doublePreferencesKey("sun_avoid")
        val maxRate = doublePreferencesKey("max_rate")
        val invAz = booleanPreferencesKey("inv_az")
        val invAlt = booleanPreferencesKey("inv_alt")
        val manualLoc = booleanPreferencesKey("manual_loc")
        val lat = doublePreferencesKey("lat")
        val lon = doublePreferencesKey("lon")
        val elev = doublePreferencesKey("elev")
        val sysTz = booleanPreferencesKey("sys_tz")
        val utcOff = doublePreferencesKey("utc_offset")
        val dst = booleanPreferencesKey("dst")
        val ntp = stringPreferencesKey("ntp")
        val pressure = doublePreferencesKey("pressure")
        val temp = doublePreferencesKey("temp")
        val mounting = stringPreferencesKey("mounting")
        val obsMode = stringPreferencesKey("observing_mode")
        val eyepiece = stringPreferencesKey("eyepiece")
        val barlow = doublePreferencesKey("barlow_factor")
        val lxOn = booleanPreferencesKey("lx_on")
        val lxPort = intPreferencesKey("lx_port")
        val lxEpoch = stringPreferencesKey("lx_epoch")
        val stOn = booleanPreferencesKey("st_on")
        val stPort = intPreferencesKey("st_port")
        val stEpoch = stringPreferencesKey("st_epoch")
        val night = booleanPreferencesKey("night")
        val screenOn = booleanPreferencesKey("screen_on")
        val padRate = intPreferencesKey("pad_rate")
        val padMode = stringPreferencesKey("pad_mode")
        val cometUrl = stringPreferencesKey("comet_url")
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            mountHost = this[K.host] ?: d.mountHost,
            mountPort = this[K.port] ?: d.mountPort,
            transport = enumOf(this[K.transport], d.transport),
            ssidPrefix = this[K.ssid] ?: d.ssidPrefix,
            wifiPassphrase = this[K.pass] ?: d.wifiPassphrase,
            minAltitudeDeg = this[K.minAlt] ?: d.minAltitudeDeg,
            maxAltitudeDeg = this[K.maxAlt] ?: d.maxAltitudeDeg,
            sunAvoidanceDeg = this[K.sun] ?: d.sunAvoidanceDeg,
            maxSlewRateDegPerSec = this[K.maxRate] ?: d.maxSlewRateDegPerSec,
            invertAzimuth = this[K.invAz] ?: d.invertAzimuth,
            invertAltitude = this[K.invAlt] ?: d.invertAltitude,
            useManualLocation = this[K.manualLoc] ?: d.useManualLocation,
            manualLatitude = this[K.lat] ?: d.manualLatitude,
            manualLongitude = this[K.lon] ?: d.manualLongitude,
            manualElevationM = this[K.elev] ?: d.manualElevationM,
            useSystemTimeZone = this[K.sysTz] ?: d.useSystemTimeZone,
            manualUtcOffsetHours = this[K.utcOff] ?: d.manualUtcOffsetHours,
            dstEnabled = this[K.dst] ?: d.dstEnabled,
            ntpServer = this[K.ntp] ?: d.ntpServer,
            pressureHpa = this[K.pressure] ?: d.pressureHpa,
            temperatureC = this[K.temp] ?: d.temperatureC,
            phoneMounting = enumOf(this[K.mounting], d.phoneMounting),
            observingMode = enumOf(this[K.obsMode], d.observingMode),
            eyepieceId = this[K.eyepiece] ?: d.eyepieceId,
            barlowFactor = this[K.barlow] ?: d.barlowFactor,
            lx200Enabled = this[K.lxOn] ?: d.lx200Enabled,
            lx200Port = this[K.lxPort] ?: d.lx200Port,
            lx200Epoch = enumOf(this[K.lxEpoch], d.lx200Epoch),
            stellariumEnabled = this[K.stOn] ?: d.stellariumEnabled,
            stellariumPort = this[K.stPort] ?: d.stellariumPort,
            stellariumEpoch = enumOf(this[K.stEpoch], d.stellariumEpoch),
            nightMode = this[K.night] ?: d.nightMode,
            keepScreenOn = this[K.screenOn] ?: d.keepScreenOn,
            padRateIndex = this[K.padRate] ?: d.padRateIndex,
            padMode = enumOf(this[K.padMode], d.padMode),
            cometUrl = this[K.cometUrl] ?: d.cometUrl,
        )
    }

    private fun AppSettings.writeTo(p: MutablePreferences) {
        p[K.host] = mountHost; p[K.port] = mountPort; p[K.transport] = transport.name
        p[K.ssid] = ssidPrefix; p[K.pass] = wifiPassphrase
        p[K.minAlt] = minAltitudeDeg; p[K.maxAlt] = maxAltitudeDeg; p[K.sun] = sunAvoidanceDeg
        p[K.maxRate] = maxSlewRateDegPerSec; p[K.invAz] = invertAzimuth; p[K.invAlt] = invertAltitude
        p[K.manualLoc] = useManualLocation; p[K.lat] = manualLatitude; p[K.lon] = manualLongitude
        p[K.elev] = manualElevationM; p[K.sysTz] = useSystemTimeZone; p[K.utcOff] = manualUtcOffsetHours
        p[K.dst] = dstEnabled; p[K.ntp] = ntpServer; p[K.pressure] = pressureHpa; p[K.temp] = temperatureC
        p[K.mounting] = phoneMounting.name
        p[K.obsMode] = observingMode.name; p[K.eyepiece] = eyepieceId; p[K.barlow] = barlowFactor
        p[K.lxOn] = lx200Enabled; p[K.lxPort] = lx200Port; p[K.lxEpoch] = lx200Epoch.name
        p[K.stOn] = stellariumEnabled; p[K.stPort] = stellariumPort; p[K.stEpoch] = stellariumEpoch.name
        p[K.night] = nightMode; p[K.screenOn] = keepScreenOn; p[K.padRate] = padRateIndex
        p[K.padMode] = padMode.name; p[K.cometUrl] = cometUrl
    }
}
