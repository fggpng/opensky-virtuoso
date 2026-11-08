package com.opensky.virtuoso.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensky.virtuoso.alignment.AlignmentMode
import com.opensky.virtuoso.ui.components.HandPad
import com.opensky.virtuoso.ui.components.HintText
import com.opensky.virtuoso.ui.components.InfoCard
import com.opensky.virtuoso.ui.components.LabeledValue
import com.opensky.virtuoso.ui.components.PadOptions
import com.opensky.virtuoso.ui.components.WarningText
import com.opensky.virtuoso.ui.viewmodel.AlignmentViewModel
import com.opensky.virtuoso.ui.viewmodel.ControlViewModel

@Composable
fun AlignmentScreen(vm: AlignmentViewModel, pad: ControlViewModel, snackbar: SnackbarHostState) {
    val state by vm.alignment.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val identified by vm.identified.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val mount by vm.mount.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val padMessage by pad.message.collectAsStateWithLifecycle()
    MessageEffect(message, snackbar, vm::clearMessage)
    MessageEffect(padMessage, snackbar, pad::clearMessage)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (mount == null) WarningText("Conecta la montura en Inicio antes de alinear.")

        InfoCard("Modo de alineación") {
            AlignmentMode.entries.forEach { mode ->
                val selected = state.mode == mode
                Column(
                    Modifier.fillMaxWidth().clickable { vm.begin(mode) }.padding(vertical = 6.dp),
                ) {
                    Text((if (selected) "● " else "○ ") + mode.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    HintText(mode.description)
                }
            }
            HorizontalDivider()
            TextButton(onClick = { vm.reset() }) { Text("Reset Alignment (volver a la orientación IMU)") }
        }

        state.mode?.let { mode ->
            InfoCard("Progreso: ${state.alignmentSamples.size}/${mode.starsRequired} estrellas") {
                LinearProgressIndicator(
                    progress = { (state.alignmentSamples.size.toFloat() / mode.starsRequired).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) HintText("Moviendo la montura…")
                val brightest = mode == AlignmentMode.BRIGHTEST_ONE || mode == AlignmentMode.BRIGHTEST_TWO
                if (!state.isComplete) {
                    if (brightest) {
                        HintText("Apunta a mano (pad) a la estrella más brillante de la zona del cielo que elijas y pulsa Identificar.")
                        FilledTonalButton(onClick = { vm.identifyBrightest() }) { Text("Identificar estrella") }
                        identified?.let { (star, sep) ->
                            Text("Parece ${star.name} (V=%.1f), a %.1f° del apuntado actual".format(star.magnitude ?: 0.0, sep))
                            HintText("Céntrala con el pad y confirma.")
                        }
                    } else {
                        Text("Estrellas sugeridas (bien repartidas, 20°–75° de altura):", style = MaterialTheme.typography.titleSmall)
                        suggestions.forEach { (star, hz) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.selectStar(star) }.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text((if (current == star) "▶ " else "") + star.name, fontWeight = FontWeight.Medium)
                                    HintText("V=%.1f · Az %.0f° · Alt %.0f°".format(star.magnitude ?: 0.0, hz.azDeg, hz.altDeg))
                                }
                                OutlinedButton(onClick = { vm.gotoStar(star) }, enabled = !busy) { Text("GoTo") }
                            }
                        }
                        OutlinedButton(onClick = { vm.refreshSuggestions() }) { Text("Actualizar lista") }
                    }
                    Button(
                        onClick = { vm.confirmCentered() }, enabled = !busy && (if (brightest) identified != null else current != null),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Estrella centrada en el ocular ✓") }
                }
            }
        }

        InfoCard("Pad de centrado") {
            PadOptions(settings.padRateIndex, settings.padMode, { pad.setRate(it) }, { pad.setMode(it) })
            HandPad(settings.padMode, onPress = pad::press, onRelease = pad::release, modifier = Modifier.align(Alignment.CenterHorizontally), buttonSize = 72.dp)
            HintText("Consejo: termina el centrado moviéndote con flechas ▲ y ▶ para compensar la holgura de los engranajes.")
        }

        InfoCard("Calidad del modelo") {
            val q = state.quality
            LabeledValue("Muestras (alineación + sync)", "${state.alignmentSamples.size} + ${state.syncSamples.size}")
            val precision = q.precisionPercent
            if (precision != null) {
                Text("Precisión de alineación: %.1f %%".format(precision), fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary)
                LabeledValue("Error residual RMS", "%.2f′".format(q.rmsArcmin))
                q.maxResidualArcmin?.let { LabeledValue("Residuo máximo", "%.2f′".format(it)) }
                q.fieldHitProbability?.let { LabeledValue("Prob. de que el GoTo caiga en el campo", "%.1f %%".format(it * 100)) }
                LabeledValue("Campo de referencia (radio)", "%.0f′".format(q.referenceFieldRadiusArcmin))
            } else HintText(q.method)
            if (q.residualsArcmin.isNotEmpty() && precision != null) {
                state.allSamples.zip(q.residualsArcmin).forEach { (s, r) -> LabeledValue(s.name, "%.2f′".format(r)) }
            }
            HintText("Precisión = 100·(1 − RMS/R), R = radio del campo real del ocular de referencia (configurable en Ajustes). " +
                "Añade «Sync» en objetivos cercanos para mejorar localmente el apuntado.")
        }
    }
}
