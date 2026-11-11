package io.github.sumirenokai.vesqen.playback

enum class SleepTimerMode { OFF, COUNTDOWN, END_OF_TRACK }

/** Service-owned state. The deadline uses elapsed realtime, never the civil clock. */
data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val deadlineElapsedRealtimeMs: Long? = null,
    val finishCurrentTrack: Boolean = false,
    val waitingForTrackEnd: Boolean = false,
    val revision: Long = 0,
) {
    fun remainingMs(elapsedRealtimeMs: Long): Long =
        if (mode == SleepTimerMode.COUNTDOWN && !waitingForTrackEnd) {
            ((deadlineElapsedRealtimeMs ?: 0) - elapsedRealtimeMs.coerceAtLeast(0)).coerceAtLeast(0)
        } else 0
}

/** A current queue occurrence, including requested playback while buffering or suppressed. */
internal data class SleepTimerPlayback(
    val queueEntryId: String?,
    val playWhenReady: Boolean,
    val terminal: Boolean,
)

/** Schedules exactly one deadline; cancellation must not prevent tests from delivering an old callback. */
internal fun interface SleepTimerScheduler {
    fun schedule(deadlineElapsedRealtimeMs: Long, onDeadline: () -> Unit): () -> Unit
}

/** Main-thread state machine; neither its clock nor its scheduler requires Android in unit tests. */
internal class SleepTimer(
    private val elapsedRealtimeMs: () -> Long,
    private val scheduler: SleepTimerScheduler,
    private val playback: () -> SleepTimerPlayback,
    private val setPauseAtTrackEnd: (Boolean) -> Unit,
    private val pause: () -> Unit,
    private val onChanged: (SleepTimerState) -> Unit,
) {
    var state = SleepTimerState()
        private set
    private var generation = 0L
    private var cancelDeadline: (() -> Unit)? = null
    private var boundEntryId: String? = null
    private var lastPlayback = playback()
    private var closed = false

    fun startCountdown(durationMs: Long, finishCurrentTrack: Boolean): Boolean {
        val now = elapsedRealtimeMs()
        val current = playback()
        if (closed || durationMs <= 0 || now < 0 || durationMs > Long.MAX_VALUE - now ||
            current.terminal || current.queueEntryId == null
        ) return false
        val deadline = now + durationMs
        val token = generation + 1
        // Register first. If scheduling fails, the existing timer and its end-of-item gate survive.
        val cancellation = scheduler.schedule(deadline) {
            if (!closed && generation == token) expire(playback())
        }
        lastPlayback = current
        replace(
            SleepTimerState(SleepTimerMode.COUNTDOWN, deadline, finishCurrentTrack),
            cancellation = cancellation,
        )
        return true
    }

    fun pauseAfterCurrentTrack(expectedEntryId: String): Boolean {
        val current = playback()
        if (closed || current.terminal || expectedEntryId.isBlank() || current.queueEntryId != expectedEntryId) return false
        lastPlayback = current
        replace(SleepTimerState(mode = SleepTimerMode.END_OF_TRACK, waitingForTrackEnd = true), expectedEntryId)
        return true
    }

    fun cancel() {
        if (state.mode != SleepTimerMode.OFF) replace(SleepTimerState())
    }

    /** End-of-item pause reasons also cover repeat-one, whose queue identity does not change. */
    fun onTrackEnded() {
        reconcileDeadline()
        if (state.waitingForTrackEnd) finish(pausePlayback = true)
    }

    fun onManualTrackChange() {
        if (state.waitingForTrackEnd) {
            // A timed wait has already expired; skipping it does not move its deadline to another song.
            finish(pausePlayback = state.mode == SleepTimerMode.COUNTDOWN)
        }
        onPlaybackChanged()
    }

    fun onPlaybackChanged() {
        if (closed) return
        val current = playback()
        reconcileDeadline()
        if (state.mode != SleepTimerMode.OFF) {
            when {
                current.terminal || current.queueEntryId == null -> cancel()
                state.waitingForTrackEnd && current.queueEntryId != boundEntryId ->
                    finish(pausePlayback = state.mode == SleepTimerMode.COUNTDOWN)
            }
        }
        lastPlayback = current
    }

    private fun reconcileDeadline() {
        if (state.mode == SleepTimerMode.COUNTDOWN && !state.waitingForTrackEnd &&
            elapsedRealtimeMs() >= checkNotNull(state.deadlineElapsedRealtimeMs)
        ) {
            // A deadline deferred while a paused device slept must not stop a newly requested Play.
            expire(lastPlayback)
        }
    }

    fun close() {
        if (closed) return
        cancel()
        closed = true
        generation++
    }

    private fun expire(current: SleepTimerPlayback) {
        if (state.mode != SleepTimerMode.COUNTDOWN || state.waitingForTrackEnd) return
        val deadline = checkNotNull(state.deadlineElapsedRealtimeMs)
        if (elapsedRealtimeMs() < deadline) return
        cancelDeadline?.invoke()
        cancelDeadline = null
        when {
            current.terminal || current.queueEntryId == null || !current.playWhenReady -> finish(false)
            !state.finishCurrentTrack -> finish(true)
            else -> replace(state.copy(waitingForTrackEnd = true), current.queueEntryId)
        }
    }

    private fun finish(pausePlayback: Boolean) {
        if (state.mode == SleepTimerMode.OFF) return
        replace(SleepTimerState())
        if (pausePlayback) pause()
    }

    private fun replace(next: SleepTimerState, entryId: String? = null, cancellation: (() -> Unit)? = null) {
        generation++
        cancelDeadline?.invoke()
        cancelDeadline = cancellation
        boundEntryId = entryId
        state = next.copy(revision = state.revision + 1)
        setPauseAtTrackEnd(state.waitingForTrackEnd)
        onChanged(state)
    }
}
