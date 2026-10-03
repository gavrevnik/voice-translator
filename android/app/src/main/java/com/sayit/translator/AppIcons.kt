package com.sayit.translator

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The six non-core Material glyphs used by the app, kept locally to avoid the icons-extended AAR. */
internal object AppIcons {
    val ContentCopy: ImageVector by lazy {
        icon("ContentCopy") {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineTo(17f)
            horizontalLineTo(4f)
            verticalLineTo(3f)
            horizontalLineTo(16f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineTo(21f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
            horizontalLineTo(19f)
            curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            verticalLineTo(7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineTo(19f)
            close()
        }
    }

    val Mic: ImageVector by lazy {
        icon("Mic") {
            moveTo(12f, 14f)
            curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
            verticalLineTo(5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            verticalLineTo(11f)
            curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
            close()
            moveTo(17.3f, 11f)
            curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
            curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
            horizontalLineTo(5f)
            curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
            verticalLineTo(21f)
            horizontalLineTo(8f)
            verticalLineTo(23f)
            horizontalLineTo(16f)
            verticalLineTo(21f)
            horizontalLineTo(13f)
            verticalLineTo(17.72f)
            curveTo(16.28f, 17.24f, 19f, 14.42f, 19f, 11f)
            close()
        }
    }

    val Stop: ImageVector by lazy {
        icon("Stop") {
            moveTo(6f, 6f)
            horizontalLineTo(18f)
            verticalLineTo(18f)
            horizontalLineTo(6f)
            close()
        }
    }

    val SwapVert: ImageVector by lazy {
        icon("SwapVert") {
            moveTo(16f, 17.01f)
            verticalLineTo(10f)
            horizontalLineTo(14f)
            verticalLineTo(17.01f)
            horizontalLineTo(11f)
            lineTo(15f, 21f)
            lineTo(19f, 17.01f)
            close()
            moveTo(9f, 3f)
            lineTo(5f, 6.99f)
            horizontalLineTo(8f)
            verticalLineTo(14f)
            horizontalLineTo(10f)
            verticalLineTo(6.99f)
            horizontalLineTo(13f)
            close()
        }
    }

    val Translate: ImageVector by lazy {
        icon("Translate") {
            moveTo(12.87f, 15.07f)
            lineTo(10.33f, 12.56f)
            lineTo(10.36f, 12.53f)
            curveTo(12.1f, 10.59f, 13.38f, 8.36f, 14.07f, 6f)
            horizontalLineTo(17f)
            verticalLineTo(4f)
            horizontalLineTo(10f)
            verticalLineTo(2f)
            horizontalLineTo(8f)
            verticalLineTo(4f)
            horizontalLineTo(1f)
            verticalLineTo(5.99f)
            horizontalLineTo(12.17f)
            curveTo(11.5f, 7.92f, 10.43f, 9.73f, 9f, 11.35f)
            curveTo(8.09f, 10.35f, 7.32f, 9.25f, 6.69f, 7f)
            horizontalLineTo(4.69f)
            curveTo(5.42f, 8.63f, 6.42f, 10.17f, 7.79f, 11.56f)
            lineTo(2.7f, 16.58f)
            lineTo(4.11f, 18f)
            lineTo(9f, 13.11f)
            lineTo(12.04f, 16.15f)
            close()
            moveTo(18.5f, 10f)
            horizontalLineTo(16.5f)
            lineTo(12f, 22f)
            horizontalLineTo(14f)
            lineTo(15.12f, 19f)
            horizontalLineTo(19.87f)
            lineTo(21f, 22f)
            horizontalLineTo(23f)
            close()
            moveTo(15.88f, 17f)
            lineTo(17.5f, 12.67f)
            lineTo(19.12f, 17f)
            close()
        }
    }

    val VolumeUp: ImageVector by lazy {
        icon("VolumeUp") {
            moveTo(3f, 9f)
            verticalLineTo(15f)
            horizontalLineTo(7f)
            lineTo(12f, 20f)
            verticalLineTo(4f)
            lineTo(7f, 9f)
            close()
            moveTo(16.5f, 12f)
            curveTo(16.5f, 10.23f, 15.48f, 8.71f, 14f, 7.97f)
            verticalLineTo(16.02f)
            curveTo(15.48f, 15.29f, 16.5f, 13.77f, 16.5f, 12f)
            close()
            moveTo(14f, 3.23f)
            verticalLineTo(5.29f)
            curveTo(16.89f, 6.15f, 19f, 8.83f, 19f, 12f)
            curveTo(19f, 15.17f, 16.89f, 17.85f, 14f, 18.71f)
            verticalLineTo(20.77f)
            curveTo(18.01f, 19.86f, 21f, 16.28f, 21f, 12f)
            curveTo(21f, 7.72f, 18.01f, 4.14f, 14f, 3.23f)
            close()
        }
    }

    private fun icon(name: String, body: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = body)
        }.build()
}
