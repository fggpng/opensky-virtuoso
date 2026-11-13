package com.opensky.virtuoso.ui.screens

import com.opensky.virtuoso.ui.components.screenPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opensky.virtuoso.control.Activity
import com.opensky.virtuoso.control.TrackingRate
import com.opensky.virtuoso.ui.components.HandPad
import com.opensky.virtuoso.ui.components.HintText
import com.opensky.virtuoso.ui.components.InfoCard
import com.opensky.virtuoso.ui.components.LabeledValue
import com.opensky.virtuoso.ui.components.OpenSkyIcons
import com.opensky.virtuoso.ui.components.PadOptions
import com.opensky.virtuoso.ui.components.WarningText
import com.opensky.virtuoso.ui.components.altText
import com.opensky.virtuoso.ui.components.azText
import com.opensky.virtuoso.ui.components.decText
import com.opensky.virtuoso.ui.components.raText
import com.opensky.virtuoso.ui.viewmodel.ControlViewModel

@Composable
fun ControlScreen(vm: ControlViewModel, snackbar: SnackbarHostState) {
    val mount by vm.mount.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageEffect(message, snackbar, vm::clearMessage)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = { vm.stopAll() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Icon(OpenSkyIcons.Stop, contentDescription = null)
            Text("  DETENER TODO", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (mount == null) WarningText("Montura desconectada")

        InfoCard("Posición") {
            val m = mount
            LabeledValue("Actividad", m?.activity?.name ?: "—")
            m?.target?.let { LabeledValue("Objetivo", it.name, monospace = false) }
            m?.pointing?.let { LabeledValue("Az / Alt", "${it.azText()}  ${it.altText()}") }
            m?.pointing?.let { p -> vm.maxExposureAt(p) }?.let {
                LabeledValue("Exposición máx. (rotación de campo)", com.opensky.virtuoso.control.FieldRotation.format(it))
            }
            m?.pointingApparent?.let { LabeledValue("AR / Dec (JNow)", "${it.raText()}  ${it.decText()}") }
            m?.pointingJ2000?.let { LabeledValue("AR / Dec (J2000)", "${it.raText()}  ${it.decText()}") }
            m?.let { LabeledValue("Encoders Az / Alt", "%.3f°  %.3f°".format(it.encoderAzDeg ?: 0.0, it.encoderAltDeg ?: 0.0)) }
            m?.message?.let { HintText(it) }
        }

        InfoCard("Mando manual") {
            PadOptions(settings.padRateIndex, settings.padMode, { vm.setRate(it) }, { vm.setMode(it) })
            HandPad(settings.padMode, onPress = vm::press, onRelease = vm::release, modifier = Modifier.align(Alignment.CenterHorizontally))
            HintText("Con el seguimiento activo, el pad se suma a él y al soltar se conserva el nuevo centrado.")
        }

        InfoCard("Seguimiento automático") {
            val tracking = mount?.activity == Activity.TRACKING
            LabeledValue("Estado", if (tracking) "Activo (${mount?.tracking?.label})" else "Detenido", monospace = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TrackingRate.SIDEREAL, TrackingRate.LUNAR, TrackingRate.SOLAR).forEach { rate ->
                    FilterChip(selected = tracking && mount?.tracking == rate, onClick = { vm.startTracking(rate) }, label = { Text(rate.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.startTracking(TrackingRate.EPHEMERIS) }) { Text("Seguir objetivo (efemérides)") }
                OutlinedButton(onClick = { vm.stopTracking() }) { Text("Parar") }
            }
            HintText("Alt-Az: la app recalcula ambas velocidades cada segundo (prealimentación + corrección por encoders). " +
                "Cerca del cenit el acimut debe girar muy rápido: evita objetos por encima de ~85°.")
        }
    }
}
