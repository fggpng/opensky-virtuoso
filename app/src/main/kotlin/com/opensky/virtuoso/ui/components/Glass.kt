package com.opensky.virtuoso.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Efecto cristal («Liquid Glass») con presupuesto de rendimiento:
 *  - Tarjetas: solo relleno + reflejo (un único degradado) y canto especular de 1 dp. Sin desenfoque,
 *    sin sombra y sin recortes con formas complejas (en Android, recortar con una forma que no es un
 *    rectángulo redondeado obliga a generar una máscara en cada fotograma mientras la tarjeta se mueve).
 *  - Fondo ambiental: se rasteriza una sola vez en una textura de GPU.
 *  - Barra inferior: único elemento con desenfoque real, limitado a su franja y a 1/4 de resolución.
 * Todos los degradados, rutas y efectos se crean en drawWithCache (una vez por tamaño), nunca por fotograma.
 */

/**
 * Efecto cristal: solo en Android 16 o superior, donde el desenfoque por GPU es estable y barato.
 * En versiones anteriores la app conserva su aspecto plano de siempre.
 */
object GlassSupport {
    const val MIN_SDK = 36 // Android 16
    val available: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK
}

/** Sombra suave exterior (solo la barra flotante; se pinta una vez en un mapa de bits). */
@Immutable
data class GlassShadow(val color: Color, val spread: Dp, val offsetY: Dp)

/**
 * Material de cristal: relleno translúcido, reflejo diagonal (arriba-izquierda → abajo-derecha) y
 * canto especular de tres paradas (la luz entra arriba a la izquierda y se refleja abajo a la derecha).
 */
@Immutable
data class GlassMaterial(
    val base: Color,
    val sheenStart: Color,
    val sheenEnd: Color,
    val rimStart: Color,
    val rimMid: Color,
    val rimEnd: Color,
) {
    /**
     * Relleno y reflejo ya mezclados («source over»): pintar este único degradado da el mismo color
     * que pintar el relleno y encima el reflejo, pero con un solo pase de GPU en lugar de dos.
     */
    val surfaceStart: Color = sheenStart.compositeOver(base)
    val surfaceEnd: Color = sheenEnd.compositeOver(base)

    /** Degradado diagonal del cuerpo para un tamaño concreto (llamar solo desde drawWithCache). */
    fun surfaceBrush(size: Size): Brush =
        Brush.linearGradient(listOf(surfaceStart, surfaceEnd), start = Offset.Zero, end = Offset(size.width, size.height))

    /** Canto especular para un tamaño concreto, o adaptable al tamaño si [size] es nulo. */
    fun rimBrush(size: Size? = null): Brush = Brush.linearGradient(
        0f to rimStart, 0.5f to rimMid, 1f to rimEnd,
        start = Offset.Zero,
        end = if (size == null) Offset.Infinite else Offset(size.width, size.height),
    )
}

/** Si el cristal está activo en la pantalla actual y con qué paleta (azul o roja nocturna). */
@Immutable
data class GlassStyle(val enabled: Boolean, val night: Boolean) {
    /** Tarjetas (secciones agrupadas). */
    val card: GlassMaterial get() = if (night) NightCard else DayCard

    /** Barra de pestañas flotante (lleva desenfoque real debajo). */
    val bar: GlassMaterial get() = if (night) NightBar else DayBar

    /** Sombra de la barra flotante. */
    val barShadow: GlassShadow get() = BarShadow

    /** Ventanas de diálogo (el sistema desenfoca la pantalla de atrás). */
    val dialog: GlassMaterial get() = if (night) NightDialog else DayDialog
}

private val White = Color.White

private val DayCard = GlassMaterial(
    base = Color(0xFF0F172A).copy(alpha = 0.65f),
    sheenStart = White.copy(alpha = 0.12f),
    sheenEnd = White.copy(alpha = 0.02f),
    rimStart = White.copy(alpha = 0.38f),
    rimMid = White.copy(alpha = 0.04f),
    rimEnd = White.copy(alpha = 0.18f),
)

