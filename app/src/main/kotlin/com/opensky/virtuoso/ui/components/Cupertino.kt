package com.opensky.virtuoso.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Componentes de estilo Cupertino (listas agrupadas de iOS) para el modo cristal. Fuera del modo
 * cristal usan los colores del tema Material, así que también funcionan en el aspecto plano.
 * En el modo nocturno todo sale en rojos tenues: nada de blanco ni azul.
 */

/** Medidas de la barra de pestañas flotante (las usa también la pantalla para dejarle sitio). */
object GlassMetrics {
    val TabBarHeight = 64.dp
    val TabBarMargin = 16.dp

    /** Aire entre la última tarjeta y la barra: 64 + 16 + 24 = 104 dp más la barra del sistema. */
    val TabBarClearance = 24.dp
}

/** Colores semánticos de la interfaz (texto, acento, avisos…) para el modo actual. */
@Immutable
data class UiPalette(
    val textPrimary: Color,
    val textSecondary: Color,
    val sectionHeader: Color,
    val accent: Color,
    val onAccent: Color,
    val destructive: Color,
    val divider: Color,
    val radioStroke: Color,
    val buttonFill: Color,
    val controlFill: Color,
    val track: Color,
    val danger: Color,
    val dangerFill: Color,
    val dangerBorder: Color,
    val caution: Color,
    val cautionFill: Color,
    val cautionBorder: Color,
    val success: Color,
    val tabInactive: Color,
    val tabActivePill: Color,
    val tabPillRim: Color,
    val segmentTrack: Color,
    val segmentThumb: Color,
    val disabledFill: Color,
    val disabledText: Color,
)

private val IosLabelSecondary = Color(0xFFEBEBF5)
private val IosBlue = Color(0xFF0A84FF)
private val IosRed = Color(0xFFFF453A)
private val IosOrange = Color(0xFFFF9F0A)

private val CupertinoPalette = UiPalette(
    textPrimary = Color.White,
    textSecondary = IosLabelSecondary.copy(alpha = 0.65f),
    sectionHeader = IosLabelSecondary.copy(alpha = 0.60f),
    accent = IosBlue,
    onAccent = Color.White,
    destructive = IosRed,
    divider = Color.White.copy(alpha = 0.08f),
    radioStroke = Color.White.copy(alpha = 0.28f),
    buttonFill = Color.White.copy(alpha = 0.06f),
    controlFill = Color.White.copy(alpha = 0.08f),
    track = Color.White.copy(alpha = 0.12f),
    danger = Color(0xFFFF6961),
    dangerFill = IosRed.copy(alpha = 0.12f),
    dangerBorder = IosRed.copy(alpha = 0.28f),
    caution = Color(0xFFFFB340),
    cautionFill = IosOrange.copy(alpha = 0.12f),
    cautionBorder = IosOrange.copy(alpha = 0.28f),
    success = Color(0xFF30D158),
    tabInactive = Color.White.copy(alpha = 0.55f),
    tabActivePill = Color.White.copy(alpha = 0.12f),
    tabPillRim = Color.White,
    segmentTrack = Color.White.copy(alpha = 0.08f),
    segmentThumb = Color.White.copy(alpha = 0.20f),
    disabledFill = Color.White.copy(alpha = 0.08f),
    disabledText = Color.White.copy(alpha = 0.30f),
)

private val NightRedText = Color(0xFFD32F2F)
private val NightRedDim = Color(0xFFB71C1C)
private val NightRedAccent = Color(0xFFE53935)
private val NightRedDeep = Color(0xFF3A0000)
private val NightRedLine = Color(0xFF5C0000)

