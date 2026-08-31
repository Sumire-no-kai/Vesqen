package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

@Composable
fun PlaybackControls(
    snapshot: PlaybackSnapshot,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controlsEnabled = snapshot.isControllerReady
    val transportColor = MaterialTheme.colorScheme.onSurface

    BoxWithConstraints(modifier = modifier) {
        val compactTransport = maxWidth < 300.dp
        val secondaryControlSize = if (compactTransport) 48.dp else 56.dp
        val primaryControlSize = if (compactTransport) 56.dp else 72.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                if (compactTransport) VesqenSpacing.xs else VesqenSpacing.md,
                alignment = Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                modifier = Modifier
                    .size(secondaryControlSize)
                    .testTag("vesqen.now.previous"),
                onClick = onPrevious,
                enabled = controlsEnabled && snapshot.canSkipPrevious,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = transportColor,
                    disabledContentColor = transportColor.copy(alpha = .38f),
                ),
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
                    modifier = Modifier.size(if (compactTransport) 26.dp else 30.dp),
                )
            }
            IconButton(
                modifier = Modifier
                    .size(secondaryControlSize)
                    .testTag("vesqen.now.next"),
                onClick = onNext,
                enabled = controlsEnabled && snapshot.canSkipNext,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = transportColor,
                    disabledContentColor = transportColor.copy(alpha = .38f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.next),
                )
            }
        }
    }
}
