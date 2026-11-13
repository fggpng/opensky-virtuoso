package com.opensky.virtuoso.ui.screens

import com.opensky.virtuoso.ui.components.screenPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensky.virtuoso.sensors.PhoneMounting
import com.opensky.virtuoso.ui.components.HintText
import com.opensky.virtuoso.ui.components.InfoCard
import com.opensky.virtuoso.ui.components.LabeledValue
import com.opensky.virtuoso.ui.components.WarningText
import com.opensky.virtuoso.ui.viewmodel.SensorViewModel

@Composable
fun SensorScreen(vm: SensorViewModel, snackbar: SnackbarHostState) {
    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }
    val o by vm.orientation.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageEffect(message, snackbar, vm::clearMessage)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!vm.available) WarningText("Este teléfono no tiene sensor de vector de rotación (giroscopio/brújula).")

        InfoCard("Colocación del teléfono") {
            PhoneMounting.entries.forEach { m ->
                FilterChip(selected = settings.phoneMounting == m, onClick = { vm.setMounting(m) }, label = { Text(m.label) })
            }
        }

        InfoCard("Orientación medida") {
            val r = o
            if (r == null) HintText("Esperando datos del sensor…")
            else {
                Text("Az %.1f°   Alt %.1f°".format(r.trueAzimuthDeg, r.altitudeDeg), fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary)
                LabeledValue("Acimut magnético", "%.1f°".format(r.magneticAzimuthDeg))
                LabeledValue("Declinación magnética (WMM)", "%+.2f°".format(r.declinationDeg))
                LabeledValue("Calibración", r.accuracyLabel, monospace = false)
                r.headingAccuracyDeg?.let { LabeledValue("Incertidumbre de rumbo", "±%.1f°".format(it)) }
                if (r.fieldStrengthUt != null) {
                    LabeledValue("Campo medido / esperado", "%.1f / %s µT".format(r.fieldStrengthUt, r.expectedFieldUt?.let { "%.1f".format(it) } ?: "?"))
                }
                if (r.interference) WarningText("Interferencia magnética: el campo difiere >25 % del modelo. Aleja el teléfono de los motores/metal o sepáralo del tubo con un soporte no magnético.")
                if (r.expectedFieldUt == null) HintText("Sin ubicación todavía: la declinación magnética no está aplicada.")
            }
        }

        InfoCard("Calibración rápida") {
            HintText("1) Antes de colocarlo en el tubo, mueve el teléfono dibujando un «8» en el aire durante 10 s hasta que la calibración sea Alta.\n" +
                "2) Apóyalo sobre el tubo según la colocación elegida y no lo toques.\n" +
                "3) Pulsa el botón: se promedian 2.5 s de lecturas y se fijan los encoders de la montura.")
            Button(onClick = { vm.applyInitialOrientation() }, enabled = !busy && o != null, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Midiendo…" else "Usar como orientación inicial de la montura")
            }
        }
    }
}