private val DayBar = GlassMaterial(
    base = Color(0xFF111827).copy(alpha = 0.82f),
    sheenStart = White.copy(alpha = 0.14f),
    sheenEnd = White.copy(alpha = 0.04f),
    rimStart = White.copy(alpha = 0.35f),
    rimMid = White.copy(alpha = 0.05f),
    rimEnd = White.copy(alpha = 0.15f),
)

private val DayDialog = DayCard.copy(base = Color(0xFF0F172A).copy(alpha = 0.80f))

// Modo nocturno: el mismo cristal, pero solo con rojos tenues para no perder la adaptación a la oscuridad.
private val NightRed = Color(0xFFE53935)

private val NightCard = GlassMaterial(
    base = Color(0xFF140000).copy(alpha = 0.70f),
    sheenStart = NightRed.copy(alpha = 0.07f),
    sheenEnd = NightRed.copy(alpha = 0.01f),
    rimStart = NightRed.copy(alpha = 0.45f),
    rimMid = NightRed.copy(alpha = 0.06f),
    rimEnd = NightRed.copy(alpha = 0.22f),
)

private val NightBar = GlassMaterial(
    base = Color(0xFF0A0000).copy(alpha = 0.86f),
    sheenStart = NightRed.copy(alpha = 0.09f),
    sheenEnd = NightRed.copy(alpha = 0.02f),
    rimStart = NightRed.copy(alpha = 0.42f),
    rimMid = NightRed.copy(alpha = 0.06f),
    rimEnd = NightRed.copy(alpha = 0.20f),
)

private val NightDialog = NightCard.copy(base = Color(0xFF120000).copy(alpha = 0.86f))

private val BarShadow = GlassShadow(Color.Black.copy(alpha = 0.40f), spread = 18.dp, offsetY = 8.dp)

val LocalGlass = staticCompositionLocalOf { GlassStyle(enabled = false, night = false) }

/** Espacio que hay que dejar libre al final de cada pantalla para la barra flotante (0 sin cristal). */
val LocalBottomInset = compositionLocalOf { 0.dp }

/** Relleno estándar de las pantallas; con cristal deja al final el hueco de la barra flotante. */
@Composable
fun screenPadding(): PaddingValues {
    val glass = LocalGlass.current.enabled
    val bottom = LocalBottomInset.current
    return PaddingValues(start = 16.dp, top = if (glass) 8.dp else 16.dp, end = 16.dp, bottom = if (bottom > 0.dp) bottom else 16.dp)
}

// ---------------------------------------------------------------------------------------------
// Esquinas continuas (squircle, como iOS): la curvatura crece poco a poco en vez de pasar de golpe
// de recta a círculo. Construcción de «corner smoothing» de Figma con suavizado 0.6. Se usan solo
// para PINTAR (relleno y canto); para recortar se usa el rectángulo redondeado equivalente, que la
// GPU resuelve de forma analítica y gratis.
// ---------------------------------------------------------------------------------------------

@Immutable
class ContinuousRoundedShape(val radius: Dp, private val smoothing: Float = 0.6f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().apply { addContinuousRoundRect(size.width, size.height, with(density) { radius.toPx() }, smoothing) })

    override fun equals(other: Any?): Boolean = other is ContinuousRoundedShape && other.radius == radius && other.smoothing == smoothing
    override fun hashCode(): Int = 31 * radius.hashCode() + smoothing.hashCode()
    override fun toString(): String = "ContinuousRoundedShape($radius)"
}

