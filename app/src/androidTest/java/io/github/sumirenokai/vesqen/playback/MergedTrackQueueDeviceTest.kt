package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #139: when the Library merges a song into a record of the same file, the queue follows. */
class MergedTrackQueueDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testPrefix = "merged-track-test-${UUID.randomUUID()}"
    private val usedPreferences = mutableSetOf<String>()
    private val isolatedContext = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("$testPrefix-$name".also(usedPreferences::add), mode)
    }
    private val playback = AtomicReference(PlaybackSnapshot())
    private lateinit var controller: PlaybackController

    @After
    fun removeTestPreferences() {
        usedPreferences.forEach(context::deleteSharedPreferences)
    }

    @Test
    fun theSavedQueueAndCursorTakeTheRecord() {
        val store = PlaybackStateStore(isolatedContext)
        store.save(
            PersistedPlaybackState(
                queueTrackIds = listOf(1, 2, 3, 2),
                currentTrackId = 2,
                currentQueueIndex = 1,
                positionMs = 0,
                shuffleEnabled = false,
                repeatMode = PlaybackRepeatMode.OFF,
            ),
        )
        store.saveProgress(
            currentTrackId = 2,
            positionMs = 42_000,
            shuffleEnabled = true,
            repeatMode = PlaybackRepeatMode.ALL,
            currentQueueIndex = 3,
            queueEnded = false,
            countedOccurrenceId = "occurrence",
        )

        store.replaceTrackId(fromTrackId = 2, toTrackId = 7)

        val restored = requireNotNull(PlaybackStateStore(isolatedContext).load())
        assertEquals(listOf(1L, 7L, 3L, 7L), restored.queueTrackIds)
        assertEquals(7L, restored.currentTrackId)
        assertEquals(3, restored.currentQueueIndex)
        assertEquals(42_000L, restored.positionMs)
        assertEquals("occurrence", restored.countedOccurrenceId)

        // The store that rewrote the queue keeps writing its progress over it.
        store.saveProgress(
            currentTrackId = 7,
            positionMs = 50_000,
            shuffleEnabled = true,
            repeatMode = PlaybackRepeatMode.ALL,
            currentQueueIndex = 3,
            queueEnded = false,
        )
        assertEquals(50_000L, PlaybackStateStore(isolatedContext).load()?.positionMs)
    }

    @Test
    fun thePlayingEntriesTakeTheRecordAndKeepPlaying() = runBlocking {
        val app = isolatedPlaybackTestApplication()
        val queued = (1L..3L).map { silentTrack(app, it) }
        // The record took over track 2's row: the same file under the record's catalog ID.
        val record = queued[1].copy(id = -2_000, title = "Record")
        try {
            main { controller = PlaybackController(app) { playback.set(it) } }
            awaitPlayback { it.isControllerReady }
            main { controller.playQueue(queued + queued[1], 1) }
            awaitPlayback { it.trackId == queued[1].id && it.positionMs >= 1_000 }
            val position = playback.get().positionMs

            main {
                controller.syncLibrary(listOf(queued[0], record, queued[2]))
                controller.replaceQueueTrack(fromTrackId = queued[1].id, toTrackId = record.id)
            }
            awaitPlayback { it.trackId == record.id && it.positionMs >= position + 1_000 }

            val after = playback.get()
            assertTrue(after.isPlaying)
            assertEquals(1, after.queueIndex)
            assertEquals(listOf(queued[0].id, record.id, queued[2].id, record.id), after.queue.map(PlaybackQueueItem::trackId))
        } finally {
            main {
                if (playback.get().showsPauseAction) controller.togglePlayback()
                controller.clearQueue()
                controller.release()
                // Later tests expect a service that has never played.
                app.stopService(Intent(app, PlaybackService::class.java))
            }
            File(app.cacheDir, FIXTURE_DIR).deleteRecursively()
        }
    }

    private suspend fun awaitPlayback(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
        while (true) {
            main { controller.refreshPosition() }
            if (predicate(playback.get())) break
            delay(50)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    /** Twenty seconds of silence, private to the deviceTest app. */
    private fun silentTrack(app: Context, id: Long): AudioTrack {
        val file = File(File(app.cacheDir, FIXTURE_DIR), "VESQEN_QA_PRIVATE_$id.wav").also { writeSilentWav(it, durationMs = 20_000) }
        return AudioTrack(-1_000 - id, file.toURI().toString(), "Track $id", "", "", 20_000,
            fileName = file.name, fileSizeBytes = file.length(), mimeType = "audio/wav", codec = "PCM",
            sampleRateHz = 8_000, bitDepth = 16, channelCount = 1)
    }

    private companion object {
        const val FIXTURE_DIR = "merged-track-queue"
    }
}
