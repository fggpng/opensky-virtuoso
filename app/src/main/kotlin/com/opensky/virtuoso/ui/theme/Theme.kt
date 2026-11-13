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
    inversePrimary = Color(0xFF3A4FB8),
    secondary = Color(0xFFFFD54F),
    onSecondary = Color(0xFF231A00),
    // Chips seleccionados y pista de los deslizadores: azul (antes salía el gris morado por defecto).
    secondaryContainer = Color(0xFF26315C),
    onSecondaryContainer = Color(0xFFDDE3FF),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF05070F),
    onBackground = Color(0xFFE3E6F2),
    surface = Color(0xFF0B1020),
    onSurface = Color(0xFFE3E6F2),
    surfaceVariant = Color(0xFF1A2138),
    onSurfaceVariant = Color(0xFFB9C0D8),
    surfaceContainerLowest = Color(0xFF05070F),
    surfaceContainerLow = Color(0xFF0D1324),
    surfaceContainer = Color(0xFF111829),
    surfaceContainerHigh = Color(0xFF161E33),
    surfaceContainerHighest = Color(0xFF1C253E),
    surfaceBright = Color(0xFF242D47),
    surfaceDim = Color(0xFF05070F),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
    outline = Color(0xFF3D4766),
    outlineVariant = Color(0xFF262E47),
)

/**
 * Variante Cupertino del tema azul para el modo cristal: acento azul de iOS, texto blanco,
 * texto secundario #EBEBF5 al 65 % y separadores muy tenues. Así los controles Material de las
 * pantallas (botones, interruptores, chips, campos) combinan con las tarjetas de cristal.
 */
private val CupertinoNight: ColorScheme = NightSky.copy(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    inversePrimary = Color(0xFF0A84FF),
    secondaryContainer = Color.White.copy(alpha = 0.14f),
    onSecondaryContainer = Color.White,
    background = Color(0xFF050811),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFEBEBF5).copy(alpha = 0.65f),
    inverseSurface = Color(0xFF1C2333),
    inverseOnSurface = Color.White,
    error = Color(0xFFFF6961),
    onError = Color.Black,
    outline = Color.White.copy(alpha = 0.22f),
    outlineVariant = Color.White.copy(alpha = 0.08f),
)

/**
 * Modo visión nocturna: solo rojos sobre negro para no destruir la adaptación a la oscuridad
 * (la rodopsina es poco sensible al rojo profundo). Se definen también los colores de
 * contenedores, avisos (snackbar) y chips: los valores por defecto de Material son grises claros
 * o morados, que de noche deslumbran.
 */
private val RedNight: ColorScheme = darkColorScheme(
    primary = Color(0xFFE53935),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3A0000),
    onPrimaryContainer = Color(0xFFFF8A80),
    inversePrimary = Color(0xFFE53935),
    secondary = Color(0xFFC62828),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF3A0000),
    onSecondaryContainer = Color(0xFFFF8A80),
    tertiary = Color(0xFFB71C1C),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF2A0000),
    onTertiaryContainer = Color(0xFFFF8A80),
    background = Color(0xFF000000),
    onBackground = Color(0xFFD32F2F),
    surface = Color(0xFF0A0000),
    onSurface = Color(0xFFD32F2F),
    surfaceVariant = Color(0xFF1C0000),
    onSurfaceVariant = Color(0xFFB71C1C),
    surfaceTint = Color(0xFFE53935),
    inverseSurface = Color(0xFF2A0000),
    inverseOnSurface = Color(0xFFFF8A80),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF080000),
    surfaceContainer = Color(0xFF0E0000),
    surfaceContainerHigh = Color(0xFF140000),
    surfaceContainerHighest = Color(0xFF1C0000),
    surfaceBright = Color(0xFF240000),
    surfaceDim = Color(0xFF000000),
    error = Color(0xFFFF5252),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF3A0000),
    onErrorContainer = Color(0xFFFF8A80),
    outline = Color(0xFF5C0000),
    outlineVariant = Color(0xFF3A0000),
    scrim = Color(0xFF000000),
)

/**
 * @param redNightMode modo nocturno rojo (tiene prioridad).
 * @param cupertino modo cristal con el tema azul: variante de colores estilo iOS.
 */
@Composable
fun OpenSkyTheme(redNightMode: Boolean, cupertino: Boolean = false, content: @Composable () -> Unit) {
    val scheme = when {
        redNightMode -> RedNight
        cupertino -> CupertinoNight
        else -> NightSky
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
