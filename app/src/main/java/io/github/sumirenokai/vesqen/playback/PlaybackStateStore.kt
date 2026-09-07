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
        )
    }

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
        }
    }

    /** Update only the scalar recovery cursor; the cached queue string is left untouched. */
    fun saveProgress(
        currentTrackId: Long?,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: PlaybackRepeatMode,
        currentQueueIndex: Int,
    ) {
        preferences.edit {
            putLong(KEY_CURRENT_TRACK_ID, currentTrackId ?: NO_TRACK_ID)
            putInt(KEY_CURRENT_QUEUE_INDEX, currentQueueIndex)
            putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0))
            putBoolean(KEY_SHUFFLE_ENABLED, shuffleEnabled)
            putString(KEY_REPEAT_MODE, repeatMode.name)
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
    return RestoredPlaybackQueue(
        tracks = restoredEntries.map { it.second },
        startIndex = currentEntryIndex.coerceAtLeast(0),
        positionMs = positionMs.takeIf { currentEntryIndex >= 0 } ?: 0,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
    )
}
