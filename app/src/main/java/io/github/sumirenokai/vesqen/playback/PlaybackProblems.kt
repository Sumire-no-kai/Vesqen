package io.github.sumirenokai.vesqen.playback

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** #344: the "Skip tracks Android can't decode" switch. Off, the default, pauses on such a track and explains it. */
internal class UnplayableTrackSettings(context: Context) {
    private val preferences = context.getSharedPreferences("unplayable_tracks", Context.MODE_PRIVATE)
    private val skipState = MutableStateFlow(preferences.getBoolean(SKIP, false))

    val skip: StateFlow<Boolean> = skipState.asStateFlow()

    fun setSkip(enabled: Boolean) {
        preferences.edit { putBoolean(SKIP, enabled) }
        skipState.value = enabled
    }

    private companion object {
        const val SKIP = "skip"
    }
}

/** Playback stopped on [queueEntryId] because of [kind]; the app explains it once (#344). */
internal data class PlaybackInterruption(
    val id: Long,
    val kind: PlaybackProblem,
    val queueEntryId: String,
    val trackTitle: String,
)

/**
 * The latest interruption, written by the playback service and read by the app's dialog and the
 * service's background notification. Both run in the app process. Written on the main thread.
 */
internal class PlaybackInterruptions {
    private val state = MutableStateFlow<PlaybackInterruption?>(null)
    private var nextId = 1L

    val current: StateFlow<PlaybackInterruption?> = state.asStateFlow()

    fun interrupted(kind: PlaybackProblem, queueEntryId: String, trackTitle: String) {
        state.value = PlaybackInterruption(nextId++, kind, queueEntryId, trackTitle)
    }

    fun clear() {
        state.value = null
    }

    /** The listener has read the explanation; a newer interruption stays. */
    fun acknowledge(id: Long) {
        state.update { if (it?.id == id) null else it }
    }
}

/**
 * The item has audio, but no decoder or sink can take any of it, so Media3 leaves audio unselected
 * and would run the item silently to its end (#344). Unknown tracks ([Tracks.EMPTY]) never match.
 */
internal fun Tracks.hasUnplayableAudio(): Boolean =
    containsType(C.TRACK_TYPE_AUDIO) && !isTypeSelected(C.TRACK_TYPE_AUDIO) &&
        !isTypeSupported(C.TRACK_TYPE_AUDIO, /* allowExceedsCapabilities= */ true)

/**
 * Stops at a track Android cannot decode or output instead of letting it run silently, and records
 * what interrupted playback for the app's dialog (#344).
 *
 * Media3 leaves such a track unselected, so the song before it plays to its end. The guard then
 * pauses at the start of the track, before any sound. With [skipUnplayable] on, it moves on in the
 * direction the listener was going instead, but never for a track the listener picked directly,
 * never under repeat one, and for at most one lap or [SKIP_LIMIT] tracks. A Play pressed on a track
 * the guard paused moves on to the next track that plays (owner, 2026-11-24), so a headset or car
 * button is never dead there. Only a Next or Previous command, marked through [navigating], counts
 * as moving through the queue; any other seek to another item is a pick.
 *
 * Register it on the service's player after the shuffle listener and before the output coordinator,
 * the state keeper and the session: it acts synchronously inside callbacks, and the listeners after
 * it then read its pause or seek through the player's getters. Main thread only.
 */
