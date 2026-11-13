package com.opensky.virtuoso.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
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
import com.opensky.virtuoso.control.Diagnostics
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

    /** Óptica de la lente de la barra (Android 13+): tinte del 18 % y brillos blancos, o rojos de noche. */
    val barLens: GlassLens get() = if (night) NightLens else DayLens

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

// Lente de día: tinte 0.08/0.10/0.14 al 18 %. El filo brillante del shader se sustituye por un trazo
// fino de 1 dp blanco al 12 %, y el brillo del bisel se suaviza para que no parezca un borde grueso.
private val DayLens = GlassLens(
    tint = Color(0.08f, 0.10f, 0.14f), tintAmount = 0.18f, light = Color.White,
    glow = 0.12f, rim = 0f, cylinder = 0.08f, border = Color.White.copy(alpha = 0.12f),
)

// Lente de noche: tinte casi negro y brillos y borde en rojo tenue (nada de blanco).
private val NightLens = GlassLens(
    tint = Color(0.05f, 0f, 0f), tintAmount = 0.25f, light = Color(0.55f, 0.06f, 0.05f),
    glow = 0.12f, rim = 0f, cylinder = 0.08f, border = Color(0xFFE53935).copy(alpha = 0.22f),
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

/**
 * Contenido que se verá a través de la barra. Se graba UNA vez en la raíz de la app (fondo
 * ambiental + todas las pantallas: Inicio, Mando manual, Alineación, Ajustes…), así que cualquier
 * pantalla que se desplace queda conectada a la barra sin grabar nada más por pantalla.
 */
@Stable
class GlassBackdropState internal constructor(internal val contentLayer: GraphicsLayer) {
    internal var sourceOffset by mutableStateOf(Offset.Zero)
    internal var sourceSize by mutableStateOf(IntSize.Zero)
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
    .onGloballyPositioned {
        state.sourceOffset = it.positionInRoot()
        state.sourceSize = it.size
    }
    .drawWithContent {
        state.contentLayer.record { this@drawWithContent.drawContent() }
        drawLayer(state.contentLayer)
    }

/** Capa de la lente, posición, sombra ya pintada y shader (compilado una vez) de un panel de cristal. */
@Stable
class GlassPanelState internal constructor(internal val blurLayer: GraphicsLayer) {
    internal var offset by mutableStateOf(Offset.Zero)
    internal var shadowSize: Size = Size.Unspecified
    internal var shadowImage: ImageBitmap? = null
    internal var lens: Any? = null          // LiquidLens (Android 13+), se crea una sola vez
    internal var lensFailed = false         // si el shader no compila en este teléfono, desenfoque clásico
}

@Composable
fun rememberGlassPanelState(): GlassPanelState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassPanelState(layer) }
}

/**
 * Óptica de la lente de la barra: color y cantidad del tinte (muy poco: el fondo se ve claro) y
 * color de los brillos (blanco; rojo tenue en modo nocturno).
 */
@Immutable
data class GlassLens(
    val tint: Color,
    val tintAmount: Float,
    val light: Color,
    /** Intensidad del brillo Fresnel interior del bisel. */
    val glow: Float,
    /** Intensidad del filo especular del shader (0: lo sustituye el trazo fino de [border]). */
    val rim: Float,
    /** Luz de cilindro arriba y abajo. */
    val cylinder: Float,
    /** Borde exterior de 1 dp. */
    val border: Color,
)

/** Escala a la que se graba y desenfoca lo que hay detrás de la barra en el modo clásico (1/4). */
private const val BLUR_DOWNSAMPLE = 0.25f
private const val SHADOW_STEPS = 8

/** Ancho del bisel de la lente: las 26 unidades del diseño de referencia equivalen a 14 dp. */
private val LENS_BEVEL = 14.dp

/** Desenfoque de la lente: muy suave, para que lo de detrás se lea. */
private val LENS_BLUR = 4.dp

/**
 * Panel de cristal con lo que pasa por detrás. Con [lens] (Android 13+) es una lente de cristal
 * líquido: centro casi transparente con desenfoque de 4 dp, lupa y menisco invertido en el bisel,
 * aberración cromática, brillo Fresnel y filo especular (shader AGSL). Sin lente, o si el shader no
 * compila, es el panel clásico: desenfoque amplio a 1/4 de resolución con relleno translúcido.
 * Solo se procesa la franja del panel (más el margen justo); la sombra se pinta una sola vez.
 */
