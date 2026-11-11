package io.github.sumirenokai.vesqen.playback

import android.os.Bundle

internal sealed interface SleepTimerRequest {
    data object Cancel : SleepTimerRequest
    data class Countdown(val durationMs: Long, val finishCurrentTrack: Boolean) : SleepTimerRequest
    data class EndOfTrack(val queueEntryId: String) : SleepTimerRequest
}

/** A namespaced bundle keeps timer changes independent of USB output/session extras. */
internal object SleepTimerSessionContract {
    const val SET_ACTION = "io.github.sumirenokai.vesqen.sleep_timer.SET"
    private const val STATE = "io.github.sumirenokai.vesqen.sleep_timer.STATE"
    private const val MODE = "mode"
    private const val DURATION = "duration_ms"
    private const val DEADLINE = "deadline_elapsed_ms"
    private const val FINISH = "finish_current_track"
    private const val WAITING = "waiting_for_track_end"
    private const val ENTRY = "queue_entry_id"
    private const val REVISION = "revision"

    fun arguments(request: SleepTimerRequest): Bundle = Bundle().apply {
        when (request) {
            SleepTimerRequest.Cancel -> putString(MODE, SleepTimerMode.OFF.name)
            is SleepTimerRequest.Countdown -> {
                putString(MODE, SleepTimerMode.COUNTDOWN.name)
                putLong(DURATION, request.durationMs)
                putBoolean(FINISH, request.finishCurrentTrack)
            }
            is SleepTimerRequest.EndOfTrack -> {
                putString(MODE, SleepTimerMode.END_OF_TRACK.name)
                putString(ENTRY, request.queueEntryId)
            }
        }
    }

    fun readRequest(arguments: Bundle): SleepTimerRequest? = when (arguments.value(MODE) as? String) {
        SleepTimerMode.OFF.name -> SleepTimerRequest.Cancel
        SleepTimerMode.COUNTDOWN.name -> {
            val duration = (arguments.value(DURATION) as? Long)?.takeIf { it > 0 }
            val finish = arguments.value(FINISH) as? Boolean
            if (duration == null || finish == null) null else SleepTimerRequest.Countdown(duration, finish)
        }
        SleepTimerMode.END_OF_TRACK.name -> (arguments.value(ENTRY) as? String)?.takeIf(String::isNotBlank)
            ?.let(SleepTimerRequest::EndOfTrack)
        else -> null
    }

    fun withState(extras: Bundle, state: SleepTimerState): Bundle = Bundle(extras).apply {
        putBundle(STATE, Bundle().apply {
            putString(MODE, state.mode.name)
            state.deadlineElapsedRealtimeMs?.let { putLong(DEADLINE, it) }
            putBoolean(FINISH, state.finishCurrentTrack)
            putBoolean(WAITING, state.waitingForTrackEnd)
            putLong(REVISION, state.revision)
        })
    }

    fun fromExtras(extras: Bundle): SleepTimerState? {
        val values = extras.value(STATE) as? Bundle ?: return null
        val modeName = values.value(MODE) as? String ?: return null
        val mode = SleepTimerMode.entries.firstOrNull { it.name == modeName } ?: return null
        val finish = values.value(FINISH) as? Boolean ?: return null
        val waiting = values.value(WAITING) as? Boolean ?: return null
        val revision = (values.value(REVISION) as? Long)?.takeIf { it >= 0 } ?: return null
        val deadline = values.value(DEADLINE) as? Long
        if (mode == SleepTimerMode.COUNTDOWN) {
            if (deadline == null || deadline < 0 || waiting && !finish) return null
        } else if (deadline != null || finish || waiting != (mode == SleepTimerMode.END_OF_TRACK)) return null
        return SleepTimerState(mode, deadline, finish, waiting, revision)
    }

    // Bundle's typed getters otherwise turn a wrong-type value into an indistinguishable default.
    @Suppress("DEPRECATION")
    private fun Bundle.value(key: String): Any? = get(key)
}