private val NightGlassPalette = UiPalette(
    textPrimary = NightRedText,
    textSecondary = NightRedDim,
    sectionHeader = NightRedDim.copy(alpha = 0.85f),
    accent = NightRedAccent,
    onAccent = Color.Black,
    destructive = Color(0xFFFF5252),
    divider = NightRedLine.copy(alpha = 0.55f),
    radioStroke = NightRedDim.copy(alpha = 0.60f),
    buttonFill = NightRedAccent.copy(alpha = 0.08f),
    controlFill = NightRedDeep.copy(alpha = 0.55f),
    track = NightRedDeep,
    danger = Color(0xFFFF5252),
    dangerFill = Color(0xFFFF5252).copy(alpha = 0.10f),
    dangerBorder = Color(0xFFFF5252).copy(alpha = 0.30f),
    caution = NightRedAccent,
    cautionFill = NightRedAccent.copy(alpha = 0.10f),
    cautionBorder = NightRedAccent.copy(alpha = 0.28f),
    success = NightRedAccent,
    tabInactive = NightRedDim.copy(alpha = 0.80f),
    tabActivePill = NightRedAccent.copy(alpha = 0.14f),
    tabPillRim = NightRedAccent,
    segmentTrack = NightRedDeep.copy(alpha = 0.55f),
    segmentThumb = NightRedLine,
    disabledFill = NightRedDeep.copy(alpha = 0.40f),
    disabledText = NightRedLine,
)

private fun flatPalette(cs: ColorScheme, night: Boolean): UiPalette {
    val caution = if (night) cs.primary else cs.secondary
    return UiPalette(
        textPrimary = cs.onSurface,
        textSecondary = cs.onSurfaceVariant,
        sectionHeader = cs.primary,
        accent = cs.primary,
        onAccent = cs.onPrimary,
        destructive = cs.error,
        divider = cs.outline.copy(alpha = 0.6f),
        radioStroke = cs.onSurfaceVariant.copy(alpha = 0.6f),
        buttonFill = cs.primary.copy(alpha = 0.10f),
        controlFill = cs.primaryContainer,
        track = cs.secondaryContainer,
        danger = cs.error,
        dangerFill = cs.error.copy(alpha = 0.12f),
        dangerBorder = cs.error.copy(alpha = 0.30f),
        caution = caution,
        cautionFill = caution.copy(alpha = 0.12f),
        cautionBorder = caution.copy(alpha = 0.30f),
        success = cs.tertiary,
        tabInactive = cs.onSurfaceVariant,
        tabActivePill = cs.secondaryContainer,
        tabPillRim = cs.primary,
        segmentTrack = cs.surfaceVariant,
        segmentThumb = cs.secondaryContainer,
        disabledFill = cs.onSurface.copy(alpha = 0.12f),
        disabledText = cs.onSurface.copy(alpha = 0.38f),
    )
}

@Composable
@ReadOnlyComposable
fun uiPalette(): UiPalette {
    val glass = LocalGlass.current
    return when {
        glass.enabled && !glass.night -> CupertinoPalette
        glass.enabled -> NightGlassPalette
        else -> flatPalette(MaterialTheme.colorScheme, glass.night)
    }
}

// Las esquinas continuas se usan para PINTAR. Para RECORTAR (que las ondas de toque no se salgan) se
// usa el rectángulo redondeado del mismo radio: la GPU lo recorta de forma analítica, sin máscaras.
private val CardShape = ContinuousRoundedShape(24.dp)
private val CardClip = RoundedCornerShape(24.dp)
private val ControlShape = ContinuousRoundedShape(14.dp)
private val ControlClip = RoundedCornerShape(14.dp)
private val CapsuleShape = RoundedCornerShape(percent = 50)

// ---------------------------------------------------------------------------------------------
// Estructura de pantalla
// ---------------------------------------------------------------------------------------------

/** Título grande de iOS (30 sp, negrita, alineado a la izquierda). */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Encabezado de sección de lista agrupada (mayúsculas, gris secundario). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).semantics { heading() },
        color = uiPalette().sectionHeader,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    )
}

