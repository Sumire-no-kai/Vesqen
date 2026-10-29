package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.content.SharedPreferences
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
    /**
     * The occurrence of the current entry when its listen was already counted, so restoring it
     * resumes that listen instead of counting another (#165). Null when it was not counted yet.
     */
    val countedOccurrenceId: String? = null,
    /**
     * While shuffled, the queue's indices in the order they play (#170), so a restore continues in the
     * same order. Null without shuffle and for saves before 1.1.0.
     */
    val playOrder: List<Int>? = null,
)

internal data class RestoredPlaybackQueue(
    val tracks: List<AudioTrack>,
    val startIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: PlaybackRepeatMode,
    /** The saved occurrence of the start entry when it resumes a listen already counted. */
    val startOccurrenceId: String? = null,
    /** Saved tracks the Library cannot play now; see [PlaybackStateStore.holdBack]. */
    val heldBackTrackIds: Set<Long> = emptySet(),
    /** While shuffled, the restored queue's indices in the saved play order; null to shuffle anew. */
    val playOrder: List<Int>? = null,
)

/** The listen the state keeper already counted: a track in one queue occurrence. */
internal data class CountedPlayback(
    val trackId: Long,
    val occurrenceId: String,
)

/**
 * The queue and the cursor live in two preference files (#165). SharedPreferences rewrites its whole
 * file on every change, so a five-second checkpoint in the queue's file rewrote the queue each time.
 * The queue's file keeps the full state as of the last queue change, which is all that releases
 * before 1.1.0 read. Later cursors go to the small progress file, which names the queue file's state
 * it was written over and is ignored once that file was changed by anything else.
 *
 * While the player holds a restored queue unchanged, the saved queue also keeps the tracks the
 * restore held back, and the saved cursor indexes that queue ([holdBack]).
 *
 * A shuffled queue's play order is saved with the queue (#170). It names the queue file it was
 * written with: a release before 1.1.0 rewrites that file but keeps keys it does not know, and the
 * order it leaves behind is then ignored.
 */
