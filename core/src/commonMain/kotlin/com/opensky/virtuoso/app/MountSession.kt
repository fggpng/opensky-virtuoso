package com.opensky.virtuoso.app

import com.opensky.virtuoso.alignment.AlignmentManager
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.Weather
import com.opensky.virtuoso.control.Diagnostics
import com.opensky.virtuoso.control.MountController
import com.opensky.virtuoso.control.OffsetAstroClock
import com.opensky.virtuoso.control.Site
import com.opensky.virtuoso.data.MountTransportType
import com.opensky.virtuoso.data.SettingsStore
import com.opensky.virtuoso.network.MountClient
import com.opensky.virtuoso.network.bridge.Lx200TcpServer
import com.opensky.virtuoso.network.bridge.MountControllerBridge
import com.opensky.virtuoso.network.bridge.StellariumTcpServer
import com.opensky.virtuoso.network.bridge.TcpBridgeServer
import com.opensky.virtuoso.network.protocol.MountInfo
import com.opensky.virtuoso.network.protocol.MountTimeoutException
import com.opensky.virtuoso.network.simulator.MountSimulator
import com.opensky.virtuoso.network.transport.InProcessMountTransport
import com.opensky.virtuoso.network.transport.MountTransport
import com.opensky.virtuoso.network.transport.TcpMountTransport
import com.opensky.virtuoso.network.transport.UdpMountTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cómo se llega a la montura desde la app de iOS. */
enum class ConnectionMode(val label: String) {
    /** El iPhone ya está unido a la Wi-Fi «SynScan_…» de la montura (iOS no deja que la app se una sola). */
    MOUNT_WIFI("Wi-Fi de la montura"),
    SIMULATOR("Simulador (sin hardware)"),
}

data class SessionState(
    val connecting: Boolean = false,
    val mode: ConnectionMode? = null,
    val step: String? = null,
    val error: String? = null,
)

/** Estado de un servidor del puente (para la pantalla de Stellarium). */
data class ServerInfo(val name: String, val port: Int, val running: Boolean, val clients: Int, val lastError: String?)

/**
 * Ciclo de vida de una sesión con la montura: transporte → [MountClient] → [MountController] →
 * servidores LX200/Stellarium. Versión común (iOS) de TelescopeSession de Android, sin la parte
 * de Wi-Fi: en iOS el usuario une el iPhone a la red de la montura en Ajustes.
 */