/**
 * Tarjeta de cristal líquido: esquinas continuas de 24 dp, relleno translúcido con reflejo
 * diagonal y canto especular. Sin desenfoque: detrás solo pasa el fondo ambiental, que ya es difuso.
 * El texto de dentro es blanco (o rojo de noche).
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlass.current
    val palette = uiPalette()
    // El mismo modificador entre recomposiciones: la caché de dibujo (ruta y degradados) no se rehace.
    val surface = remember(glass.card) { Modifier.liquidGlass(CardShape, glass.card).clip(CardClip) }
    CompositionLocalProvider(LocalContentColor provides palette.textPrimary) {
        Column(
            modifier
                .fillMaxWidth()
                .then(surface)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * Sección de lista agrupada («inset grouped»): con cristal, el título va fuera, encima de la
 * tarjeta; sin cristal es una tarjeta Material con el título dentro.
 */
@Composable
fun GroupedSection(
    title: String?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalGlass.current.enabled) {
        Column(modifier.fillMaxWidth()) {
            if (title != null) SectionHeader(title)
            LiquidGlassCard(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(spacing), content = content)
        }
    } else {
        Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
        }
    }
}

/** Divisor de 0.5 dp que empieza alineado con el texto de la fila (52 dp con radio a la izquierda). */
@Composable
fun InsetDivider(modifier: Modifier = Modifier, startInset: Dp = 52.dp) {
    HorizontalDivider(modifier.padding(start = startInset), thickness = 0.5.dp, color = uiPalette().divider)
}

// ---------------------------------------------------------------------------------------------
// Avisos
// ---------------------------------------------------------------------------------------------

enum class StatusTone { Danger, Caution }

