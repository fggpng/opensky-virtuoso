package com.opensky.virtuoso.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensky.virtuoso.ui.components.HintText
import com.opensky.virtuoso.ui.components.InfoCard
import com.opensky.virtuoso.ui.components.LabeledValue
import com.opensky.virtuoso.ui.components.WarningText
import com.opensky.virtuoso.ui.viewmodel.UpdateState
import com.opensky.virtuoso.ui.viewmodel.UpdateViewModel

@Composable
fun UpdateScreen(vm: UpdateViewModel, snackbar: SnackbarHostState) {
    val state by vm.state.collectAsStateWithLifecycle()
    val installerMessage by vm.installerMessage.collectAsStateWithLifecycle()
    MessageEffect(installerMessage, snackbar, vm::clearInstallerMessage)
    LaunchedEffect(Unit) { if (vm.state.value == UpdateState.Idle) vm.check() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard("Versión instalada") {
            LabeledValue("Versión", vm.installedVersion, monospace = false)
            if (vm.isConfigured) LabeledValue("Origen", "github.com/${vm.repo}", monospace = false)
        }

        InfoCard("Actualizaciones") {
            if (!vm.isConfigured) {
                WarningText("Esta compilación no tiene repositorio de actualizaciones configurado.")
                HintText("Las versiones compiladas por GitHub Actions lo traen de fábrica. En Android Studio, " +
                    "define opensky.updateRepo=usuario/repositorio en gradle.properties.")
            } else {
                when (val s = state) {
                    UpdateState.Idle -> HintText("Pulsa «Buscar actualización».")
                    UpdateState.Checking -> {
                        HintText("Consultando GitHub…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    UpdateState.UpToDate -> Text("Tienes la versión más reciente ✓", fontWeight = FontWeight.Bold)
                    is UpdateState.Available -> {
                        Text("Nueva versión ${s.release.versionName}", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary)
                        if (s.release.notes.isNotBlank()) {
                            Text("Cambios:", style = MaterialTheme.typography.titleSmall)
                            Text(s.release.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (s.release.sizeBytes > 0) HintText("Tamaño: %.1f MB".format(s.release.sizeBytes / 1_048_576.0))
                        Button(onClick = { vm.downloadAndInstall(s.release) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Descargar e instalar")
                        }
                    }
                    is UpdateState.Downloading -> {
                        Text("Descargando ${s.release.versionName}…")
                        val p = s.progress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is UpdateState.Installing -> HintText("Android te pedirá confirmar la instalación. La app se reiniciará al terminar.")
                    is UpdateState.NeedsPermission -> {
                        WarningText("Android necesita que permitas a OpenSky instalar actualizaciones.")
                        HintText("Activa «Permitir de esta fuente», vuelve atrás y pulsa de nuevo «Descargar e instalar».")
                        Button(onClick = { vm.openInstallPermission(s.release) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Abrir el permiso")
                        }
                    }
                    is UpdateState.Error -> WarningText(s.message)
                }
                if (state !is UpdateState.Checking && state !is UpdateState.Downloading && state !is UpdateState.Installing) {
                    OutlinedButton(onClick = { vm.check() }) { Text("Buscar actualización") }
                }
                HintText("Si no conecta, revisa tener datos móviles: la red Wi-Fi de la montura no tiene Internet.")
            }
        }
    }
}
