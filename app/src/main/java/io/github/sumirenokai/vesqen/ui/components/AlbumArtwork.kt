package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.library.AlbumArtworkLoader
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders real MediaStore/embedded artwork when it is available. Twin Paths remains a neutral
 * fallback for a missing, unreadable, or still-loading picture; it never represents an album.
 *
 * Lists pass [fallbackPainter] from [rememberTwinPathsPainter] so that all their rows draw one
 * cached picture of Twin Paths instead of its strokes.
 */
@Composable
fun AlbumArtwork(
    modifier: Modifier = Modifier,
    track: AudioTrack? = null,
    targetSize: Dp? = null,
    emphasized: Boolean = false,
    fallbackContainerColor: Color? = null,
    showFallback: Boolean = true,
    loadedArtworkModifier: Modifier = Modifier,
    shape: Shape = AlbumArtworkShape,
    fallbackPainter: Painter? = null,
) {
    val density = LocalDensity.current
    if (targetSize != null) {
        val targetPx = with(density) {
            targetSize.coerceAtLeast(32.dp).coerceAtMost(512.dp).roundToPx()
        }
        AlbumArtworkContent(
            modifier = modifier,
            track = track,
            targetPx = targetPx,
            emphasized = emphasized,
            fallbackContainerColor = fallbackContainerColor,
            showFallback = showFallback,
            loadedArtworkModifier = loadedArtworkModifier,
            shape = shape,
            fallbackPainter = fallbackPainter,
        )
    } else {
        BoxWithConstraints(modifier = modifier) {
            val targetPx = with(density) {
                maxWidth.coerceAtLeast(32.dp).coerceAtMost(512.dp).roundToPx()
            }
            AlbumArtworkContent(
                modifier = Modifier.fillMaxSize(),
                track = track,
                targetPx = targetPx,
                emphasized = emphasized,
                fallbackContainerColor = fallbackContainerColor,
                showFallback = showFallback,
                loadedArtworkModifier = loadedArtworkModifier,
                shape = shape,
                fallbackPainter = fallbackPainter,
            )
        }
    }
}

@Composable
private fun AlbumArtworkContent(
    modifier: Modifier,
    track: AudioTrack?,
    targetPx: Int,
    emphasized: Boolean,
    fallbackContainerColor: Color?,
    showFallback: Boolean,
    loadedArtworkModifier: Modifier,
    shape: Shape,
    fallbackPainter: Painter?,
) {
    val appContext = LocalContext.current.applicationContext
    val loader = remember(appContext) { AlbumArtworkLoader(appContext) }
    val bitmapState = remember(track?.contentUri, track?.albumArtworkUri, track?.dateModifiedSeconds, track?.artworkRevision, targetPx) {
        androidx.compose.runtime.mutableStateOf(track?.let { loader.peek(it, targetPx) })
    }
    androidx.compose.runtime.LaunchedEffect(bitmapState) {
        if (bitmapState.value == null) {
            bitmapState.value = track?.let { requestedTrack ->
                withContext(ArtworkLoadDispatcher) { loader.load(requestedTrack, targetPx) }
            }
        }
    }
    val bitmap by bitmapState

    Surface(
        modifier = modifier,
        shape = shape,
        color = fallbackContainerColor ?: if (emphasized) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        val artworkBitmap = bitmap
        if (artworkBitmap == null && showFallback) {
            TwinPathsPlaceholder(emphasized = emphasized, painter = fallbackPainter)
        } else if (artworkBitmap != null) {
            Image(
                bitmap = artworkBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .then(loadedArtworkModifier),
            )
        }
    }
}

private val AlbumArtworkShape = RoundedCornerShape(VesqenRadii.album)

private val ArtworkLoadDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
private fun TwinPathsPlaceholder(emphasized: Boolean, painter: Painter?) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val drawing = Modifier
        .fillMaxSize()
        .testTag("vesqen.album-artwork.fallback")
    if (painter != null) {
        Box(drawing.paint(painter))
    } else {
        Canvas(drawing) { drawTwinPaths(emphasized, primary, onSurface) }
    }
}

/**
 * One Twin Paths picture for every row of a list. Drawing the four round-capped lines again in
 * every row on every frame took about 4 ms of RenderThread time per frame while the library
 * scrolled on Android 9 (Honor, #50). The painter draws Twin Paths once into a bitmap at the
 * size the rows use, and each row draws that bitmap. Use one painter per row size, or it redraws
 * the bitmap. Single surfaces such as Now keep drawing the lines, which stay sharp as they scale.
 */
@Composable
internal fun rememberTwinPathsPainter(emphasized: Boolean = false): Painter {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    return remember(emphasized, primary, onSurface) {
        TwinPathsPainter(emphasized, primary, onSurface)
    }
}

private class TwinPathsPainter(
    private val emphasized: Boolean,
    private val primary: Color,
    private val onSurface: Color,
) : Painter() {
    private var picture: ImageBitmap? = null

    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width <= 0 || height <= 0) return
        val current = picture?.takeIf { it.width == width && it.height == height }
            ?: ImageBitmap(width, height).also { image ->
                CanvasDrawScope().draw(this, layoutDirection, Canvas(image), Size(width.toFloat(), height.toFloat())) {
                    drawTwinPaths(emphasized, primary, onSurface)
                }
                image.prepareToDraw()
                picture = image
            }
        drawImage(current)
    }
}

private fun DrawScope.drawTwinPaths(emphasized: Boolean, primary: Color, onSurface: Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension * if (emphasized) .31f else .24f
    drawCircle(
        color = primary.copy(alpha = if (emphasized) .22f else .14f),
        radius = radius,
        center = center,
    )
    // Twin Paths B (2026-10-06): strokes 6.5 / 5.5 of 108, optically heavier when small.
    val lineWidth = size.minDimension * if (emphasized) .065f else .084f
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
        strokeWidth = lineWidth * .77f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = primary,
        start = Offset(size.width / 2f, size.height * .57f),
        end = Offset(size.width * .61f, top),
        strokeWidth = lineWidth * .77f,
        cap = StrokeCap.Round,
    )
}

