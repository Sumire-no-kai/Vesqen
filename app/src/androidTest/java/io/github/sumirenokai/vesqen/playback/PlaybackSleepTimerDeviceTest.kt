package io.github.sumirenokai.vesqen.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses only generated silent files and a player private to the instrumentation application. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackSleepTimerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()

    @Test fun repeatOnePausesAtTheNaturalBoundaryWithoutChangingTheEntry() = runBlocking {
        withPlayer(repeat = Player.REPEAT_MODE_ONE) { player, timer ->
            assertTrue(timer.apply(SleepTimerRequest.EndOfTrack("first")))
            player.play()
        }
    }

    @Test fun endOfCurrentTrackDoesNotStartTheNextQueuedTrack() = runBlocking {
        withPlayer { player, timer ->
            assertTrue(timer.apply(SleepTimerRequest.EndOfTrack("first")))
            player.play()
        }
    }

    @Test fun manualNextCancelsTheBoundTrackWaitAndKeepsTheRequestedPlayback() = runBlocking {
        withPlayer(expectNextPlaying = true) { player, timer ->
            assertTrue(timer.apply(SleepTimerRequest.EndOfTrack("first")))
            player.play()
            player.seekToNextMediaItem()
        }
    }

    @Test fun countdownFinishCurrentArmsTheSameNaturalEndGate() = runBlocking {
        var now = 0L
        var callback: (() -> Unit)? = null
        val scheduler = SleepTimerScheduler { _, task ->
            callback = task
            val cancel: () -> Unit = { callback = null }
            cancel
        }
        withPlayer(scheduler = scheduler, clock = { now }) { player, timer ->
            player.play()
            assertTrue(timer.apply(SleepTimerRequest.Countdown(1_000, true)))
            now = 1_000
            checkNotNull(callback).invoke()
            assertTrue(timer.state.waitingForTrackEnd)
            assertTrue(player.pauseAtEndOfMediaItems)
        }
    }

    @Test fun serviceOwnedListenerAlarmPausesWithoutClearingTheQueue() = runBlocking {
        withPlayer(
            scheduler = AndroidSleepTimerScheduler(app, Handler(Looper.getMainLooper())),
            expectAtTrackEnd = false,
        ) { player, timer ->
            player.play()
            assertTrue(timer.apply(SleepTimerRequest.Countdown(100, false)))
        }
    }

    private suspend fun withPlayer(
        repeat: Int = Player.REPEAT_MODE_OFF,
        scheduler: SleepTimerScheduler = SleepTimerScheduler { _, _ -> {} },
        clock: () -> Long = android.os.SystemClock::elapsedRealtime,
        expectNextPlaying: Boolean = false,
        expectAtTrackEnd: Boolean = true,
        start: (ExoPlayer, PlaybackSleepTimer) -> Unit,
    ) {
        val directory = File(app.cacheDir, "sleep-timer-${UUID.randomUUID()}").apply { mkdirs() }
        lateinit var player: ExoPlayer
        lateinit var timer: PlaybackSleepTimer
        var playerCreated = false
        var timerCreated = false
        var nextStarted = false
        try {
            val first = File(directory, "first.wav").also { writeSilentWav(it, durationMs = 1_000) }
            val second = File(directory, "second.wav").also { writeSilentWav(it, durationMs = 10_000) }
            main {
                player = ExoPlayer.Builder(app).build()
                playerCreated = true
                player.repeatMode = repeat
                player.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (player.currentMediaItem?.queueEntryId() == "second" && player.isPlaying) nextStarted = true
                    }
                })
                timer = PlaybackSleepTimer(player, scheduler, clock) {}
                timerCreated = true
                player.setMediaItems(listOf(entry(first, "first"), entry(second, "second")))
                player.prepare()
            }
            await { player.playbackState == Player.STATE_READY }
            main { start(player, timer) }
            await {
                timer.state.mode == SleepTimerMode.OFF && if (expectNextPlaying) {
                    player.currentMediaItem?.queueEntryId() == "second" && player.isPlaying
                } else !player.playWhenReady
            }
            main {
                assertEquals(2, player.mediaItemCount)
                assertFalse(player.pauseAtEndOfMediaItems)
                if (!expectNextPlaying) {
                    assertEquals("first", player.currentMediaItem?.queueEntryId())
                    assertFalse(nextStarted)
                    if (expectAtTrackEnd) assertTrue("The first track did not reach its end", player.currentPosition >= 900)
                }
            }
        } finally {
            main {
                if (timerCreated) timer.close()
                if (playerCreated) player.release()
            }
            directory.deleteRecursively()
        }
    }

    private fun entry(file: File, id: String): MediaItem = MediaItem.Builder()
        .setUri(file.toURI().toString())
        .setMediaId("same-song")
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            putString(PLAYBACK_OCCURRENCE_EXTRA, id)
        }).build())
        .build()

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) {
        while (true) {
            var done = false
            main { done = condition() }
            if (done) break
            delay(20)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