internal class PlaybackStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val progressPreferences = context.applicationContext.getSharedPreferences(
        PROGRESS_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    /** [queueFileSignature] as of this store's last queue save, so a checkpoint does not hash the queue. */
    private var savedQueueSignature: Int? = null

    /**
     * As of this store's last queue save, the saved queue index of each player queue entry while the
     * saved queue keeps held-back tracks; null when the player's queue was saved as it is.
     */
    private var savedQueueIndexes: IntArray? = null

    fun load(): PersistedPlaybackState? {
        val queueIds = preferences.getString(KEY_QUEUE_IDS, null)
            ?.toTrackIds()
            ?.takeIf(List<Long>::isNotEmpty)
            ?: return null
        val cursor = currentCursorPreferences()
        val repeatMode = runCatching {
            PlaybackRepeatMode.valueOf(
                cursor.getString(KEY_REPEAT_MODE, PlaybackRepeatMode.OFF.name)
                    ?: PlaybackRepeatMode.OFF.name,
            )
        }.getOrDefault(PlaybackRepeatMode.OFF)
        val currentTrackId = cursor.getLong(KEY_CURRENT_TRACK_ID, NO_TRACK_ID).takeIf { it != NO_TRACK_ID }
        return PersistedPlaybackState(
            queueTrackIds = queueIds,
            currentTrackId = currentTrackId,
            positionMs = cursor.getLong(KEY_POSITION_MS, 0).coerceAtLeast(0),
            shuffleEnabled = cursor.getBoolean(KEY_SHUFFLE_ENABLED, false),
            repeatMode = repeatMode,
            currentQueueIndex = cursor.getInt(KEY_CURRENT_QUEUE_INDEX, -1).takeIf { it >= 0 },
            queueEnded = if (cursor.contains(KEY_QUEUE_ENDED)) cursor.getBoolean(KEY_QUEUE_ENDED, false) else null,
            countedOccurrenceId = cursor.countedOccurrenceId(currentTrackId),
            playOrder = savedPlayOrder(queueIds.size),
        )
    }

    /** The listen counted for the saved current entry, without parsing the queue. */
    fun loadCountedPlayback(): CountedPlayback? {
        if (!hasSavedQueue()) return null
        val cursor = currentCursorPreferences()
        val trackId = cursor.getLong(KEY_CURRENT_TRACK_ID, NO_TRACK_ID).takeIf { it != NO_TRACK_ID } ?: return null
        val occurrenceId = cursor.countedOccurrenceId(trackId) ?: return null
        return CountedPlayback(trackId, occurrenceId)
    }

    /**
     * Whether a queue was saved, without parsing it: a media button start asks this on the main
     * thread before deciding to take the press (#52). [load] still decides what can be restored.
     */
    fun hasSavedQueue(): Boolean = !preferences.getString(KEY_QUEUE_IDS, null).isNullOrEmpty()

    /**
     * Keeps [trackIds], saved tracks a restore could not play (#165), in the saved queue for as long
     * as the player's queue is that restore's. A card that is not mounted yet or a lost folder grant
     * then takes no track out of the queue for good: a later restore brings back those the Library
     * can play by then. Once the queue is edited, it is saved as the player holds it.
     */
    fun holdBack(trackIds: Set<Long>) {
        preferences.edit {
            if (trackIds.isEmpty()) remove(KEY_HELD_BACK_TRACK_IDS) else putString(KEY_HELD_BACK_TRACK_IDS, trackIds.joinToString(","))
        }
    }

    fun save(state: PersistedPlaybackState) {
        if (state.queueTrackIds.isEmpty()) {
            clear()
            return
        }
        val heldBack = heldBackSavedQueue(state.queueTrackIds)
        val savedIndexes = heldBack?.indexes
        savedQueueIndexes = savedIndexes
        val playOrder = state.playOrder?.let { order -> heldBack?.let { heldBackPlayOrder(order, it) } ?: order }
        preferences.edit {
            if (savedIndexes == null) {
                putString(KEY_QUEUE_IDS, state.queueTrackIds.joinToString(","))
                remove(KEY_HELD_BACK_TRACK_IDS)
            }
            if (playOrder == null) remove(KEY_PLAY_ORDER) else putString(KEY_PLAY_ORDER, playOrder.joinToString(","))
            remove(KEY_PLAY_ORDER_SIGNATURE)
            putCursor(
                currentTrackId = state.currentTrackId,
                currentQueueIndex = savedQueueIndex(state.currentQueueIndex ?: -1),
                positionMs = state.positionMs,
                shuffleEnabled = state.shuffleEnabled,
                repeatMode = state.repeatMode,
                // Only states built without the field (tests, a 1.0.0 save loaded and saved back) lack
                // it; the state keeper always knows it. Unknown stays unknown, so the position rule
                // still applies.
                queueEnded = state.queueEnded,
                countedOccurrenceId = state.countedOccurrenceId,
            )
        }
        // The queue's file now holds this cursor; an older progress file must no longer apply.
        val signature = queueFileSignature().also { savedQueueSignature = it }
        // The order names the file it was written with, which is known only once the file holds it.
        if (playOrder != null) preferences.edit { putInt(KEY_PLAY_ORDER_SIGNATURE, signature) }
        progressPreferences.edit { clear() }
    }

    /** Update only the cursor, in the progress file; the queue's file is left untouched. */
    fun saveProgress(
        currentTrackId: Long?,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: PlaybackRepeatMode,
        currentQueueIndex: Int,
        queueEnded: Boolean,
        countedOccurrenceId: String? = null,
    ) {
        if (!hasSavedQueue()) return
        val base = savedQueueSignature ?: queueFileSignature().also { savedQueueSignature = it }
        progressPreferences.edit {
            putInt(KEY_QUEUE_FILE_SIGNATURE, base)
            putCursor(currentTrackId, savedQueueIndex(currentQueueIndex), positionMs, shuffleEnabled, repeatMode, queueEnded, countedOccurrenceId)
        }
    }

    /** [durable]: on disk before this returns, for a queue whose catalog IDs are about to name other songs (#165). */
    fun clear(durable: Boolean = false) {
        savedQueueSignature = null
        savedQueueIndexes = null
        preferences.edit(commit = durable) { clear() }
        progressPreferences.edit(commit = durable) { clear() }
    }

    /**
     * Where each entry of [playerQueue] is in the saved queue, when [playerQueue] is the saved queue
     * without its held-back tracks: the player still holds the restored queue.
     */
    private fun heldBackSavedQueue(playerQueue: List<Long>): HeldBackSavedQueue? {
        val heldBack = preferences.getString(KEY_HELD_BACK_TRACK_IDS, null)?.toTrackIds()?.toSet()
            ?.takeIf(Set<Long>::isNotEmpty)
            ?: return null
        val savedQueue = preferences.getString(KEY_QUEUE_IDS, null)?.toTrackIds() ?: return null
        val kept = savedQueue.indices.filter { savedQueue[it] !in heldBack }
        return HeldBackSavedQueue(kept.toIntArray(), savedQueue.size).takeIf { kept.map(savedQueue::get) == playerQueue }
    }

    /** [indexes]: the saved queue index of each player queue entry, in a saved queue of [size] entries. */
    private class HeldBackSavedQueue(val indexes: IntArray, val size: Int)

    /**
     * The player's play order over the saved queue. A held-back entry keeps its place in the order
     * saved before; without one, it plays right after its list predecessor, as PlayOrder places an
     * insert, so a later restore that brings it back finds it there.
     */
    private fun heldBackPlayOrder(playerOrder: List<Int>, savedQueue: HeldBackSavedQueue): List<Int> {
        val order = playerOrder.map(savedQueue.indexes::get)
        val kept = BooleanArray(savedQueue.size).also { kept -> savedQueue.indexes.forEach { kept[it] = true } }
        savedPlayOrder(savedQueue.size)?.let { previous ->
            val next = order.iterator()
            return previous.map { if (kept[it]) next.next() else it }
        }
        fun heldBackFrom(index: Int) = (index until savedQueue.size).takeWhile { !kept[it] }
        return heldBackFrom(0) + order.flatMap { listOf(it) + heldBackFrom(it + 1) }
    }

    /** The saved play order, while it belongs to the queue's file as it is and orders all [queueSize] entries. */
    private fun savedPlayOrder(queueSize: Int): List<Int>? {
        if (!preferences.contains(KEY_PLAY_ORDER_SIGNATURE) || preferences.getInt(KEY_PLAY_ORDER_SIGNATURE, 0) != queueFileSignature()) {
            return null
        }
        val order = preferences.getString(KEY_PLAY_ORDER, null)?.split(',')?.map { it.toIntOrNull() ?: return null } ?: return null
        return order.takeIf { it.sorted() == List(queueSize) { index -> index } }
    }

    private fun savedQueueIndex(playerQueueIndex: Int): Int =
        savedQueueIndexes?.getOrNull(playerQueueIndex) ?: playerQueueIndex

    private fun String.toTrackIds(): List<Long> = split(',').mapNotNull(String::toLongOrNull)

    /** The progress file when it was written over the queue's file as it is now, else the queue's file. */
    private fun currentCursorPreferences(): SharedPreferences {
        val progressBase = progressPreferences.getInt(KEY_QUEUE_FILE_SIGNATURE, 0)
            .takeIf { progressPreferences.contains(KEY_QUEUE_FILE_SIGNATURE) }
            ?: return preferences
        return if (progressBase == queueFileSignature()) progressPreferences else preferences
    }

    /**
     * Identifies what the queue's file holds. A release before 1.1.0 writes its cursor there on every
     * change (after a downgrade, say), which changes this, so a progress file from before is not
     * applied over that newer state.
     */
    private fun queueFileSignature(): Int = listOf(
        preferences.getString(KEY_QUEUE_IDS, null),
        preferences.getLong(KEY_CURRENT_TRACK_ID, NO_TRACK_ID),
        preferences.getInt(KEY_CURRENT_QUEUE_INDEX, -1),
        preferences.getLong(KEY_POSITION_MS, 0),
        preferences.getBoolean(KEY_SHUFFLE_ENABLED, false),
        preferences.getString(KEY_REPEAT_MODE, null),
        if (preferences.contains(KEY_QUEUE_ENDED)) preferences.getBoolean(KEY_QUEUE_ENDED, false) else null,
        preferences.getString(KEY_COUNTED_OCCURRENCE_ID, null),
    ).hashCode()

    private fun SharedPreferences.Editor.putCursor(
        currentTrackId: Long?,
        currentQueueIndex: Int,
        positionMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: PlaybackRepeatMode,
        queueEnded: Boolean?,
        countedOccurrenceId: String?,
    ) {
        putLong(KEY_CURRENT_TRACK_ID, currentTrackId ?: NO_TRACK_ID)
        putInt(KEY_CURRENT_QUEUE_INDEX, currentQueueIndex)
        putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0))
        putBoolean(KEY_SHUFFLE_ENABLED, shuffleEnabled)
        putString(KEY_REPEAT_MODE, repeatMode.name)
        if (queueEnded == null) remove(KEY_QUEUE_ENDED) else putBoolean(KEY_QUEUE_ENDED, queueEnded)
        if (countedOccurrenceId == null) {
            remove(KEY_COUNTED_OCCURRENCE_ID)
            remove(KEY_COUNTED_TRACK_ID)
        } else {
            putString(KEY_COUNTED_OCCURRENCE_ID, countedOccurrenceId)
            putLong(KEY_COUNTED_TRACK_ID, currentTrackId ?: NO_TRACK_ID)
        }
    }

    /**
     * The counted occurrence, only while the cursor's current track is the one it was counted for.
     * A release before 1.1.0 changes the current track in the queue's file and leaves both keys, so
     * after a downgrade and an upgrade the occurrence would pass to another track (#165 U3).
     */
    private fun SharedPreferences.countedOccurrenceId(currentTrackId: Long?): String? =
        getString(KEY_COUNTED_OCCURRENCE_ID, null)
            ?.takeIf { currentTrackId != null && getLong(KEY_COUNTED_TRACK_ID, NO_TRACK_ID) == currentTrackId }

    private companion object {
        const val PREFERENCES_NAME = "playback-state"
        const val PROGRESS_PREFERENCES_NAME = "playback-progress"
        const val KEY_QUEUE_IDS = "queue_track_ids"
        const val KEY_CURRENT_TRACK_ID = "current_track_id"
        const val KEY_CURRENT_QUEUE_INDEX = "current_queue_index"
        const val KEY_POSITION_MS = "position_ms"
        const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
        const val KEY_REPEAT_MODE = "repeat_mode"
        const val KEY_QUEUE_ENDED = "queue_ended"
        const val KEY_COUNTED_OCCURRENCE_ID = "counted_occurrence_id"
        const val KEY_COUNTED_TRACK_ID = "counted_track_id"
        const val KEY_HELD_BACK_TRACK_IDS = "held_back_track_ids"
        const val KEY_QUEUE_FILE_SIGNATURE = "queue_file_signature"
        const val KEY_PLAY_ORDER = "play_order"
        const val KEY_PLAY_ORDER_SIGNATURE = "play_order_signature"
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
    // The saved play order without the tracks left out, in restored indices (#170).
    val restoredPlayOrder = playOrder?.takeIf { shuffleEnabled }?.let { order ->
        val restoredIndexOf = restoredEntries.withIndex().associate { (restoredIndex, entry) -> entry.first to restoredIndex }
        order.mapNotNull(restoredIndexOf::get).takeIf { it.sorted() == restoredEntries.indices.toList() }
    }
    // A queue saved after it played to the end, typically an album with repeat off, would restore
    // at the end and play nothing. It continues with the next track instead, or from the start of
    // the queue after the last one. A position near the end alone is a pause, not an end (#170 P2).
    val finished = currentEntryIndex >= 0 &&
        (queueEnded ?: savedAtLegacyEnd(restoredEntries[currentEntryIndex].second))
    // Next and first in the play order; without a saved one, in the list. Play next after the end,
    // shuffled or not, puts its track right after the ended one in both.
    val nextIndex = when (restoredPlayOrder) {
        null -> (currentEntryIndex + 1).takeIf { it in restoredEntries.indices }
        else -> restoredPlayOrder.getOrNull(restoredPlayOrder.indexOf(currentEntryIndex) + 1)
    }
    val firstIndex = restoredPlayOrder?.firstOrNull() ?: 0
    val (startIndex, startPositionMs) = when {
        currentEntryIndex < 0 -> firstIndex to 0L
        !finished -> currentEntryIndex to positionMs
        repeatMode == PlaybackRepeatMode.ONE -> currentEntryIndex to 0L
        nextIndex != null -> nextIndex to 0L
        else -> firstIndex to 0L
    }
    return RestoredPlaybackQueue(
        tracks = restoredEntries.map { it.second },
        startIndex = startIndex,
        positionMs = startPositionMs,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
        // Only the saved entry, resumed where it was, continues its listen; any other start is new.
        startOccurrenceId = countedOccurrenceId?.takeIf { currentEntryIndex >= 0 && !finished },
        heldBackTrackIds = queueTrackIds.filterNot(tracksById::containsKey).toSet(),
        playOrder = restoredPlayOrder,
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
