package io.github.sumirenokai.vesqen.playback

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat
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
import org.junit.Assert.assertTrue
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

    /**
     * A Play press after the process ended reaches the service the way Media3's MediaButtonReceiver
     * delivers it. The service must enter the foreground at once (Android's deadline does not wait
     * for the queue to be restored), and when nothing can play it must leave it again and remove
     * the hidden notification, keeping the saved queue. Run with an activity of this app in front,
     * which allows the foreground start the receiver gets from Android.
     */
    @Test
    fun aPlayPressStartLeavesTheForegroundAndKeepsTheQueueWhenNothingCanPlay() = runBlocking {
        val store = PlaybackStateStore(app)
        val previous = store.load()
        val saved = PersistedPlaybackState(
            queueTrackIds = listOf(-52, -53),
            currentTrackId = -53,
            positionMs = 12_345,
            shuffleEnabled = false,
            repeatMode = PlaybackRepeatMode.OFF,
            currentQueueIndex = 1,
        )
        store.save(saved)
        val service = ComponentName(app, PlaybackService::class.java)
        try {
            val press = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(service)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            main { ContextCompat.startForegroundService(app, press) }
            withTimeout(15_000) { while (!serviceRunning(service)) delay(25) }
            // Ended without playback: out of the foreground, the hidden notification gone.
            withTimeout(15_000) { while (serviceInForeground(service)) delay(25) }
            val notifications = app.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue(
                "The hidden media button notification must be removed",
                notifications.none { it.id == androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID },
            )
            assertEquals("The saved queue must survive a failed restore", saved, store.load())
        } finally {
            main { app.stopService(Intent().setComponent(service)) }
            if (previous != null) store.save(previous) else store.clear()
        }
    }

    @Suppress("DEPRECATION") // Still returns this app's own services.
    private fun runningService(service: ComponentName) =
        app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).firstOrNull { it.service == service }

    private fun serviceRunning(service: ComponentName) = runningService(service) != null

    private fun serviceInForeground(service: ComponentName) = runningService(service)?.foreground == true

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
