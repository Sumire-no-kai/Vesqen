package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot

@Composable
fun PlaybackControls(
    snapshot: PlaybackSnapshot,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controlsEnabled = snapshot.isControllerReady
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconButton(
            modifier = Modifier.size(56.dp).testTag("vesqen.now.previous"),
            onClick = onPrevious,
            enabled = controlsEnabled && snapshot.hasPrevious,
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.previous),
            )
        }
        FilledIconButton(
            modifier = Modifier.size(64.dp).testTag("vesqen.now.play-pause"),
            onClick = onPlayPause,
            enabled = controlsEnabled,
            shape = androidx.compose.foundation.shape.CircleShape,
        ) {
            Icon(
                imageVector = if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (snapshot.isPlaying) R.string.pause else R.string.play),
                modifier = Modifier.size(30.dp),
            )
        }
        IconButton(
            modifier = Modifier.size(56.dp).testTag("vesqen.now.next"),
            onClick = onNext,
            enabled = controlsEnabled && snapshot.hasNext,
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.next),
            )
        }
    }
}
