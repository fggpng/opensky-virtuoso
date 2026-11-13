package com.opensky.virtuoso.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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

/**
 * Efecto cristal (estilo «Liquid Glass»): solo en Android 16 o superior, donde el desenfoque por
 * GPU es estable y barato. En versiones anteriores la app conserva su aspecto plano de siempre.
 */
object GlassSupport {
    const val MIN_SDK = 36 // Android 16
    val available: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK
}

/** Sombra suave dibujada solo por fuera de la forma (no oscurece el cristal translúcido). */
@Immutable
data class GlassShadow(val color: Color, val spread: Dp, val offsetY: Dp)

/**
 * Material de cristal: relleno translúcido, reflejo diagonal («refracción») y canto especular
 * (la luz entra por arriba a la izquierda y se refleja abajo a la derecha).
 */
@Immutable
data class GlassMaterial(val fill: Color, val sheen: Brush, val rim: Brush, val shadow: GlassShadow?)

/** Si el cristal está activo en la pantalla actual y con qué paleta (azul o roja nocturna). */
@Immutable
data class GlassStyle(val enabled: Boolean, val night: Boolean) {
    /** Tarjetas (secciones agrupadas). */
    val card: GlassMaterial get() = if (night) NightCard else DayCard

    /** Barra de pestañas flotante (lleva desenfoque real debajo). */
    val bar: GlassMaterial get() = if (night) NightBar else DayBar

    /** Ventanas de diálogo (el sistema desenfoca la pantalla de atrás). */
    val dialog: GlassMaterial get() = if (night) NightDialog else DayDialog
}

private fun diagonal(vararg stops: Pair<Float, Color>): Brush =
    Brush.linearGradient(*stops, start = Offset.Zero, end = Offset.Infinite)

private val White = Color.White
private val Black = Color.Black

private val DayCard = GlassMaterial(
    fill = Color(0xFF0F172A).copy(alpha = 0.65f),
    sheen = diagonal(0f to White.copy(alpha = 0.12f), 1f to White.copy(alpha = 0.02f)),
    rim = diagonal(0f to White.copy(alpha = 0.38f), 0.5f to White.copy(alpha = 0.04f), 1f to White.copy(alpha = 0.18f)),
    shadow = GlassShadow(Black.copy(alpha = 0.35f), spread = 16.dp, offsetY = 6.dp),
)

private val DayBar = GlassMaterial(
    fill = Color(0xFF111827).copy(alpha = 0.82f),
    sheen = diagonal(0f to White.copy(alpha = 0.14f), 1f to White.copy(alpha = 0.04f)),
    rim = diagonal(0f to White.copy(alpha = 0.35f), 0.5f to White.copy(alpha = 0.05f), 1f to White.copy(alpha = 0.15f)),
    shadow = GlassShadow(Black.copy(alpha = 0.40f), spread = 18.dp, offsetY = 8.dp),
)

private val DayDialog = GlassMaterial(
    fill = Color(0xFF0F172A).copy(alpha = 0.80f),
    sheen = DayCard.sheen,
    rim = DayCard.rim,
    shadow = GlassShadow(Black.copy(alpha = 0.45f), spread = 20.dp, offsetY = 10.dp),
)

// Modo nocturno: el mismo cristal, pero solo con rojos tenues para no perder la adaptación a la oscuridad.
private val NightRed = Color(0xFFE53935)

private val NightCard = GlassMaterial(
    fill = Color(0xFF140000).copy(alpha = 0.70f),
    sheen = diagonal(0f to NightRed.copy(alpha = 0.07f), 1f to NightRed.copy(alpha = 0.01f)),
    rim = diagonal(0f to NightRed.copy(alpha = 0.45f), 0.5f to NightRed.copy(alpha = 0.06f), 1f to NightRed.copy(alpha = 0.22f)),
    shadow = GlassShadow(Black.copy(alpha = 0.35f), spread = 16.dp, offsetY = 6.dp),
)

private val NightBar = GlassMaterial(
    fill = Color(0xFF0A0000).copy(alpha = 0.86f),
    sheen = diagonal(0f to NightRed.copy(alpha = 0.09f), 1f to NightRed.copy(alpha = 0.02f)),
    rim = diagonal(0f to NightRed.copy(alpha = 0.42f), 0.5f to NightRed.copy(alpha = 0.06f), 1f to NightRed.copy(alpha = 0.20f)),
    shadow = GlassShadow(Black.copy(alpha = 0.40f), spread = 18.dp, offsetY = 8.dp),
)

private val NightDialog = GlassMaterial(
    fill = Color(0xFF120000).copy(alpha = 0.86f),
    sheen = NightCard.sheen,
    rim = NightCard.rim,
    shadow = GlassShadow(Black.copy(alpha = 0.45f), spread = 20.dp, offsetY = 10.dp),
)

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
// de recta a círculo. Construcción de «corner smoothing» de Figma con suavizado 0.6.
// ---------------------------------------------------------------------------------------------

@Immutable
class ContinuousRoundedShape(val radius: Dp, private val smoothing: Float = 0.6f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().apply { addContinuousRoundRect(size.width, size.height, with(density) { radius.toPx() }, smoothing) })

    /** La misma forma agrandada (para las capas de la sombra). */
    fun grown(by: Dp): ContinuousRoundedShape = ContinuousRoundedShape(radius + by, smoothing)

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
// Sombra suave: 8 capas cada vez más grandes y tenues, recortadas por fuera de la forma. Se calculan
// una sola vez por tamaño (unas pocas rutas), así que no cuesta nada al desplazar.
// ---------------------------------------------------------------------------------------------

private const val SHADOW_STEPS = 8

