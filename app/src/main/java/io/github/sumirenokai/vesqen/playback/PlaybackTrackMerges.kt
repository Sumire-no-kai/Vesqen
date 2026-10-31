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
 * During normal operation the running service writes playback state: it applies a transaction's
 * merges to its saved queue and its player in one step (#191 A4). Without a running service the
 * saved queue follows here. Both run on the main thread, where the service starts and stops, so a
 * merge reaches exactly one of them, whether or not a later read of the Library succeeds.
 *
 * The merges of this process are kept as well: a queue the app still holds from before one, such as
 * Undo after Clear queue or a queue started while the service was disconnected, takes the new IDs as
 * it enters the service's player (#191 A3, [mergedTrackId]). Before a catalog commit, the recovery
 * journal is flushed to disk. Opening that catalog checks which intents actually committed and
 * repairs the saved queue before restoration, even if the commit callback never ran (#191 A5).
 */
internal class PlaybackTrackMerges(context: Context) : TrackMergeFollower {
    private val appContext = context.applicationContext
    private val journal = context.applicationContext.getSharedPreferences("playback-track-merges", Context.MODE_PRIVATE)
    private var generation = 0L // Under journal's lock; a catalog rebuild invalidates queued callbacks.
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
        synchronized(journal) {
            generation++
            check(journal.edit().clear().commit()) { "Could not clear playback merge recovery" }
            mainHandler.post { merged.clear() }
        }
    }

    override fun prepareMerge(from: Long, into: Long) = Unit

    /** Written once per catalog transaction, before SQLite can commit (#191 A5). */
    override fun prepareCommit(merges: List<Pair<Long, Long>>) {
        synchronized(journal) { writePending(readPending() + merges) }
    }

    override fun recoverMerges(trackExists: (Long) -> Boolean) {
        synchronized(journal) {
            val pending = readPending()
            if (pending.isEmpty()) return
            val committed = recoverCommittedTrackMerges(pending, trackExists)
            // Discard rolled-back intents while the catalog is still opening. Deferring their
            // acknowledgment could erase an identical retry prepared by a later transaction.
            val replay = pending.filterKeys(committed::containsKey)
            if (replay.size != pending.size) writePending(replay)
            if (committed.isEmpty()) return
            // Before either restore path reads the queue. Keep the journal until the main-thread
            // follower has also updated any running keeper, so another crash can replay it again.
            PlaybackStateStore(appContext).replaceTrackIds(committed)
            publish(committed.toList(), replay)
        }
    }

    override fun mergeCommitted(from: Long, into: Long) = mergesCommitted(listOf(from to into))

    override fun mergesCommitted(merges: List<Pair<Long, Long>>) {
        if (merges.isEmpty()) return
        synchronized(journal) { publish(merges.toList(), merges.toMap()) }
    }

    /** Called under journal's lock; acknowledgment follows the durable queue write, never precedes it. */
    private fun publish(committed: List<Pair<Long, Long>>, acknowledged: Map<Long, Long>) {
        val expectedGeneration = generation
        mainHandler.post {
            synchronized(journal) {
                if (generation != expectedGeneration) return@post
                committed.forEach { (from, into) -> merged[from] = into }
                val batch = committed.associate { (from, _) -> from to mergedTrackId(from) }
                val running = service
                if (running != null) running(batch) else PlaybackStateStore(appContext).replaceTrackIds(batch)
                removePending(acknowledged)
            }
        }
    }

    private fun readPending(): Map<Long, Long> = journal.all.map { (from, into) -> from.toLong() to (into as Long) }.toMap()

    private fun writePending(pending: Map<Long, Long>) {
        val editor = journal.edit()
        editor.clear()
        pending.forEach { (from, into) -> editor.putLong(from.toString(), into) }
        check(editor.commit()) { "Could not save playback merge recovery" }
    }

    private fun removePending(acknowledged: Map<Long, Long>) {
        val pending = readPending()
        val remaining = pending.filterNot { (from, into) -> acknowledged[from] == into }
        if (remaining.size != pending.size) writePending(remaining)
    }

    override fun mergeRolledBack(from: Long, into: Long) {
        synchronized(journal) { removePending(mapOf(from to into)) }
    }
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

/** A missing source ID proves its merge committed; a surviving source means SQLite rolled back. */
internal fun recoverCommittedTrackMerges(pending: Map<Long, Long>, trackExists: (Long) -> Boolean): Map<Long, Long> {
    val exists = HashMap<Long, Boolean>()
    fun present(id: Long) = exists.getOrPut(id) { trackExists(id) }
    val committed = pending.filterKeys { !present(it) }
    return buildMap {
        for (from in committed.keys) {
            var into = from
            val visited = HashSet<Long>()
            while (into in committed && visited.add(into)) into = committed.getValue(into)
            if (into !in committed && present(into)) put(from, into)
        }
    }
}
