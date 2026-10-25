package io.github.sumirenokai.vesqen.playback

import android.content.Context
import androidx.core.content.edit
import io.github.sumirenokai.vesqen.library.AudioTrack

internal data class PersistedPlaybackState(
    val queueTrackIds: List<Long>,
    val currentTrackId: Long?,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: PlaybackRepeatMode,
    /** Queue occurrence, since one track can appear more than once. Null for legacy saves. */
    val currentQueueIndex: Int? = null,
    /** Whether the player had played to the end (`STATE_ENDED`). Null for saves before 1.0.1. */
    val queueEnded: Boolean? = null,
)

internal data class RestoredPlaybackQueue(
    val tracks: List<AudioTrack>,
    val startIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: PlaybackRepeatMode,
)

internal class PlaybackStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): PersistedPlaybackState? {
        val queueIds = preferences.getString(KEY_QUEUE_IDS, null)
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            ?.takeIf(List<Long>::isNotEmpty)
            ?: return null
        val repeatMode = runCatching {
            PlaybackRepeatMode.valueOf(
                preferences.getString(KEY_REPEAT_MODE, PlaybackRepeatMode.OFF.name)
                    ?: PlaybackRepeatMode.OFF.name,
            )
        }.getOrDefault(PlaybackRepeatMode.OFF)
        return PersistedPlaybackState(
            queueTrackIds = queueIds,
            currentTrackId = preferences.getLong(KEY_CURRENT_TRACK_ID, NO_TRACK_ID).takeIf { it != NO_TRACK_ID },
            positionMs = preferences.getLong(KEY_POSITION_MS, 0).coerceAtLeast(0),
            shuffleEnabled = preferences.getBoolean(KEY_SHUFFLE_ENABLED, false),
            repeatMode = repeatMode,
            currentQueueIndex = preferences.getInt(KEY_CURRENT_QUEUE_INDEX, -1).takeIf { it >= 0 },
            queueEnded = if (preferences.contains(KEY_QUEUE_ENDED)) preferences.getBoolean(KEY_QUEUE_ENDED, false) else null,
        )
    }

    /**
     * Whether a queue was saved, without parsing it: a media button start asks this on the main
     * thread before deciding to take the press (#52). [load] still decides what can be restored.
     */
    fun hasSavedQueue(): Boolean = !preferences.getString(KEY_QUEUE_IDS, null).isNullOrEmpty()

    fun save(state: PersistedPlaybackState) {
        if (state.queueTrackIds.isEmpty()) {
            clear()
            return
        }
        preferences.edit {
            putString(KEY_QUEUE_IDS, state.queueTrackIds.joinToString(","))
            putLong(KEY_CURRENT_TRACK_ID, state.currentTrackId ?: NO_TRACK_ID)
            putInt(KEY_CURRENT_QUEUE_INDEX, state.currentQueueIndex ?: -1)
            putLong(KEY_POSITION_MS, state.positionMs.coerceAtLeast(0))
            putBoolean(KEY_SHUFFLE_ENABLED, state.shuffleEnabled)
            putString(KEY_REPEAT_MODE, state.repeatMode.name)
            // Unknown stays unknown, so a 1.0.0 save keeps its position rule until the player saves again.
            state.queueEnded?.let { putBoolean(KEY_QUEUE_ENDED, it) } ?: remove(KEY_QUEUE_ENDED)
        }
    }

    /** Update only the scalar recovery cursor; the cached queue string is left untouched. */
    fun saveProgress(
        currentTrackId: Long?,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: PlaybackRepeatMode,
        currentQueueIndex: Int,
        queueEnded: Boolean,
    ) {
        preferences.edit {
            putLong(KEY_CURRENT_TRACK_ID, currentTrackId ?: NO_TRACK_ID)
            putInt(KEY_CURRENT_QUEUE_INDEX, currentQueueIndex)
            putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0))
            putBoolean(KEY_SHUFFLE_ENABLED, shuffleEnabled)
            putString(KEY_REPEAT_MODE, repeatMode.name)
            putBoolean(KEY_QUEUE_ENDED, queueEnded)
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val PREFERENCES_NAME = "playback-state"
        const val KEY_QUEUE_IDS = "queue_track_ids"
        const val KEY_CURRENT_TRACK_ID = "current_track_id"
        const val KEY_CURRENT_QUEUE_INDEX = "current_queue_index"
        const val KEY_POSITION_MS = "position_ms"
        const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
        const val KEY_REPEAT_MODE = "repeat_mode"
        const val KEY_QUEUE_ENDED = "queue_ended"
        const val NO_TRACK_ID = Long.MIN_VALUE
    }
}

internal fun PersistedPlaybackState.restoreAgainst(tracks: List<AudioTrack>): RestoredPlaybackQueue? {
    if (queueTrackIds.isEmpty() || tracks.isEmpty()) return null
    val tracksById = tracks.associateBy(AudioTrack::id)
    val restoredEntries = queueTrackIds.mapIndexedNotNull { index, trackId ->
        tracksById[trackId]?.let { index to it }
    }
    if (restoredEntries.isEmpty()) return null
    val savedIndex = currentQueueIndex?.takeIf {
        it in queueTrackIds.indices && queueTrackIds[it] == currentTrackId
    } ?: queueTrackIds.indexOfFirst { it == currentTrackId }
    val currentEntryIndex = restoredEntries.indexOfFirst { it.first == savedIndex }
    // A queue saved after it played to the end, typically an album with repeat off, would restore
    // at the end and play nothing. It continues with the next track instead, or from the start of
    // the queue after the last one. A position near the end alone is a pause, not an end (#170 P2).
    val finished = currentEntryIndex >= 0 &&
        (queueEnded ?: savedAtLegacyEnd(restoredEntries[currentEntryIndex].second))
    val (startIndex, startPositionMs) = when {
        currentEntryIndex < 0 -> 0 to 0L
        !finished -> currentEntryIndex to positionMs
        repeatMode == PlaybackRepeatMode.ONE -> currentEntryIndex to 0L
        currentEntryIndex < restoredEntries.lastIndex -> currentEntryIndex + 1 to 0L
        else -> 0 to 0L
    }
    return RestoredPlaybackQueue(
        tracks = restoredEntries.map { it.second },
        startIndex = startIndex,
        positionMs = startPositionMs,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
    )
}

/**
 * Saves before 1.0.1 carry no end state, only the position. Only repeat off can end a queue, and a
 * track no longer than the tolerance would count as finished at its start.
 */
private fun PersistedPlaybackState.savedAtLegacyEnd(track: AudioTrack): Boolean =
    repeatMode == PlaybackRepeatMode.OFF &&
        track.durationMs > LEGACY_FINISHED_TOLERANCE_MS &&
        positionMs >= track.durationMs - LEGACY_FINISHED_TOLERANCE_MS

/** For saves before 1.0.1: within this of a track's end, a saved position counts as finished. */
private const val LEGACY_FINISHED_TOLERANCE_MS = 1_000L
