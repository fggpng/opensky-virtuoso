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

    /*
     * Iconos de la barra y de la pantalla principal: familia «outline» con el mismo trazo (1.75 dp a
     * 24 dp), puntas y uniones redondeadas. Así todos pesan lo mismo visualmente.
     */
    private const val STROKE = 1.75f

    private fun outline(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector = icon(name) {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    /** Punto relleno (para «i», «?» o la antena de la Wi-Fi) dentro de un icono outline. */
    private fun ImageVector.Builder.dot(cx: Float, cy: Float, r: Float) {
        path(fill = SolidColor(Color.White)) {
            moveTo(cx + r, cy)
            arcTo(r, r, 0f, true, true, cx - r, cy)
            arcTo(r, r, 0f, true, true, cx + r, cy)
            close()
        }
    }

    val Home: ImageVector by lazy {
        outline("Home") {
            moveTo(3.5f, 11f); lineTo(12f, 4f); lineTo(20.5f, 11f)
            moveTo(6f, 9.5f); verticalLineTo(20f); horizontalLineTo(10f); verticalLineTo(14.5f)
            horizontalLineTo(14f); verticalLineTo(20f); horizontalLineTo(18f); verticalLineTo(9.5f)
        }
    }

    /** Retícula de alineación: círculo con cuatro marcas. */
    val Target: ImageVector by lazy {
        outline("Target") {
            moveTo(19f, 12f); arcTo(7f, 7f, 0f, true, true, 5f, 12f); arcTo(7f, 7f, 0f, true, true, 19f, 12f)
            moveTo(12f, 2.5f); verticalLineTo(7f)
            moveTo(12f, 17f); verticalLineTo(21.5f)
            moveTo(2.5f, 12f); horizontalLineTo(7f)
            moveTo(17f, 12f); horizontalLineTo(21.5f)
        }
    }

    val Star: ImageVector by lazy {
        outline("Star") {
            moveTo(12f, 3f); lineTo(14.6f, 8.9f); lineTo(21f, 9.5f); lineTo(16.2f, 13.8f); lineTo(17.6f, 20.1f)
            lineTo(12f, 16.8f); lineTo(6.4f, 20.1f); lineTo(7.8f, 13.8f); lineTo(3f, 9.5f); lineTo(9.4f, 8.9f); close()
        }
    }

    /** Cruceta del mando. */
    val Pad: ImageVector by lazy {
        outline("Pad") {
            moveTo(9.5f, 3.5f); horizontalLineTo(14.5f); verticalLineTo(9.5f); horizontalLineTo(20.5f); verticalLineTo(14.5f)
            horizontalLineTo(14.5f); verticalLineTo(20.5f); horizontalLineTo(9.5f); verticalLineTo(14.5f); horizontalLineTo(3.5f)
            verticalLineTo(9.5f); horizontalLineTo(9.5f); close()
        }
    }

    val More: ImageVector by lazy {
        outline("More") {
            for (x in listOf(5f, 12f, 19f)) {
                moveTo(x + 1.6f, 12f); arcTo(1.6f, 1.6f, 0f, true, true, x - 1.6f, 12f); arcTo(1.6f, 1.6f, 0f, true, true, x + 1.6f, 12f)
            }
        }
    }

    /** Wi-Fi: tres arcos y la antena. */
    val Wifi: ImageVector by lazy {
        icon("Wifi") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round) {
                moveTo(2.5f, 9f); quadTo(12f, 1f, 21.5f, 9f)
                moveTo(5.5f, 12.5f); quadTo(12f, 7f, 18.5f, 12.5f)
                moveTo(8.5f, 16f); quadTo(12f, 13f, 15.5f, 16f)
            }
            dot(12f, 19.5f, 1.4f)
        }
    }

    /** Pin de ubicación. */
    val Pin: ImageVector by lazy {
        outline("Pin") {
            moveTo(12f, 21.5f)
            curveTo(8f, 17f, 5.5f, 13.5f, 5.5f, 9.5f)
            arcTo(6.5f, 6.5f, 0f, false, true, 18.5f, 9.5f)
            curveTo(18.5f, 13.5f, 16f, 17f, 12f, 21.5f)
            close()
            moveTo(14.5f, 9.5f); arcTo(2.5f, 2.5f, 0f, true, true, 9.5f, 9.5f); arcTo(2.5f, 2.5f, 0f, true, true, 14.5f, 9.5f)
        }
    }

    /** Ayuda: círculo con «?». */
    val Help: ImageVector by lazy {
        icon("Help") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f)
                moveTo(9.6f, 9.6f)
                curveTo(9.6f, 8.1f, 10.7f, 7.1f, 12f, 7.1f)
                curveTo(13.4f, 7.1f, 14.5f, 8.1f, 14.5f, 9.4f)
                curveTo(14.5f, 11.2f, 12f, 11.4f, 12f, 13.4f)
            }
            dot(12f, 16.7f, 1.15f)
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
