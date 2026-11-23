package com.opensky.virtuoso.data

import com.opensky.virtuoso.network.bridge.CoordinateEpoch
import com.opensky.virtuoso.platform.PlatformLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Almacén clave-valor de texto (NSUserDefaults en iOS; un mapa en las pruebas). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putAll(values: Map<String, String>)
}

/** Almacén en memoria (pruebas y vista previa). */
class MemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val lock = PlatformLock()
    private val map = initial.toMutableMap()
    override fun getString(key: String): String? = lock.withLock { map[key] }
    override fun putAll(values: Map<String, String>) {
        lock.withLock { map.putAll(values) }
    }
    fun snapshot(): Map<String, String> = lock.withLock { map.toMap() }
}

/**
 * Conversión de [AppSettings] a pares clave-valor. Las claves son las mismas que usa Android en
 * DataStore, así una copia de seguridad o una futura migración no tiene que traducir nada.
 */
object SettingsCodec {

    fun encode(s: AppSettings): Map<String, String> = mapOf(
        "mount_host" to s.mountHost, "mount_port" to s.mountPort.toString(), "transport" to s.transport.name,
        "ssid_prefix" to s.ssidPrefix, "wifi_pass" to s.wifiPassphrase,
        "min_alt" to s.minAltitudeDeg.toString(), "max_alt" to s.maxAltitudeDeg.toString(),
        "sun_avoid" to s.sunAvoidanceDeg.toString(), "max_rate" to s.maxSlewRateDegPerSec.toString(),
        "inv_az" to s.invertAzimuth.toString(), "inv_alt" to s.invertAltitude.toString(),
        "manual_loc" to s.useManualLocation.toString(), "lat" to s.manualLatitude.toString(),
        "lon" to s.manualLongitude.toString(), "elev" to s.manualElevationM.toString(),
        "sys_tz" to s.useSystemTimeZone.toString(), "utc_offset" to s.manualUtcOffsetHours.toString(),
        "dst" to s.dstEnabled.toString(), "ntp" to s.ntpServer,
        "pressure" to s.pressureHpa.toString(), "temp" to s.temperatureC.toString(),
        "mounting" to s.phoneMounting.name, "observing_mode" to s.observingMode.name,
        "eyepiece" to s.eyepieceId, "barlow_factor" to s.barlowFactor.toString(),
        "lx_on" to s.lx200Enabled.toString(), "lx_port" to s.lx200Port.toString(), "lx_epoch" to s.lx200Epoch.name,
        "st_on" to s.stellariumEnabled.toString(), "st_port" to s.stellariumPort.toString(), "st_epoch" to s.stellariumEpoch.name,
        "night" to s.nightMode.toString(), "screen_on" to s.keepScreenOn.toString(),
        "glass_day" to s.glassDay.toString(), "glass_night" to s.glassNight.toString(),
        "pad_rate" to s.padRateIndex.toString(), "pad_mode" to s.padMode.name, "comet_url" to s.cometUrl,
    )

    fun decode(get: (String) -> String?): AppSettings {
        val d = AppSettings()
        fun str(k: String, def: String) = get(k) ?: def
        fun int(k: String, def: Int) = get(k)?.toIntOrNull() ?: def
        fun dbl(k: String, def: Double) = get(k)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: def
        fun bool(k: String, def: Boolean) = get(k)?.toBooleanStrictOrNull() ?: def
        return AppSettings(
            mountHost = str("mount_host", d.mountHost),
            mountPort = int("mount_port", d.mountPort),
            transport = enumOr(get("transport"), d.transport),
            ssidPrefix = str("ssid_prefix", d.ssidPrefix),
            wifiPassphrase = str("wifi_pass", d.wifiPassphrase),
            minAltitudeDeg = dbl("min_alt", d.minAltitudeDeg),
            maxAltitudeDeg = dbl("max_alt", d.maxAltitudeDeg),
            sunAvoidanceDeg = dbl("sun_avoid", d.sunAvoidanceDeg),
            maxSlewRateDegPerSec = dbl("max_rate", d.maxSlewRateDegPerSec),
            invertAzimuth = bool("inv_az", d.invertAzimuth),
            invertAltitude = bool("inv_alt", d.invertAltitude),
            useManualLocation = bool("manual_loc", d.useManualLocation),
            manualLatitude = dbl("lat", d.manualLatitude),
            manualLongitude = dbl("lon", d.manualLongitude),
            manualElevationM = dbl("elev", d.manualElevationM),
            useSystemTimeZone = bool("sys_tz", d.useSystemTimeZone),
            manualUtcOffsetHours = dbl("utc_offset", d.manualUtcOffsetHours),
            dstEnabled = bool("dst", d.dstEnabled),
            ntpServer = str("ntp", d.ntpServer),
            pressureHpa = dbl("pressure", d.pressureHpa),
            temperatureC = dbl("temp", d.temperatureC),
            phoneMounting = enumOr(get("mounting"), d.phoneMounting),
            observingMode = enumOr(get("observing_mode"), d.observingMode),
            eyepieceId = str("eyepiece", d.eyepieceId),
            barlowFactor = dbl("barlow_factor", d.barlowFactor),
            lx200Enabled = bool("lx_on", d.lx200Enabled),
            lx200Port = int("lx_port", d.lx200Port),
            lx200Epoch = enumOr(get("lx_epoch"), d.lx200Epoch),
            stellariumEnabled = bool("st_on", d.stellariumEnabled),
            stellariumPort = int("st_port", d.stellariumPort),
            stellariumEpoch = enumOr<CoordinateEpoch>(get("st_epoch"), d.stellariumEpoch),
            nightMode = bool("night", d.nightMode),
            keepScreenOn = bool("screen_on", d.keepScreenOn),
            glassDay = bool("glass_day", d.glassDay),
            glassNight = bool("glass_night", d.glassNight),
            padRateIndex = int("pad_rate", d.padRateIndex),
            padMode = enumOr(get("pad_mode"), d.padMode),
            cometUrl = str("comet_url", d.cometUrl),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}

/** Ajustes con estado observable y guardado inmediato (iOS; Android usa DataStore). */
class SettingsStore(private val store: KeyValueStore) {
    private val lock = PlatformLock()
    private val _settings = MutableStateFlow(SettingsCodec.decode(store::getString))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        lock.withLock {
            val next = transform(_settings.value)
            if (next == _settings.value) return@withLock
            store.putAll(SettingsCodec.encode(next))
            _settings.value = next
        }
    }
}
