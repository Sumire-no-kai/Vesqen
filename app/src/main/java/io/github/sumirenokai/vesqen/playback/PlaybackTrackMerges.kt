package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.sumirenokai.vesqen.library.TrackMergeFollower

/**
 * Carries the catalog's committed merges to playback: the #139 choice, the #164 folder reattachment
 * and the legacy MediaStore re-key (#191 D1, D7, D8). The surviving row names the same file, so queue
 * entries only change their track ID.
 *
 * The running service is the only writer of the playback-state files: it applies a transaction's
 * merges to its saved queue and its player in one step (#191 A4). Without a running service nothing
 * else writes them, so the saved queue follows here. Both run on the main thread, where the service
 * starts and stops, so a merge reaches exactly one of them. A merge applies as it commits, whether or
 * not a later read of the Library succeeds.
 *
 * The merges of this process are kept as well: a queue the app still holds from before one, such as
 * Undo after Clear queue or a queue started while the service was disconnected, takes the new IDs as
 * it enters the service's player (#191 A3, [mergedTrackId]).
 */
internal class PlaybackTrackMerges(context: Context) : TrackMergeFollower {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The running service's merge step, on the main thread; null while no service runs. */
    private var service: ((merges: Map<Long, Long>) -> Unit)? = null

    /** Every merge committed in this catalog during this process, on the main thread. */
    private val merged = HashMap<Long, Long>()

    /** On the main thread, from the service's creation until [detach]. */
    fun attach(applyMerges: (merges: Map<Long, Long>) -> Unit) {
        service = applyMerges
    }

    fun detach() {
        service = null
    }

    /** On the main thread: the ID a queue entry of [trackId] has now, after this process's merges. */
    fun mergedTrackId(trackId: Long): Long {
        var current = trackId
        // A merged row is deleted, so its ID never merges again: a chain has at most one step per merge.
        repeat(merged.size) { current = merged[current] ?: return current }
        return current
    }

    /** Before the catalog is rebuilt: its IDs name other songs, or none, afterwards. From any thread. */
    fun forgetMerges() {
        mainHandler.post { merged.clear() }
    }

    override fun prepareMerge(from: Long, into: Long) = Unit

    override fun mergeCommitted(from: Long, into: Long) = mergesCommitted(listOf(from to into))

    override fun mergesCommitted(merges: List<Pair<Long, Long>>) {
        if (merges.isEmpty()) return
        val committed = merges.toList()
        mainHandler.post {
            committed.forEach { (from, into) -> merged[from] = into }
            // A chain within the transaction ends at its last row.
            val batch = committed.associate { (from, _) -> from to mergedTrackId(from) }
            val running = service
            if (running != null) running(batch) else PlaybackStateStore(appContext).replaceTrackIds(batch)
        }
    }

    override fun mergeRolledBack(from: Long, into: Long) = Unit
}

/**
 * Queue entries whose track ID is a key of [merges] take its value, which names the same file, and
 * keep their occurrences and places in the play order. One replacement over the entries that change,
 * so the keeper and the session see one timeline change (#191 A4). Media3 updates entries with the same
 * source in place, so the current one keeps playing.
 */
internal fun Player.replaceTrackEntries(merges: Map<Long, Long>) {
    var first = -1
    var last = -1
    for (index in 0 until mediaItemCount) {
        if (getMediaItemAt(index).mediaId.toLongOrNull() in merges) {
            if (first < 0) first = index
            last = index
        }
    }
    if (first < 0) return
    replaceMediaItems(first, last + 1, List(last - first + 1) { getMediaItemAt(first + it).withMergedTrackId(merges::get) })
}

/** This item under the ID [mergedId] gives its track, or itself when that is unchanged. */
internal fun MediaItem.withMergedTrackId(mergedId: (Long) -> Long?): MediaItem {
    val into = mediaId.toLongOrNull()?.let(mergedId)?.toString() ?: return this
    return if (into == mediaId) this else buildUpon().setMediaId(into).build()
}
