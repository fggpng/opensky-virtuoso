package com.opensky.virtuoso.service

import com.opensky.virtuoso.alignment.AlignmentManager
import com.opensky.virtuoso.astronomy.GeoLocation
import com.opensky.virtuoso.astronomy.Weather
import com.opensky.virtuoso.control.MountController
import com.opensky.virtuoso.control.OffsetAstroClock
import com.opensky.virtuoso.control.Site
import com.opensky.virtuoso.data.MountTransportType
import com.opensky.virtuoso.data.SettingsRepository
import com.opensky.virtuoso.location.LocationTimeManager
import com.opensky.virtuoso.network.MountClient
import com.opensky.virtuoso.network.bridge.Lx200TcpServer
import com.opensky.virtuoso.network.bridge.MountControllerBridge
import com.opensky.virtuoso.network.bridge.StellariumTcpServer
import com.opensky.virtuoso.network.bridge.TcpBridgeServer
import com.opensky.virtuoso.network.protocol.MountInfo
import com.opensky.virtuoso.network.simulator.MountSimulator
import com.opensky.virtuoso.network.transport.MountTransport
import com.opensky.virtuoso.network.transport.TcpMountTransport
import com.opensky.virtuoso.network.transport.UdpMountTransport
import com.opensky.virtuoso.network.wifi.SynScanWifiManager
import com.opensky.virtuoso.network.wifi.WifiLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnectionMode(val label: String) {
    WIFI_SYNSCAN("Wi-Fi SynScan (automático)"),
    CURRENT_WIFI("Wi-Fi actual del teléfono"),
    SIMULATOR("Simulador (sin hardware)"),
}

data class SessionState(
    val connecting: Boolean = false,
    val mode: ConnectionMode? = null,
    val step: String? = null,
    val error: String? = null,
)

/**
 * Ciclo de vida de una sesión con la montura: enlace Wi-Fi → transporte → [MountClient] →
 * [MountController] → servidores LX200/Stellarium. Vive en el ámbito de la aplicación; el
 * [TelescopeService] en primer plano mantiene vivo el proceso mientras dure la sesión.
 */
class TelescopeSession(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val wifi: SynScanWifiManager,
    private val locationTime: LocationTimeManager,
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
    private var simulator: MountSimulator? = null
    private var wifiWatcher: Job? = null

    /** Lugar de observación: manual (Ajustes) o GPS; null si aún no hay ninguno. */
    fun siteOrNull(): Site? {
        val s = settings.current
        val loc = when {
            s.useManualLocation -> GeoLocation(s.manualLatitude, s.manualLongitude, s.manualElevationM)
            else -> locationTime.location.value?.let { GeoLocation(it.latitudeDeg, it.longitudeDeg, it.altitudeM) }
        } ?: return null
        return Site(loc, Weather(s.pressureHpa, s.temperatureC))
    }

    fun site(): Site = siteOrNull() ?: settings.current.let {
        Site(GeoLocation(it.manualLatitude, it.manualLongitude, it.manualElevationM), Weather(it.pressureHpa, it.temperatureC))
    }

    suspend fun connect(mode: ConnectionMode): Result<MountInfo> {
        disconnect()
        val s = settings.current
        _state.value = SessionState(connecting = true, mode = mode, step = "Preparando enlace…")
        return try {
            val t: MountTransport = when (mode) {
                ConnectionMode.SIMULATOR -> {
                    val sim = MountSimulator().start()
                    simulator = sim
                    UdpMountTransport("127.0.0.1", sim.boundPort)
                }
                ConnectionMode.WIFI_SYNSCAN, ConnectionMode.CURRENT_WIFI -> {
                    if (mode == ConnectionMode.WIFI_SYNSCAN) {
                        _state.value = _state.value.copy(step = "Esperando la red ${s.ssidPrefix}* (aprueba el diálogo del sistema)…")
                        wifi.requestSynScanNetwork(s.ssidPrefix, s.wifiPassphrase.ifBlank { null })
                        val result = withTimeoutOrNull(65_000) {
                            wifi.state.first { it is WifiLinkState.Connected || it is WifiLinkState.Unavailable }
                        }
                        if (result !is WifiLinkState.Connected) {
                            throw IllegalStateException((result as? WifiLinkState.Unavailable)?.reason ?: "Tiempo de espera agotado buscando la Wi-Fi SynScan")
                        }
                    } else {
                        wifi.useCurrentWifi()
                        withTimeoutOrNull(5_000) { wifi.state.first { it is WifiLinkState.Connected } }
                    }
                    if (s.transport == MountTransportType.UDP) UdpMountTransport(s.mountHost, s.mountPort) { wifi.bindDatagram(it) }
                    else TcpMountTransport(s.mountHost, s.mountPort) { wifi.bindSocket(it) }
                }
            }
            transport = t
            _state.value = _state.value.copy(step = "Hablando con la montura (${t.description})…")
            val client = MountClient(t)
            val controller = MountController(client, alignment, scope, clock, ::site) { settings.current.controllerConfig() }
            alignment.referenceFieldRadiusArcmin = s.referenceFieldDiameterArcmin / 2
            val info = controller.connect()
            _controller.value = controller
            startServers(controller)
            if (mode != ConnectionMode.SIMULATOR) watchWifi(mode)
            _state.value = SessionState(connecting = false, mode = mode)
            Result.success(info)
        } catch (e: Exception) {
            cleanup()
            _state.value = SessionState(connecting = false, mode = mode, error = e.message ?: e.toString())
            Result.failure(e)
        }
    }

    /**
     * Reconexión automática: si la red SynScan se pierde (montura apagada, alcance) se vuelve a
     * solicitar; al recuperarla se cierra el socket para que el siguiente comando lo reabra
     * enlazado a la nueva red.
     */
    private fun watchWifi(mode: ConnectionMode) {
        wifiWatcher?.cancel()
        wifiWatcher = scope.launch {
            var lastNetwork = (wifi.state.value as? WifiLinkState.Connected)?.network
            wifi.state.collect { st ->
                when (st) {
                    is WifiLinkState.Connected -> if (st.network != lastNetwork) {
                        lastNetwork = st.network
                        transport?.close()
                    }
                    is WifiLinkState.Lost, is WifiLinkState.Unavailable -> if (mode == ConnectionMode.WIFI_SYNSCAN) {
                        delay(3_000)
                        val s = settings.current
                        wifi.requestSynScanNetwork(s.ssidPrefix, s.wifiPassphrase.ifBlank { null })
                    }
                    else -> Unit
                }
            }
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

    private fun cleanup() {
        stopServers()
        wifiWatcher?.cancel(); wifiWatcher = null
        transport?.close(); transport = null
        simulator?.close(); simulator = null
        wifi.release()
    }

    suspend fun disconnect() {
        _controller.value?.let { runCatching { it.disconnect() } }
        _controller.value = null
        cleanup()
        _state.value = SessionState()
    }
}
