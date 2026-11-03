package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.PlaybackQueueItem
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import io.github.sumirenokai.vesqen.ui.theme.rememberVesqenMotionPolicy
import kotlinx.coroutines.delay
import kotlin.math.abs

/** B · Paper & Sound queue: one card of hairline rows; tap a row to play it, long-press to move it. */
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
        // Half open, the list's end is off the screen, where no drag can reach the edge that scrolls it.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VesqenSpacing.lg)
                .padding(bottom = VesqenSpacing.md),
        ) {
            QueueSheetHeader(
                clearEnabled = snapshot.isControllerReady && snapshot.queue.isNotEmpty(),
                onClearQueue = {
                    onClearQueue()
                    onDismiss()
                },
                onClose = onDismiss,
            )
            // On its own line, so the buttons beside the title never squeeze the count into two (#35).
            Text(
                text = pluralStringResource(R.plurals.library_song_total, snapshot.queue.size, snapshot.queue.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!snapshot.isControllerReady) {
                Text(
                    text = stringResource(R.string.playback_controls_connecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = VesqenSpacing.xxs),
                )
            }
            PaperCard(Modifier.padding(top = VesqenSpacing.sm)) {
                QueueList(snapshot, onPlayItem, onRemoveItem, onMoveItem)
            }
        }
    }
}

/**
 * The rows in play order. A long press picks a row up and it follows the finger; the others make
 * room in a preview, and only the drop edits the queue (#211). The queue owns the order: a change
 * to it during a drag ends the drag without moving anything, so a drop never lands on another entry.
 */
