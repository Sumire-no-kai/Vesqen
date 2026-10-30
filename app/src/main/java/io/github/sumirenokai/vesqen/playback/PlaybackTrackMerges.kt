package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import io.github.sumirenokai.vesqen.library.TrackMergeFollower

/**
 * Carries the catalog's committed merges to playback: the #139 choice, the #164 folder reattachment
 * and the legacy MediaStore re-key (#191 D1, D7, D8). The surviving row names the same file, so queue
 * entries only change their track ID.
 *
 * The running service is the only writer of the playback-state files: it applies each merge to its
 * saved queue and its player in one step. Without a running service nothing else writes them, so the
 * saved queue follows here. Both run on the main thread, where the service starts and stops, so a
 * merge reaches exactly one of them. A merge applies as it commits, whether or not a later read of
 * the Library succeeds.
 */
internal class PlaybackTrackMerges(context: Context) : TrackMergeFollower {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The running service's merge, on the main thread; null while no service runs. */
    private var service: ((from: Long, into: Long) -> Unit)? = null

    /** On the main thread, from the service's creation until [detach]. */
    fun attach(applyMerge: (from: Long, into: Long) -> Unit) {
        service = applyMerge
    }

    fun detach() {
        service = null
    }

    override fun prepareMerge(from: Long, into: Long) = Unit

    override fun mergeCommitted(from: Long, into: Long) {
        mainHandler.post {
            val running = service
            if (running != null) running(from, into) else PlaybackStateStore(appContext).replaceTrackId(from, into)
        }
    }

    override fun mergeRolledBack(from: Long, into: Long) = Unit
}

/**
 * Queue entries of the track [from] take the track ID [into], which names the same file, and keep
 * their occurrences and places in the play order. Media3 updates an entry with the same source in
 * place, so the current one keeps playing.
 */
internal fun Player.replaceTrackEntries(from: Long, into: Long) {
    val fromId = from.toString()
    for (index in 0 until mediaItemCount) {
        val item = getMediaItemAt(index)
        if (item.mediaId == fromId) replaceMediaItem(index, item.buildUpon().setMediaId(into.toString()).build())
    }
}
