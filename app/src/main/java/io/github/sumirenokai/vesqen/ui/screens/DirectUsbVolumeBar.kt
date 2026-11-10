package io.github.sumirenokai.vesqen.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.DirectUsbVolume
import io.github.sumirenokai.vesqen.playback.DirectUsbVolumeControl
import io.github.sumirenokai.vesqen.ui.theme.LocalVesqenColors
import io.github.sumirenokai.vesqen.ui.theme.VesqenDataStyle
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The own USB output's volume, provided by the activity; null where nothing provides it (tests, previews). */
internal val LocalDirectUsbVolume = staticCompositionLocalOf<DirectUsbVolumeControl?> { null }

internal val DirectUsbVolumeBarHeight = 48.dp

/** "-30.1 dB" from the UAC unit of 1/256 dB, with the unit Chain uses. */
internal fun formatUsbVolume(context: Context, volume: Int): String =
    context.getString(R.string.chain_unit_decibels, usbVolumeNumber(volume))

private fun usbVolumeNumber(volume: Int): String {
    val decibels = volume / 256.0
    // A level that rounds to zero reads "0.0", not "-0.0".
    return String.format(Locale.getDefault(), "%.1f", if (abs(decibels) < 0.05) 0.0 else decibels)
}

/** The device's setting nearest to a slider position. */
internal fun DirectUsbVolume.settingNear(position: Float): Int {
    val steps = ((position - minimum) / resolution).roundToInt()
    return (minimum + steps * resolution).coerceIn(minimum, maximum)
}

/**
 * The DAC's hardware volume on Vesqen's own USB output (D4): the device's own range and steps, and
 * the level it read back. A drag asks for each setting it passes; the bar follows the device again
 * shortly after the drag ends, so a refused change shows as the level that still applies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DirectUsbVolumeBar(
    volume: DirectUsbVolume,
    onSet: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var dragged by remember { mutableStateOf<Int?>(null) }
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(settling, volume.current) {
        if (settling) {
            delay(SETTLE_MS)
            dragged = null
            settling = false
        }
    }
    val shown = (dragged ?: volume.current).coerceIn(volume.minimum, volume.maximum)
    val label = stringResource(R.string.usb_volume_label)
    val state = stringResource(R.string.usb_volume_state, usbVolumeNumber(shown))
    val played = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DirectUsbVolumeBarHeight)
            .testTag("vesqen.usb-volume"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            modifier = Modifier
                .weight(1f)
                .height(DirectUsbVolumeBarHeight)
                .padding(horizontal = VesqenSpacing.sm)
                .semantics {
                    contentDescription = label
                    stateDescription = state
                },
            value = shown.toFloat(),
            onValueChange = { position ->
                val setting = volume.settingNear(position)
                settling = false
                if (setting != dragged) {
                    dragged = setting
                    onSet(setting)
                }
            },
            onValueChangeFinished = { settling = true },
            valueRange = volume.minimum.toFloat()..volume.maximum.toFloat(),
            thumb = {
                Box(Modifier.size(width = 10.dp, height = DirectUsbVolumeBarHeight), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(10.dp).background(played, CircleShape))
                }
            },
            track = { sliderState ->
                Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                    val fraction = ((sliderState.value - sliderState.valueRange.start) /
                        (sliderState.valueRange.endInclusive - sliderState.valueRange.start)).coerceIn(0f, 1f)
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val start = Offset(if (rtl) size.width else 0f, size.height / 2f)
                    val end = Offset(if (rtl) 0f else size.width, size.height / 2f)
                    drawLine(rail, start, end, size.height)
                    if (fraction > 0f) drawLine(played, start, start + (end - start) * fraction, size.height)
                }
            },
        )
        // Sized for the longest value, so the slider keeps its width while the number changes.
        Box(Modifier.clearAndSetSemantics {}, contentAlignment = Alignment.CenterEnd) {
            val style = VesqenDataStyle.copy(fontSize = 12.sp)
            Text(formatUsbVolume(context, volume.minimum), style = style, maxLines = 1, modifier = Modifier.alpha(0f))
            Text(
                formatUsbVolume(context, shown),
                style = style,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("vesqen.usb-volume.value"),
            )
        }
    }
}

/**
 * Shown for a moment after a volume-key press that Vesqen took, in place of the system's volume
 * panel, which does not act on this output.
 */
@Composable
internal fun DirectUsbVolumeKeyOverlay(control: DirectUsbVolumeControl, modifier: Modifier = Modifier) {
    val volume by control.state.collectAsStateWithLifecycle()
    val presses by control.keyPresses.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(presses) }
    LaunchedEffect(presses) {
        if (presses != seen) {
            seen = presses
            visible = true
            delay(OVERLAY_MS)
            visible = false
        }
    }
    val current = volume
    AnimatedVisibility(
        visible = visible && current != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.statusBarsPadding().padding(horizontal = VesqenSpacing.lg, vertical = VesqenSpacing.sm),
    ) {
        if (current != null) {
            Surface(
                modifier = Modifier.widthIn(max = 420.dp).testTag("vesqen.usb-volume.overlay"),
                shape = RoundedCornerShape(VesqenRadii.surface),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, LocalVesqenColors.current.hairline),
                shadowElevation = 2.dp,
            ) {
                DirectUsbVolumeBar(current, control::set, Modifier.padding(horizontal = VesqenSpacing.md))
            }
        }
    }
}

private const val SETTLE_MS = 400L
private const val OVERLAY_MS = 2_000L
