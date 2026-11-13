package com.opensky.virtuoso.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensky.virtuoso.alignment.AlignmentMode
import com.opensky.virtuoso.control.StartPosition
import com.opensky.virtuoso.ui.components.GlassAlertDialog
import com.opensky.virtuoso.ui.components.GroupedSection
import com.opensky.virtuoso.ui.components.HandPad
import com.opensky.virtuoso.ui.components.HintText
import com.opensky.virtuoso.ui.components.InfoCard
import com.opensky.virtuoso.ui.components.InsetDivider
import com.opensky.virtuoso.ui.components.LabeledValue
import com.opensky.virtuoso.ui.components.OpenSkyIcons
import com.opensky.virtuoso.ui.components.PadOptions
import com.opensky.virtuoso.ui.components.PillButton
import com.opensky.virtuoso.ui.components.ProminentButton
import com.opensky.virtuoso.ui.components.SelectableRow
import com.opensky.virtuoso.ui.components.StatusPill
import com.opensky.virtuoso.ui.components.StatusTone
import com.opensky.virtuoso.ui.components.TintButton
import com.opensky.virtuoso.ui.components.screenPadding
import com.opensky.virtuoso.ui.components.uiPalette
import com.opensky.virtuoso.ui.viewmodel.AlignmentViewModel
import com.opensky.virtuoso.ui.viewmodel.ControlViewModel

/**
 * Alineación (estilo lista agrupada de iOS). Solo cambia la presentación: los estados que se leen
 * del ViewModel y las acciones que se le piden son los mismos de siempre.
 */
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
    val doubtful by vm.doubtful.collectAsStateWithLifecycle()
    doubtful?.let { d ->
        GlassAlertDialog(
            onDismissRequest = { vm.dismissDoubtful() },
            title = { Text("¿Seguro que es ${d.star.name}?") },
            text = {
                Text("El tubo está a %.0f° de donde debería estar %s (lo normal en este paso es menos de %.0f°). ".format(d.deviationDeg, d.star.name, d.limitDeg) +
                    "Confirmar una estrella equivocada estropea toda la alineación. Revisa con el buscador o con una app de mapa del cielo.")
            },
            confirmButton = { TextButton(onClick = { vm.confirmCentered(force = true) }) { Text("Sí, es ${d.star.name}") } },
            dismissButton = { TextButton(onClick = { vm.dismissDoubtful() }) { Text("No, voy a revisar") } },
        )
    }
    MessageEffect(message, snackbar, vm::clearMessage)
    MessageEffect(padMessage, snackbar, pad::clearMessage)

    val palette = uiPalette()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Estado: píldoras en vez de texto suelto.
        if (mount == null) {
            StatusPill("Conecta la montura en Inicio antes de alinear.", tone = StatusTone.Danger, icon = OpenSkyIcons.Plug)
        } else if (mount?.startPosition == StartPosition.UNCONFIRMED && state.allSamples.isEmpty()) {
            StatusPill(
                "Confirma primero la posición de inicio en Inicio (tubo horizontal hacia el norte). Sin eso los GoTo están bloqueados.",
                tone = StatusTone.Caution,
                icon = OpenSkyIcons.Warning,
            )
        }

        // Modo de alineación: lista con radio de iOS; la fila entera es pulsable.
        GroupedSection("Modo de alineación", contentPadding = PaddingValues(0.dp), spacing = 0.dp) {
            Column(Modifier.selectableGroup()) {
                AlignmentMode.entries.forEachIndexed { i, mode ->
                    if (i > 0) InsetDivider()
                    SelectableRow(
                        title = mode.label,
                        description = mode.description,
                        selected = state.mode == mode,
                        onClick = { vm.begin(mode) },
                    )
                }
            }
            InsetDivider(startInset = 0.dp)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Reset Alignment", onClick = { vm.reset() }, icon = OpenSkyIcons.Refresh, destructive = true)
                Text(
                    "Vuelve a la orientación IMU (posición de inicio) y borra las estrellas confirmadas.",
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        state.mode?.let { mode ->
            val brightest = mode == AlignmentMode.BRIGHTEST_ONE || mode == AlignmentMode.BRIGHTEST_TWO
            val done = state.alignmentSamples.size
            GroupedSection("Progreso: $done/${mode.starsRequired} estrellas", contentPadding = PaddingValues(0.dp), spacing = 0.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / mode.starsRequired).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = palette.accent,
                        trackColor = palette.track,
                        strokeCap = StrokeCap.Round,
                    )
                    if (busy) HintText("Moviendo la montura…")
                    if (state.isComplete) {
                        Text("✓ Alineación completa", color = palette.success, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    } else if (brightest) {
                        HintText("Apunta a mano (pad) a la estrella más brillante de la zona del cielo que elijas y pulsa Identificar.")
                        PillButton("Identificar estrella", onClick = { vm.identifyBrightest() }, icon = OpenSkyIcons.Target)
                        identified?.let { (star, sep) ->
                            Text(
                                "Parece ${star.name} (V=%.1f), a %.1f° del apuntado actual".format(star.magnitude ?: 0.0, sep),
                                color = palette.textPrimary,
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            HintText("Céntrala con el pad y confirma.")
                        }
                    } else {
                        Text("Estrellas sugeridas", color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        HintText("Bien repartidas, entre 20° y 75° de altura. Toca una para elegirla o pulsa GoTo.")
                    }
                }
                if (!state.isComplete) {
                    if (!brightest && suggestions.isNotEmpty()) {
                        InsetDivider(startInset = 0.dp)
                        Column(Modifier.selectableGroup()) {
                            suggestions.forEachIndexed { i, (star, hz) ->
                                if (i > 0) InsetDivider()
                                SelectableRow(
                                    title = star.name,
                                    description = "V=%.1f · Az %.0f° · Alt %.0f°".format(star.magnitude ?: 0.0, hz.azDeg, hz.altDeg),
                                    selected = current == star,
                                    onClick = { vm.selectStar(star) },
                                    trailing = { TintButton("GoTo", onClick = { vm.gotoStar(star) }, enabled = !busy) },
                                )
                            }
                        }
                    }
                    InsetDivider(startInset = 0.dp)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!brightest) PillButton("Actualizar lista", onClick = { vm.refreshSuggestions() }, icon = OpenSkyIcons.Refresh)
                        val setup = settings.opticalSetup()
                        HintText("Centra la estrella ${setup.centeringHint}.")
                        ProminentButton(
                            text = if (setup.usesCamera) "Estrella centrada en la cámara ✓" else "Estrella centrada en el ocular ✓",
                            onClick = { vm.confirmCentered() },
                            enabled = !busy && (if (brightest) identified != null else current != null),
                        )
                    }
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
