package com.opensky.virtuoso.network.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramSocket
import java.net.Socket

/** Estado del enlace Wi-Fi con el punto de acceso SynScan de la montura. */
sealed class WifiLinkState {
    data object Idle : WifiLinkState()
    data object Requesting : WifiLinkState()
    data class Connected(val network: Network, val ssid: String?, val viaSpecifier: Boolean) : WifiLinkState()
    data class Unavailable(val reason: String) : WifiLinkState()
    data object Lost : WifiLinkState()
}

/**
 * Conexión con la red "SynScan_xxxx" de la Virtuoso GTi.
 *
 * Desde Android 10 una app no puede unirse sola a una Wi-Fi: se usa [WifiNetworkSpecifier]
 * (el sistema muestra un diálogo de aprobación con las redes que coinciden con el prefijo).
 * La red resultante es local (sin Internet) y NO pasa a ser la predeterminada, de modo que
 * NTP y las descargas siguen saliendo por datos móviles. Por eso los sockets de la montura se
 * enlazan explícitamente a esa red con [Network.bindSocket] (ver [bindDatagram]).
 */
class SynScanWifiManager(context: Context) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow<WifiLinkState>(WifiLinkState.Idle)
    val state: StateFlow<WifiLinkState> = _state.asStateFlow()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Solicita la red SynScan por prefijo de SSID (y contraseña WPA2 si se configuró). */
    fun requestSynScanNetwork(ssidPrefix: String, passphrase: String?, timeoutMs: Int = 60_000) {
        release()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(ssidPrefix, PatternMatcher.PATTERN_PREFIX))
            .apply { if (!passphrase.isNullOrEmpty()) setWpa2Passphrase(passphrase) }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = newCallback(viaSpecifier = true)
        _state.value = WifiLinkState.Requesting
        cm.requestNetwork(request, cb, timeoutMs)
        callback = cb
    }

    /**
     * Alternativa: el usuario ya se unió a la Wi-Fi SynScan desde Ajustes. Se escucha cualquier
     * red Wi-Fi (con o sin Internet) para poder enlazar los sockets a ella.
     */
    fun useCurrentWifi() {
        release()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = newCallback(viaSpecifier = false)
        _state.value = WifiLinkState.Requesting
        cm.registerNetworkCallback(request, cb)
        callback = cb
    }

    private fun newCallback(viaSpecifier: Boolean): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: sin este flag el SSID llega redactado en transportInfo.
            LinkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO, viaSpecifier)
        } else {
            LinkCallback(viaSpecifier)
        }

    /** Callback con dos constructores: el de flags solo existe desde Android 12 (API 31). */
    private inner class LinkCallback : ConnectivityManager.NetworkCallback {
        private val viaSpecifier: Boolean

        constructor(viaSpecifier: Boolean) : super() {
            this.viaSpecifier = viaSpecifier
        }

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int, viaSpecifier: Boolean) : super(flags) {
            this.viaSpecifier = viaSpecifier
        }

        override fun onAvailable(network: Network) {
            _state.value = WifiLinkState.Connected(network, ssidOf(cm.getNetworkCapabilities(network)), viaSpecifier)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val current = _state.value
            if (current is WifiLinkState.Connected && current.network == network) {
                ssidOf(caps)?.let { _state.value = current.copy(ssid = it) }
            }
        }

        override fun onUnavailable() {
            _state.value = WifiLinkState.Unavailable(
                "No se encontró ninguna red que empiece por el prefijo configurado o se canceló el diálogo. " +
                    "Enciende la montura y espera ~30 s a que emita su Wi-Fi.",
            )
        }

        override fun onLost(network: Network) {
            val current = _state.value
            if (current is WifiLinkState.Connected && current.network == network) _state.value = WifiLinkState.Lost
        }
    }

    private fun ssidOf(caps: NetworkCapabilities?): String? {
        val info = caps?.transportInfo as? WifiInfo ?: return null
        @Suppress("DEPRECATION")
        val ssid = info.ssid ?: return null
        return ssid.trim('"').takeUnless { it == WifiManagerUnknownSsid }
    }

    /** Red actual (null si no hay enlace). */
    val network: Network? get() = (_state.value as? WifiLinkState.Connected)?.network

    /** Enlaza un socket UDP a la red SynScan (si existe); si no, usa el enrutamiento por defecto. */
    fun bindDatagram(socket: DatagramSocket) {
        network?.bindSocket(socket)
    }

    fun bindSocket(socket: Socket) {
        network?.bindSocket(socket)
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        _state.value = WifiLinkState.Idle
    }

    companion object {
        private const val WifiManagerUnknownSsid = "<unknown ssid>"
    }
}
