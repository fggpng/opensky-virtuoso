package com.opensky.virtuoso.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Piezas de «instrumento» para pantallas con datos: cifras tabulares (todas del mismo ancho, así no
 * bailan al cambiar), unidades más pequeñas y tenues, indicadores de estado en vivo y chips.
 * Funcionan con y sin cristal (toman los colores de uiPalette()).
 */

/**
 * Cifras tabulares. En Compose no existe «fontVariantNumeric»; su equivalente es la característica
 * OpenType «tnum», que Roboto (la fuente del sistema) incluye.
 */
const val TABULAR_NUMS = "tnum"

private val StatShape = ContinuousRoundedShape(14.dp)

/** Valor con su unidad: la unidad va en labelSmall y con menos opacidad. */
@Composable
fun valueWithUnit(value: String, unit: String?): AnnotatedString {
    val palette = uiPalette()
    val unitSize = MaterialTheme.typography.labelSmall.fontSize
    return buildAnnotatedString {
        append(value)
        if (!unit.isNullOrEmpty()) {
            withStyle(SpanStyle(fontSize = unitSize, fontWeight = FontWeight.Medium, color = palette.textSecondary)) {
                append(if (unit.startsWith("″") || unit.startsWith("′") || unit == "×") unit else " $unit")
            }
        }
    }
}

/** Un dato de la cuadrícula: rótulo, valor, unidad y detalle opcional (p. ej. «f/10.0»). */
@Immutable
data class Stat(val label: String, val value: String, val unit: String? = null, val detail: String? = null)

/**
 * Cuadrícula de datos en columnas iguales, separadas por divisores finos, sobre un fondo apenas más
 * claro que la tarjeta para que se lean como un bloque delimitado.
 */
@Composable
fun StatGrid(stats: List<Stat>, modifier: Modifier = Modifier) {
    val palette = uiPalette()
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(palette.buttonFill, StatShape)
            .padding(vertical = 12.dp),
    ) {
        stats.forEachIndexed { i, stat ->
            if (i > 0) VerticalDivider(Modifier.fillMaxHeight(), thickness = 0.5.dp, color = palette.divider)
            StatCell(stat, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(stat: Stat, modifier: Modifier) {
    val palette = uiPalette()
    Column(modifier.padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stat.label.uppercase(),
            color = palette.sectionHeader,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            valueWithUnit(stat.value, stat.unit),
            color = palette.textPrimary,
            style = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR_NUMS),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (stat.detail != null) {
            Text(
                stat.detail,
                color = palette.textSecondary,
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TABULAR_NUMS),
                maxLines = 1,
            )
        }
    }
}

/** Dato en línea: rótulo pequeño y valor tabular con unidad (p. ej. «Altitud 1 566 m»). */
@Composable
fun InlineStat(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    val palette = uiPalette()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            valueWithUnit(value, unit),
            color = palette.textPrimary,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR_NUMS),
        )
    }
}

/** Tono de un indicador en vivo. De noche los tres son rojos (la paleta nocturna no tiene verde). */
enum class LiveTone { Ok, Busy, Off }

@Composable
private fun LiveTone.color(): Color {
    val palette = uiPalette()
    return when (this) {
        LiveTone.Ok -> palette.success
        LiveTone.Busy -> palette.caution
        LiveTone.Off -> palette.danger
    }
}

/** Indicador de estado en vivo: punto de color y texto («● Desconectada»). */
@Composable
fun LiveStatus(text: String, tone: LiveTone, modifier: Modifier = Modifier) {
    val color = tone.color()
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Indicador compacto para la cabecera: cápsula translúcida con punto y texto corto. */
@Composable
fun LiveStatusChip(text: String, tone: LiveTone, modifier: Modifier = Modifier) {
    val color = tone.color()
    Row(
        modifier
            .background(color.copy(alpha = 0.14f), CapsuleChipShape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Chip de dato (p. ej. «GPS ±6 m»), con icono opcional y tono. */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier, tone: LiveTone? = null, icon: ImageVector? = null) {
    val palette = uiPalette()
    val fg = tone?.color() ?: palette.textPrimary
    Row(
        modifier
            .background(if (tone != null) fg.copy(alpha = 0.14f) else palette.buttonFill, CapsuleChipShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = fg, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR_NUMS), maxLines = 1)
    }
}

/** Botón redondo de icono (ayuda, actualizar…), 36 dp de área táctil. */
@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = uiPalette()
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(22.dp))
    }
}

private val CapsuleChipShape = RoundedCornerShape(percent = 50)

/** Separación estándar dentro de una fila de botones simétricos. */
val ButtonRowSpacing = Arrangement.spacedBy(8.dp)
