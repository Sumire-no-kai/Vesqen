package io.github.sumirenokai.vesqen.playback

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the private command through Media3's real controller/service transport. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SleepTimerServiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()
    private val command = SessionCommand(SleepTimerSessionContract.SET_ACTION, Bundle.EMPTY)

    @Test fun timerCommandsPublishStateAndRejectInvalidRequestsWithoutChangingTheQueue() = runBlocking {
        withPausedQueue { player ->
            val countdown = send(player, SleepTimerSessionContract.arguments(SleepTimerRequest.Countdown(60_000, false)))
            assertEquals(SessionResult.RESULT_SUCCESS, countdown.resultCode)
            val countdownState = checkNotNull(SleepTimerSessionContract.fromExtras(countdown.extras))
            assertEquals(SleepTimerMode.COUNTDOWN, countdownState.mode)
            assertNotNull(countdownState.deadlineElapsedRealtimeMs)
            assertFalse(countdownState.waitingForTrackEnd)
            awaitState(player, countdownState)

            // Missing fields, non-positive time and deadline overflow must retain the active timer.
            for (invalid in listOf(
                Bundle.EMPTY,
                SleepTimerSessionContract.arguments(SleepTimerRequest.Countdown(0, false)),
                SleepTimerSessionContract.arguments(SleepTimerRequest.Countdown(Long.MAX_VALUE, false)),
            )) {
                assertEquals(SessionError.ERROR_BAD_VALUE, send(player, invalid).resultCode)
                main { assertEquals(countdownState, SleepTimerSessionContract.fromExtras(player.sessionExtras)) }
            }

            val endOfTrack = send(player, SleepTimerSessionContract.arguments(SleepTimerRequest.EndOfTrack("current")))
            assertEquals(SessionResult.RESULT_SUCCESS, endOfTrack.resultCode)
            val endState = checkNotNull(SleepTimerSessionContract.fromExtras(endOfTrack.extras))
            assertEquals(SleepTimerMode.END_OF_TRACK, endState.mode)
            assertTrue(endState.waitingForTrackEnd)
            awaitState(player, endState)
            assertEquals(
                SessionError.ERROR_BAD_VALUE,
                send(player, SleepTimerSessionContract.arguments(SleepTimerRequest.EndOfTrack("next"))).resultCode,
            )
            main { assertEquals(endState, SleepTimerSessionContract.fromExtras(player.sessionExtras)) }

            val cancelled = send(player, SleepTimerSessionContract.arguments(SleepTimerRequest.Cancel))
            assertEquals(SessionResult.RESULT_SUCCESS, cancelled.resultCode)
            val off = checkNotNull(SleepTimerSessionContract.fromExtras(cancelled.extras))
            assertEquals(SleepTimerMode.OFF, off.mode)
            awaitState(player, off)
            main {
                assertEquals(2, player.mediaItemCount)
                assertEquals("current", player.currentMediaItem?.queueEntryId())
                assertEquals(1_500L, player.currentPosition)
                assertFalse(player.playWhenReady)
                assertFalse(player.isPlaying)
            }
        }
    }

    @Test fun countdownPausesPlayingServiceAndKeepsTheQueue() = runBlocking {
        withPausedQueue { player ->
            val screenOff = InstrumentationRegistry.getArguments().getString("sleepTimerScreenOff") == "true"
            val durationMs = if (screenOff) 120_000L else 750L
            val power = app.getSystemService(PowerManager::class.java)
            val restoreDisplay = screenOff && power.isInteractive
            val pausedAt = CompletableDeferred<Long>()
            val listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) pausedAt.complete(SystemClock.elapsedRealtime())
                }
            }
            try {
                main {
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    player.play()
                }
                await { main { player.isPlaying } }
                if (restoreDisplay) screenKey("KEYCODE_SLEEP")
                if (screenOff) await { !power.isInteractive }
                main { player.addListener(listener) }
                val result = send(player, SleepTimerSessionContract.arguments(SleepTimerRequest.Countdown(durationMs, false)))
                assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
                val deadline = checkNotNull(SleepTimerSessionContract.fromExtras(result.extras)?.deadlineElapsedRealtimeMs)
                // Observe the actual pause without polling during screen-off playback.
                val observed = withTimeout(durationMs + 15_000) { pausedAt.await() }
                assertTrue("Pause arrived ${observed - deadline} ms from its deadline", observed in deadline..(deadline + 15_000))
                await { main { !player.playWhenReady && SleepTimerSessionContract.fromExtras(player.sessionExtras)?.mode == SleepTimerMode.OFF } }
                if (screenOff) assertFalse(power.isInteractive)
                main {
                    assertEquals(2, player.mediaItemCount)
                    assertEquals("current", player.currentMediaItem?.queueEntryId())
                    assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
                }
            } finally {
                main { player.removeListener(listener) }
                if (restoreDisplay) screenKey("KEYCODE_WAKEUP")
            }
        }
    }

    private fun screenKey(key: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input keyevent $key"),
        ).use { it.readBytes() }
    }

    private suspend fun withPausedQueue(test: suspend (MediaController) -> Unit) {
        val directory = File(app.cacheDir, "sleep-service-${UUID.randomUUID()}")
        val file = File(directory, "silent.wav").also { writeSilentWav(it, durationMs = 20_000) }
        val store = PlaybackStateStore(app)
        val previous = store.load()
        val future = main {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
                .buildAsync()
        }
        var controller: MediaController? = null
        var restoreMode: UsbOutputMode? = null
        try {
            val player = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
            controller = player
            // A reused device-lab install may still require the DAC from a previous test.
            val mode = main { checkNotNull(UsbOutputSessionContract.fromBundle(player.sessionExtras)).mode }
            if (mode != UsbOutputMode.SYSTEM) {
                restoreMode = mode
                setOutputMode(player, UsbOutputMode.SYSTEM)
            }
            main {
                assertTrue(player.isSessionCommandAvailable(command))
                player.setMediaItems(listOf(entry(file, "current"), entry(file, "next")), 0, 1_500)
                player.prepare()
            }
            await { main { player.playbackState == Player.STATE_READY && player.currentPosition == 1_500L } }
            main { assertFalse(player.playWhenReady) }

            test(player)
        } finally {
            try {
                controller?.let { active ->
                    main {
                        active.pause()
                        active.stop()
                        active.clearMediaItems()
                    }
                    // Media3 delivers controller commands asynchronously. Drain the session's
                    // earlier player commands before releasing the binding and restoring storage.
                    try {
                        send(active, SleepTimerSessionContract.arguments(SleepTimerRequest.Cancel))
                    } finally {
                        restoreMode?.let { setOutputMode(active, it) }
                    }
                }
            } finally {
                main {
                    MediaController.releaseFuture(future)
                    app.stopService(Intent(app, PlaybackService::class.java))
                }
                instrumentation.waitForIdleSync()
                if (previous != null) store.save(previous) else store.clear()
                directory.deleteRecursively()
            }
        }
    }

    private suspend fun setOutputMode(controller: MediaController, mode: UsbOutputMode) {
        val result = send(controller, UsbOutputSessionContract.modeArguments(mode),
            SessionCommand(UsbOutputSessionContract.SET_MODE_ACTION, Bundle.EMPTY))
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(mode, UsbOutputSessionContract.fromBundle(result.extras)?.mode)
    }

    private suspend fun send(controller: MediaController, arguments: Bundle, action: SessionCommand = command): SessionResult {
        val future = main { controller.sendCustomCommand(action, arguments) }
        return withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
    }

    private suspend fun awaitState(controller: MediaController, expected: SleepTimerState) {
        await { main { SleepTimerSessionContract.fromExtras(controller.sessionExtras) == expected } }
        main { assertNotNull("Timer publication must preserve USB output extras", UsbOutputSessionContract.fromBundle(controller.sessionExtras)) }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) delay(20)
    }

    private fun entry(file: File, occurrence: String): MediaItem = MediaItem.Builder()
        .setUri(file.toURI().toString())
        .setMediaId("-32500")
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            putString(PLAYBACK_OCCURRENCE_EXTRA, occurrence)
        }).build())
        .build()

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
