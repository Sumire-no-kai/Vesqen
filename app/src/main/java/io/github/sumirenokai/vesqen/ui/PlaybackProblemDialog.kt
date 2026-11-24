package io.github.sumirenokai.vesqen.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.PlaybackInterruption
import io.github.sumirenokai.vesqen.playback.PlaybackInterruptions
import io.github.sumirenokai.vesqen.playback.PlaybackProblem
import io.github.sumirenokai.vesqen.playback.UnplayableTrackSettings
import io.github.sumirenokai.vesqen.playback.message
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/** #344: what interrupted playback, written by the playback service. Null in screens tested alone. */
internal val LocalPlaybackInterruptions = staticCompositionLocalOf<PlaybackInterruptions?> { null }

/** #344: the "Skip tracks Android can't decode" switch. */
internal val LocalUnplayableTrackSettings = staticCompositionLocalOf<UnplayableTrackSettings?> { null }

/**
 * Explains why playback stopped, once per interruption (#344, owner 2026-11-24). Next track is the
 * primary action. Retry appears only for a playback error, since a track Android cannot decode
 * fails the same way again. Back or a tap outside is the same as OK: playback stays stopped.
 */
@Composable
internal fun PlaybackProblemDialog(
    interruption: PlaybackInterruption,
    canSkipNext: Boolean,
    showAutoSkipNote: Boolean,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    val unplayable = interruption.kind == PlaybackProblem.UNPLAYABLE_TRACK
    val ok = @Composable {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.playback-problem.ok")) {
            Text(stringResource(R.string.playback_problem_ok))
        }
    }
    val retry = @Composable {
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.playback-problem.retry")) {
            Text(stringResource(R.string.retry_playback))
        }
    }
    AlertDialog(
        modifier = Modifier.testTag("vesqen.playback-problem"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playback_problem_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (unplayable) {
                        stringResource(
                            R.string.playback_problem_unplayable_body,
                            interruption.trackTitle.ifBlank { stringResource(R.string.unknown_title) },
                        )
                    } else {
                        stringResource(interruption.kind.message())
                    },
                )
                if (unplayable && showAutoSkipNote) {
                    Spacer(Modifier.height(VesqenSpacing.sm))
                    Text(
                        text = stringResource(R.string.playback_problem_auto_skip_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("vesqen.playback-problem.note"),
                    )
                }
            }
        },
        confirmButton = {
            if (canSkipNext) {
                TextButton(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.playback-problem.next")) {
                    Text(stringResource(R.string.playback_problem_next))
                }
            } else {
                ok()
            }
        },
        // Several buttons in this slot wrap onto their own lines with large text, as in SupportPromptDialog.
        dismissButton = {
            if (canSkipNext) ok()
            if (!unplayable) retry()
        },
    )
}