fun Modifier.glassPanel(
    backdrop: GlassBackdropState,
    panel: GlassPanelState,
    shape: Shape,
    material: GlassMaterial,
    shadow: GlassShadow?,
    lens: GlassLens? = null,
    blurRadius: Dp = 24.dp,
): Modifier = this
    .onGloballyPositioned { panel.offset = it.positionInRoot() }
    .drawWithCache {
        val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val shadowImage = shadow?.let { panelShadow(panel, shape, it) }
        val shadowTopLeft = shadow?.let { Offset(-it.spread.toPx(), -it.spread.toPx()) } ?: Offset.Zero
        val geometry = if (lens != null && !panel.lensFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            liquidLens(backdrop, panel, lens)
        } else null

        if (geometry != null && lens != null) {
            val border = Stroke(width = 1.dp.toPx())
            onDrawBehind {
                // Sombra exterior: la lente es opaca por dentro y la tapa.
                shadowImage?.let { drawImage(it, topLeft = shadowTopLeft) }
                // Lo que hay detrás de la barra (y un margen para el menisco), a resolución completa.
                val rel = panel.offset - backdrop.sourceOffset
                panel.blurLayer.record(size = geometry.layerSize) {
                    translate(left = -rel.x + geometry.left, top = -rel.y + geometry.top) { drawLayer(backdrop.contentLayer) }
                }
                clipPath(outline) {
                    translate(left = -geometry.left, top = -geometry.top) { drawLayer(panel.blurLayer) }
                }
                // Borde exterior: trazo fino de 1 dp (blanco al 12 %).
                drawPath(outline, lens.border, style = border)
            }
        } else {
            val k = BLUR_DOWNSAMPLE
            val radius = blurRadius.toPx()
            val pad = ceil(radius)
            val layerSize = IntSize(
                ceil((size.width + 2 * pad) * k).toInt().coerceAtLeast(1),
                ceil((size.height + 2 * pad) * k).toInt().coerceAtLeast(1),
            )
            panel.blurLayer.renderEffect = BlurEffect(radius * k, radius * k, TileMode.Clamp)
            val surface = material.surfaceBrush(size)
            val rim = material.rimBrush(size)
            val stroke = Stroke(width = 1.dp.toPx())
            onDrawBehind {
                shadowImage?.let { drawImage(it, topLeft = shadowTopLeft) }
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
                // Mismo borde fino que la lente si es la barra; si no, el canto especular del material.
                val borderColor = lens?.border
                if (borderColor != null) drawPath(outline, borderColor, style = stroke) else drawPath(outline, rim, style = stroke)
            }
        }
    }

/** Dónde cae la barra dentro de la capa de la lente y tamaño de esa capa. */
private class LensGeometry(val left: Float, val top: Float, val layerSize: IntSize)

/**
 * Prepara la lente para el tamaño actual (solo aquí se tocan los uniforms: una vez por tamaño o
 * posición, nunca por fotograma). Devuelve null si el shader no está disponible.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun CacheDrawScope.liquidLens(backdrop: GlassBackdropState, panel: GlassPanelState, optics: GlassLens): LensGeometry? {
    val unit = LENS_BEVEL.toPx() / 26f
    val blurPx = LENS_BLUR.toPx()
    // Margen para el menisco (hasta 16 unidades hacia fuera), la aberración y el desenfoque.
    val pad = ceil(16f * unit + 2.2f * unit + 2f * blurPx + 2f)
    val rel = panel.offset - backdrop.sourceOffset
    val source = backdrop.sourceSize
    // Sin salirse de la pantalla: fuera de ella no hay imagen y los bordes se oscurecerían.
    val left = min(pad, rel.x).coerceAtLeast(0f)
    val top = min(pad, rel.y).coerceAtLeast(0f)
    val right = if (source.width > 0) min(pad, source.width - rel.x - size.width).coerceAtLeast(0f) else pad
    val bottom = if (source.height > 0) min(pad, source.height - rel.y - size.height).coerceAtLeast(0f) else pad
    val layerSize = IntSize(
        ceil(size.width + left + right).toInt().coerceAtLeast(1),
        ceil(size.height + top + bottom).toInt().coerceAtLeast(1),
    )
    return try {
        val shader = (panel.lens as? LiquidLens) ?: LiquidLens().also { panel.lens = it }
        panel.blurLayer.renderEffect = shader.effect(
            originX = left, originY = top,
            width = size.width, height = size.height,
            layerWidth = layerSize.width.toFloat(), layerHeight = layerSize.height.toFloat(),
            cornerRadius = min(size.width, size.height) / 2f,
            unit = unit, blurPx = blurPx, optics = optics,
        )
        LensGeometry(left, top, layerSize)
    } catch (e: Exception) {
        panel.lensFailed = true
        Diagnostics.log("Lente de cristal (AGSL) no disponible, se usa el desenfoque clásico: ${e.message}")
        null
    }
}

/** Shader de la lente, compilado una sola vez por barra; solo se le cambian los uniforms. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class LiquidLens {
    private val shader = RuntimeShader(LIQUID_GLASS_AGSL)

    fun effect(
        originX: Float, originY: Float, width: Float, height: Float, layerWidth: Float, layerHeight: Float,
        cornerRadius: Float, unit: Float, blurPx: Float, optics: GlassLens,
    ): RenderEffect {
        shader.setFloatUniform("origin", originX, originY)
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("layerSize", layerWidth, layerHeight)
        shader.setFloatUniform("cornerRadius", cornerRadius)
        shader.setFloatUniform("unit", unit)
        shader.setFloatUniform("tint", optics.tint.red, optics.tint.green, optics.tint.blue)
        shader.setFloatUniform("tintAmount", optics.tintAmount)
        shader.setFloatUniform("light", optics.light.red, optics.light.green, optics.light.blue)
        shader.setFloatUniform("glowStrength", optics.glow)
        shader.setFloatUniform("rimStrength", optics.rim)
        shader.setFloatUniform("cylinderStrength", optics.cylinder)
        // Primero el desenfoque suave y, sobre esa imagen, la refracción de la lente.
        val refraction = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        val blur = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
        return android.graphics.RenderEffect.createChainEffect(refraction, blur).asComposeRenderEffect()
    }
}

/**
 * Lente de cristal líquido de iOS 26 (AGSL). Es el shader de referencia con tres adaptaciones:
 * trabaja sobre una capa con margen alrededor de la barra (así el menisco del filo muestra, invertido,
 * lo que hay justo fuera en vez de repetir el borde), las medidas en píxeles se escalan con la
 * densidad de pantalla (26 unidades de bisel = 14 dp en cualquier teléfono) y el tinte y los brillos
 * llegan como uniforms (blancos de día, rojos tenues de noche).
 */
