package com.opensky.virtuoso.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensky.virtuoso.control.Activity
import com.opensky.virtuoso.control.Equipment
import com.opensky.virtuoso.control.Eyepiece
import com.opensky.virtuoso.control.MountState
import com.opensky.virtuoso.control.ObservingMode
import com.opensky.virtuoso.control.OpticalSetup
import com.opensky.virtuoso.control.StartPosition
import com.opensky.virtuoso.location.LocationTimeManager
import com.opensky.virtuoso.network.LinkState
import com.opensky.virtuoso.network.wifi.WifiLinkState
import com.opensky.virtuoso.service.ConnectionMode
import com.opensky.virtuoso.ui.components.ButtonRowSpacing
import com.opensky.virtuoso.ui.components.HintText
import com.opensky.virtuoso.ui.components.InfoCard
import com.opensky.virtuoso.ui.components.InfoChip
import com.opensky.virtuoso.ui.components.InlineStat
import com.opensky.virtuoso.ui.components.LabeledValue
import com.opensky.virtuoso.ui.components.LiveStatus
import com.opensky.virtuoso.ui.components.LiveStatusChip
import com.opensky.virtuoso.ui.components.LiveTone
import com.opensky.virtuoso.ui.components.OpenSkyIcons
import com.opensky.virtuoso.ui.components.PillButton
import com.opensky.virtuoso.ui.components.ProminentButton
import com.opensky.virtuoso.ui.components.RoundIconButton
import com.opensky.virtuoso.ui.components.SegmentedControl
import com.opensky.virtuoso.ui.components.Stat
import com.opensky.virtuoso.ui.components.StatGrid
import com.opensky.virtuoso.ui.components.TABULAR_NUMS
import com.opensky.virtuoso.ui.components.WarningText
import com.opensky.virtuoso.ui.components.altText
import com.opensky.virtuoso.ui.components.azText
import com.opensky.virtuoso.ui.components.decText
import com.opensky.virtuoso.ui.components.raText
import com.opensky.virtuoso.ui.components.screenPadding
import com.opensky.virtuoso.ui.components.uiPalette
import com.opensky.virtuoso.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

@Composable
fun MessageEffect(message: String?, snackbar: SnackbarHostState, onShown: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onShown()
        }
    }
}

