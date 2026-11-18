package io.github.sumirenokai.vesqen.playback

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Media3 copies the whole queue into the platform session on every timeline change, and Android
 * sends each copy to every system controller; a long one ran the Bluetooth app out of memory. The
 * app keeps a queue longer than [PLATFORM_QUEUE_LIMIT] to itself, and a short one still reaches the
 * platform session. The platform session is read the way the Bluetooth app reads it, with the
 * shell's media control permission (Android 10+).
 */
class PlatformQueueDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()

    @Test
    fun theAppKeepsALongQueueAndThePlatformSessionOnlyAShortOne() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val store = PlaybackStateStore(app)
        val previous = store.load()
        val file = File(File(app.cacheDir, FIXTURE_DIR), "VESQEN_QA_PRIVATE_platform_queue.wav")
            .also { writeSilentWav(it, durationMs = 20_000) }
        val track = AudioTrack(-2_004, file.toURI().toString(), "Platform queue", "", "", 20_000,
            fileName = file.name, fileSizeBytes = file.length(), mimeType = "audio/wav", codec = "PCM",
            sampleRateHz = 8_000, bitDepth = 16, channelCount = 1)
        val service = ComponentName(app, PlaybackService::class.java)
        val future = main { MediaController.Builder(app, SessionToken(app, service)).buildAsync() }
        var platform: android.media.session.MediaController? = null
        val largestQueue = AtomicInteger()
        val shortQueueObserved = AtomicBoolean()
        val observer = object : android.media.session.MediaController.Callback() {
            override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) {
                val size = queue?.size ?: 0
                largestQueue.accumulateAndGet(size, ::maxOf)
                if (size == PLATFORM_QUEUE_LIMIT) shortQueueObserved.set(true)
            }
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
        try {
            val sessions = app.getSystemService(MediaSessionManager::class.java)
            val connectedPlatform = withTimeout(15_000) {
                var session: android.media.session.MediaController? = null
                while (session == null) {
                    session = sessions.getActiveSessions(null).firstOrNull { it.packageName == app.packageName }
                    if (session == null) delay(25)
                }
                session
            }
            platform = connectedPlatform
            connectedPlatform.registerCallback(observer, Handler(Looper.getMainLooper()))
            val controller = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }

            // The first command after a cold connection is already a long queue. Watch every
            // platform callback, so publishing the full list and clearing it later cannot pass.
            val longQueueSize = 2_010
            main { controller.setMediaItems(List(longQueueSize) { track.toPlaybackMediaItem() }) }
            withTimeout(15_000) {
                while (connectedPlatform.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) != track.title) delay(25)
            }
            awaitPlatformQueue(connectedPlatform, null)
            assertEquals("The app keeps its initial long queue", longQueueSize, main { controller.mediaItemCount })

            main { controller.removeMediaItems(PLATFORM_QUEUE_LIMIT, longQueueSize) }
            awaitPlatformQueue(connectedPlatform, PLATFORM_QUEUE_LIMIT)
            withTimeout(15_000) { while (!shortQueueObserved.get()) delay(25) }

            main { controller.addMediaItem(track.toPlaybackMediaItem()) }
            awaitPlatformQueue(connectedPlatform, null)
            assertEquals("The app keeps the whole queue", PLATFORM_QUEUE_LIMIT + 1, main { controller.mediaItemCount })

            main { controller.removeMediaItem(0) }
            awaitPlatformQueue(connectedPlatform, PLATFORM_QUEUE_LIMIT)
            assertTrue("No initial or later platform callback may contain a long queue: ${largestQueue.get()}",
                largestQueue.get() <= PLATFORM_QUEUE_LIMIT)
        } finally {
            platform?.unregisterCallback(observer)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            main {
                if (future.isDone) future.get().clearMediaItems()
                MediaController.releaseFuture(future)
                // Later tests expect a service that has never played.
                app.stopService(Intent().setComponent(service))
            }
            if (previous != null) store.save(previous) else store.clear()
            File(app.cacheDir, FIXTURE_DIR).deleteRecursively()
        }
    }

    /** Waits for the platform session's queue to have [size] items, or none when [size] is null. */
    private suspend fun awaitPlatformQueue(platform: android.media.session.MediaController, size: Int?) {
        val reached = withTimeoutOrNull(15_000) {
            while (platform.queue?.size != size) delay(25)
        }
        assertNotNull("The platform session has ${platform.queue?.size} queue items, expected $size", reached)
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val FIXTURE_DIR = "platform-queue"
    }
}