private const val LIQUID_GLASS_AGSL = """
uniform shader content;
uniform float2 origin;
uniform float2 size;
uniform float2 layerSize;
uniform float cornerRadius;
uniform float unit;
uniform float3 tint;
uniform float tintAmount;
uniform float3 light;
uniform float glowStrength;
uniform float rimStrength;
uniform float cylinderStrength;

float sdRoundedBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 fragCoord) {
    float2 halfSize = size * 0.5;
    float2 p = fragCoord - (origin + halfSize);
    float r = min(cornerRadius, min(halfSize.x, halfSize.y));

    // Distancia al borde (negativa dentro de la cápsula); 1 px de holgura para el borde suavizado.
    float d = sdRoundedBox(p, halfSize, r);
    if (d > 1.0) {
        return half4(0.0);
    }
    float distFromEdge = max(-d, 0.0);
    float bevelWidth = min(size.y * 0.38, 26.0 * unit);
    float edgeFactor = clamp(1.0 - distFromEdge / bevelWidth, 0.0, 1.0);

    // Normal hacia el borde más cercano (radial en los extremos de la cápsula).
    float2 q = abs(p) - (halfSize - r);
    float2 outer = max(q, 0.0);
    float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    float2 normal = float2(0.0, s.y);
    if (length(outer) > 0.001) {
        normal = normalize(s * outer);
    } else if (halfSize.x - abs(p.x) < halfSize.y - abs(p.y)) {
        normal = float2(s.x, 0.0);
    }

    // Lente convexa que se invierte en los últimos píxeles del filo (menisco).
    float lensPull = (edgeFactor * edgeFactor * 18.0 - pow(edgeFactor, 5.0) * 34.0) * unit;
    float2 refracted = fragCoord - normal * lensPull;

    // Aberración cromática sutil en el borde del cristal.
    float2 chroma = normal * (edgeFactor * edgeFactor * 2.2 * unit);
    float2 lo = float2(0.5);
    float2 hi = layerSize - float2(0.5);
    float red = content.eval(clamp(refracted + chroma, lo, hi)).r;
    float green = content.eval(clamp(refracted, lo, hi)).g;
    float blue = content.eval(clamp(refracted - chroma, lo, hi)).b;

    // Tinte ultra transparente: deja ver el fondo claramente.
    float3 glass = mix(float3(red, green, blue), tint, tintAmount);

    // Bisel de volumen: brillo Fresnel interior, filo especular y luz de cilindro arriba y abajo.
    float innerGlow = pow(edgeFactor, 2.6) * glowStrength;
    float rimHighlight = (1.0 - smoothstep(0.0, 2.2 * unit, distFromEdge)) * rimStrength;
    float verticalHighlight = pow(abs(p.y / halfSize.y), 3.0) * cylinderStrength;
    glass += light * (innerGlow + rimHighlight + verticalHighlight);
    return half4(half3(glass), 1.0);
}
"""

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
