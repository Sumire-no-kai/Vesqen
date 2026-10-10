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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import io.github.sumirenokai.vesqen.ui.formatDuration
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.serif

/**
 * B · Paper & Sound track details: a fixed header, then the title, Play and Favorite, queue and
 * playlist actions as hairline rows, and the file's facts as a definition list. The sheet wraps
 * its content, so a short track never ends in an empty half-screen.
 */
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
    queueActionsEnabled: Boolean = true,
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
                .testTag("vesqen.track-details.layout"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = VesqenSpacing.lg, end = VesqenSpacing.xs)
                    .testTag("vesqen.track-details.header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.track_details),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
            Column(
                // Takes only the height it needs; scrolls once the sheet reaches its maximum.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = VesqenSpacing.lg, end = VesqenSpacing.lg, bottom = VesqenSpacing.md)
                    .testTag("vesqen.track-details.content"),
                verticalArrangement = Arrangement.spacedBy(VesqenSpacing.md),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = track.title.ifBlank { stringResource(R.string.unknown_title) },
                        style = MaterialTheme.typography.headlineMedium.serif(),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist.ifBlank { stringResource(R.string.unknown_artist) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(VesqenSpacing.sm),
                ) {
                    Button(onClick = onPlay, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.play_this_track), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    onToggleFavorite?.let { toggle ->
                        OutlinedButton(onClick = toggle, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Text(
                                stringResource(if (track.isFavorite) R.string.remove_favorite else R.string.favorite),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                TrackActions(
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    queueActionsEnabled = queueActionsEnabled,
                    onRemoveFromPlaylist = onRemoveFromPlaylist,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                )
                TrackFacts(track)
                if (track.playCount > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.play_count_value, track.playCount, track.playCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onAddToPlaylist != null && playlists.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
                        Text(
                            text = stringResource(R.string.add_to_playlist),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        PaperCard {
                            playlists.forEachIndexed { index, playlist ->
                                if (index > 0) PaperDivider()
                                TrackActionRow(playlist.name, onClick = { onAddToPlaylist(playlist.id) })
                            }
                        }
                    }
                }
                // The scroll content ends here; tests measure the gap below it.
                Spacer(Modifier.testTag("vesqen.track-details.end"))
            }
        }
    }
}

@Composable
private fun TrackActions(
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    queueActionsEnabled: Boolean,
    onRemoveFromPlaylist: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val actions = buildList<@Composable () -> Unit> {
        onPlayNext?.let { add { TrackActionRow(stringResource(R.string.play_next), it, enabled = queueActionsEnabled) } }
        onAddToQueue?.let {
            add {
                TrackActionRow(
                    label = stringResource(R.string.add_to_queue),
                    onClick = it,
                    enabled = queueActionsEnabled,
                    modifier = Modifier.testTag("vesqen.track-details.add-to-queue"),
                )
            }
        }
        onRemoveFromPlaylist?.let { add { TrackActionRow(stringResource(R.string.remove_from_playlist), it) } }
        onMoveUp?.let { add { TrackActionRow(stringResource(R.string.move_up), it) } }
        onMoveDown?.let { add { TrackActionRow(stringResource(R.string.move_down), it) } }
    }
    if (actions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xs)) {
        PaperCard {
            actions.forEachIndexed { index, action ->
                if (index > 0) PaperDivider()
                action()
            }
        }
        if (!queueActionsEnabled && (onPlayNext != null || onAddToQueue != null)) {
            Text(
                text = stringResource(R.string.playback_controls_connecting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = VesqenSpacing.md, vertical = VesqenSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The file's facts, in the order they matter for listening; empty values are left out. */
@Composable
private fun TrackFacts(track: AudioTrack) {
    val facts = buildList {
        add(stringResource(R.string.detail_album) to track.album.ifBlank { stringResource(R.string.unknown_album) })
        add(stringResource(R.string.detail_duration) to formatDuration(track.durationMs))
        track.albumArtist.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.detail_album_artist) to it) }
        if (track.trackNumber != null || track.discNumber != null) {
            add(
                stringResource(R.string.detail_track_number) to
                    stringResource(R.string.track_disc_value, track.trackNumber ?: 0, track.discNumber ?: 1),
            )
        }
        track.year?.let { add(stringResource(R.string.detail_year) to it.toString()) }
        track.genre.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.detail_genre) to it) }
        track.codec.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.detail_format) to it) }
        track.sampleRateHz?.takeIf { it > 0 }?.let {
            add(stringResource(R.string.detail_sample_rate) to stringResource(R.string.sample_rate_value, it / 1_000f))
        }
        track.bitDepth?.takeIf { it > 0 }?.let {
            add(stringResource(R.string.detail_bit_depth) to stringResource(R.string.bit_depth_value, it))
        }
        track.channelCount?.takeIf { it > 0 }?.let {
            add(stringResource(R.string.detail_channels) to pluralStringResource(R.plurals.channel_count_value, it, it))
        }
        track.bitrate?.takeIf { it > 0 }?.let {
            add(stringResource(R.string.detail_bitrate) to stringResource(R.string.bitrate_value, it / 1_000))
        }
        track.fileName.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.detail_file) to it) }
        track.folderName.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.detail_folder) to it) }
        track.fileSizeBytes.takeIf { it > 0 }?.let { add(stringResource(R.string.detail_size) to formatFileSize(it)) }
    }
    PaperCard {
        facts.forEachIndexed { index, (label, value) ->
            if (index > 0) PaperDivider()
            DetailLine(label, value)
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

/** B: a definition list with an 84 dp label column. */
@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 44.dp)
            .padding(horizontal = VesqenSpacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(84.dp)
                .padding(top = 2.dp),
        )
        Spacer(Modifier.width(VesqenSpacing.xs))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.weight(1f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
