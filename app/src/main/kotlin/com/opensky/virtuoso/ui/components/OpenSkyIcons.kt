package com.opensky.virtuoso.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
}
