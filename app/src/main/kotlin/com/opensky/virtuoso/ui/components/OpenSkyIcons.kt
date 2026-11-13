package com.opensky.virtuoso.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Iconos propios (vectores 24×24). Se evita material-icons-extended, que ya no se publica.
 */
object OpenSkyIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    val Home: ImageVector by lazy {
        icon("Home") {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 3f); lineTo(2f, 12f); horizontalLineTo(5f); verticalLineTo(20f)
                horizontalLineTo(10f); verticalLineTo(14f); horizontalLineTo(14f); verticalLineTo(20f)
                horizontalLineTo(19f); verticalLineTo(12f); horizontalLineTo(22f); close()
            }
        }
    }

    val Target: ImageVector by lazy {
        icon("Target") {
            path(fill = SolidColor(Color.White)) {
                // retícula: cuatro trazos y un punto central
                moveTo(11f, 2f); horizontalLineTo(13f); verticalLineTo(8f); horizontalLineTo(11f); close()
                moveTo(11f, 16f); horizontalLineTo(13f); verticalLineTo(22f); horizontalLineTo(11f); close()
                moveTo(2f, 11f); horizontalLineTo(8f); verticalLineTo(13f); horizontalLineTo(2f); close()
                moveTo(16f, 11f); horizontalLineTo(22f); verticalLineTo(13f); horizontalLineTo(16f); close()
                moveTo(10.5f, 10.5f); horizontalLineTo(13.5f); verticalLineTo(13.5f); horizontalLineTo(10.5f); close()
            }
        }
    }

    val Star: ImageVector by lazy {
        icon("Star") {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 2f); lineTo(14.9f, 8.6f); lineTo(22f, 9.3f); lineTo(16.6f, 14f); lineTo(18.2f, 21f)
                lineTo(12f, 17.3f); lineTo(5.8f, 21f); lineTo(7.4f, 14f); lineTo(2f, 9.3f); lineTo(9.1f, 8.6f); close()
            }
        }
    }

    val Pad: ImageVector by lazy {
        icon("Pad") {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 2f); lineTo(16f, 7f); horizontalLineTo(8f); close()
                moveTo(12f, 22f); lineTo(8f, 17f); horizontalLineTo(16f); close()
                moveTo(2f, 12f); lineTo(7f, 8f); verticalLineTo(16f); close()
                moveTo(22f, 12f); lineTo(17f, 16f); verticalLineTo(8f); close()
            }
        }
    }

    val More: ImageVector by lazy {
        icon("More") {
            path(fill = SolidColor(Color.White)) {
                moveTo(4f, 10f); horizontalLineTo(8f); verticalLineTo(14f); horizontalLineTo(4f); close()
                moveTo(10f, 10f); horizontalLineTo(14f); verticalLineTo(14f); horizontalLineTo(10f); close()
                moveTo(16f, 10f); horizontalLineTo(20f); verticalLineTo(14f); horizontalLineTo(16f); close()
            }
        }
    }

    val Stop: ImageVector by lazy {
        icon("Stop") {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 6f); horizontalLineTo(18f); verticalLineTo(18f); horizontalLineTo(6f); close()
            }
        }
    }

    /** Triángulo de advertencia con «!» calado (trazado de Material Icons, Apache 2.0). */
    val Warning: ImageVector by lazy {
        icon("Warning") {
            path(fill = SolidColor(Color.White)) {
                moveTo(1f, 21f); horizontalLineToRelative(22f); lineTo(12f, 2f); lineTo(1f, 21f); close()
                moveTo(13f, 18f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(13f, 14f); horizontalLineToRelative(-2f); verticalLineToRelative(-4f); horizontalLineToRelative(2f); verticalLineToRelative(4f); close()
            }
        }
    }

    /** Flecha circular de reinicio (trazado de Material Icons «refresh», Apache 2.0). */
    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            path(fill = SolidColor(Color.White)) {
                moveTo(17.65f, 6.35f)
                curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -7.99f, 8f)
                reflectiveCurveToRelative(3.57f, 8f, 7.99f, 8f)
                curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
                horizontalLineToRelative(-2.08f)
                curveToRelative(-0.82f, 2.33f, -3.04f, 4f, -5.65f, 4f)
                curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
                reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
                curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
                lineTo(13f, 11f)
                horizontalLineToRelative(7f)
                verticalLineTo(4f)
                lineToRelative(-2.35f, 2.35f)
                close()
            }
        }
    }

    /** Enchufe (montura sin conectar). */
    val Plug: ImageVector by lazy {
        icon("Plug") {
            path(fill = SolidColor(Color.White)) {
                moveTo(8.5f, 2f); horizontalLineTo(10.5f); verticalLineTo(7f); horizontalLineTo(8.5f); close()
                moveTo(13.5f, 2f); horizontalLineTo(15.5f); verticalLineTo(7f); horizontalLineTo(13.5f); close()
                moveTo(6f, 7f); horizontalLineTo(18f); verticalLineTo(11f)
                curveTo(18f, 14.31f, 15.31f, 17f, 12f, 17f)
                curveTo(8.69f, 17f, 6f, 14.31f, 6f, 11f); close()
                moveTo(11f, 17f); horizontalLineTo(13f); verticalLineTo(22f); horizontalLineTo(11f); close()
            }
        }
    }

    /** Chevrón «›» de las filas que abren otra pantalla. */
    val Chevron: ImageVector by lazy {
        icon("Chevron") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f)
            }
        }
    }
}