class MountSession(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val location: LocationStore,
    val alignment: AlignmentManager,
    val clock: OffsetAstroClock,
) {
    private val _controller = MutableStateFlow<MountController?>(null)
    val controller: StateFlow<MountController?> = _controller.asStateFlow()
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val _servers = MutableStateFlow<List<TcpBridgeServer>>(emptyList())
    val servers: StateFlow<List<TcpBridgeServer>> = _servers.asStateFlow()

    private var transport: MountTransport? = null

    /** Lugar de observación: manual (Ajustes) o GPS; null si aún no hay ninguno. */
    fun siteOrNull(): Site? {
        val s = settings.current
        val loc = when {
            s.useManualLocation -> GeoLocation(s.manualLatitude, s.manualLongitude, s.manualElevationM)
            else -> location.location.value?.let { GeoLocation(it.latitudeDeg, it.longitudeDeg, it.altitudeM) }
        } ?: return null
        return Site(loc, Weather(s.pressureHpa, s.temperatureC))
    }

    /** Hay una ubicación real (GPS o manual distinta de 0°, 0°): requisito para apuntar. */
    fun siteKnown(): Boolean = siteOrNull()?.location?.let { !(it.latitudeDeg == 0.0 && it.longitudeDeg == 0.0) } ?: false

    fun site(): Site = siteOrNull() ?: settings.current.let {
        Site(GeoLocation(it.manualLatitude, it.manualLongitude, it.manualElevationM), Weather(it.pressureHpa, it.temperatureC))
    }

    suspend fun connect(mode: ConnectionMode): Result<MountInfo> {
        disconnect()
        val s = settings.current
        Diagnostics.log("SESIÓN: conectando (${mode.label}); ubicación ${siteOrNull()?.location ?: "DESCONOCIDA"}; " +
            "equipo ${s.observingMode}; invertir Az=${s.invertAzimuth} Alt=${s.invertAltitude}; límites ${s.minAltitudeDeg}°…${s.maxAltitudeDeg}°")
        _state.value = SessionState(connecting = true, mode = mode, step = "Preparando enlace…")
        return try {
            val t: MountTransport = when (mode) {
                ConnectionMode.SIMULATOR -> InProcessMountTransport(MountSimulator())
                ConnectionMode.MOUNT_WIFI ->
                    if (s.transport == MountTransportType.UDP) UdpMountTransport(s.mountHost, s.mountPort)
                    else TcpMountTransport(s.mountHost, s.mountPort)
            }
            transport = t
            _state.value = _state.value.copy(step = "Hablando con la montura (${t.description})…")
            val client = MountClient(t)
            val controller = MountController(client, alignment, scope, clock, ::site, { settings.current.controllerConfig() }, ::siteKnown)
            alignment.referenceFieldRadiusArcmin = s.opticalSetup().referenceRadiusArcmin
            val info = controller.connect()
            _controller.value = controller
            startServers(controller)
            _state.value = SessionState(connecting = false, mode = mode)
            Result.success(info)
        } catch (e: Exception) {
            Diagnostics.log("SESIÓN: falló la conexión: ${e.message}")
            cleanup()
            _state.value = SessionState(connecting = false, mode = mode, error = explain(e, mode))
            Result.failure(e)
        }
    }

    /** Mensaje útil en iOS: casi siempre falta unirse a la Wi-Fi o el permiso de red local. */
    private fun explain(e: Exception, mode: ConnectionMode): String {
        val raw = e.message ?: e.toString()
        if (mode == ConnectionMode.SIMULATOR) return raw
        val s = settings.current
        return when {
            e is MountTimeoutException || raw.contains("respuesta", ignoreCase = true) ->
                "La montura no responde en ${s.mountHost}:${s.mountPort}. Une el iPhone a la Wi-Fi «${s.ssidPrefix}…» en " +
                    "Ajustes › Wi-Fi (aunque diga «Sin conexión a Internet») y vuelve a intentarlo."
            raw.contains("No route to host", ignoreCase = true) || raw.contains("not permitted", ignoreCase = true) ||
                raw.contains("denied", ignoreCase = true) ->
                "iOS bloqueó la red local. Activa «Red local» para OpenSky en Ajustes › Privacidad y seguridad › Red local."
            else -> raw
        }
    }

    fun startServers(controller: MountController) {
        stopServers()
        val s = settings.current
        val bridge = MountControllerBridge(controller, clock::now, ::site) { settings.current.minAltitudeDeg }
        val list = mutableListOf<TcpBridgeServer>()
        if (s.lx200Enabled) list += Lx200TcpServer(s.lx200Port, scope, bridge, { settings.current.lx200Epoch }).also { it.start() }
        if (s.stellariumEnabled) list += StellariumTcpServer(s.stellariumPort, scope, bridge, { settings.current.stellariumEpoch }).also { it.start() }
        _servers.value = list
    }

    fun restartServers() {
        _controller.value?.let { startServers(it) }
    }

    fun stopServers() {
        _servers.value.forEach { it.stop() }
        _servers.value = emptyList()
    }

    fun serverInfo(): List<ServerInfo> = _servers.value.map {
        ServerInfo(it.name, it.boundPort, it.running.value, it.clients.value, it.lastError.value)
    }

    private fun cleanup() {
        stopServers()
        transport?.close(); transport = null
    }

    suspend fun disconnect() {
        if (_controller.value != null) Diagnostics.log("SESIÓN: desconectada por el usuario")
        _controller.value?.let { runCatching { it.disconnect() } }
        _controller.value = null
        cleanup()
        _state.value = SessionState()
    }
}
