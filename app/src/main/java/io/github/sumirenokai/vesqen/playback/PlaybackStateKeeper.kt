package io.github.sumirenokai.vesqen.playback

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal const val PLAYBACK_OCCURRENCE_EXTRA = "io.github.sumirenokai.vesqen.playback.occurrence"

/**
 * Service-owned playback recovery state.
 *
 * Queue projection is rebuilt only for timeline and shuffle mode changes. Position checkpoints reuse
 * that immutable projection, so a large queue does not turn the five-second recovery tick into an O(N)
 * walk. While shuffled, the play order is part of the saved queue (#170): shuffle on or a shuffled
 * move saves the queue too.
 */
internal class PlaybackStateKeeper(
    private val player: Player,
    private val stateStore: PlaybackStateStore,
    private val handler: Handler,
    private val checkpointIntervalMs: Long = POSITION_SAVE_BUCKET_MS,
    private val onPlaybackStarted: (Long) -> Unit = {},
) : Player.Listener {
    private val persistenceGate = PlaybackPersistenceGate(checkpointIntervalMs)
    private val playbackStartGate = PlaybackStartGate()
    private var queueTrackIds: List<Long> = emptyList()
    private var queuePlayOrder: List<Int>? = null
    private var queueRevision = 0L
    private var started = false
    private var queueEnded = false

    private val checkpoint = object : Runnable {
        override fun run() {
            if (!started || !player.isPlaying) return
            persist(force = false)
            handler.postDelayed(this, checkpointIntervalMs)
        }
    }

    fun start() {
        if (started) return
        started = true
        // A listen counted before the service or the process ended is resumed, not counted again,
        // when the restored queue starts at that entry (#165).
        stateStore.loadCountedPlayback()?.let(playbackStartGate::resumeCounted)
        player.addListener(this)
        refreshQueueProjection()
        persist(force = false)
        updateCheckpointSchedule()
    }

    fun stop() {
        if (!started) return
        handler.removeCallbacks(checkpoint)
        persist(force = true)
        player.removeListener(this)
        started = false
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        playbackStartGate.onMediaItemTransition(
            trackId = mediaItem?.mediaId?.toLongOrNull(),
            reason = reason,
            occurrenceId = mediaItem?.mediaMetadata?.extras?.getString(PLAYBACK_OCCURRENCE_EXTRA),
        )
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED)
        if (timelineChanged || events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) refreshQueueProjection()

        val playingChanged = events.contains(Player.EVENT_IS_PLAYING_CHANGED)
        val mediaItemTransition = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
        val positionDiscontinuity = events.contains(Player.EVENT_POSITION_DISCONTINUITY)
        // A new queue or another item is a transition; editing other items or a library refresh
        // changes the timeline but leaves the queue at its end.
        if (mediaItemTransition || positionDiscontinuity) {
            queueEnded = queueEndedAfter(queueEnded, player.playbackState, movedAway = true)
        }
        if (mediaItemTransition || playingChanged) {
            playbackStartGate.trackStarted(
                isPlaying = player.isPlaying,
                trackId = player.currentMediaItem?.mediaId?.toLongOrNull(),
            )?.let(onPlaybackStarted)
        }
        val shouldPersist = timelineChanged ||
            mediaItemTransition ||
            positionDiscontinuity ||
            // Reaching or leaving the end of the queue; the gate skips other state changes.
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
            events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
            events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
            playingChanged
        if (shouldPersist) {
            // Pausing is a natural lifecycle boundary, so retain the exact position rather than
            // waiting for the next five-second bucket.
            persist(
                force = shouldForcePlaybackPersistence(
                    positionDiscontinuity = positionDiscontinuity,
                    playingChanged = playingChanged,
                    isPlaying = player.isPlaying,
                ),
            )
        }
        if (playingChanged) updateCheckpointSchedule()
    }

    private fun refreshQueueProjection() {
        val updated = buildList {
            repeat(player.mediaItemCount) { index ->
                player.getMediaItemAt(index).mediaId.toLongOrNull()?.let(::add)
            }
        }
        // Saved indices are list indices only while every item has a track ID, as the app's items do.
        val playOrder = if (player.shuffleModeEnabled && updated.size == player.mediaItemCount) player.playOrder() else null
        if (updated != queueTrackIds || playOrder != queuePlayOrder) {
            queueTrackIds = updated
            queuePlayOrder = playOrder
            queueRevision++
        }
    }

    private fun persist(force: Boolean) {
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val countedOccurrenceId = playbackStartGate.countedOccurrence(player.currentMediaItem?.mediaId?.toLongOrNull())
        queueEnded = queueEndedAfter(queueEnded, player.playbackState, movedAway = false)
        when (
            persistenceGate.decide(
                PlaybackPersistenceObservation(
                    queueRevision = queueRevision,
                    hasQueue = queueTrackIds.isNotEmpty(),
                    currentTrackId = player.currentMediaItem?.mediaId?.toLongOrNull(),
                    currentQueueIndex = player.currentMediaItemIndex,
                    positionMs = positionMs,
                    shuffleEnabled = player.shuffleModeEnabled,
                    repeatMode = player.repeatMode.toPlaybackRepeatMode(),
                    queueEnded = queueEnded,
                    countedOccurrenceId = countedOccurrenceId,
                ),
                force = force,
            )
        ) {
            PlaybackPersistenceDecision.SKIP -> Unit
            PlaybackPersistenceDecision.CLEAR -> stateStore.clear()
            PlaybackPersistenceDecision.SAVE_QUEUE -> stateStore.save(
                PersistedPlaybackState(
                    queueTrackIds = queueTrackIds,
                    currentTrackId = player.currentMediaItem?.mediaId?.toLongOrNull(),
                    currentQueueIndex = player.currentMediaItemIndex,
                    positionMs = positionMs,
                    shuffleEnabled = player.shuffleModeEnabled,
                    repeatMode = player.repeatMode.toPlaybackRepeatMode(),
                    queueEnded = queueEnded,
                    countedOccurrenceId = countedOccurrenceId,
                    playOrder = queuePlayOrder,
                ),
            )
            PlaybackPersistenceDecision.SAVE_PROGRESS -> stateStore.saveProgress(
                currentTrackId = player.currentMediaItem?.mediaId?.toLongOrNull(),
                currentQueueIndex = player.currentMediaItemIndex,
                positionMs = positionMs,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode.toPlaybackRepeatMode(),
                queueEnded = queueEnded,
                countedOccurrenceId = countedOccurrenceId,
            )
        }
    }

    private fun updateCheckpointSchedule() {
        handler.removeCallbacks(checkpoint)
        if (started && player.isPlaying) handler.postDelayed(checkpoint, checkpointIntervalMs)
    }

    private fun Int.toPlaybackRepeatMode(): PlaybackRepeatMode = when (this) {
        Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ALL
        Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.ONE
        else -> PlaybackRepeatMode.OFF
    }

    private companion object {
        const val POSITION_SAVE_BUCKET_MS = 5_000L
    }
}