fun Path.addContinuousRoundRect(w: Float, h: Float, radius: Float, smoothing: Float = 0.6f) {
    val budget = min(w, h) / 2f
    val r = radius.coerceIn(0f, budget)
    if (r <= 0f) {
        addRect(Rect(0f, 0f, w, h))
        return
    }
    val s = smoothing.coerceIn(0f, max(0f, budget / r - 1f))
    val p = (1f + s) * r                       // largo de borde que ocupa cada esquina
    val arc = 90f * (1f - s)                   // parte circular de la esquina, en grados
    val arcChord = sin(Math.toRadians(arc / 2.0)).toFloat() * r * sqrt(2f)
    val alpha = (90f - arc) / 2f
    val p3p4 = r * tan(Math.toRadians(alpha / 2.0)).toFloat()
    val beta = 45.0 * s
    val c = p3p4 * cos(Math.toRadians(beta)).toFloat()
    val d = c * tan(Math.toRadians(beta)).toFloat()
    val b = (p - arcChord - c - d) / 3f
    val a = 2f * b
    val half = arc / 2f
    val r2 = 2f * r

    moveTo(w - p, 0f)
    cubicTo(w - p + a, 0f, w - p + a + b, 0f, w - p + a + b + c, d)
    arcTo(Rect(w - r2, 0f, w, r2), -45f - half, arc, false)
    cubicTo(w, p - a - b, w, p - a, w, p)
    lineTo(w, h - p)
    cubicTo(w, h - p + a, w, h - p + a + b, w - d, h - p + a + b + c)
    arcTo(Rect(w - r2, h - r2, w, h), 45f - half, arc, false)
    cubicTo(w - p + a + b, h, w - p + a, h, w - p, h)
    lineTo(p, h)
    cubicTo(p - a, h, p - a - b, h, p - a - b - c, h - d)
    arcTo(Rect(0f, h - r2, r2, h), 135f - half, arc, false)
    cubicTo(0f, h - p + a + b, 0f, h - p + a, 0f, h - p)
    lineTo(0f, p)
    cubicTo(0f, p - a, 0f, p - a - b, d, p - a - b - c)
    arcTo(Rect(0f, 0f, r2, r2), 225f - half, arc, false)
    cubicTo(p - a - b, 0f, p - a, 0f, p, 0f)
    close()
}

// ---------------------------------------------------------------------------------------------
// Tarjetas: superficie de cristal sin desenfoque (detrás solo está el fondo ambiental, que ya es
// difuso). Dos dibujos por fotograma: el cuerpo (relleno + reflejo en un degradado) y el canto.
// ---------------------------------------------------------------------------------------------

/**
 * Superficie de cristal multicapa. La ruta, los degradados y el trazo se crean una sola vez por
 * tamaño en drawWithCache; al desplazar, la GPU solo repite dos dibujos ya grabados.
 */
fun Modifier.liquidGlass(shape: Shape, material: GlassMaterial): Modifier = drawWithCache {
    val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val surface = material.surfaceBrush(size)
    val rim = material.rimBrush(size)
    val stroke = Stroke(width = 1.dp.toPx())
    onDrawWithContent {
        drawPath(outline, surface)
        drawContent()
        drawPath(outline, rim, style = stroke)
    }
}

// ---------------------------------------------------------------------------------------------
// Desenfoque real (solo barra flotante): el contenido de la pantalla se graba en una capa de GPU y
// la barra dibuja esa misma capa, reducida y desenfocada, detrás de su cristal.
// ---------------------------------------------------------------------------------------------

/** Contenido que se verá desenfocado detrás de la barra. */
@Stable
class GlassBackdropState internal constructor(internal val contentLayer: GraphicsLayer) {
    internal var sourceOffset by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberGlassBackdropState(): GlassBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdropState(layer) }
}

/**
 * Graba lo que dibuja este elemento (fondo y pantallas) en una capa de GPU y la dibuja normalmente.
 * La barra reutiliza esa misma capa para desenfocarla: no se dibuja nada dos veces en la CPU, y al
 * desplazar una lista la GPU actualiza el desenfoque sola (el desplazamiento solo mueve capas; esta
 * grabación no se repite).
 */
fun Modifier.glassSource(state: GlassBackdropState): Modifier = this
    .onGloballyPositioned { state.sourceOffset = it.positionInRoot() }
    .drawWithContent {
        state.contentLayer.record { this@drawWithContent.drawContent() }
        drawLayer(state.contentLayer)
    }

/** Capa de desenfoque, posición y sombra ya pintada de un panel de cristal. */
@Stable
class GlassPanelState internal constructor(internal val blurLayer: GraphicsLayer) {
    internal var offset by mutableStateOf(Offset.Zero)
    internal var shadowSize: Size = Size.Unspecified
    internal var shadowImage: ImageBitmap? = null
}

@Composable
fun rememberGlassPanelState(): GlassPanelState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassPanelState(layer) }
}

/** Escala a la que se graba y desenfoca lo que hay detrás de la barra (1/4: 16 veces menos píxeles). */
private const val BLUR_DOWNSAMPLE = 0.25f
private const val SHADOW_STEPS = 8

