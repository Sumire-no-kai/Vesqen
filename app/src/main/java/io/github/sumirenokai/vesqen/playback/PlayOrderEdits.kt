package io.github.sumirenokai.vesqen.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * #158: queue edits that must land at a place in the play order. With shuffle on, Media3 inserts a
 * new item at a random place in that order and a list move leaves the order as it was, so "Play
 * next" rarely played next. Only the service's ExoPlayer can set a shuffle order, so the app's
 * controller asks for these edits with custom session commands.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlayOrderSessionContract {
    const val INSERT_ACTION = "io.github.sumirenokai.vesqen.queue.INSERT"
    const val MOVE_ACTION = "io.github.sumirenokai.vesqen.queue.MOVE"
    private const val KEY_ITEM = "item"
    private const val KEY_INDEX = "index"
    private const val KEY_AFTER_CURRENT = "after_current"
    private const val KEY_ITEM_ID = "item_id"
    private const val KEY_ANCHOR = "anchor"
    private const val KEY_ANCHOR_ID = "anchor_id"
    private const val KEY_AFTER = "after"

    data class Insert(val item: MediaItem, val listIndex: Int, val afterCurrent: Boolean)

    /** Moves the item at list index [item] to just before or [after] the item at list index [anchor]. */
    data class Move(val item: Int, val itemId: String, val anchor: Int, val anchorId: String, val after: Boolean)

    fun insertArgs(insert: Insert): Bundle = Bundle().apply {
        // The service runs in the app's process, so the item keeps its local URI.
        putBundle(KEY_ITEM, insert.item.toBundleIncludeLocalConfiguration())
        putInt(KEY_INDEX, insert.listIndex)
        putBoolean(KEY_AFTER_CURRENT, insert.afterCurrent)
    }

    fun readInsert(args: Bundle): Insert? {
        val item = args.getBundle(KEY_ITEM)?.let(MediaItem::fromBundle) ?: return null
        if (!args.containsKey(KEY_INDEX)) return null
        return Insert(item, args.getInt(KEY_INDEX), args.getBoolean(KEY_AFTER_CURRENT))
    }

    fun moveArgs(move: Move): Bundle = Bundle().apply {
        putInt(KEY_ITEM, move.item)
        putString(KEY_ITEM_ID, move.itemId)
        putInt(KEY_ANCHOR, move.anchor)
        putString(KEY_ANCHOR_ID, move.anchorId)
        putBoolean(KEY_AFTER, move.after)
    }

    fun readMove(args: Bundle): Move? {
        if (!args.containsKey(KEY_ITEM) || !args.containsKey(KEY_ANCHOR)) return null
        return Move(
            item = args.getInt(KEY_ITEM),
            itemId = args.getString(KEY_ITEM_ID) ?: return null,
            anchor = args.getInt(KEY_ANCHOR),
            anchorId = args.getString(KEY_ANCHOR_ID) ?: return null,
            after = args.getBoolean(KEY_AFTER),
        )
    }
}

/**
 * Inserts the item and, while shuffled, plays it right after the current item or after every other
 * item. Without shuffle the list order is the play order already.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ExoPlayer.insertInPlayOrder(insert: PlayOrderSessionContract.Insert) {
    val listIndex = insert.listIndex.coerceIn(0, mediaItemCount)
    val before = shuffleOrder.playOrder()
    val anchor = currentMediaItemIndex.takeIf { insert.afterCurrent && it != C.INDEX_UNSET && it < mediaItemCount }
    addMediaItem(listIndex, insert.item)
    if (shuffleModeEnabled) {
        setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(before.withInsertedAt(listIndex, anchor), Random.nextLong()))
    }
}

/** Reorders the shuffled play order; false when not shuffled or the queue changed since the request. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ExoPlayer.moveInPlayOrder(move: PlayOrderSessionContract.Move): Boolean {
    val indices = 0 until mediaItemCount
    if (!shuffleModeEnabled || move.item !in indices || move.anchor !in indices || move.item == move.anchor) return false
    if (getMediaItemAt(move.item).mediaId != move.itemId || getMediaItemAt(move.anchor).mediaId != move.anchorId) return false
    val order = shuffleOrder.playOrder().withMoved(move.item, move.anchor, move.after)
    setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, Random.nextLong()))
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
    setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(current) + order.filter { it != current }, Random.nextLong()))
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
 * The play order after an item is inserted into the list at [listIndex]: later list indices move up
 * by one, and the new item plays right after [anchor] (a list index from before the insert), or last.
 */
internal fun IntArray.withInsertedAt(listIndex: Int, anchor: Int?): IntArray {
    val order = map { if (it >= listIndex) it + 1 else it }.toMutableList()
    val shiftedAnchor = anchor?.let { if (it >= listIndex) it + 1 else it }
    val position = shiftedAnchor?.let { order.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: order.size
    order.add(position, listIndex)
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