/**
 * Pantalla principal. Cada sección es un composable propio; los datos y las acciones son los mismos
 * de siempre (solo cambia la presentación). El relleno inferior de screenPadding() deja libres al
 * menos 104 dp (más la barra del sistema) para que nada quede detrás de la barra flotante.
 */
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
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    if (manualOpen) ManualStartDialog(vm) { manualOpen = false }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EquipmentSection(
            setup = settings.opticalSetup(),
            onMode = { vm.setObservingMode(it) },
            onEyepiece = { vm.setEyepiece(it) },
        )

        MountSection(
            link = mountLink(session.connecting, mount),
            step = session.step,
            error = session.error,
            mount = mount,
            modeLabel = session.mode?.label,
            ssidPrefix = settings.ssidPrefix,
            wifi = wifi,
            onConnect = { vm.connect(it) },
            onDisconnect = { vm.disconnect() },
        )

        val m0 = mount
        if (m0 != null && m0.activity != Activity.DISCONNECTED && !session.connecting) {
            StartPositionSection(
                mount = m0,
                locationKnown = locationKnown,
                alignedSamples = alignedSamples,
                checking = checking,
                onConfirmHome = { vm.confirmHome() },
                onOpenSensors = onOpenSensors,
                onManual = { manualOpen = true },
                onCheckAltitude = { vm.checkAltitudeWithPhone() },
            )
        }

        if (settings.useManualLocation) {
            LocationSection(
                lat = settings.manualLatitude, lon = settings.manualLongitude, altitudeM = settings.manualElevationM,
                manual = true, accuracyM = null, onRefresh = null,
            )
        } else {
            val l = location
            if (l == null) {
                InfoCard("Ubicación") {
                    WarningText("Sin fix GPS todavía. Concede el permiso de ubicación o introduce coordenadas manuales en Ajustes.")
                    PillButton("Actualizar GPS", onClick = { vm.refreshLocation() }, icon = OpenSkyIcons.Pin, compact = true)
                }
            } else {
                LocationSection(
                    lat = l.latitudeDeg, lon = l.longitudeDeg, altitudeM = l.altitudeM,
                    manual = false, accuracyM = l.accuracyM, onRefresh = { vm.refreshLocation() },
                )
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

// ---------------------------------------------------------------------------------------------
// Estado de la montura (compartido con el indicador de la cabecera)
// ---------------------------------------------------------------------------------------------

enum class MountLink(val label: String, val short: String, val tone: LiveTone) {
    CONNECTING("Conectando…", "Conectando", LiveTone.Busy),
    ONLINE("Conectada", "En línea", LiveTone.Ok),
    RETRYING("Reintentando…", "Reintentando", LiveTone.Busy),
    LOST("Enlace perdido", "Sin enlace", LiveTone.Off),
    OFFLINE("Desconectada", "Sin montura", LiveTone.Off),
}

fun mountLink(connecting: Boolean, m: MountState?): MountLink = when {
    connecting -> MountLink.CONNECTING
    m == null || m.activity == Activity.DISCONNECTED -> MountLink.OFFLINE
    m.link == LinkState.LOST -> MountLink.LOST
    m.link == LinkState.RETRYING || m.link == LinkState.CONNECTING -> MountLink.RETRYING
    else -> MountLink.ONLINE
}

/**
 * Indicador rápido para la cabecera («● En línea»). Observa solo el estado del enlace (no cada
 * lectura de posición), así que no recompone la cabecera dos veces por segundo.
 */
@Composable
fun HomeStatusChip(vm: HomeViewModel) {
    val link by remember(vm) {
        combine(vm.session, vm.mount) { s, m -> mountLink(s.connecting, m) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = MountLink.OFFLINE)
    LiveStatusChip(link.short, link.tone)
}

// ---------------------------------------------------------------------------------------------
// 1. Equipo de observación
// ---------------------------------------------------------------------------------------------

@Composable
private fun EquipmentSection(setup: OpticalSetup, onMode: (ObservingMode) -> Unit, onEyepiece: (String) -> Unit) {
    InfoCard("Equipo de observación") {
        // Selector de pastilla deslizante: Ocular · R50 · R50 + Barlow.
        val modes = ObservingMode.entries
        SegmentedControl(
            options = modes.map { it.shortLabel },
            selectedIndex = modes.indexOf(setup.mode),
            onSelect = { onMode(modes[it]) },
        )
        if (setup.mode == ObservingMode.VISUAL) {
            val eyepieces = Equipment.EYEPIECES
            SegmentedControl(
                options = eyepieces.map { it.shortName() },
                selectedIndex = eyepieces.indexOfFirst { it.id == setup.eyepiece.id }.coerceAtLeast(0),
                onSelect = { onEyepiece(eyepieces[it].id) },
            )
        }
        // Óptica · Campo · Escala (o Aumento con ocular), en tres columnas delimitadas.
        StatGrid(opticsStats(setup), Modifier.padding(top = 4.dp))
        HintText(
            if (setup.usesCamera) "Seguimiento suave para fotografía. La exposición máxima por rotación de campo aparece en Objetivos y en Control."
            else "Seguimiento visual. El % de precisión de la alineación se calcula con el campo de este ocular."
        )
    }
}

/** Nombre corto del ocular para el selector («Super 25», «X-Cel LX 25»). */
private fun Eyepiece.shortName(): String =
    name.removePrefix("Sky-Watcher ").removePrefix("Celestron ").removeSuffix(" mm")

/** Los tres datos del equipo (antes un texto corrido: «R50 · 1500 mm f/10.0 · campo …»). */
private fun opticsStats(setup: OpticalSetup): List<Stat> {
    val optics = Stat("Óptica", "%.0f".format(setup.effectiveFocalMm), "mm", "f/%.1f".format(setup.focalRatio))
    return if (setup.usesCamera) listOf(
        optics,
        Stat("Campo", "%.0f′ × %.0f′".format(setup.fieldWidthArcmin, setup.fieldHeightArcmin), null, "sensor completo"),
        Stat("Escala", "%.2f".format(setup.arcsecPerPixel ?: 0.0), "″/px", "%.2f µm/px".format(setup.camera.pixelUm)),
    ) else listOf(
        optics,
        Stat("Campo", "%.0f".format(setup.fieldWidthArcmin), "′", "%.2f°".format(setup.fieldWidthArcmin / 60)),
        Stat("Aumento", "%.0f".format(setup.magnification ?: 0.0), "×", "ocular %.0f mm".format(setup.eyepiece.focalMm)),
    )
}

// ---------------------------------------------------------------------------------------------
// 2. Montura (conexión Wi-Fi)
// ---------------------------------------------------------------------------------------------

@Composable
private fun MountSection(
    link: MountLink,
    step: String?,
    error: String?,
    mount: MountState?,
    modeLabel: String?,
    ssidPrefix: String,
    wifi: WifiLinkState,
    onConnect: (ConnectionMode) -> Unit,
    onDisconnect: () -> Unit,
) {
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    InfoCard("Montura Sky-Watcher Virtuoso GTi") {
        // Encabezado: estado en vivo a la izquierda y ayuda ( ? ) a la derecha.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LiveStatus(
                if (link == MountLink.ONLINE && modeLabel != null) "${link.label} · $modeLabel" else link.label,
                link.tone,
                Modifier.weight(1f),
            )
            RoundIconButton(OpenSkyIcons.Help, contentDescription = "Cómo conectar la montura", onClick = { helpOpen = true })
        }

        when (link) {
            MountLink.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = uiPalette().accent)
                Spacer(Modifier.width(12.dp))
                Text(step ?: "Conectando…", color = uiPalette().textSecondary, fontSize = 14.sp)
            }
            MountLink.OFFLINE -> {
                error?.let { WarningText(it) }
                ProminentButton("Conectar a la Wi-Fi SynScan", onClick = { onConnect(ConnectionMode.WIFI_SYNSCAN) }, icon = OpenSkyIcons.Wifi)
                // Opciones secundarias: dos columnas simétricas, más bajas y con menos peso visual.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = ButtonRowSpacing) {
                    PillButton("Red actual", onClick = { onConnect(ConnectionMode.CURRENT_WIFI) }, Modifier.weight(1f), compact = true)
                    PillButton("Modo simulador", onClick = { onConnect(ConnectionMode.SIMULATOR) }, Modifier.weight(1f), compact = true)
                }
            }
            else -> mount?.let { m -> ConnectedMountDetails(m, onDisconnect) }
        }

        when (wifi) {
            is WifiLinkState.Connected -> HintText("Wi-Fi: ${wifi.ssid ?: "red local"} ${if (wifi.viaSpecifier) "(solicitada por la app)" else ""}")
            is WifiLinkState.Lost -> WarningText("Se perdió la Wi-Fi de la montura; reintentando…")
            else -> Unit
        }
    }
    if (helpOpen) ConnectionHelpSheet(ssidPrefix) { helpOpen = false }
}

