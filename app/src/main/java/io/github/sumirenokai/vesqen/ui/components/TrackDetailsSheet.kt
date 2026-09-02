package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import io.github.sumirenokai.vesqen.ui.formatDuration
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailsSheet(
    track: AudioTrack,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    playlists: List<LibraryPlaylist> = emptyList(),
    onToggleFavorite: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: ((Long) -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        modifier = Modifier.testTag("vesqen.track-details"),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .testTag("vesqen.track-details.layout"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VesqenSpacing.lg)
                    .testTag("vesqen.track-details.header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = VesqenSpacing.lg)
                    .padding(top = VesqenSpacing.xs, bottom = VesqenSpacing.xs)
                    .testTag("vesqen.track-details.content"),
                verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
            ) {
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
                track.albumArtist.takeIf(String::isNotBlank)?.let { value ->
                    DetailLine(stringResource(R.string.detail_album_artist), value)
                }
                if (track.trackNumber != null || track.discNumber != null) {
                    DetailLine(
                        label = stringResource(R.string.detail_track_number),
                        value = stringResource(
                            R.string.track_disc_value,
                            track.trackNumber ?: 0,
                            track.discNumber ?: 1,
                        ),
                    )
                }
                track.year?.let { DetailLine(stringResource(R.string.detail_year), it.toString()) }
                track.genre.takeIf(String::isNotBlank)?.let { value ->
                    DetailLine(stringResource(R.string.detail_genre), value)
                }
                track.fileName.takeIf(String::isNotBlank)?.let { value ->
                    DetailLine(stringResource(R.string.detail_file), value)
                }
                track.folderName.takeIf(String::isNotBlank)?.let { value ->
                    DetailLine(stringResource(R.string.detail_folder), value)
                }
                track.fileSizeBytes.takeIf { it > 0 }?.let { value ->
                    DetailLine(stringResource(R.string.detail_size), formatFileSize(value))
                }
                track.codec.takeIf(String::isNotBlank)?.let { value ->
                    DetailLine(stringResource(R.string.detail_format), value)
                }
                track.sampleRateHz?.takeIf { it > 0 }?.let { value ->
                    DetailLine(
                        stringResource(R.string.detail_sample_rate),
                        stringResource(R.string.sample_rate_value, value / 1_000f),
                    )
                }
                track.bitDepth?.takeIf { it > 0 }?.let { value ->
                    DetailLine(
                        stringResource(R.string.detail_bit_depth),
                        stringResource(R.string.bit_depth_value, value),
                    )
                }
                track.channelCount?.takeIf { it > 0 }?.let { value ->
                    DetailLine(
                        stringResource(R.string.detail_channels),
                        pluralStringResource(R.plurals.channel_count_value, value, value),
                    )
                }
                track.bitrate?.takeIf { it > 0 }?.let { value ->
                    DetailLine(
                        stringResource(R.string.detail_bitrate),
                        stringResource(R.string.bitrate_value, value / 1_000),
                    )
                }
                if (track.playCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.play_count_value,
                            track.playCount,
                            track.playCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
                ) {
                    Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.play_this_track))
                    }
                    onToggleFavorite?.let { toggle ->
                        OutlinedButton(onClick = toggle, modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    if (track.isFavorite) R.string.remove_favorite else R.string.favorite,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                onPlayNext?.let { action ->
                    TrackActionButton(stringResource(R.string.play_next), action)
                }
                onAddToQueue?.let { action ->
                    TrackActionButton(
                        label = stringResource(R.string.add_to_queue),
                        onClick = action,
                        modifier = Modifier.testTag("vesqen.track-details.add-to-queue"),
                    )
                }
                onRemoveFromPlaylist?.let { action ->
                    TrackActionButton(stringResource(R.string.remove_from_playlist), action)
                }
                onMoveUp?.let { action ->
                    TrackActionButton(stringResource(R.string.move_up), action)
                }
                onMoveDown?.let { action ->
                    TrackActionButton(stringResource(R.string.move_down), action)
                }
                if (onAddToPlaylist != null && playlists.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.add_to_playlist),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    playlists.forEach { playlist ->
                        TrackActionButton(
                            label = playlist.name,
                            onClick = { onAddToPlaylist(playlist.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
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
