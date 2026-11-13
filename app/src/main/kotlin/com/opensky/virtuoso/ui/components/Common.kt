package com.opensky.virtuoso.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.astronomy.HorizontalCoord
import com.opensky.virtuoso.astronomy.math.Angles

/**
 * Tarjeta con título. Con el efecto cristal es una sección agrupada de iOS (título en mayúsculas
 * fuera y tarjeta de cristal líquido con el texto en blanco); sin él, la tarjeta Material de siempre.
 */
@Composable
fun InfoCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (LocalGlass.current.enabled) {
        GroupedSection(title, modifier, content = content)
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, monospace: Boolean = true) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun WarningText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun HintText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

fun EquatorialCoord.raText(): String = Angles.formatHms(raHours, 1)
fun EquatorialCoord.decText(): String = Angles.formatDms(decDeg, 0)
fun HorizontalCoord.azText(): String = "%.2f°".format(azDeg)
fun HorizontalCoord.altText(): String = "%+.2f°".format(altDeg)