/**
 * Panel con desenfoque real de lo que pasa por detrás. Solo se procesa la franja del panel (más un
 * margen igual al radio, para que el borde no se vea cortado) y a 1/4 de resolución: con un
 * desenfoque tan amplio y el relleno al 82 %, la diferencia no se ve y la GPU trabaja 16 veces menos.
 * La sombra se pinta una sola vez en un mapa de bits y después solo se copia.
 */
fun Modifier.glassPanel(
    backdrop: GlassBackdropState,
    panel: GlassPanelState,
    shape: Shape,
    material: GlassMaterial,
    shadow: GlassShadow?,
    blurRadius: Dp = 24.dp,
): Modifier = this
    .onGloballyPositioned { panel.offset = it.positionInRoot() }
    .drawWithCache {
        val k = BLUR_DOWNSAMPLE
        val radius = blurRadius.toPx()
        val pad = ceil(radius)
        val layerSize = IntSize(
            ceil((size.width + 2 * pad) * k).toInt().coerceAtLeast(1),
            ceil((size.height + 2 * pad) * k).toInt().coerceAtLeast(1),
        )
        panel.blurLayer.renderEffect = BlurEffect(radius * k, radius * k, TileMode.Clamp)
        val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val surface = material.surfaceBrush(size)
        val rim = material.rimBrush(size)
        val stroke = Stroke(width = 1.dp.toPx())
        val shadowImage = shadow?.let { panelShadow(panel, shape, it) }
        val shadowTopLeft = shadow?.let { Offset(-it.spread.toPx(), -it.spread.toPx()) } ?: Offset.Zero
        onDrawBehind {
            // Sombra exterior: el desenfoque opaco de encima la tapa por dentro del panel.
            shadowImage?.let { drawImage(it, topLeft = shadowTopLeft) }
            // Lo que hay detrás de la barra, grabado a 1/4 de resolución (solo la franja del panel).
            val rel = panel.offset - backdrop.sourceOffset
            panel.blurLayer.record(size = layerSize) {
                scale(k, k, pivot = Offset.Zero) {
                    translate(left = -rel.x + pad, top = -rel.y + pad) { drawLayer(backdrop.contentLayer) }
                }
            }
            clipPath(outline) {
                translate(left = -pad, top = -pad) {
                    scale(1f / k, 1f / k, pivot = Offset.Zero) { drawLayer(panel.blurLayer) }
                }
                drawRect(surface)
            }
            drawPath(outline, rim, style = stroke)
        }
    }

/** Pinta la sombra del panel en un mapa de bits (una vez por tamaño) con las mismas 8 capas de antes. */
private fun CacheDrawScope.panelShadow(panel: GlassPanelState, shape: Shape, shadow: GlassShadow): ImageBitmap {
    panel.shadowImage?.let { if (panel.shadowSize == size) return it }
    val spread = shadow.spread.toPx()
    val dy = shadow.offsetY.toPx()
    val panelSize = size
    val width = ceil(panelSize.width + 2 * spread).toInt().coerceAtLeast(1)
    val height = ceil(panelSize.height + 2 * spread + dy).toInt().coerceAtLeast(1)
    val image = ImageBitmap(width, height)
    val perLayer = shadow.color.copy(alpha = 1f - (1f - shadow.color.alpha).pow(1f / SHADOW_STEPS))
    CanvasDrawScope().draw(this, layoutDirection, Canvas(image), Size(width.toFloat(), height.toFloat())) {
        for (i in 1..SHADOW_STEPS) {
            val e = spread * i / SHADOW_STEPS
            val layerOutline = shape.createOutline(Size(panelSize.width + 2 * e, panelSize.height + 2 * e), layoutDirection, this)
            translate(left = spread - e, top = spread - e + dy) { drawOutline(layerOutline, perLayer) }
        }
    }
    panel.shadowImage = image
    panel.shadowSize = size
    return image
}

// ---------------------------------------------------------------------------------------------
// Fondo ambiental: degradado nocturno profundo y tres orbes de luz difusa, sin estrellas.
// ---------------------------------------------------------------------------------------------

private class Orb(val x: Float, val y: Float, val radius: Float, val color: Color, val alpha: Float)

