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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.library.AlbumArtworkLoader
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders real MediaStore/embedded artwork when it is available. Twin Paths remains a neutral
 * fallback for a missing, unreadable, or still-loading picture; it never represents an album.
 */
@Composable
fun AlbumArtwork(
    modifier: Modifier = Modifier,
    track: AudioTrack? = null,
    emphasized: Boolean = false,
    fallbackContainerColor: Color? = null,
    showFallback: Boolean = true,
    loadedArtworkModifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val appContext = LocalContext.current.applicationContext
        val loader = remember(appContext) { AlbumArtworkLoader(appContext) }
        val targetPx = with(LocalDensity.current) {
            maxWidth.coerceAtLeast(32.dp).coerceAtMost(512.dp).roundToPx()
        }
        val bitmap by produceState<android.graphics.Bitmap?>(
            initialValue = null,
            track?.contentUri,
            track?.albumArtworkUri,
            track?.dateModifiedSeconds,
            track?.artworkRevision,
            targetPx,
        ) {
            // produceState retains its State object across key changes. Clear the old bitmap
            // synchronously so a rescan, track change, or permission revoke cannot flash the
            // previous listener's artwork while the new provider request is in flight.
            value = null
            value = track?.let { requestedTrack ->
                withContext(Dispatchers.IO) { loader.load(requestedTrack, targetPx) }
            }
        }
        val shape = RoundedCornerShape(VesqenRadii.album)

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            color = fallbackContainerColor ?: if (emphasized) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ) {
            val artworkBitmap = bitmap
            if (artworkBitmap == null && showFallback) {
                TwinPathsPlaceholder(emphasized = emphasized)
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
}

@Composable
private fun TwinPathsPlaceholder(emphasized: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("vesqen.album-artwork.fallback"),
        contentAlignment = Alignment.Center,
    ) {
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