/** Píldora de estado (aviso con icono) en lugar de texto suelto. */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Danger,
    icon: ImageVector = OpenSkyIcons.Warning,
) {
    val palette = uiPalette()
    val fg = if (tone == StatusTone.Danger) palette.danger else palette.caution
    val fill = if (tone == StatusTone.Danger) palette.dangerFill else palette.cautionFill
    val stroke = if (tone == StatusTone.Danger) palette.dangerBorder else palette.cautionBorder
    Row(
        modifier
            .fillMaxWidth()
            .background(fill, ControlShape)
            .border(1.dp, stroke, ControlShape)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = fg, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------------------------
// Selección
// ---------------------------------------------------------------------------------------------

/** Radio de iOS (22 dp): aro fino inactivo; relleno azul con punto blanco de 8 dp activo. */
@Composable
fun IosRadio(selected: Boolean, modifier: Modifier = Modifier) {
    val palette = uiPalette()
    val t by animateFloatAsState(if (selected) 1f else 0f, label = "radio")
    Canvas(modifier.size(22.dp)) {
        val radius = size.minDimension / 2f
        val ring = 1.5.dp.toPx()
        if (t < 1f) drawCircle(palette.radioStroke.copy(alpha = palette.radioStroke.alpha * (1f - t)), radius - ring / 2f, style = Stroke(ring))
        if (t > 0f) {
            drawCircle(palette.accent.copy(alpha = palette.accent.alpha * t), radius)
            drawCircle(palette.onAccent, 4.dp.toPx() * t)
        }
    }
}

/**
 * Fila seleccionable de lista agrupada: toda la fila es pulsable (14 dp vertical, 16 dp horizontal),
 * radio a la izquierda, título 16 sp seminegrita y descripción secundaria 13/18 sp.
 */
@Composable
fun SelectableRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = uiPalette()
    Row(
        modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IosRadio(selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
            if (description != null) {
                Spacer(Modifier.height(4.dp))
                Text(description, color = palette.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Fila de navegación (texto + chevrón) para menús agrupados. */
@Composable
fun NavigationRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = uiPalette()
    Row(
        modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = palette.textPrimary, fontSize = 16.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
        Icon(OpenSkyIcons.Chevron, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(18.dp))
    }
}

/** Control segmentado de iOS (dos o tres opciones excluyentes). */
@Composable
fun SegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val palette = uiPalette()
    val track = RoundedCornerShape(10.dp)
    val thumb = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().height(36.dp).clip(track).background(palette.segmentTrack).padding(2.dp).selectableGroup(),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            // El color animado se lee solo al dibujar: la animación no recompone nada.
            val bg = animateColorAsState(if (selected) palette.segmentThumb else palette.segmentThumb.copy(alpha = 0f), label = "segment")
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(thumb)
                    .drawBehind { drawRect(bg.value) }
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) palette.textPrimary else palette.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Botones
// ---------------------------------------------------------------------------------------------

/** Botón secundario tipo píldora: 48 dp, esquinas de 14 dp, texto de acento 15 sp e icono opcional. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val palette = uiPalette()
    val fg = if (destructive) palette.destructive else palette.accent
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(palette.buttonFill, ControlShape)
            .clip(ControlClip)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Botón principal relleno con el color de acento (acción que avanza el proceso). */
@Composable
fun ProminentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = uiPalette()
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .background(if (enabled) palette.accent else palette.disabledFill, ControlShape)
            .clip(ControlClip)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) palette.onAccent else palette.disabledText,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** Botón pequeño en cápsula teñida (p. ej. «GoTo» dentro de una fila). */
@Composable
fun TintButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = uiPalette()
    Box(
        modifier
            .height(34.dp)
            .clip(CapsuleShape)
            .background(palette.accent.copy(alpha = if (enabled) 0.18f else 0.08f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) palette.accent else palette.disabledText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Barra de pestañas flotante
// ---------------------------------------------------------------------------------------------

/**
 * Cápsula de cristal despegada de los bordes (16 dp a los lados y abajo, 64 dp de alto). Debajo
 * lleva desenfoque real del contenido que pasa por detrás, limitado a esta franja.
 */
@Composable
fun GlassTabBar(backdrop: GlassBackdropState, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val glass = LocalGlass.current
    val panel = rememberGlassPanelState()
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = GlassMetrics.TabBarMargin, end = GlassMetrics.TabBarMargin, bottom = GlassMetrics.TabBarMargin)
            .height(GlassMetrics.TabBarHeight),
    ) {
        // Fondo aparte: al cambiar de pestaña solo se recomponen las pestañas, el cristal no se toca.
        GlassBarBackground(backdrop, panel, glass.bar, glass.barShadow, glass.barLens)
        Row(
            Modifier.matchParentSize().clip(CapsuleShape).padding(6.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Burbuja de cristal (pestaña activa): relleno claro y filo especular diagonal, creados una vez por tamaño. */
private fun Modifier.glassBubble(fill: Color, rimColor: Color): Modifier = drawWithCache {
    val outline = Path().apply { addOutline(CapsuleShape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val rim = Brush.linearGradient(
        0f to rimColor.copy(alpha = 0.34f), 0.5f to rimColor.copy(alpha = 0.05f), 1f to rimColor.copy(alpha = 0.16f),
        start = Offset.Zero, end = Offset(size.width, size.height),
    )
    val stroke = Stroke(width = 1.dp.toPx())
    onDrawBehind {
        drawPath(outline, fill)
        drawPath(outline, rim, style = stroke)
    }
}

@Composable
private fun BoxScope.GlassBarBackground(
    backdrop: GlassBackdropState,
    panel: GlassPanelState,
    material: GlassMaterial,
    shadow: GlassShadow,
    lens: GlassLens,
) {
    Spacer(Modifier.matchParentSize().glassPanel(backdrop, panel, CapsuleShape, material, shadow, lens))
}

/**
 * Pestaña: la activa lleva una burbuja de cristal (relleno muy claro con su propio filo especular)
 * e icono y texto de acento; la inactiva va en gris. El cambio es instantáneo, como en iOS.
 */
@Composable
fun RowScope.GlassTab(selected: Boolean, label: String, icon: ImageVector, onClick: () -> Unit) {
    val palette = uiPalette()
    val tint = if (selected) palette.accent else palette.tabInactive
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .then(if (selected) Modifier.glassBubble(palette.tabActivePill, palette.tabPillRim) else Modifier)
            .clip(CapsuleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = tint,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
