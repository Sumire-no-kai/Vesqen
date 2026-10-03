package io.github.sumirenokai.vesqen.ui.components

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.sumirenokai.vesqen.library.AlbumArtworkLoader
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.ui.theme.AlbumHue
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.albumBackground
import io.github.sumirenokai.vesqen.ui.theme.dominantAlbumHue
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TINT_SAMPLE_PX = 64

/** A cached result, including "achromatic", so covers without colour are not decoded again. */
private class CoverHue(val hue: AlbumHue?)

private val coverHueCache = LruCache<String, CoverHue>(128)

private fun AudioTrack.tintKey(): String =
    "${albumId ?: contentUri}:$artworkRevision:$dateModifiedSeconds"

/**
 * The Now and album-detail background follows the cover (B spec §2.3). Only the background is
 * tinted; text, controls and evidence states keep their fixed roles. The previous colour stays
 * until the next cover's hue is known, so a track change never flashes plain paper first.
 */
@Composable
internal fun rememberAlbumBackground(
    track: AudioTrack?,
    dark: Boolean,
    motionPolicy: VesqenMotionPolicy,
): Color {
    val context = LocalContext.current.applicationContext
    val key = track?.tintKey()
    var hue by remember { mutableStateOf(key?.let(coverHueCache::get)) }
    LaunchedEffect(key) {
        if (track == null || key == null) {
            hue = CoverHue(null)
            return@LaunchedEffect
        }
        hue = coverHueCache.get(key) ?: withContext(Dispatchers.IO) {
            CoverHue(AlbumArtworkLoader(context).load(track, TINT_SAMPLE_PX * 2)?.let { cover ->
                dominantAlbumHue(cover.samplePixels())
            })
        }.also { coverHueCache.put(key, it) }
    }
    val background by animateColorAsState(
        targetValue = albumBackground(hue?.hue, dark),
        animationSpec = tween(motionPolicy.albumTintMillis, easing = CubicBezierEasing(.22f, 1f, .36f, 1f)),
        label = "vesqen.album-tint",
    )
    return background
}

/** Scales the cover so its longest side is at most 64 px and returns its ARGB pixels. */
private fun Bitmap.samplePixels(): IntArray {
    // Platform thumbnails may be hardware bitmaps, whose pixels cannot be read directly.
    val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE) {
        copy(Bitmap.Config.ARGB_8888, false)
    } else {
        this
    }
    val scale = TINT_SAMPLE_PX.toFloat() / max(readable.width, readable.height)
    val sample = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            readable,
            (readable.width * scale).roundToInt().coerceAtLeast(1),
            (readable.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        readable
    }
    return IntArray(sample.width * sample.height).also { pixels ->
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
    }
}
