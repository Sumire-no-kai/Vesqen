package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing

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
                .padding(horizontal = VesqenSpacing.md)
                .padding(bottom = VesqenSpacing.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.queue),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        onClearQueue()
                        onDismiss()
                    },
                    enabled = snapshot.queue.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.clear_queue))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(
                    items = snapshot.queue,
                    key = { index, item -> "${item.trackId}:$index" },
                ) { index, item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = VesqenSpacing.xxs)
                            .testTag("vesqen.queue.item.$index"),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(VesqenRadii.control),
                        color = if (item.isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = VesqenSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { onPlayItem(index) }) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.play_this_track),
                                )
                            }
                            Spacer(Modifier.width(VesqenSpacing.xxs))
                            Column(modifier = Modifier.weight(1f)) {
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
                            IconButton(
                                onClick = { onMoveItem(index, index - 1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.move_up))
                            }
                            IconButton(
                                onClick = { onMoveItem(index, index + 1) },
                                enabled = index < snapshot.queue.lastIndex,
                            ) {
                                Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.move_down))
                            }
                            IconButton(onClick = { onRemoveItem(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove_from_queue))
                            }
                        }
                    }
                }
            }
        }
    }
}