internal class PlaybackProblemGuard(
    private val player: Player,
    private val skipUnplayable: () -> Boolean,
    private val interruptions: PlaybackInterruptions,
    /** The official strict path holds a Play while it rebuilds the output ([UsbOutputCoordinator.holdsPlaybackForOutput]). */
    private val outputHoldsPlay: () -> Boolean = { false },
    /** Playback stopped without sound: ends a media button start and any held strict Play. */
    private val onStopped: () -> Unit = {},
    /** Once per queue entry that cannot be played, for the device report. */
    private val onUnplayable: (MediaItem) -> Unit = {},
) : Player.Listener {
    private enum class Arrival { AUTO, FORWARD, BACKWARD, PICKED }

    private var arrival = Arrival.PICKED
    private var heldEntry: String? = null
    private var reportedEntry: String? = null
    private var skips = 0

    /** Set around a Next or Previous command ([navigating]). */
    private var navigationArrival: Arrival? = null

    /** Set by the guard's own seek and taken by the discontinuity it causes. */
    private var ownSeekArrival: Arrival? = null

    /** A Play on a paused track keeps moving past unplayable tracks until one plays. */
    private var recovering = false

    /**
     * Runs a Next or Previous command from any controller, so its seek counts as moving through the
     * queue. Media3 reports the discontinuity before the command returns.
     */
    fun navigating(backward: Boolean, command: () -> Unit) {
        navigationArrival = if (backward) Arrival.BACKWARD else Arrival.FORWARD
        try {
            command()
        } finally {
            navigationArrival = null
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        when (reason) {
            // The first event of an automatic transition, so system controls never see the track as playing.
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                arrival = Arrival.AUTO
                check(trusted = true)
            }
            Player.DISCONTINUITY_REASON_SEEK -> if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                val own = ownSeekArrival
                ownSeekArrival = null
                if (own != null) {
                    arrival = own
                } else {
                    // The listener moved: a queue tap next to the current track is still a pick.
                    recovering = false
                    arrival = navigationArrival ?: Arrival.PICKED
                }
            }
            else -> Unit
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val entry = mediaItem?.queueEntryId()
        val interruption = interruptions.current.value
        if (interruption != null && interruption.queueEntryId != entry) interruptions.clear()
        // A Play moves on only while the listener stays on the track the guard paused; coming back
        // to it later is a new choice (PR #345 review). An automatic transition holds before this.
        if (heldEntry != entry) heldEntry = null
        when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> {
                arrival = Arrival.PICKED
                skips = 0
                recovering = false
            }
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> check(trusted = true)
            // A seek waits for the item's tracks.
            else -> Unit
        }
    }

    // Tracks delivered with the event belong to the current item. Between stop() and the next
    // prepared period the getter still holds the old ones, so other callbacks wait for READY.
    override fun onTracksChanged(tracks: Tracks) = check(trusted = true)

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) check(trusted = false)
    }

    // A Play while still buffering is checked when the item becomes ready.
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady && player.playbackState == Player.STATE_READY) check(trusted = false)
    }

    override fun onPlayerError(error: PlaybackException) {
        val item = player.currentMediaItem ?: return
        interruptions.interrupted(error.toPlaybackProblem(), item.queueEntryId(), item.title())
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (timeline.isEmpty) {
            heldEntry = null
            skips = 0
            interruptions.clear()
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (player.isPlaying && player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) interruptions.clear()
    }

    private fun check(trusted: Boolean) {
        val tracks = player.currentTracks
        if (tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
            heldEntry = null
            skips = 0
            recovering = false
            return
        }
        val state = player.playbackState
        if (!trusted && state != Player.STATE_READY) return
        if (state != Player.STATE_BUFFERING && state != Player.STATE_READY) return
        if (!tracks.hasUnplayableAudio()) return
        val item = player.currentMediaItem ?: return
        // Moving onto such a track while paused does nothing until Play.
        if (!player.playWhenReady && !outputHoldsPlay()) return
        val entry = item.queueEntryId()
        if (entry != reportedEntry) {
            reportedEntry = entry
            onUnplayable(item)
        }
        // The guard paused this entry, so a Play is all that can have asked for it again.
        val insist = entry == heldEntry
        val shouldMoveOn = insist || recovering || (skipUnplayable() && arrival != Arrival.PICKED)
        if (shouldMoveOn && moveOn(backward = !insist && !recovering && arrival == Arrival.BACKWARD)) {
            if (insist) recovering = true
            return
        }
        hold(entry, item)
    }

    private fun moveOn(backward: Boolean): Boolean {
        // Repeat one asks for this track; skipping would repeat another one instead.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return false
        val target = if (backward) player.previousMediaItemIndex else player.nextMediaItemIndex
        if (target == C.INDEX_UNSET || target == player.currentMediaItemIndex) return false
        if (skips >= minOf(player.mediaItemCount, SKIP_LIMIT)) return false
        skips++
        ownSeekArrival = if (backward) Arrival.BACKWARD else Arrival.FORWARD
        player.seekToDefaultPosition(target)
        return true
    }

    private fun hold(entry: String, item: MediaItem) {
        skips = 0
        recovering = false
        heldEntry = entry
        if (player.playWhenReady) player.pause()
        onStopped()
        interruptions.interrupted(PlaybackProblem.UNPLAYABLE_TRACK, entry, item.title())
    }

    internal companion object {
        /** Bounds the prepares, saved cursors and notification updates of a run of unplayable tracks. */
        const val SKIP_LIMIT = 20
    }
}

private fun MediaItem.title(): String = mediaMetadata.title?.toString().orEmpty()
