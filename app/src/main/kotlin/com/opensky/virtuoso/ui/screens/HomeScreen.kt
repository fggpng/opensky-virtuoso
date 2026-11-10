package com.opensky.virtuoso.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensky.virtuoso.control.Activity
import com.opensky.virtuoso.control.Equipment
import com.opensky.virtuoso.control.ObservingMode
import com.opensky.virtuoso.control.StartPosition
import com.opensky.virtuoso.location.LocationTimeManager
import com.opensky.virtuoso.network.LinkState
import com.opensky.virtuoso.network.wifi.WifiLinkState
import com.opensky.virtuoso.service.ConnectionMode
import com.opensky.virtuoso.ui.components.HintText
import com.opensky.virtuoso.ui.components.InfoCard
import com.opensky.virtuoso.ui.components.LabeledValue
import com.opensky.virtuoso.ui.components.WarningText
import com.opensky.virtuoso.ui.components.altText
import com.opensky.virtuoso.ui.components.azText
import com.opensky.virtuoso.ui.components.decText
import com.opensky.virtuoso.ui.components.raText
import com.opensky.virtuoso.ui.viewmodel.HomeViewModel

@Composable
fun MessageEffect(message: String?, snackbar: SnackbarHostState, onShown: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onShown()
        }
    }
}

