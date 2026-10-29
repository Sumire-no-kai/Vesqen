package io.github.sumirenokai.vesqen.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * #158: moves that must land at a place in the play order. With shuffle on, a list move leaves
 * Media3's play order as it was. Only the service's ExoPlayer can set a shuffle order, so the app's
 * controller asks for moves with a custom session command.
 */
internal object PlayOrderSessionContract {
    const val MOVE_ACTION = "io.github.sumirenokai.vesqen.queue.MOVE"
    private const val KEY_ITEM = "item"
    private const val KEY_ITEM_ID = "item_id"
    private const val KEY_ANCHOR = "anchor"
    private const val KEY_ANCHOR_ID = "anchor_id"
    private const val KEY_AFTER = "after"

    /**
     * The item at list index [item] takes the place of the item at list index [anchor], its
     * neighbor in the play order, and so plays right [after] it or right before it. The entry IDs
     * ([queueEntryId]) let the service refuse a request made against a queue that has changed
     * since; a shuffled move keeps the list, so the direction makes a repeated request harmless.
     */
    data class Move(val item: Int, val itemId: String, val anchor: Int, val anchorId: String, val after: Boolean)

    fun moveArgs(move: Move): Bundle = Bundle().apply {
        putInt(KEY_ITEM, move.item)
        putString(KEY_ITEM_ID, move.itemId)
        putInt(KEY_ANCHOR, move.anchor)
        putString(KEY_ANCHOR_ID, move.anchorId)
        putBoolean(KEY_AFTER, move.after)
    }

    fun readMove(args: Bundle): Move? {
        if (!args.containsKey(KEY_ITEM) || !args.containsKey(KEY_ANCHOR) || !args.containsKey(KEY_AFTER)) return null
        return Move(
            item = args.getInt(KEY_ITEM),
            itemId = args.getString(KEY_ITEM_ID) ?: return null,
            anchor = args.getInt(KEY_ANCHOR),
            anchorId = args.getString(KEY_ANCHOR_ID) ?: return null,
            after = args.getBoolean(KEY_AFTER),
        )
    }
}

/** Where Play next and Add to queue put their item: right after the current item, or last (#158). */
internal enum class PlayOrderPlacement { NEXT, LAST }

private const val PLAY_ORDER_PLACEMENT_EXTRA = "io.github.sumirenokai.vesqen.playback.play_order_placement"

/** The item as the app's controller inserts it: its request names its placement. */
internal fun MediaItem.withPlayOrderPlacement(placement: PlayOrderPlacement): MediaItem =
    withRequestExtras(Bundle().apply { putString(PLAY_ORDER_PLACEMENT_EXTRA, placement.name) })

internal fun MediaItem.playOrderPlacement(): PlayOrderPlacement? =
    requestMetadata.extras?.getString(PLAY_ORDER_PLACEMENT_EXTRA)
        ?.let { name -> PlayOrderPlacement.entries.firstOrNull { it.name == name } }

private const val PLAY_ORDER_POSITION_EXTRA = "io.github.sumirenokai.vesqen.playback.play_order_position"

/**
 * A restored shuffled queue's items (#170): each request names the item's place in [order], the saved
 * play order (list indices in the order they play). Null leaves the items as they are.
 */
internal fun List<MediaItem>.withPlayOrder(order: List<Int>?): List<MediaItem> {
    if (order == null) return this
    require(order.size == size)
    val positions = IntArray(size).also { positions -> order.forEachIndexed { position, index -> positions[index] = position } }
    return mapIndexed { index, item -> item.withRequestExtras(Bundle().apply { putInt(PLAY_ORDER_POSITION_EXTRA, positions[index]) }) }
}

/** The play order the items name ([withPlayOrder]), or null unless each of them names a different place. */
internal fun List<MediaItem>.namedPlayOrder(): IntArray? {
    if (isEmpty()) return null
    val order = IntArray(size) { C.INDEX_UNSET }
    forEachIndexed { index, item ->
        val position = item.requestMetadata.extras?.getInt(PLAY_ORDER_POSITION_EXTRA, C.INDEX_UNSET) ?: return null
        if (position !in indices || order[position] != C.INDEX_UNSET) return null
        order[position] = index
    }
    return order
}