@Composable
private fun QueueList(
    snapshot: PlaybackSnapshot,
    onPlayItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
) {
    val queue = snapshot.queue
    val enabled = snapshot.isControllerReady
    val rowKeys = remember(queue) { queueRowKeys(queue) }
    // Position keys stay the same when entries change places, so the keys alone cannot show a change.
    val queueContent = remember(queue) { queue.map { Triple(it.listIndex, it.trackId, it.entryId) } }
    val rowsByKey = remember(queue, rowKeys) { rowKeys.zip(queue).toMap() }
    val currentQueue by rememberUpdatedState(queue)
    val currentKeys by rememberUpdatedState(rowKeys)
    val currentContent by rememberUpdatedState(queueContent)
    val moveItem by rememberUpdatedState(onMoveItem)
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val motionPolicy = rememberVesqenMotionPolicy()
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var liftY by remember { mutableFloatStateOf(0f) }
    // The order shown while dragging, and after a drop until the queue arrives in that order.
    var preview by remember { mutableStateOf<List<String>?>(null) }
    // What the preview rearranges. Position keys would apply it to other rows once the queue changes.
    var previewContent by remember { mutableStateOf(queueContent) }
    var reorderViewport by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val density = LocalDensity.current
    val edgePx = with(density) { 48.dp.toPx() }
    val speedPx = with(density) { 1000.dp.toPx() }
    val removeAreaPx = with(density) { 48.dp.toPx() }
    val liftPx = with(density) { 6.dp.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val upLabel = stringResource(R.string.move_up)
    val downLabel = stringResource(R.string.move_down)
    val shownKeys = preview?.takeIf { previewContent == queueContent } ?: rowKeys
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    LaunchedEffect(queueContent) {
        dragged = null
        preview = null
    }
    // A refused move leaves the queue as it was, so the preview cannot wait for it forever.
    LaunchedEffect(preview, dragged) {
        if (preview == null || dragged != null) return@LaunchedEffect
        delay(MOVE_CONFIRM_TIMEOUT_MILLIS)
        preview = null
    }
    SideEffect {
        reorderViewport?.let { (index, offset) ->
            // After the new order is composed; keyed rows would otherwise pull the list along.
            listState.requestScrollToItem(index, offset)
            reorderViewport = null
        }
    }
    LaunchedEffect(dragged) {
        val key = dragged ?: return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (dragged == key) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(.05f)
            previousFrame = frame
            val layout = listState.layoutInfo
            // A row lifted beside an edge stays until the finger moves toward that edge: a still
            // finger must not scroll rows past it and reorder the queue.
            val edgeFraction = when {
                dragY < layout.viewportStartOffset + edgePx && dragY < liftY - touchSlop ->
                    -((layout.viewportStartOffset + edgePx - dragY) / edgePx).coerceIn(0f, 1f)
                dragY > layout.viewportEndOffset - edgePx && dragY > liftY + touchSlop ->
                    ((dragY - layout.viewportEndOffset + edgePx) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (edgeFraction != 0f) listState.scrollBy(edgeFraction * speedPx * seconds)
            val target = listState.layoutInfo.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2f - dragY) }?.index ?: continue
            val order = preview ?: continue
            val from = order.indexOf(key)
            if (from >= 0 && from != target && target in order.indices) {
                reorderViewport = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                preview = order.toMutableList().apply { add(target, removeAt(from)) }
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .heightIn(max = 520.dp)
            .testTag("vesqen.queue.list")
            .pointerInput(enabled, removeAreaPx, rtl) {
                if (enabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // The trailing button keeps its own press.
                    val onRemoveButton = if (rtl) down.position.x <= removeAreaPx else down.position.x >= size.width - removeAreaPx
                    if (onRemoveButton) return@awaitEachGesture
                    // A scroll consumes the move first and ends the wait; a tap ends it too.
                    awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    // A dropped row still waits for its move. Going back to the queue's order here would
                    // put another row under the finger and move it with no drag at all. The hold does
                    // nothing, so its release must not reach the row's click and play it.
                    if (preview != null) {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                        }
                        return@awaitEachGesture
                    }
                    val y = down.position.y + listState.layoutInfo.viewportStartOffset
                    val item = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return@awaitEachGesture
                    val key = item.key as? String ?: return@awaitEachGesture
                    val origin = item.offset + item.size / 2f
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    dragY = origin
                    liftY = origin
                    preview = currentKeys
                    previewContent = currentContent
                    dragged = key
                    var dropped = false
                    try {
                        while (true) {
                            // First in line: neither the row's click nor the sheet's drag sees it.
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                dropped = true
                                break
                            }
                            dragY = origin + change.position.y - down.position.y
                        }
                    } finally {
                        // Still this drag only if the queue did not change under it.
                        val order = preview
                        val from = currentKeys.indexOf(key)
                        val to = order?.indexOf(key) ?: -1
                        val moved = dropped && dragged == key && from >= 0 && to >= 0 && from != to
                        if (moved) {
                            moveItem(currentQueue[from].listIndex, currentQueue[to].listIndex)
                        } else if (dragged == key) {
                            preview = null
                        }
                        dragged = null
                    }
                }
            },
    ) {
        itemsIndexed(items = shownKeys, key = { _, key -> key }) { index, key ->
            val item = rowsByKey[key] ?: return@itemsIndexed
            val lifted = dragged == key
            Column(
                Modifier
                    .zIndex(if (lifted) 1f else 0f)
                    .animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = null,
                        placementSpec = if (lifted || motionPolicy.reduceMotion) null else spring(
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        ),
                    )
                    .graphicsLayer {
                        if (lifted) {
                            translationY = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                                ?.let { dragY - it.offset - it.size / 2f } ?: 0f
                            shadowElevation = liftPx
                        }
                    }
                    .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
            ) {
                if (index > 0 && !lifted) PaperDivider()
                QueueRow(
                    index = index,
                    item = item,
                    enabled = enabled,
                    isPlaying = snapshot.isPlaying,
                    // Rows follow the play order; edits name the player's list index (#158).
                    // Until the queue answers a drop, a list index may already name another entry.
                    onPlay = { if (preview == null) onPlayItem(item.listIndex) },
                    // The row's own merged node carries the moves, which TalkBack and other services reach.
                    // Its neighbors below are the queue's, so a shown order that differs offers none.
                    moves = if (!enabled || preview != null) emptyList() else buildList {
                        if (index > 0) {
                            add(CustomAccessibilityAction(upLabel) { onMoveItem(item.listIndex, queue[index - 1].listIndex); true })
                        }
                        if (index < queue.lastIndex) {
                            add(CustomAccessibilityAction(downLabel) { onMoveItem(item.listIndex, queue[index + 1].listIndex); true })
                        }
                    },
                    onRemove = { if (preview == null) onRemoveItem(item.listIndex) },
                )
            }
        }
    }
}