/**
 * Whether the queue still stands at its end. A stop() after the end (a dismissed notification, a
 * STOP key, a strict USB rebuild) leaves the player idle at the end of its last track, so idle keeps
 * the end; a seek, another item or a new queue ends it (#170 P2).
 */
internal fun queueEndedAfter(previous: Boolean, playbackState: Int, movedAway: Boolean): Boolean = when {
    playbackState == Player.STATE_ENDED -> true
    movedAway -> false
    playbackState == Player.STATE_IDLE -> previous
    else -> false
}

/** User seeks and pause boundaries retain their exact cursor instead of waiting for a time bucket. */
internal fun shouldForcePlaybackPersistence(
    positionDiscontinuity: Boolean,
    playingChanged: Boolean,
    isPlaying: Boolean,
): Boolean = positionDiscontinuity || (playingChanged && !isPlaying)

/** Counts a track once per transition, while ignoring pause/resume and duplicate callbacks. */
internal class PlaybackStartGate {
    private var lastCountedTrackId: Long? = null
    private var currentOccurrenceId: String? = null

    fun onMediaItemTransition(trackId: Long?, reason: Int, occurrenceId: String? = null) {
        // Catalog refreshes preserve the queue occurrence token; an explicit queue replacement
        // allocates new tokens even for the same song. Unknown identity must not suppress a listen.
        // The token is unique to its queue entry, so the same token under another track ID is the
        // same entry after the Library merged its song into a record (#139): the listen follows it.
        val metadataOnly =
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            trackId != null &&
            occurrenceId != null && occurrenceId == currentOccurrenceId
        if (!metadataOnly) {
            reset()
        } else if (lastCountedTrackId != null) {
            lastCountedTrackId = trackId
        }
        currentOccurrenceId = occurrenceId
    }