/**
 * El fondo no cambia al desplazar ni al cambiar de pestaña: se rasteriza UNA vez en una textura de
 * GPU (capa «offscreen») y en cada fotograma solo se copia esa textura. Los degradados se crean en
 * drawWithCache (una vez por tamaño) con tramado (dither) para que no se vean escalones.
 */
@Composable
fun AmbientBackdrop(night: Boolean, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithCache {
                val w = size.width
                val h = size.height
                // Sin tamaño todavía (los degradados de Android no admiten radio 0): no se dibuja nada.
                if (w < 1f || h < 1f) return@drawWithCache onDrawBehind { }
                val top = if (night) Color(0xFF070000) else Color(0xFF050811)
                val bottom = if (night) Color(0xFF0C0000) else Color(0xFF0A1020)
                val orbs = if (night) listOf(
                    Orb(w * 0.12f, h * 0.16f, w * 0.95f, Color(0xFF5C0000), 0.20f),
                    Orb(w * 0.96f, h * 0.50f, w * 0.85f, Color(0xFF4A0000), 0.16f),
                    Orb(w * 0.50f, h * 0.95f, w * 0.95f, Color(0xFF3A0000), 0.14f),
                ) else listOf(
                    Orb(w * 0.12f, h * 0.16f, w * 0.95f, Color(0xFF1E3A8A), 0.18f), // azul cobalto, arriba-izquierda
                    Orb(w * 0.96f, h * 0.50f, w * 0.85f, Color(0xFF312E81), 0.15f), // índigo, centro-derecha
                    Orb(w * 0.50f, h * 0.95f, w * 0.95f, Color(0xFF0369A1), 0.12f), // celeste oscuro, abajo (pad y barra)
                )
                val basePaint = android.graphics.Paint().apply {
                    isDither = true
                    shader = android.graphics.LinearGradient(0f, 0f, 0f, h, top.toArgb(), bottom.toArgb(), android.graphics.Shader.TileMode.CLAMP)
                }
                val orbPaints = orbs.map { o ->
                    android.graphics.Paint().apply {
                        isDither = true
                        isAntiAlias = true
                        shader = android.graphics.RadialGradient(
                            o.x, o.y, o.radius,
                            intArrayOf(
                                o.color.copy(alpha = o.alpha).toArgb(),
                                o.color.copy(alpha = o.alpha * 0.45f).toArgb(),
                                o.color.copy(alpha = 0f).toArgb(),
                            ),
                            floatArrayOf(0f, 0.5f, 1f),
                            android.graphics.Shader.TileMode.CLAMP,
                        )
                    }
                }
                onDrawBehind {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.drawRect(0f, 0f, w, h, basePaint)
                        orbs.forEachIndexed { i, o -> native.drawCircle(o.x, o.y, o.radius, orbPaints[i]) }
                    }
                }
            },
    )
}

// ---------------------------------------------------------------------------------------------
// Diálogos
// ---------------------------------------------------------------------------------------------

/** Desenfoca la pantalla detrás de una ventana de diálogo (lo hace el compositor del sistema). */
@Composable
private fun GlassDialogWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val attrs = window.attributes
            attrs.blurBehindRadius = (28 * view.resources.displayMetrics.density).toInt()
            window.attributes = attrs
            window.setDimAmount(0.3f)
        }
        onDispose { }
    }
}

private val DialogShape = ContinuousRoundedShape(28.dp)
private val DialogClip = RoundedCornerShape(28.dp)

/**
 * AlertDialog de la app: igual al de Material 3, pero de cristal cuando el efecto está activo
 * (esquinas continuas, relleno translúcido, canto especular y la pantalla de atrás desenfocada).
 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val glass = LocalGlass.current
    if (!glass.enabled) {
        AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton, dismissButton = dismissButton, title = title, text = text)
        return
    }
    val palette = uiPalette()
    val surface = remember(glass.dialog) { Modifier.liquidGlass(DialogShape, glass.dialog) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { GlassDialogWindowEffect(); confirmButton() },
        dismissButton = dismissButton,
        title = title,
        text = text,
        modifier = surface,
        shape = DialogClip,
        containerColor = Color.Transparent,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textPrimary.copy(alpha = 0.86f),
        tonalElevation = 0.dp,
    )
}
