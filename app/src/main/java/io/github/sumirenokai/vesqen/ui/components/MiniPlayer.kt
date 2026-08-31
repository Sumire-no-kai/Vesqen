package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.theme.VesqenRadii

@Composable
fun MiniPlayer(
    snapshot: PlaybackSnapshot,
    onOpenNow: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = snapshot.title.ifBlank { stringResource(R.string.unknown_title) }
    val artist = snapshot.artist.ifBlank { stringResource(R.string.unknown_artist) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("vesqen.mini-player"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.surface),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .testTag("vesqen.mini-player.open-now")
                    .clickable(onClick = onOpenNow),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtwork(modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                modifier = Modifier.size(48.dp).testTag("vesqen.mini-player.previous"),
                onClick = onPrevious,
                enabled = snapshot.isControllerReady && snapshot.hasPrevious,
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.previous),
                    modifier = Modifier.size(20.dp),
                )
            }
            FilledIconButton(
                modifier = Modifier.size(48.dp).testTag("vesqen.mini-player.play-pause"),
                onClick = onPlayPause,
                enabled = snapshot.isControllerReady,
                shape = androidx.compose.foundation.shape.CircleShape,
            ) {
                Icon(
                    imageVector = if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (snapshot.isPlaying) R.string.pause else R.string.play),
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(
                modifier = Modifier.size(48.dp).testTag("vesqen.mini-player.next"),
                onClick = onNext,
                enabled = snapshot.isControllerReady && snapshot.hasNext,
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.next),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