    fun trackStarted(isPlaying: Boolean, trackId: Long?): Long? {
        if (!isPlaying || trackId == null || trackId == lastCountedTrackId) return null
        lastCountedTrackId = trackId
        return trackId
    }

    /** The occurrence whose listen was counted, while [currentTrackId] is still that listen's track. */
    fun countedOccurrence(currentTrackId: Long?): String? =
        currentOccurrenceId?.takeIf { lastCountedTrackId != null && lastCountedTrackId == currentTrackId }

    /**
     * Starts from a listen counted by an earlier gate. The restore that sets this occurrence again
     * is then a metadata-only transition, as after a catalog refresh; any other item resets the gate.
     */
    fun resumeCounted(counted: CountedPlayback) {
        lastCountedTrackId = counted.trackId
        currentOccurrenceId = counted.occurrenceId
    }

    fun reset() {
        lastCountedTrackId = null
        currentOccurrenceId = null
    }
}

internal data class PlaybackPersistenceObservation(
    val queueRevision: Long,
    val hasQueue: Boolean,
    val currentTrackId: Long?,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: PlaybackRepeatMode,
    val currentQueueIndex: Int = 0,
    val queueEnded: Boolean = false,
    val countedOccurrenceId: String? = null,
)

internal enum class PlaybackPersistenceDecision {
    SKIP,
    SAVE_QUEUE,
    SAVE_PROGRESS,
    CLEAR,
}

/** Pure decision gate kept independent from Android so checkpoint semantics remain unit-testable. */
internal class PlaybackPersistenceGate(
    private val positionBucketMs: Long,
) {
    init {
        require(positionBucketMs > 0)
    }

    private var hasPersistedQueue = false
    private var lastPersistedQueueRevision: Long? = null
    private var lastSignature: PlaybackPersistenceSignature? = null

    fun decide(
        observation: PlaybackPersistenceObservation,
        force: Boolean = false,
    ): PlaybackPersistenceDecision {
        if (!observation.hasQueue) {
            if (!hasPersistedQueue) return PlaybackPersistenceDecision.SKIP
            hasPersistedQueue = false
            lastPersistedQueueRevision = null
            lastSignature = null
            return PlaybackPersistenceDecision.CLEAR
        }

        val signature = PlaybackPersistenceSignature(
            queueRevision = observation.queueRevision,
            currentTrackId = observation.currentTrackId,
            currentQueueIndex = observation.currentQueueIndex,
            positionBucket = observation.positionMs.coerceAtLeast(0) / positionBucketMs,
            shuffleEnabled = observation.shuffleEnabled,
            repeatMode = observation.repeatMode,
            queueEnded = observation.queueEnded,
            countedOccurrenceId = observation.countedOccurrenceId,
        )
        if (!force && hasPersistedQueue && signature == lastSignature) {
            return PlaybackPersistenceDecision.SKIP
        }
        val queueChanged = !hasPersistedQueue ||
            observation.queueRevision != lastPersistedQueueRevision
        hasPersistedQueue = true
        lastPersistedQueueRevision = observation.queueRevision
        lastSignature = signature
        return if (queueChanged) {
            PlaybackPersistenceDecision.SAVE_QUEUE
        } else {
            PlaybackPersistenceDecision.SAVE_PROGRESS
        }
    }
}

private data class PlaybackPersistenceSignature(
    val queueRevision: Long,
    val currentTrackId: Long?,
    val currentQueueIndex: Int,
    val positionBucket: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: PlaybackRepeatMode,
    val queueEnded: Boolean,
    val countedOccurrenceId: String?,
)
