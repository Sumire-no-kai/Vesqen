package io.github.sumirenokai.vesqen.playback

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #52 on the isolated deviceTest application, whose queue and catalog are its own. Run without
 * the app UI open, so no other controller restores the queue.
 */
class PlaybackResumptionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()

    @Test
    fun startingTheServiceNeitherRestoresNorPlaysAndAnUnplayableQueueStaysSilent() = runBlocking {
        val store = PlaybackStateStore(app)
        val previous = store.load()
        // The catalog never assigns negative ids, so nothing in this saved queue can be played.
        val saved = PersistedPlaybackState(
            queueTrackIds = listOf(-52, -53),
            currentTrackId = -53,
            positionMs = 12_345,
            shuffleEnabled = false,
            repeatMode = PlaybackRepeatMode.OFF,
            currentQueueIndex = 1,
        )
        store.save(saved)
        val future = main {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
                .buildAsync()
        }
        try {
            val controller = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
            // Binding is how Android and the UI start the service: no queue and no Play request.
            assertNull(
                "Starting the service must not restore or play the saved queue",
                withTimeoutOrNull(2_000) {
                    while (main { controller.mediaItemCount == 0 && !controller.playWhenReady }) delay(25)
                },
            )

            main { controller.play() }
            // After a failed restore Media3 still applies Play, which prepares the empty player and
            // ends it. The controller sets playWhenReady locally right away, so wait for the ended
            // state that only the session reports.
            withTimeout(15_000) { while (main { controller.playbackState } != Player.STATE_ENDED) delay(25) }
            assertEquals(0, main { controller.mediaItemCount })
            assertFalse(main { controller.isPlaying })
            assertEquals("The saved queue must survive a failed restore", saved, store.load())
        } finally {
            main { MediaController.releaseFuture(future) }
            if (previous != null) store.save(previous) else store.clear()
        }
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
