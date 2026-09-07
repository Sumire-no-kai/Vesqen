package io.github.sumirenokai.vesqen.playback

import android.content.Context
import io.github.sumirenokai.vesqen.library.LibraryCatalogStore
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Application-lifetime, service-fed listening-history writer.
 *
 * The bounded channel preserves the order of retained transitions, database work stays off the
 * playback thread, and a failed write is isolated so it cannot interrupt playback or disable
 * later history updates.
 */
internal class PlaybackHistoryRecorder(
    scope: CoroutineScope,
    private val backend: PlaybackHistoryBackend,
    private val clock: () -> Long = System::currentTimeMillis,
    requestCapacity: Int = PLAYBACK_HISTORY_REQUEST_CAPACITY,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        scope = scope,
        backend = AndroidPlaybackHistoryBackend(context.applicationContext),
    )

    private val requests = Channel<PlaybackHistoryRequest>(
        capacity = requestCapacity.also { require(it > 0) },
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _recordedTrackIds = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val recordedTrackIds = _recordedTrackIds.asSharedFlow()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                for (request in requests) {
                    try {
                        backend.recordPlayback(request.trackId, request.playedAtMs)
                        _recordedTrackIds.emit(request.trackId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Listening history is secondary state. A locked/corrupt store must never
                        // take down the MediaSession or prevent a later transition from retrying.
                    }
                }
            } finally {
                // Process teardown and test-scope cancellation must not turn a secondary history
                // store close failure into an uncaught application-scope coroutine failure.
                runCatching(backend::close)
            }
        }
    }

    fun recordPlayback(trackId: Long) {
        // Playback callbacks must never wait for SQLite. Under an extreme transition flood the
        // oldest pending history event is dropped so memory stays bounded and recent listens can
        // still be recorded once the backend catches up.
        requests.trySend(PlaybackHistoryRequest(trackId, clock()))
    }
}

internal const val PLAYBACK_HISTORY_REQUEST_CAPACITY = 64

internal interface PlaybackHistoryBackend : Closeable {
    suspend fun recordPlayback(trackId: Long, playedAtMs: Long)
}

private class AndroidPlaybackHistoryBackend(context: Context) : PlaybackHistoryBackend {
    private val store = LibraryCatalogStore(context)

    override suspend fun recordPlayback(trackId: Long, playedAtMs: Long) {
        store.recordPlayback(trackId, playedAtMs)
    }

    override fun close() {
        store.close()
    }
}

private data class PlaybackHistoryRequest(
    val trackId: Long,
    val playedAtMs: Long,
)
