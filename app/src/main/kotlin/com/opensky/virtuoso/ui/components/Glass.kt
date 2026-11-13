package com.opensky.virtuoso.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Efecto cristal (estilo «Liquid Glass»): solo en Android 16 o superior, donde el desenfoque por
 * GPU es estable y barato. En versiones anteriores la app conserva su aspecto plano de siempre.
 */
object GlassSupport {
    const val MIN_SDK = 36 // Android 16
    val available: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK
}

/** Si el cristal está activo en la pantalla actual y con qué paleta (azul o roja nocturna). */
@Immutable
data class GlassStyle(val enabled: Boolean, val night: Boolean) {
    /** Relleno translúcido de las tarjetas (cristal simulado: sin desenfoque, cuesta casi nada). */
    val cardColor: Color get() = if (night) Color(0xFF1C0000).copy(alpha = 0.62f) else Color(0xFF1A2138).copy(alpha = 0.55f)

    /** Borde con brillo arriba, como el canto de un vidrio. */
    val borderBrush: Brush get() = if (night) {
        Brush.verticalGradient(listOf(Color(0xFFB71C1C).copy(alpha = 0.55f), Color(0xFF5C0000).copy(alpha = 0.15f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.06f)))
    }

    /** Tinte sobre el desenfoque real (barra inferior y ventanas). */
    val tint: Color get() = if (night) Color(0xFF2A0000).copy(alpha = 0.55f) else Color(0xFF26315C).copy(alpha = 0.38f)

    /** Reflejo suave de arriba hacia abajo («líquido»). */
    val sheen: Brush get() = if (night) {
        Brush.verticalGradient(listOf(Color(0xFFE53935).copy(alpha = 0.08f), Color.Transparent))
    } else {
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.20f), 0.45f to Color.Transparent, 1f to Color.White.copy(alpha = 0.05f))
    }

    val dialogColor: Color get() = if (night) Color(0xFF1C0000).copy(alpha = 0.78f) else Color(0xFF1A2138).copy(alpha = 0.66f)
}

val LocalGlass = staticCompositionLocalOf { GlassStyle(enabled = false, night = false) }

/** Alto que ocupa la barra inferior flotante: las pantallas lo dejan libre al final de su lista. */
val LocalBottomInset = compositionLocalOf { 0.dp }

/** Relleno estándar de las pantallas (16 dp) más el espacio de la barra flotante si la hay. */
@Composable
fun screenPadding(): PaddingValues =
    PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalBottomInset.current)

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
fun Modifier.glassPanel(state: GlassBackdropState, shape: Shape, style: GlassStyle, blurRadius: Dp = 22.dp): Modifier = composed {
    val blurLayer = rememberGraphicsLayer()
    var offset by remember { mutableStateOf(Offset.Zero) }
    val effect = remember { arrayOfNulls<BlurEffect>(1) }
    this
        .onGloballyPositioned { offset = it.positionInRoot() }
        .drawBehind {
            val r = blurRadius.toPx()
            val pad = ceil(r * 2f)
            val blur = effect[0] ?: BlurEffect(r, r, TileMode.Clamp).also { effect[0] = it }
            blurLayer.renderEffect = blur
            val rel = offset - state.sourceOffset
            blurLayer.record(size = IntSize(ceil(size.width + 2 * pad).toInt(), ceil(size.height + 2 * pad).toInt())) {
                translate(left = -rel.x + pad, top = -rel.y + pad) { drawLayer(state.contentLayer) }
            }
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
            clipPath(path) {
                translate(left = -pad, top = -pad) { drawLayer(blurLayer) }
                drawRect(style.tint)
                drawRect(style.sheen)
            }
            drawPath(path, style.borderBrush, style = Stroke(width = 1.dp.toPx()))
        }
}

/**
 * Fondo de cielo para el modo cristal: degradado, resplandores tenues y estrellas fijas. Va en su
 * propia capa, así que solo se vuelve a dibujar si cambia el tamaño de la pantalla.
 */
@Composable
fun SkyBackdrop(night: Boolean, modifier: Modifier = Modifier) {
    Spacer(
        modifier.fillMaxSize().graphicsLayer().drawWithCache {
            val w = size.width
            val h = size.height
            val base = if (night) Brush.verticalGradient(listOf(Color(0xFF120000), Color(0xFF000000)))
            else Brush.verticalGradient(listOf(Color(0xFF14204A), Color(0xFF0A1028), Color(0xFF05070F)))
            val glows = if (night) listOf(
                Triple(Offset(w * 0.85f, h * 0.15f), w * 0.8f, Color(0x2A5C0000)),
                Triple(Offset(w * 0.1f, h * 0.75f), w * 0.7f, Color(0x1F3A0000)),
            ) else listOf(
                Triple(Offset(w * 0.12f, h * 0.16f), w * 0.85f, Color(0x553A4FB8)),
                Triple(Offset(w * 0.95f, h * 0.52f), w * 0.8f, Color(0x40306E8C)),
                Triple(Offset(w * 0.35f, h * 0.92f), w * 0.75f, Color(0x38603A9C)),
            )
            val rnd = Random(7)
            val starColor = if (night) Color(0xFFB71C1C) else Color.White
            val stars = List(170) {
                Triple(Offset(rnd.nextFloat() * w, rnd.nextFloat() * h), (0.5f + rnd.nextFloat() * 1.4f) * density, 0.25f + rnd.nextFloat() * 0.6f)
            }
            onDrawBehind {
                drawRect(base)
                glows.forEach { (c, r, col) -> drawCircle(Brush.radialGradient(listOf(col, Color.Transparent), center = c, radius = r), r, c) }
                stars.forEach { (o, r, a) -> drawCircle(starColor.copy(alpha = if (night) a * 0.5f else a), r, o) }
            }
        },
    )
}

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
 * (fondo translúcido, borde con brillo y la pantalla de atrás desenfocada).
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
    val shape = RoundedCornerShape(28.dp)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { GlassDialogWindowEffect(); confirmButton() },
        dismissButton = dismissButton,
        title = title,
        text = text,
        modifier = Modifier.border(BorderStroke(1.dp, glass.borderBrush), shape),
        shape = shape,
        containerColor = glass.dialogColor,
    )
}
