package com.opensky.virtuoso.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensky.virtuoso.data.AppSettings
import com.opensky.virtuoso.data.CatalogStatus
import com.opensky.virtuoso.data.MountTransportType
import com.opensky.virtuoso.network.bridge.CoordinateEpoch
import com.opensky.virtuoso.ui.components.HintText
import com.opensky.virtuoso.ui.components.InfoCard
import com.opensky.virtuoso.ui.components.LabeledValue
import com.opensky.virtuoso.ui.components.WarningText
import com.opensky.virtuoso.ui.viewmodel.MoreViewModel

@Composable
fun MoreScreen(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            "sensors" to "Orientación por sensores (IMU + brújula)",
            "stellarium" to "Puente Stellarium / LX200",
            "catalog" to "Catálogo Tycho-2",
            "settings" to "Ajustes y utilidades",
            "update" to "Actualizar la app",
        ).forEach { (route, label) ->
            Text(label, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(route) }.padding(vertical = 16.dp))
            HorizontalDivider()
        }
    }
}

@Composable
fun StellariumScreen(vm: MoreViewModel, snackbar: SnackbarHostState) {
    val servers by vm.servers.collectAsStateWithLifecycle()
    val ips by vm.ipAddresses.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageEffect(message, snackbar, vm::clearMessage)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("Servidores") {
            if (servers.isEmpty()) HintText("Se inician al conectar la montura.")
            servers.forEach { srv ->
                key(srv) {
                    val running by srv.running.collectAsStateWithLifecycle()
                    val clients by srv.clients.collectAsStateWithLifecycle()
                    val error by srv.lastError.collectAsStateWithLifecycle()
                    LabeledValue("${srv.name} :${srv.boundPort}", if (running) "activo · $clients cliente(s)" else "detenido")
                    error?.let { WarningText(it) }
                }
            }
            Text("IP de este teléfono:", style = MaterialTheme.typography.titleSmall)
            ips.forEach { Text(it) }
            HintText("Mismo teléfono: usa 127.0.0.1. Otro dispositivo: debe estar en la misma red (p. ej. también unido a la Wi-Fi SynScan) y usar la IP de wlan0.")
        }
        InfoCard("LX200 por TCP (Stellarium Mobile/Plus, SkySafari)") {
            SettingSwitch("Activado", s.lx200Enabled) { v -> vm.update { it.copy(lx200Enabled = v) } }
            IntField("Puerto", s.lx200Port) { v -> vm.update { it.copy(lx200Port = v) } }
            EpochChips(s.lx200Epoch) { e -> vm.update { it.copy(lx200Epoch = e) } }
            HintText("En el cliente elige «Meade LX200 Classic/Autostar» por Wi-Fi/TCP con la IP y el puerto de arriba.")
        }
        InfoCard("Protocolo binario de Stellarium (escritorio)") {
            SettingSwitch("Activado", s.stellariumEnabled) { v -> vm.update { it.copy(stellariumEnabled = v) } }
            IntField("Puerto", s.stellariumPort) { v -> vm.update { it.copy(stellariumPort = v) } }
            EpochChips(s.stellariumEpoch) { e -> vm.update { it.copy(stellariumEpoch = e) } }
            HintText("Stellarium → Control de telescopio → Añadir → «Software externo u ordenador remoto», host = IP del teléfono, puerto = el de arriba.")
        }
        Button(onClick = { vm.restartServers() }) { Text("Aplicar y reiniciar servidores") }
    }
}

@Composable
fun CatalogScreen(vm: MoreViewModel, snackbar: SnackbarHostState) {
    val status by vm.catalogStatus.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageEffect(message, snackbar, vm::clearMessage)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importCatalog(uris)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("Tycho-2 (~2.5 millones de estrellas, V ≤ 11.5)") {
            when (val st = status) {
                is CatalogStatus.NotInstalled -> HintText("No instalado. Las estrellas con nombre integradas funcionan sin él.")
                is CatalogStatus.Working -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${st.phase}: ${st.count} estrellas")
                }
                is CatalogStatus.Ready -> LabeledValue("Instalado", "${st.stars} estrellas")
                is CatalogStatus.Error -> WarningText(st.message)
            }
            HintText("Opción A (recomendada): convierte en el PC con la herramienta tools:catalog-builder y selecciona aquí tycho2.dat, tycho2.idx y tycho2.ref.\n" +
                "Opción B: selecciona los archivos oficiales del CDS (tyc2.dat.00.gz … tyc2.dat.19.gz y suppl_1.dat.gz) y se convierten en el teléfono (~60 MB de RAM, varios minutos).")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = status !is CatalogStatus.Working) { Text("Importar archivos") }
                OutlinedButton(onClick = { vm.testCatalog() }, enabled = status is CatalogStatus.Ready) { Text("Probar consulta") }
            }
            OutlinedButton(onClick = { vm.deleteCatalog() }, enabled = status is CatalogStatus.Ready) { Text("Eliminar catálogo") }
        }
    }
}