private fun MediaItem.withRequestExtras(extras: Bundle): MediaItem =
    buildUpon().setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build()).build()

internal fun MediaItem.withoutRequestMetadata(): MediaItem =
    buildUpon().setRequestMetadata(MediaItem.RequestMetadata.EMPTY).build()

/** One queue entry: the same track queued twice has two entries (PLAYBACK_OCCURRENCE_EXTRA). */
internal fun MediaItem.queueEntryId(): String =
    mediaMetadata.extras?.getString(PLAYBACK_OCCURRENCE_EXTRA) ?: mediaId

/**
 * The session's player (#170 F8). Play next and Add to queue arrive as ordinary inserts, which the
 * app's controller shows at once, so a library refresh right after one works on the new queue. The
 * service places the item from its own current item, which may have moved on while the request
 * travelled. A restored shuffled queue, from the app's controller or a media button resumption,
 * arrives with its saved play order in the items ([withPlayOrder]).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlayOrderSessionPlayer(val exoPlayer: ExoPlayer) : ForwardingPlayer(exoPlayer) {
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        val item = mediaItems.singleOrNull()
        val placement = item?.playOrderPlacement() ?: return super.addMediaItems(index, mediaItems)
        exoPlayer.insertInPlayOrder(item.withoutRequestMetadata(), placement)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val order = mediaItems.namedPlayOrder() ?: return super.setMediaItems(mediaItems, startIndex, startPositionMs)
        exoPlayer.setMediaItemsInPlayOrder(mediaItems.map { it.withoutRequestMetadata() }, order, startIndex, startPositionMs)
    }
}

/**
 * Sets a queue that plays in [order], a restored queue's saved play order (#170). The current order,
 * with the same traversal so the timeline does not change, hands it to the queue set below, which is
 * then the only timeline change.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ExoPlayer.setMediaItemsInPlayOrder(items: List<MediaItem>, order: IntArray, startIndex: Int, startPositionMs: Long) {
    setShuffleOrder(PlayOrder(shuffleOrder.playOrder(), nextSetOrder = order))
    setMediaItems(items, startIndex, startPositionMs)
}

/**
 * Inserts the item after the current one, or last. Without shuffle the list order is the play
 * order; with it, [PlayOrder] plays an inserted item right after its list predecessor, the current
 * item for Play next, and is told to play Add to queue's item last. Either is one list change, so
 * the session rebuilds its queue once (#170 F9).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ExoPlayer.insertInPlayOrder(item: MediaItem, placement: PlayOrderPlacement) {
    val next = placement == PlayOrderPlacement.NEXT && mediaItemCount > 0
    if (!next && shuffleModeEnabled && mediaItemCount > 0) {
        // The same order, so the timeline does not change; only the insert below reads the flag.
        setShuffleOrder(PlayOrder(shuffleOrder.playOrder(), nextInsertPlaysLast = true))
    }
    addMediaItem(if (next) currentMediaItemIndex + 1 else mediaItemCount, item)
}

/**
 * Moves an item to its neighbor's place in the play order: while shuffled only that order changes,
 * so the list keeps its order for when shuffle is turned off; otherwise the list is the order. False
 * when the queue changed since the request.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ExoPlayer.moveInPlayOrder(move: PlayOrderSessionContract.Move): Boolean {
    val indices = 0 until mediaItemCount
    if (move.item !in indices || move.anchor !in indices || move.item == move.anchor) return false
    if (getMediaItemAt(move.item).queueEntryId() != move.itemId || getMediaItemAt(move.anchor).queueEntryId() != move.anchorId) {
        return false
    }
    if (!shuffleModeEnabled) {
        moveMediaItem(move.item, move.anchor)
        return true
    }
    setShuffleOrder(PlayOrder(shuffleOrder.playOrder().withMoved(move.item, move.anchor, move.after)))
    return true
}

/**
 * Puts the current item first in the shuffled order. Media3 keeps its order when shuffle turns on,
 * so the current item could sit anywhere in it and the items before it would not play.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ExoPlayer.playCurrentItemFirst() {
    val current = currentMediaItemIndex
    if (!shuffleModeEnabled || current !in 0 until mediaItemCount) return
    val order = shuffleOrder.playOrder()
    if (order.firstOrNull() == current) return
    setShuffleOrder(PlayOrder(intArrayOf(current) + order.filter { it != current }))
}

/**
 * The service player's shuffle order (#170 F9). Media3's default order puts every inserted item at a
 * random place, so each insert while shuffled needed a second order to correct it. Here the order is
 * random only where a queue starts, and an edit leaves every other item where it plays:
 * - a new queue plays its start item first, the others in random order; one set after an order with
 *   [nextSetOrder] (a restored queue's saved order, #170) plays in that order instead;
 * - inserted items play right after their list predecessor, or first without one. Play next inserts
 *   after the current item, and an item a library refresh re-creates (Media3 inserts it right after
 *   the one it replaces) keeps its place. With [nextInsertPlaysLast], the next insert plays last;
 * - removed items leave the others in their order.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlayOrder(
    private val order: IntArray,
    private val nextInsertPlaysLast: Boolean = false,
    private val nextSetOrder: IntArray? = null,
    private val random: Random = Random,
) : ShuffleOrder {
    private val positions = IntArray(order.size).also { positions ->
        order.forEachIndexed { position, index -> positions[index] = position }
    }

    override fun getLength(): Int = order.size

    override fun getNextIndex(index: Int): Int = order.getOrElse(positions[index] + 1) { C.INDEX_UNSET }

    override fun getPreviousIndex(index: Int): Int = order.getOrElse(positions[index] - 1) { C.INDEX_UNSET }

    override fun getLastIndex(): Int = order.lastOrNull() ?: C.INDEX_UNSET

    override fun getFirstIndex(): Int = order.firstOrNull() ?: C.INDEX_UNSET

    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder {
        nextSetOrder?.takeIf { it.size == insertionCount }?.let { return PlayOrder(it, random = random) }
        val start = listOf(startIndex).filter { it in 0 until insertionCount }
        return PlayOrder((start + (0 until insertionCount).filter { it != startIndex }.shuffled(random)).toIntArray(), random = random)
    }

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder =
        PlayOrder(order.withInserted(insertionIndex, insertionCount, last = nextInsertPlaysLast), random = random)

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val removed = indexToExclusive - indexFrom
        val kept = order.filter { it !in indexFrom until indexToExclusive }
        return PlayOrder(kept.map { if (it >= indexToExclusive) it - removed else it }.toIntArray(), random = random)
    }

    override fun cloneAndClear(): ShuffleOrder = PlayOrder(IntArray(0), random = random)
}

/** List indices in the order they play. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ShuffleOrder.playOrder(): IntArray {
    val order = IntArray(length)
    var index = firstIndex
    for (position in order.indices) {
        order[position] = index
        index = getNextIndex(index)
    }
    return order
}

/** List indices in the order they play, as a controller sees the session's timeline. */
internal fun Player.playOrder(): List<Int> {
    val timeline = currentTimeline
    if (timeline.windowCount != mediaItemCount) return List(mediaItemCount) { it }
    return buildList(mediaItemCount) {
        var index = timeline.getFirstWindowIndex(shuffleModeEnabled)
        while (index != C.INDEX_UNSET && size < mediaItemCount) {
            add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
        }
    }
}

/**
 * The play order after [count] items are inserted into the list at [listIndex]: later list indices
 * move up, and the new items play in list order right after their list predecessor, first without
 * one, or [last].
 */
internal fun IntArray.withInserted(listIndex: Int, count: Int, last: Boolean): IntArray {
    val order = map { if (it >= listIndex) it + count else it }.toMutableList()
    val position = when {
        last -> order.size
        listIndex == 0 -> 0
        else -> order.indexOf(listIndex - 1) + 1
    }
    order.addAll(position, (listIndex until listIndex + count).toList())
    return order.toIntArray()
}

/** The play order with [item] taken out and put back right before or [after] [anchor]. */
internal fun IntArray.withMoved(item: Int, anchor: Int, after: Boolean): IntArray {
    val order = toMutableList()
    order.removeAt(order.indexOf(item))
    val anchorPosition = order.indexOf(anchor)
    order.add(if (after) anchorPosition + 1 else anchorPosition, item)
    return order.toIntArray()
}