private class ShadowLayers(val paths: List<Path>, val color: Color)

private fun CacheDrawScope.shadowLayers(shape: Shape, shadow: GlassShadow): ShadowLayers {
    val spread = shadow.spread.toPx()
    val dy = shadow.offsetY.toPx()
    val paths = (1..SHADOW_STEPS).map { i ->
        val e = spread * i / SHADOW_STEPS
        val layerShape = if (shape is ContinuousRoundedShape) shape.grown(e.toDp()) else shape
        Path().apply {
            addOutline(layerShape.createOutline(Size(size.width + 2 * e, size.height + 2 * e), layoutDirection, this@shadowLayers))
            translate(Offset(-e, dy - e))
        }
    }
    // Con todas las capas superpuestas junto al borde se llega a la opacidad pedida (p. ej. 0.35).
    val perLayer = 1f - (1f - shadow.color.alpha).pow(1f / SHADOW_STEPS)
    return ShadowLayers(paths, shadow.color.copy(alpha = perLayer))
}

private fun DrawScope.drawShadowLayers(outline: Path, layers: ShadowLayers) {
    clipPath(outline, clipOp = ClipOp.Difference) {
        layers.paths.forEach { drawPath(it, layers.color) }
    }
}

/**
 * Superficie de cristal multicapa sin desenfoque: sombra exterior, relleno translúcido, reflejo
 * diagonal y canto especular de 1 dp por encima del contenido. Detrás de las tarjetas solo hay el
 * fondo ambiental (un degradado suave), así que desenfocarlo no cambiaría nada y gastaría GPU.
 */
fun Modifier.liquidGlass(shape: Shape, material: GlassMaterial): Modifier = drawWithCache {
    val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val shadow = material.shadow?.let { shadowLayers(shape, it) }
    val rim = Stroke(width = 1.dp.toPx())
    onDrawWithContent {
        shadow?.let { drawShadowLayers(outline, it) }
        drawPath(outline, material.fill)
        drawPath(outline, material.sheen)
        drawContent()
        drawPath(outline, material.rim, style = rim)
    }
}

// ---------------------------------------------------------------------------------------------
// Desenfoque real (barra flotante): el contenido de la pantalla se graba en una capa de GPU y la
// barra dibuja esa misma capa desenfocada detrás de su cristal.
// ---------------------------------------------------------------------------------------------

/** Contenido que se verá desenfocado detrás de los paneles de cristal. */
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
 * Los paneles de cristal reutilizan esa misma capa para desenfocarla: no se dibuja nada dos veces
 * en la CPU, y cuando el contenido cambia (al desplazar una lista) la GPU actualiza el desenfoque sola.
 */
fun Modifier.glassSource(state: GlassBackdropState): Modifier = this
    .onGloballyPositioned { state.sourceOffset = it.positionInRoot() }
    .drawWithContent {
        state.contentLayer.record { this@drawWithContent.drawContent() }
        drawLayer(state.contentLayer)
    }

/**
 * Panel de cristal con desenfoque real de lo que tiene detrás. Solo se desenfoca el área del panel
 * (más un margen para que los bordes no se vean cortados), no la pantalla completa.
 */
fun Modifier.glassPanel(state: GlassBackdropState, shape: Shape, material: GlassMaterial, blurRadius: Dp = 24.dp): Modifier = composed {
    val blurLayer = rememberGraphicsLayer()
    var offset by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { offset = it.positionInRoot() }
        .drawWithCache {
            val r = blurRadius.toPx()
            val pad = ceil(r * 2f)
            blurLayer.renderEffect = BlurEffect(r, r, TileMode.Clamp)
            val layerSize = IntSize(ceil(size.width + 2 * pad).toInt(), ceil(size.height + 2 * pad).toInt())
            val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val shadow = material.shadow?.let { shadowLayers(shape, it) }
            val rim = Stroke(width = 1.dp.toPx())
            onDrawBehind {
                shadow?.let { drawShadowLayers(outline, it) }
                val rel = offset - state.sourceOffset
                blurLayer.record(size = layerSize) {
                    translate(left = -rel.x + pad, top = -rel.y + pad) { drawLayer(state.contentLayer) }
                }
                clipPath(outline) {
                    translate(left = -pad, top = -pad) { drawLayer(blurLayer) }
                    drawRect(material.fill)
                    drawRect(material.sheen)
                }
                drawPath(outline, material.rim, style = rim)
            }
        }
}

// ---------------------------------------------------------------------------------------------
// Fondo ambiental: degradado nocturno profundo y tres orbes de luz difusa, sin estrellas (las
// estrellas nítidas se veían a través de las tarjetas y ensuciaban la lectura).
// ---------------------------------------------------------------------------------------------

private class Orb(val x: Float, val y: Float, val radius: Float, val color: Color, val alpha: Float)

/**
 * Va en su propia capa de GPU: solo se vuelve a dibujar si cambia el tamaño de la pantalla. Se usa
 * pintura con tramado (dither) para que los degradados tan oscuros no muestren escalones.
 */
@Composable
fun AmbientBackdrop(night: Boolean, modifier: Modifier = Modifier) {
    Spacer(
        modifier.fillMaxSize().graphicsLayer().drawWithCache {
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
    val shape = ContinuousRoundedShape(28.dp)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { GlassDialogWindowEffect(); confirmButton() },
        dismissButton = dismissButton,
        title = title,
        text = text,
        modifier = Modifier.liquidGlass(shape, glass.dialog),
        shape = shape,
        containerColor = Color.Transparent,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textPrimary.copy(alpha = 0.86f),
        tonalElevation = 0.dp,
    )
}
