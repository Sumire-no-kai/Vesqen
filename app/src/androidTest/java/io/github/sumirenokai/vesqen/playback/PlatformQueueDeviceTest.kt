package io.github.sumirenokai.vesqen.playback

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaSessionManager
import android.os.Build
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
        try {
            val controller = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }
            val sessions = app.getSystemService(MediaSessionManager::class.java)
            val platform = withTimeout(15_000) {
                var session: android.media.session.MediaController? = null
                while (session == null) {
                    session = sessions.getActiveSessions(null).firstOrNull { it.packageName == app.packageName }
                    if (session == null) delay(25)
                }
                session
            }

            main { controller.setMediaItems(List(PLATFORM_QUEUE_LIMIT) { track.toPlaybackMediaItem() }) }
            awaitPlatformQueue(platform, PLATFORM_QUEUE_LIMIT)

            main { controller.addMediaItem(track.toPlaybackMediaItem()) }
            awaitPlatformQueue(platform, null)
            assertEquals("The app keeps the whole queue", PLATFORM_QUEUE_LIMIT + 1, main { controller.mediaItemCount })

            main { controller.removeMediaItem(0) }
            awaitPlatformQueue(platform, PLATFORM_QUEUE_LIMIT)
        } finally {
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
