package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.PlaybackQueueItem
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

/** B · Paper & Sound queue: one card of hairline rows; tap a row to play it, edit it in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    snapshot: PlaybackSnapshot,
    onDismiss: () -> Unit,
    onPlayItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("vesqen.queue.sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VesqenSpacing.lg)
                .padding(bottom = VesqenSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.queue),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = pluralStringResource(R.plurals.library_song_total, snapshot.queue.size, snapshot.queue.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        onClearQueue()
                        onDismiss()
                    },
                    enabled = snapshot.isControllerReady && snapshot.queue.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.clear_queue))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                }
            }
            if (!snapshot.isControllerReady) {
                Text(
                    text = stringResource(R.string.playback_controls_connecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = VesqenSpacing.xxs),
                )
            }
            PaperCard(Modifier.padding(top = VesqenSpacing.sm)) {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    itemsIndexed(
                        items = snapshot.queue,
                        key = { index, item -> "${item.trackId}:$index" },
                    ) { index, item ->
                        if (index > 0) PaperDivider()
                        QueueRow(
                            index = index,
                            item = item,
                            enabled = snapshot.isControllerReady,
                            isLast = index == snapshot.queue.lastIndex,
                            onPlay = { onPlayItem(index) },
                            onMove = { to -> onMoveItem(index, to) },
                            onRemove = { onRemoveItem(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    item: PlaybackQueueItem,
    enabled: Boolean,
    isLast: Boolean,
    onPlay: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val nowPlaying = stringResource(R.string.chain_current_playing)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.play_this_track),
                role = Role.Button,
                onClick = onPlay,
            )
            .then(if (item.isCurrent) Modifier.semantics { stateDescription = nowPlaying } else Modifier)
            .heightIn(min = 56.dp)
            .padding(start = VesqenSpacing.md, end = VesqenSpacing.xxs)
            .testTag("vesqen.queue.item.$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The current track takes a Moss dot in place of its number (B: Moss marks what is active).
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            if (item.isCurrent) {
                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            } else {
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = VesqenSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title.ifBlank { stringResource(R.string.unknown_title) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.artist.ifBlank { stringResource(R.string.unknown_artist) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        QueueEditButton(Icons.Filled.ArrowUpward, R.string.move_up, enabled && index > 0) { onMove(index - 1) }
        QueueEditButton(Icons.Filled.ArrowDownward, R.string.move_down, enabled && !isLast) { onMove(index + 1) }
        QueueEditButton(Icons.Outlined.Delete, R.string.remove_from_queue, enabled, onRemove)
    }
}

/** Editing stays one tap away but quiet: muted icons after the title, still 48 dp to touch. */
@Composable
private fun QueueEditButton(icon: ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(20.dp))
    }
}
