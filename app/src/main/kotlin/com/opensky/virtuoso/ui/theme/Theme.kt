package com.opensky.virtuoso.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Tema nocturno estándar (azul profundo). */
private val NightSky: ColorScheme = darkColorScheme(
    primary = Color(0xFF9DB4FF),
    onPrimary = Color(0xFF0B1026),
    primaryContainer = Color(0xFF26315C),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFFFD54F),
    onSecondary = Color(0xFF231A00),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF05070F),
    onBackground = Color(0xFFE3E6F2),
    surface = Color(0xFF0B1020),
    onSurface = Color(0xFFE3E6F2),
    surfaceVariant = Color(0xFF1A2138),
    onSurfaceVariant = Color(0xFFB9C0D8),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
    outline = Color(0xFF3D4766),
)

/**
 * Modo visión nocturna: solo rojos sobre negro para no destruir la adaptación a la oscuridad
 * (la rodopsina es poco sensible al rojo profundo).
 */
private val RedNight: ColorScheme = darkColorScheme(
    primary = Color(0xFFE53935),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3A0000),
    onPrimaryContainer = Color(0xFFFF8A80),
    secondary = Color(0xFFC62828),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFFB71C1C),
    background = Color(0xFF000000),
    onBackground = Color(0xFFD32F2F),
    surface = Color(0xFF0A0000),
    onSurface = Color(0xFFD32F2F),
    surfaceVariant = Color(0xFF1C0000),
    onSurfaceVariant = Color(0xFFB71C1C),
    error = Color(0xFFFF5252),
    onError = Color(0xFF000000),
    outline = Color(0xFF5C0000),
)

@Composable
fun OpenSkyTheme(redNightMode: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (redNightMode) RedNight else NightSky, content = content)
}
