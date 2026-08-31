package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.PlaybackRepeatMode
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot

@Composable
fun PlaybackControls(
    snapshot: PlaybackSnapshot,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controlsEnabled = snapshot.isControllerReady
    val activeTint = MaterialTheme.colorScheme.primary
    val inactiveTint = MaterialTheme.colorScheme.onSurfaceVariant
    val shuffleState = stringResource(
        if (snapshot.shuffleEnabled) R.string.shuffle_on else R.string.shuffle_off,
    )
    val repeatState = stringResource(
        when (snapshot.repeatMode) {
            PlaybackRepeatMode.OFF -> R.string.repeat_off
            PlaybackRepeatMode.ALL -> R.string.repeat_all
            PlaybackRepeatMode.ONE -> R.string.repeat_one
        },
    )

    BoxWithConstraints(modifier = modifier) {
        val primaryControlSize = if (maxWidth < 288.dp) 56.dp else 64.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                modifier = Modifier
                    .size(48.dp)
                    .testTag("vesqen.now.shuffle")
                    .semantics { stateDescription = shuffleState },
                onClick = onToggleShuffle,
                enabled = controlsEnabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = stringResource(R.string.shuffle),
                    tint = if (snapshot.shuffleEnabled) activeTint else inactiveTint,
                )
            }
            IconButton(
                modifier = Modifier.size(48.dp).testTag("vesqen.now.previous"),
                onClick = onPrevious,
                enabled = controlsEnabled && snapshot.hasPrevious,
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.previous),
                )
            }
            FilledIconButton(
                modifier = Modifier.size(primaryControlSize).testTag("vesqen.now.play-pause"),
                onClick = onPlayPause,
                enabled = controlsEnabled,
                shape = androidx.compose.foundation.shape.CircleShape,
            ) {
                Icon(
                    imageVector = if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (snapshot.isPlaying) R.string.pause else R.string.play),
                    modifier = Modifier.size(if (primaryControlSize == 64.dp) 30.dp else 26.dp),
                )
            }
            IconButton(
                modifier = Modifier.size(48.dp).testTag("vesqen.now.next"),
                onClick = onNext,
                enabled = controlsEnabled && snapshot.hasNext,
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.next),
                )
            }
            IconButton(
                modifier = Modifier
                    .size(48.dp)
                    .testTag("vesqen.now.repeat")
                    .semantics { stateDescription = repeatState },
                onClick = onCycleRepeatMode,
                enabled = controlsEnabled,
            ) {
                Icon(
                    imageVector = if (snapshot.repeatMode == PlaybackRepeatMode.ONE) {
                        Icons.Filled.RepeatOne
                    } else {
                        Icons.Filled.Repeat
                    },
                    contentDescription = stringResource(R.string.repeat),
                    tint = if (snapshot.repeatMode == PlaybackRepeatMode.OFF) inactiveTint else activeTint,
                )
            }
        }
    }
}