@Composable
fun HomeScreen(vm: HomeViewModel, snackbar: SnackbarHostState, onOpenSensors: () -> Unit) {
    val session by vm.session.collectAsStateWithLifecycle()
    val mount by vm.mount.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val time by vm.time.collectAsStateWithLifecycle()
    val clock by vm.clock.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val alignedSamples by vm.alignmentSamples.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val locationKnown by vm.locationKnown.collectAsStateWithLifecycle()
    MessageEffect(message, snackbar, vm::clearMessage)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard("Equipo de observación") {
            val setup = settings.opticalSetup()
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ObservingMode.entries.forEach { mode ->
                    FilterChip(selected = setup.mode == mode, onClick = { vm.setObservingMode(mode) }, label = { Text(mode.shortLabel) })
                }
            }
            if (setup.mode == ObservingMode.VISUAL) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Equipment.EYEPIECES.forEach { e ->
                        FilterChip(selected = setup.eyepiece.id == e.id, onClick = { vm.setEyepiece(e.id) },
                            label = { Text(e.name.removePrefix("Sky-Watcher ").removePrefix("Celestron ")) })
                    }
                }
            }
            Text(setup.summary)
            HintText(
                if (setup.usesCamera) "Seguimiento suave para fotografía. La exposición máxima por rotación de campo aparece en Objetivos y en Control."
                else "Seguimiento visual. El % de precisión de la alineación se calcula con el campo de este ocular."
            )
        }

        InfoCard("Montura Sky-Watcher Virtuoso GTi") {
            val connected = mount != null && mount?.activity != Activity.DISCONNECTED
            when {
                session.connecting -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(session.step ?: "Conectando…")
                }
                connected -> {
                    val m = mount!!
                    LabeledValue("Modelo", m.info?.modelName ?: "—", monospace = false)
                    LabeledValue("Firmware", m.info?.firmwareVersion ?: "—")
                    LabeledValue("Enlace", when (m.link) {
                        LinkState.CONNECTED -> "OK"; LinkState.RETRYING -> "Reintentando…"; LinkState.LOST -> "PERDIDO"
                        LinkState.CONNECTING -> "Conectando"; LinkState.DISCONNECTED -> "Desconectado"
                    })
                    LabeledValue("Modo", session.mode?.label ?: "—", monospace = false)
                    LabeledValue("Actividad", m.activity.name)
                    m.info?.let { LabeledValue("Pasos/vuelta (Az, Alt)", "${it.azimuth.countsPerRevolution}, ${it.altitude.countsPerRevolution}") }
                    m.pointing?.let { LabeledValue("Apunta a (Az, Alt)", "${it.azText()}  ${it.altText()}") }
                    m.pointingApparent?.let { LabeledValue("AR/Dec (fecha)", "${it.raText()}  ${it.decText()}") }
                    m.lastError?.let { WarningText(it) }
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("Desconectar") }
                }
                else -> {
                    session.error?.let { WarningText(it) }
                    HintText("Enciende la montura, espera ~30 s a que emita la red «${settings.ssidPrefix}…» y pulsa Conectar. Android pedirá aprobar la red (no tiene Internet: los datos móviles siguen funcionando).")
                    Button(onClick = { vm.connect(ConnectionMode.WIFI_SYNSCAN) }, Modifier.fillMaxWidth()) { Text("Conectar a la Wi-Fi SynScan") }
                    FilledTonalButton(onClick = { vm.connect(ConnectionMode.CURRENT_WIFI) }, Modifier.fillMaxWidth()) { Text("Usar la Wi-Fi a la que ya estoy unido") }
                    OutlinedButton(onClick = { vm.connect(ConnectionMode.SIMULATOR) }, Modifier.fillMaxWidth()) { Text("Modo demo (simulador)") }
                }
            }
            when (val w = wifi) {
                is WifiLinkState.Connected -> HintText("Wi-Fi: ${w.ssid ?: "red local"} ${if (w.viaSpecifier) "(solicitada por la app)" else ""}")
                is WifiLinkState.Lost -> WarningText("Se perdió la Wi-Fi de la montura; reintentando…")
                else -> Unit
            }
        }

        val m0 = mount
        if (m0 != null && m0.activity != Activity.DISCONNECTED && !session.connecting) {
            InfoCard("Posición de inicio") {
                if (!locationKnown) WarningText("Sin ubicación todavía: el GoTo está bloqueado hasta tener GPS o coordenadas manuales.")
                val unconfirmed = m0.startPosition == StartPosition.UNCONFIRMED && alignedSamples == 0
                if (unconfirmed) WarningText("Sin confirmar: el GoTo está bloqueado hasta que la confirmes.")
                else LabeledValue("Estado", if (alignedSamples > 0) "Alineada con estrellas" else m0.startPosition.label, monospace = false)
                m0.pointing?.let { p ->
                    Text("La app cree que el tubo apunta a: Alt %.1f° · Az %.0f° (%s)".format(p.altDeg, p.azDeg, cardinal(p.azDeg)))
                }
                HintText("En cada sesión, ya en el sitio: deja el tubo horizontal y apuntando al norte (usa la brújula de la app: " +
                    "ya corrige la declinación magnética) y pulsa el botón. Si el tubo se ve distinto de lo que dice la app, no hagas GoTo.")
                Button(onClick = { vm.confirmHome() }, Modifier.fillMaxWidth()) { Text("El tubo está horizontal hacia el norte") }
                OutlinedButton(onClick = onOpenSensors, Modifier.fillMaxWidth()) { Text("Medir la posición con el teléfono sobre el tubo") }
                OutlinedButton(onClick = { vm.checkAltitudeWithPhone() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                    Text(if (checking) "Midiendo… no muevas el teléfono" else "Comprobar altura con el teléfono")
                }
                HintText("Comprobar: apoya el teléfono plano sobre el tubo 2 s. Si la altura no coincide (más de 5°), el GoTo se bloquea.")
            }
        }

        InfoCard("Ubicación") {
            if (settings.useManualLocation) {
                LabeledValue("Fuente", "Manual (Ajustes)", monospace = false)
                LabeledValue("Lat, Lon", "%.5f°, %.5f°".format(settings.manualLatitude, settings.manualLongitude))
                LabeledValue("Altitud", "%.0f m".format(settings.manualElevationM))
            } else {
                val l = location
                if (l == null) WarningText("Sin fix GPS todavía. Concede el permiso de ubicación o introduce coordenadas manuales en Ajustes.")
                else {
                    LabeledValue("Lat, Lon", "%.5f°, %.5f°".format(l.latitudeDeg, l.longitudeDeg))
                    LabeledValue("Altitud", "%.0f m".format(l.altitudeM))
                    LabeledValue("Precisión", l.accuracyM?.let { "±%.0f m".format(it) } ?: "—")
                }
                OutlinedButton(onClick = { vm.refreshLocation() }) { Text("Actualizar GPS") }
            }
        }

        InfoCard("Tiempo") {
            clock?.let {
                LabeledValue("UTC", it.utc)
                LabeledValue("Local (${it.zone})", it.local)
                LabeledValue("Tiempo sidéreo local aparente", it.lst)
            }
            LabeledValue("Fuente", time.source.label, monospace = false)
            LabeledValue("Corrección aplicada", LocationTimeManager.formatOffset(time.offsetMillis))
            if (time.verified) HintText(time.detail) else WarningText(time.detail)
            OutlinedButton(onClick = { vm.syncTime() }) { Text("Verificar hora (NTP)") }
        }

        InfoCard("Orientación inicial") {
            HintText("Coloca el teléfono sobre el tubo y usa la IMU para inicializar los encoders. Después, alinea con estrellas.")
            FilledTonalButton(onClick = onOpenSensors) { Text("Abrir orientación por sensores") }
        }
    }
}


/** Punto cardinal aproximado de un acimut (0° = norte, 90° = este). */
private fun cardinal(azDeg: Double): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    return names[(((azDeg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
}
