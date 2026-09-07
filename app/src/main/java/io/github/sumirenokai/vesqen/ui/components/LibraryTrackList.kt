package io.github.sumirenokai.vesqen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.ui.theme.VesqenSpacing
import kotlin.math.abs

/** The browsing list stays mounted while its overflow buttons become reorder handles. */
@Composable
internal fun LibraryTrackList(
    tracks: List<AudioTrack>,
    currentTrackId: Long?,
    isPlaying: Boolean,
    onTrackSelected: (AudioTrack) -> Unit,
    onTrackMore: (AudioTrack) -> Unit,
    alphabetSections: Map<String, Int> = emptyMap(),
    editing: Boolean = false,
    saving: Boolean = false,
    onReorder: (List<AudioTrack>) -> Unit = {},
) {
    val listState = rememberLazyListState()
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var reorderViewport by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val currentTracks by rememberUpdatedState(tracks)
    val reorder by rememberUpdatedState(onReorder)
    val edgePx = with(LocalDensity.current) { 64.dp.toPx() }
    val speedPx = with(LocalDensity.current) { 1000.dp.toPx() }
    val handleAreaPx = with(LocalDensity.current) { (48.dp + VesqenSpacing.md).toPx() }
    val upLabel = stringResource(R.string.move_up)
    val downLabel = stringResource(R.string.move_down)

    fun move(id: Long, target: Int) {
        if (!editing || saving) return
        val from = currentTracks.indexOfFirst { it.id == id }
        if (from >= 0 && target in currentTracks.indices && from != target) {
            // Keep the viewport fixed when the first visible key moves. Following that key
            // would scroll under the stationary pointer and repeatedly move the dragged row.
            reorderViewport = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            reorder(currentTracks.toMutableList().apply { add(target, removeAt(from)) })
        }
    }
    SideEffect {
        reorderViewport?.let { (index, offset) ->
            // Apply after the new item provider is composed; an earlier remeasure can
            // otherwise restore the old key before the reordered data reaches the list.
            listState.requestScrollToItem(index, offset)
            reorderViewport = null
        }
    }
    // A local function reference compares equal across compositions even when its captured
    // editing flag changes. Use a lambda so entering edit mode updates the running drag action.
    val moveCurrent by rememberUpdatedState<(Long, Int) -> Unit>({ id, target -> move(id, target) })
    LaunchedEffect(draggedId, editing, saving) {
        val id = draggedId ?: return@LaunchedEffect
        if (!editing || saving) { draggedId = null; return@LaunchedEffect }
        var previousFrame = withFrameNanos { it }
        while (draggedId == id) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(.05f)
            previousFrame = frame
            val layout = listState.layoutInfo
            val edgeFraction = when {
                dragY < layout.viewportStartOffset + edgePx ->
                    -((layout.viewportStartOffset + edgePx - dragY) / edgePx).coerceIn(0f, 1f)
                dragY > layout.viewportEndOffset - edgePx ->
                    ((dragY - layout.viewportEndOffset + edgePx) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (edgeFraction != 0f) listState.scrollBy(edgeFraction * speedPx * seconds)
            listState.layoutInfo.visibleItemsInfo.minByOrNull {
                abs(it.offset + it.size / 2f - dragY)
            }?.let { moveCurrent(id, it.index) }
        }
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxSize().testTag("vesqen.library.tracks")
                .pointerInput(editing, saving, handleAreaPx) {
                    if (editing && !saving) awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (down.position.x < size.width - handleAreaPx) return@awaitEachGesture
                        val contentY = down.position.y + listState.layoutInfo.viewportStartOffset
                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            contentY >= it.offset && contentY < it.offset + it.size
                        } ?: return@awaitEachGesture
                        val id = item.key as? Long ?: return@awaitEachGesture
                        val origin = item.offset + item.size / 2f
                        down.consume()
                        dragY = origin
                        draggedId = id
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                // Track the pointer in the stationary list, not a row that is
                                // translated and reindexed beneath it during edge scrolling.
                                dragY = origin + change.position.y - down.position.y
                            }
                        } finally { draggedId = null }
                    }
                },
            contentPadding = PaddingValues(
                start = VesqenSpacing.md,
                end = if (alphabetSections.isEmpty()) VesqenSpacing.md else 0.dp,
                top = VesqenSpacing.xs,
                bottom = VesqenSpacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(VesqenSpacing.xxs),
        ) {
            itemsIndexed(tracks, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track ->
                val dragging = draggedId == track.id
                val rowModifier = if (!editing) Modifier else Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) {
                            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == track.id }
                                ?.let { dragY - it.offset - it.size / 2f } ?: 0f
                        } else 0f
                    }
                    .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                    .semantics {
                        if (editing && !saving) customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction(upLabel) { move(track.id, index - 1); true })
                            if (index < tracks.lastIndex) add(CustomAccessibilityAction(downLabel) { move(track.id, index + 1); true })
                        }
                    }
                Box(rowModifier) {
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = track.id == currentTrackId && isPlaying,
                        onPlay = { onTrackSelected(track) },
                        onMore = { onTrackMore(track) },
                        enabled = !editing,
                        trailingContent = if (!editing) null else {
                            {
                                Icon(Icons.Filled.DragHandle, stringResource(R.string.drag_track_order),
                                    Modifier.size(48.dp).testTag("vesqen.library.drag.${track.id}")
                                        .padding(12.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
        if (!editing && alphabetSections.isNotEmpty()) LibraryAlphabetIndex(alphabetSections, listState)
    }
}