/** Entry ids stay with a row as the queue moves. A queue restored without them falls back to positions. */
internal fun queueRowKeys(queue: List<PlaybackQueueItem>): List<String> {
    val ids = queue.map(PlaybackQueueItem::entryId)
    return if (ids.none(String::isEmpty) && ids.distinct().size == ids.size) ids else queue.map { "list:${it.listIndex}" }
}

/**
 * The title, Clear queue and Close: one line while the title fits whole beside both buttons.
 * Otherwise Clear queue takes the line below, its label under the title, so large text or a narrow
 * window never breaks the title mid-word (#165 X4), as the count got its own line (#35).
 */
@Composable
internal fun QueueSheetHeader(clearEnabled: Boolean, onClearQueue: () -> Unit, onClose: () -> Unit) {
    val clearLabelInset = ButtonDefaults.TextButtonContentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val content: @Composable () -> Unit = {
        Text(
            text = stringResource(R.string.queue),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }.testTag("vesqen.queue.title"),
        )
        TextButton(
            onClick = onClearQueue,
            enabled = clearEnabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("vesqen.queue.clear"),
        ) {
            Text(stringResource(R.string.clear_queue))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("vesqen.queue.close")) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
        }
    }
    Layout(content) { measurables, constraints ->
        val (title, clear, close) = measurables
        val width = constraints.maxWidth
        val oneLine = title.maxIntrinsicWidth(constraints.maxHeight) + clear.maxIntrinsicWidth(constraints.maxHeight) +
            close.maxIntrinsicWidth(constraints.maxHeight) <= width
        val closePlaceable = close.measure(Constraints(maxWidth = width))
        val clearPlaceable = clear.measure(Constraints(maxWidth = width))
        val besideTitle = closePlaceable.width + if (oneLine) clearPlaceable.width else 0
        val titlePlaceable = title.measure(Constraints(maxWidth = (width - besideTitle).coerceAtLeast(0)))
        val firstLine = maxOf(titlePlaceable.height, closePlaceable.height, if (oneLine) clearPlaceable.height else 0)
        layout(width, firstLine + if (oneLine) 0 else clearPlaceable.height) {
            fun centered(height: Int) = (firstLine - height) / 2
            titlePlaceable.placeRelative(0, centered(titlePlaceable.height))
            closePlaceable.placeRelative(width - closePlaceable.width, centered(closePlaceable.height))
            if (oneLine) {
                clearPlaceable.placeRelative(width - closePlaceable.width - clearPlaceable.width, centered(clearPlaceable.height))
            } else {
                clearPlaceable.placeRelative(-clearLabelInset.roundToPx(), firstLine)
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    item: PlaybackQueueItem,
    enabled: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    moves: List<CustomAccessibilityAction>,
    onRemove: () -> Unit,
) {
    // The current row says whether it plays or is paused, like the library's rows (#35).
    val playbackState = stringResource(if (isPlaying) R.string.playing else R.string.paused)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.play_this_track),
                role = Role.Button,
                onClick = onPlay,
            )
            .then(if (item.isCurrent) Modifier.semantics { stateDescription = playbackState } else Modifier)
            .semantics { customActions = moves }
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
        QueueEditButton(Icons.Outlined.Delete, R.string.remove_from_queue, enabled, onRemove)
    }
}

/** Removal stays one tap away but quiet: a muted icon after the title, still 48 dp to touch. */
@Composable
private fun QueueEditButton(icon: ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        // Material's IconButton clicks at 40 dp; the size makes the clickable itself 48 dp (#143 Q20).
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(20.dp))
    }
}

private const val MOVE_CONFIRM_TIMEOUT_MILLIS = 1_000L
