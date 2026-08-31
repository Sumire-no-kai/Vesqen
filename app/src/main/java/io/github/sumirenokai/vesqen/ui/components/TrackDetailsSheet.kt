package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.ui.formatDuration
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailsSheet(
    track: AudioTrack,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
) {
    ModalBottomSheet(
        modifier = Modifier.testTag("vesqen.track-details"),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VesqenSpacing.lg)
                .padding(bottom = VesqenSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.track_details),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
            DetailLine(
                label = stringResource(R.string.detail_title),
                value = track.title.ifBlank { stringResource(R.string.unknown_title) },
            )
            DetailLine(
                label = stringResource(R.string.detail_artist),
                value = track.artist.ifBlank { stringResource(R.string.unknown_artist) },
            )
            DetailLine(
                label = stringResource(R.string.detail_album),
                value = track.album.ifBlank { stringResource(R.string.unknown_album) },
            )
            DetailLine(
                label = stringResource(R.string.detail_duration),
                value = formatDuration(track.durationMs),
            )
            Button(onClick = onPlay) {
                Text(stringResource(R.string.play_this_track))
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
