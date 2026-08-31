package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii

/**
 * M1 has no album-art URI contract yet. This is an intentionally neutral Twin Paths placeholder,
 * rather than fabricated cover imagery or a generic music symbol.
 */
@Composable
fun AlbumArtwork(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(VesqenRadii.album),
        color = if (emphasized) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension * if (emphasized) .31f else .24f
                drawCircle(
                    color = primary.copy(alpha = if (emphasized) .22f else .14f),
                    radius = radius,
                    center = center,
                )
                val lineWidth = size.minDimension * if (emphasized) .07f else .09f
                val left = size.width * .27f
                val right = size.width * .73f
                val top = size.height * .29f
                val bottom = size.height * .70f
                drawLine(
                    color = onSurface.copy(alpha = .82f),
                    start = Offset(left, top),
                    end = Offset(size.width / 2f, bottom),
                    strokeWidth = lineWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = onSurface.copy(alpha = .82f),
                    start = Offset(size.width / 2f, bottom),
                    end = Offset(right, top),
                    strokeWidth = lineWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = primary,
                    start = Offset(size.width * .39f, top),
                    end = Offset(size.width / 2f, size.height * .57f),
                    strokeWidth = lineWidth * .78f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = primary,
                    start = Offset(size.width / 2f, size.height * .57f),
                    end = Offset(size.width * .61f, top),
                    strokeWidth = lineWidth * .78f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
