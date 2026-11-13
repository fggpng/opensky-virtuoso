package com.opensky.virtuoso.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opensky.virtuoso.control.PAD_RATES
import com.opensky.virtuoso.control.PadDirection
import com.opensky.virtuoso.control.PadMode
import kotlin.math.roundToInt

/**
 * Cruceta del mando: mantener pulsado = mover; soltar = detener (o volver al seguimiento).
 */
@Composable
fun HandPad(
    mode: PadMode,
    onPress: (PadDirection) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 84.dp,
) {
    val labels = when (mode) {
        PadMode.ALT_AZ -> mapOf(PadDirection.UP to "▲\nArriba", PadDirection.DOWN to "▼\nAbajo", PadDirection.LEFT to "◀\nIzq.", PadDirection.RIGHT to "▶\nDer.")
        PadMode.RA_DEC -> mapOf(PadDirection.UP to "▲\nN", PadDirection.DOWN to "▼\nS", PadDirection.LEFT to "◀\nE", PadDirection.RIGHT to "▶\nO")
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PadButton(labels.getValue(PadDirection.UP), PadDirection.UP, buttonSize, onPress, onRelease)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PadButton(labels.getValue(PadDirection.LEFT), PadDirection.LEFT, buttonSize, onPress, onRelease)
            Spacer(Modifier.size(buttonSize))
            PadButton(labels.getValue(PadDirection.RIGHT), PadDirection.RIGHT, buttonSize, onPress, onRelease)
        }
        PadButton(labels.getValue(PadDirection.DOWN), PadDirection.DOWN, buttonSize, onPress, onRelease)
    }
}

private val PadKeyShape = ContinuousRoundedShape(20.dp)
private val PadKeyFlatShape = RoundedCornerShape(18.dp)

@Composable
private fun PadButton(label: String, direction: PadDirection, size: Dp, onPress: (PadDirection) -> Unit, onRelease: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val glass = LocalGlass.current
    val palette = uiPalette()
    // Con cristal: tecla translúcida con canto de luz; al pulsarla se llena del color de acento.
    // No se recorta (el texto no se sale), así que la forma continua solo se pinta: sin máscaras.
    val shape = if (glass.enabled) PadKeyShape else PadKeyFlatShape
    val rim = remember(glass.card) { glass.card.rimBrush() }
    val background = when {
        glass.enabled -> if (pressed) palette.accent else palette.controlFill
        else -> if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    }
    val textColor = when {
        glass.enabled -> if (pressed) palette.onAccent else palette.textPrimary
        else -> if (pressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(background, shape)
            .then(if (glass.enabled) Modifier.border(1.dp, rim, shape) else Modifier)
            .pointerInput(direction) {
                detectTapGestures(onPress = {
                    pressed = true
                    currentOnPress(direction)
                    try {
                        tryAwaitRelease()
                    } finally {
                        pressed = false
                        currentOnRelease()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 18.sp, color = textColor, textAlign = TextAlign.Center)
    }
}

/** Selector de velocidad 1×…800× sidérea y de modo de ejes. */
@Composable
fun PadOptions(
    rateIndex: Int, mode: PadMode,
    onRateChange: (Int) -> Unit, onModeChange: (PadMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlass.current.enabled
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Velocidad: ${PAD_RATES[rateIndex].let { if (it < 10) "%.0f".format(it) else it.roundToInt().toString() }}× sidérea",
            style = if (glass) MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            else MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = rateIndex.toFloat(),
            onValueChange = { onRateChange(it.roundToInt().coerceIn(PAD_RATES.indices)) },
            valueRange = 0f..(PAD_RATES.size - 1).toFloat(),
            steps = PAD_RATES.size - 2,
        )
        if (glass) {
            SegmentedControl(
                options = listOf("Ejes Alt/Az", "AR/Dec (N-S-E-O)"),
                selectedIndex = if (mode == PadMode.ALT_AZ) 0 else 1,
                onSelect = { onModeChange(if (it == 0) PadMode.ALT_AZ else PadMode.RA_DEC) },
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == PadMode.ALT_AZ, onClick = { onModeChange(PadMode.ALT_AZ) }, label = { Text("Ejes Alt/Az") })
                FilterChip(selected = mode == PadMode.RA_DEC, onClick = { onModeChange(PadMode.RA_DEC) }, label = { Text("AR/Dec (N-S-E-O)") })
            }
        }
    }
}
