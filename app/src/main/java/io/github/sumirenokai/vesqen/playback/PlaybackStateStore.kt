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
            putLong(KEY_POSITION_MS, state.positionMs.coerceAtLeast(0))
            putBoolean(KEY_SHUFFLE_ENABLED, state.shuffleEnabled)
            putString(KEY_REPEAT_MODE, state.repeatMode.name)
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val PREFERENCES_NAME = "playback-state"
        const val KEY_QUEUE_IDS = "queue_track_ids"
        const val KEY_CURRENT_TRACK_ID = "current_track_id"
        const val KEY_POSITION_MS = "position_ms"
        const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
        const val KEY_REPEAT_MODE = "repeat_mode"
        const val NO_TRACK_ID = Long.MIN_VALUE
    }
}

internal fun PersistedPlaybackState.restoreAgainst(tracks: List<AudioTrack>): RestoredPlaybackQueue? {
    if (queueTrackIds.isEmpty() || tracks.isEmpty()) return null
    val tracksById = tracks.associateBy(AudioTrack::id)
    val restoredTracks = queueTrackIds.mapNotNull(tracksById::get)
    if (restoredTracks.isEmpty()) return null
    val restoredCurrentIndex = currentTrackId
        ?.let { currentId -> restoredTracks.indexOfFirst { it.id == currentId } }
        ?.takeIf { it >= 0 }
        ?: 0
    return RestoredPlaybackQueue(
        tracks = restoredTracks,
        startIndex = restoredCurrentIndex,
        positionMs = positionMs.takeIf { currentTrackId == restoredTracks[restoredCurrentIndex].id } ?: 0,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
    )
}
