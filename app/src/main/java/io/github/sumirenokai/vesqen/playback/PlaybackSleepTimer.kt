package io.github.sumirenokai.vesqen.playback

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** The deadline belongs to the running service; no intent can restart it after teardown. */
internal class AndroidSleepTimerScheduler(context: Context, private val handler: Handler) : SleepTimerScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    // The listener exemption applies before S and from U. None of these alarms bypasses Doze.
    @SuppressLint("ScheduleExactAlarm")
    override fun schedule(deadlineElapsedRealtimeMs: Long, onDeadline: () -> Unit): () -> Unit {
        var active = true
        lateinit var listener: AlarmManager.OnAlarmListener
        val deadline = Runnable {
            if (active) {
                active = false
                alarms.cancel(listener)
                onDeadline()
            }
        }
        listener = AlarmManager.OnAlarmListener {
            handler.removeCallbacks(deadline)
            deadline.run()
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.S..Build.VERSION_CODES.TIRAMISU) {
            // S/T require special access even for exact listener alarms.
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsedRealtimeMs, "Vesqen sleep timer", listener, handler)
        } else {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsedRealtimeMs, "Vesqen sleep timer", listener, handler)
        }
        // Even exact alarms have a platform minimum scheduling delay. While music runs, the
        // player's existing wake mode lets this one Handler callback honor a shorter deadline;
        // the elapsed alarm also reconciles a paused device after sleep. Neither bypasses Doze.
        handler.postDelayed(deadline, (deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        return {
            active = false
            handler.removeCallbacks(deadline)
            alarms.cancel(listener)
        }
    }
}

/** Binds natural repeat boundaries and manual changes without a timer-driven playback poll. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackSleepTimer(
    private val player: ExoPlayer,
    scheduler: SleepTimerScheduler,
    elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    onChanged: (SleepTimerState) -> Unit,
) : Player.Listener {
    private val timer = SleepTimer(
        elapsedRealtimeMs = elapsedRealtimeMs,
        scheduler = scheduler,
        playback = {
            SleepTimerPlayback(
                queueEntryId = player.currentMediaItem?.queueEntryId(),
                playWhenReady = player.playWhenReady,
                terminal = player.playerError != null || player.playbackState in setOf(Player.STATE_IDLE, Player.STATE_ENDED),
            )
        },
        setPauseAtTrackEnd = { player.pauseAtEndOfMediaItems = it },
        pause = { if (player.playWhenReady) player.pause() },
        onChanged = onChanged,
    )

    val state: SleepTimerState get() = timer.state

    init { player.addListener(this) }

    fun apply(request: SleepTimerRequest): Boolean = when (request) {
        SleepTimerRequest.Cancel -> { timer.cancel(); true }
        is SleepTimerRequest.Countdown -> timer.startCountdown(request.durationMs, request.finishCurrentTrack)
        is SleepTimerRequest.EndOfTrack -> timer.pauseAfterCurrentTrack(request.queueEntryId)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) timer.onTrackEnded()
        timer.onPlaybackChanged()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> timer.onTrackEnded()
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> timer.onManualTrackChange()
        }
        timer.onPlaybackChanged()
    }

    override fun onEvents(player: Player, events: Player.Events) { timer.onPlaybackChanged() }

    fun close() {
        player.removeListener(this)
        timer.close()
    }
}
