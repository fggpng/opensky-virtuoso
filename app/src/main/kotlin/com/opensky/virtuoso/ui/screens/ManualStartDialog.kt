package com.opensky.virtuoso.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensky.virtuoso.control.HeadingReference
import com.opensky.virtuoso.control.ManualHeading
import com.opensky.virtuoso.control.ManualStartPreview
import com.opensky.virtuoso.ui.components.GlassAlertDialog
import com.opensky.virtuoso.ui.components.HintText
import com.opensky.virtuoso.ui.components.WarningText
import com.opensky.virtuoso.ui.viewmodel.HomeViewModel

/**
 * «Escribir hacia dónde apunta el tubo»: el usuario lee el rumbo en una brújula o en una app de mapa
 * del cielo, lo escribe y lo revisa en un resumen antes de aplicarlo (dos pasos, como poka-yoke).
 */
@Composable
fun ManualStartDialog(vm: HomeViewModel, onClose: () -> Unit) {
    val preview by vm.manualPreview.collectAsStateWithLifecycle()
    val error by vm.manualError.collectAsStateWithLifecycle()
    val measuring by vm.checking.collectAsStateWithLifecycle()
    var azText by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf(HeadingReference.TRUE) }
    var measureAltitude by rememberSaveable { mutableStateOf(false) }
    val declination = remember { vm.declinationHere() }

    val close = { vm.cancelManualStart(); onClose() }
    val p = preview
    if (p == null) {
        GlassAlertDialog(
            onDismissRequest = close,
            title = { Text("Escribir hacia dónde apunta el tubo") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HintText("Apunta el tubo a donde quieras con el pad, lee su rumbo en una brújula o en una app de mapa del cielo " +
                        "(Star Walk, Stellarium…) y escríbelo aquí.")
                    OutlinedTextField(
                        value = azText,
                        onValueChange = { azText = it },
                        label = { Text("Rumbo del tubo (0–360°)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Text("¿Dónde lo leíste?", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(HeadingReference.TRUE, HeadingReference.MAGNETIC).forEach { r ->
                            FilterChip(selected = reference == r, onClick = { reference = r }, label = { Text(r.label) })
                        }
                    }
                    HintText(
                        when (reference) {
                            HeadingReference.TRUE -> "Las apps de mapa del cielo ya dan el rumbo respecto al norte verdadero: no hay nada que corregir."
                            HeadingReference.MAGNETIC -> declination?.let {
                                "La brújula marca el norte magnético. Aquí la declinación es %+.1f°; la app la suma sola.".format(it)
                            } ?: "Sin ubicación no se puede corregir la brújula: espera el GPS o usa una app de mapa del cielo."
                        }
                    )
                    Text("Altura del tubo", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !measureAltitude, onClick = { measureAltitude = false }, label = { Text("Horizontal (0°)") })
                        FilterChip(selected = measureAltitude, onClick = { measureAltitude = true }, label = { Text("Medir con el teléfono") })
                    }
                    HintText(
                        if (measureAltitude) "Apoya el teléfono plano sobre el tubo, con el borde superior hacia la boca, y no lo toques 2 s. " +
                            "Su brújula también se compara con lo que escribiste."
                        else "Deja el tubo horizontal con el pad antes de continuar."
                    )
                    error?.let { WarningText(it) }
                    if (measuring) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Text("Midiendo… no muevas el teléfono")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !measuring, onClick = { vm.prepareManualStart(azText, reference, measureAltitude) }) { Text("Revisar") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancelar") } },
        )
    } else {
        GlassAlertDialog(
            onDismissRequest = { vm.editManualStart() },
            title = { Text("¿Es correcto?") },
            text = { ManualStartSummary(p) },
            confirmButton = {
                TextButton(onClick = { vm.applyManualStart(); onClose() }) {
                    Text(if (p.suspicious) "Usar de todos modos" else "Sí, usar esta posición")
                }
            },
            dismissButton = { TextButton(onClick = { vm.editManualStart() }) { Text("Corregir") } },
        )
    }
}

@Composable
private fun ManualStartSummary(p: ManualStartPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "El tubo apunta hacia el %s: acimut %.1f° (norte verdadero) y altura %.1f°%s."
                .format(ManualHeading.cardinalName(p.trueAzDeg), p.trueAzDeg, p.altDeg, if (p.altitudeMeasured) " medida con el teléfono" else " (horizontal)"),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (p.reference == HeadingReference.MAGNETIC && p.declinationDeg != null) {
            HintText("Escribiste %.1f° de brújula; con la declinación de %+.1f° queda en %.1f°.".format(p.inputAzDeg, p.declinationDeg, p.trueAzDeg))
        }
        val diff = p.phoneDisagreementDeg
        if (diff != null && p.phoneAzDeg != null) {
            if (p.suspicious) {
                WarningText("La brújula del teléfono marca %.0f°: %.0f° de diferencia con lo que escribiste. Revisa el número y dónde lo leíste antes de aceptar."
                    .format(p.phoneAzDeg, diff))
            } else {
                HintText("La brújula del teléfono marca %.0f° (%.0f° de diferencia): coincide.".format(p.phoneAzDeg, diff))
            }
            if (p.phoneInterference) HintText("El teléfono detecta interferencia magnética: su brújula puede estar desviada por el metal del tubo.")
        }
        HintText("Al aceptar se borra la alineación anterior. Después alinea con estrellas para afinar la precisión.")
    }
}