@Composable
fun SettingsScreen(vm: MoreViewModel, snackbar: SnackbarHostState) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageEffect(message, snackbar, vm::clearMessage)
    fun set(t: (AppSettings) -> AppSettings) { vm.update(t) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("Límites de seguridad") {
            DoubleField("Altura mínima (°)", s.minAltitudeDeg) { v -> set { it.copy(minAltitudeDeg = v.coerceIn(-10.0, 45.0)) } }
            DoubleField("Altura máxima (°)", s.maxAltitudeDeg) { v -> set { it.copy(maxAltitudeDeg = v.coerceIn(45.0, 90.0)) } }
            DoubleField("Distancia mínima al Sol (°)", s.sunAvoidanceDeg) { v -> set { it.copy(sunAvoidanceDeg = v.coerceIn(5.0, 60.0)) } }
            DoubleField("Velocidad máxima (°/s)", s.maxSlewRateDegPerSec) { v -> set { it.copy(maxSlewRateDegPerSec = v.coerceIn(0.5, 4.0)) } }
        }
        InfoCard("Ejes") {
            SettingSwitch("Invertir acimut", s.invertAzimuth) { v -> set { it.copy(invertAzimuth = v) } }
            SettingSwitch("Invertir altura", s.invertAltitude) { v -> set { it.copy(invertAltitude = v) } }
            HintText("Si al pulsar ▲ el tubo baja (o ▶ gira al revés), invierte el eje correspondiente y vuelve a hacer la orientación inicial.")
        }
        InfoCard("Ubicación y hora") {
            SettingSwitch("Usar coordenadas manuales", s.useManualLocation) { v -> set { it.copy(useManualLocation = v) } }
            DoubleField("Latitud (°, N+)", s.manualLatitude) { v -> set { it.copy(manualLatitude = v.coerceIn(-90.0, 90.0)) } }
            DoubleField("Longitud (°, E+)", s.manualLongitude) { v -> set { it.copy(manualLongitude = v.coerceIn(-180.0, 180.0)) } }
            DoubleField("Altitud (m)", s.manualElevationM) { v -> set { it.copy(manualElevationM = v) } }
            SettingSwitch("Zona horaria del sistema (DST automático)", s.useSystemTimeZone) { v -> set { it.copy(useSystemTimeZone = v) } }
            DoubleField("UTC offset manual (h)", s.manualUtcOffsetHours) { v -> set { it.copy(manualUtcOffsetHours = v.coerceIn(-14.0, 14.0)) } }
            SettingSwitch("Horario de verano (DST, +1 h)", s.dstEnabled) { v -> set { it.copy(dstEnabled = v) } }
            TextSetting("Servidor NTP", s.ntpServer) { v -> set { it.copy(ntpServer = v) } }
            HintText("Internamente todo se calcula en UTC: la zona horaria y el DST solo afectan a la hora mostrada y al protocolo LX200.")
        }
        InfoCard("Atmósfera (refracción)") {
            DoubleField("Presión (hPa)", s.pressureHpa) { v -> set { it.copy(pressureHpa = v.coerceIn(500.0, 1100.0)) } }
            DoubleField("Temperatura (°C)", s.temperatureC) { v -> set { it.copy(temperatureC = v.coerceIn(-40.0, 50.0)) } }
        }
        InfoCard("Equipo y alineación") {
            val setup = s.opticalSetup()
            LabeledValue("Configuración activa", setup.mode.shortLabel, monospace = false)
            LabeledValue("Campo de referencia (radio)", "%.0f′".format(setup.referenceRadiusArcmin))
            DoubleField("Factor real del Barlow con la R50", s.barlowFactor) { v ->
                set { it.copy(barlowFactor = v.coerceIn(1.5, 3.5)) }
            }
            HintText("Un Barlow «2×» suele dar 2.2–2.5× con cámara porque el sensor queda más lejos que un ocular. " +
                "La configuración (ocular o cámara) se elige arriba en Inicio.")
        }
        InfoCard("Conexión con la montura") {
            TextSetting("IP", s.mountHost) { v -> set { it.copy(mountHost = v.trim()) } }
            IntField("Puerto", s.mountPort) { v -> set { it.copy(mountPort = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MountTransportType.entries.forEach { t ->
                    FilterChip(selected = s.transport == t, onClick = { set { it.copy(transport = t) } }, label = { Text(t.name) })
                }
            }
            HintText("La Virtuoso GTi usa UDP 11880. TCP solo para puentes serie↔Wi-Fi de terceros.")
            TextSetting("Prefijo SSID", s.ssidPrefix) { v -> set { it.copy(ssidPrefix = v) } }
            TextSetting("Contraseña Wi-Fi (vacía = red abierta)", s.wifiPassphrase) { v -> set { it.copy(wifiPassphrase = v) } }
        }
        InfoCard("Pantalla") {
            SettingSwitch("Modo nocturno rojo + brillo mínimo", s.nightMode) { v -> set { it.copy(nightMode = v) } }
            SettingSwitch("Mantener pantalla encendida", s.keepScreenOn) { v -> set { it.copy(keepScreenOn = v) } }
        }
        InfoCard("Datos") {
            TextSetting("URL de elementos de cometas (MPC)", s.cometUrl) { v -> set { it.copy(cometUrl = v.trim()) } }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Campo numérico que confirma el valor al pulsar ✓ (evita escribir en DataStore en cada tecla). */
@Composable
private fun DoubleField(label: String, value: Double, onCommit: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        val parsed = text.replace(',', '.').toDoubleOrNull()
        OutlinedButton(onClick = { parsed?.let(onCommit) }, enabled = parsed != null && parsed != value, modifier = Modifier.padding(start = 8.dp)) { Text("✓") }
    }
}

@Composable
private fun IntField(label: String, value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it.filter(Char::isDigit) }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        val parsed = text.toIntOrNull()?.takeIf { it in 1..65535 }
        OutlinedButton(onClick = { parsed?.let(onCommit) }, enabled = parsed != null && parsed != value, modifier = Modifier.padding(start = 8.dp)) { Text("✓") }
    }
}

@Composable
private fun TextSetting(label: String, value: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onCommit(text) }, enabled = text != value, modifier = Modifier.padding(start = 8.dp)) { Text("✓") }
    }
}

@Composable
private fun EpochChips(selected: CoordinateEpoch, onSelect: (CoordinateEpoch) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CoordinateEpoch.entries.forEach { e -> FilterChip(selected = selected == e, onClick = { onSelect(e) }, label = { Text(e.name) }) }
    }
}