@Composable
private fun ConnectedMountDetails(m: MountState, onDisconnect: () -> Unit) {
    LabeledValue("Modelo", m.info?.modelName ?: "—", monospace = false)
    LabeledValue("Firmware", m.info?.firmwareVersion ?: "—")
    LabeledValue("Actividad", m.activity.name)
    m.info?.let { LabeledValue("Pasos/vuelta (Az, Alt)", "${it.azimuth.countsPerRevolution}, ${it.altitude.countsPerRevolution}") }
    m.pointing?.let { LabeledValue("Apunta a (Az, Alt)", "${it.azText()}  ${it.altText()}") }
    m.pointingApparent?.let { LabeledValue("AR/Dec (fecha)", "${it.raText()}  ${it.decText()}") }
    m.lastError?.let { WarningText(it) }
    PillButton("Desconectar", onClick = onDisconnect, destructive = true, compact = true)
}

/** Instrucciones de conexión en una hoja inferior (antes, un párrafo fijo de cuatro líneas). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionHelpSheet(ssidPrefix: String, onDismiss: () -> Unit) {
    val palette = uiPalette()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Cómo conectar la montura", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            HelpStep(1, "Enciende la montura y espera unos 30 s a que emita la red «$ssidPrefix…».")
            HelpStep(2, "Pulsa «Conectar a la Wi-Fi SynScan». Android te pedirá aprobar la red.")
            HelpStep(3, "Esa red no tiene Internet: los datos móviles siguen funcionando mientras estás conectado.")
            HorizontalDivider(thickness = 0.5.dp, color = palette.divider)
            Text("Red actual", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            HintText("Úsala si el teléfono ya está unido a la Wi-Fi de la montura.")
            Text("Modo simulador", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            HintText("Prueba la app sin la montura: una montura simulada responde a los comandos.")
            ProminentButton("Entendido", onClick = onDismiss, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun HelpStep(number: Int, text: String) {
    val palette = uiPalette()
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$number",
            color = palette.accent,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR_NUMS),
            modifier = Modifier.width(22.dp),
        )
        Text(text, color = palette.textPrimary, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Posición de inicio (sin cambios de contenido)
// ---------------------------------------------------------------------------------------------

@Composable
private fun StartPositionSection(
    mount: MountState,
    locationKnown: Boolean,
    alignedSamples: Int,
    checking: Boolean,
    onConfirmHome: () -> Unit,
    onOpenSensors: () -> Unit,
    onManual: () -> Unit,
    onCheckAltitude: () -> Unit,
) {
    InfoCard("Posición de inicio") {
        if (!locationKnown) WarningText("Sin ubicación todavía: el GoTo está bloqueado hasta tener GPS o coordenadas manuales.")
        val unconfirmed = mount.startPosition == StartPosition.UNCONFIRMED && alignedSamples == 0
        if (unconfirmed) WarningText("Sin confirmar: el GoTo está bloqueado hasta que la confirmes.")
        else LabeledValue("Estado", if (alignedSamples > 0) "Alineada con estrellas" else mount.startPosition.label, monospace = false)
        mount.pointing?.let { p ->
            Text("La app cree que el tubo apunta a: Alt %.1f° · Az %.0f° (%s)".format(p.altDeg, p.azDeg, cardinal(p.azDeg)))
        }
        HintText("En cada sesión, ya en el sitio: deja el tubo horizontal y apuntando al norte (usa la brújula de la app: " +
            "ya corrige la declinación magnética) y pulsa el botón. Si el tubo se ve distinto de lo que dice la app, no hagas GoTo.")
        Button(onClick = onConfirmHome, Modifier.fillMaxWidth()) { Text("El tubo está horizontal hacia el norte") }
        OutlinedButton(onClick = onOpenSensors, Modifier.fillMaxWidth()) { Text("Medir la posición con el teléfono sobre el tubo") }
        OutlinedButton(onClick = onManual, Modifier.fillMaxWidth()) { Text("Escribir hacia dónde apunta el tubo") }
        OutlinedButton(onClick = onCheckAltitude, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            Text(if (checking) "Midiendo… no muevas el teléfono" else "Comprobar altura con el teléfono")
        }
        HintText("Comprobar: apoya el teléfono plano sobre el tubo 2 s. Si la altura no coincide (más de 5°), el GoTo se bloquea.")
    }
}

// ---------------------------------------------------------------------------------------------
// 3. Ubicación (compacta)
// ---------------------------------------------------------------------------------------------

@Composable
private fun LocationSection(
    lat: Double,
    lon: Double,
    altitudeM: Double,
    manual: Boolean,
    accuracyM: Float?,
    onRefresh: (() -> Unit)?,
) {
    val palette = uiPalette()
    InfoCard("Ubicación") {
        // Arriba: coordenadas con el pin.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(OpenSkyIcons.Pin, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                coordinatesText(lat, lon),
                color = palette.textPrimary,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR_NUMS),
                modifier = Modifier.weight(1f),
            )
            if (onRefresh != null) RoundIconButton(OpenSkyIcons.Refresh, contentDescription = "Actualizar GPS", onClick = onRefresh)
        }
        // Abajo, en una sola fila: altitud y chip de precisión.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InlineStat("Altitud", "%.0f".format(altitudeM), "m", Modifier.weight(1f))
            when {
                manual -> InfoChip("Manual (Ajustes)")
                accuracyM == null -> InfoChip("GPS", tone = LiveTone.Busy)
                else -> InfoChip("GPS ±%.0f m".format(accuracyM), tone = accuracyTone(accuracyM))
            }
        }
    }
}

/** «20.65970° N · 103.34960° O» con hemisferios en lugar de signos. */
private fun coordinatesText(lat: Double, lon: Double): String =
    "%.5f° %s · %.5f° %s".format(abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "O")

private fun accuracyTone(m: Float): LiveTone = when {
    m <= 20f -> LiveTone.Ok
    m <= 100f -> LiveTone.Busy
    else -> LiveTone.Off
}

/** Punto cardinal aproximado de un acimut (0° = norte, 90° = este). */
private fun cardinal(azDeg: Double): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    return names[(((azDeg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
}
