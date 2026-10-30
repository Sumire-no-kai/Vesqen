package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.VesqenApplication
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

/**
 * #139, #164: when the Library merges a song into a row of the same file, the queue follows. The
 * merge reaches the service through [PlaybackTrackMerges] as it commits (#191 D1, D7, D8).
 */
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
    fun theSavedShuffledOrderStaysWithTheRecord() {
        // #170 W1: the order names the queue's file it was saved with, which the new ID changes.
        val store = PlaybackStateStore(isolatedContext)
        store.save(
            PersistedPlaybackState(
                queueTrackIds = listOf(1, 2, 3, 2),
                currentTrackId = 2,
                currentQueueIndex = 3,
                positionMs = 0,
                shuffleEnabled = true,
                repeatMode = PlaybackRepeatMode.OFF,
                playOrder = listOf(3, 0, 2, 1),
            ),
        )

        store.replaceTrackId(fromTrackId = 2, toTrackId = 7)

        val restored = requireNotNull(PlaybackStateStore(isolatedContext).load())
        assertEquals(listOf(1L, 7L, 3L, 7L), restored.queueTrackIds)
        assertEquals(listOf(3, 0, 2, 1), restored.playOrder)
    }

    @Test
    fun thePlayingEntriesTakeTheRecordAndKeepPlaying() = playMergedQueue(merge = { app, queued, record ->
        main { controller.syncLibrary(listOf(queued[0], record, queued[2])) }
        app.playbackTrackMerges.mergeCommitted(from = queued[1].id, into = record.id)
    })

    @Test
    fun theEntriesTakeTheRecordAsTheMergeCommits() = playMergedQueue(merge = { app, queued, record ->
        // #191 D1: the entries used to change only once a read of the Library held the record. A read
        // that failed after the merge committed left them, and every listen, on the deleted row.
        app.playbackTrackMerges.mergeCommitted(from = queued[1].id, into = record.id)
    })

    @Test
    fun theServiceKeepsSavingItsQueueAfterAHeldBackTrackMerges() = withPlayback { app ->
        // #191 D7: the controller rewrote the saved queue with a store of its own. A track the restore
        // held back is in no entry, so the service never saved the queue again, and its store stamped
        // every later checkpoint with an older state of the file: a restore ignored them all.
        val queued = (1L..3L).map { silentTrack(app, it) }
        val record = queued[1].copy(id = -2_000, title = "Record")
        PlaybackStateStore(app).save(
            PersistedPlaybackState(
                queueTrackIds = queued.map(AudioTrack::id),
                currentTrackId = queued[0].id,
                currentQueueIndex = 0,
                positionMs = 0,
                shuffleEnabled = false,
                repeatMode = PlaybackRepeatMode.OFF,
            ),
        )
        // Track 2 cannot play now, so the restore holds it back.
        main { controller.syncLibrary(listOf(queued[0], queued[2])) }
        awaitPlayback { it.trackId == queued[0].id && it.queueSize == 2 }
        main { controller.togglePlayback() }
        awaitPlayback { it.isPlaying && it.positionMs >= 1_000 }

        app.playbackTrackMerges.mergeCommitted(from = queued[1].id, into = record.id)
        awaitPlayback { it.positionMs >= 3_000 }
        main { controller.togglePlayback() }
        awaitPlayback { !it.isPlaying }
        val paused = playback.get().positionMs
        // A pause saves the position at once.
        val saved = withTimeout(15_000) {
            while (PlaybackStateStore(app).load()?.positionMs?.let { it >= paused - 100 } != true) delay(50)
            requireNotNull(PlaybackStateStore(app).load())
        }
        assertEquals(listOf(queued[0].id, record.id, queued[2].id), saved.queueTrackIds)
        assertEquals(queued[0].id, saved.currentTrackId)
    }

    @Test
    fun withoutAServiceTheSavedQueueTakesTheRecord() {
        // A merge while no service runs (#164 reattachment in a scan, say) reaches the saved queue.
        val store = PlaybackStateStore(isolatedContext)
        store.save(
            PersistedPlaybackState(
                queueTrackIds = listOf(1, 2, 3),
                currentTrackId = 2,
                currentQueueIndex = 1,
                positionMs = 7_000,
                shuffleEnabled = false,
                repeatMode = PlaybackRepeatMode.OFF,
            ),
        )
        PlaybackTrackMerges(isolatedContext).mergeCommitted(from = 2, into = 7)
        instrumentation.waitForIdleSync()

        val restored = requireNotNull(PlaybackStateStore(isolatedContext).load())
        assertEquals(listOf(1L, 7L, 3L), restored.queueTrackIds)
        assertEquals(7L, restored.currentTrackId)
        assertEquals(7_000L, restored.positionMs)
    }

    @Test
    fun aShuffledQueueKeepsItsPlayOrderThroughTheMerge() = withPlayback { app ->
        val queued = (1L..6L).map { silentTrack(app, it) }
        // Shuffled from the third track, which plays first, so the play order is not the list order.
        main { controller.playQueue(queued, 2) }
        awaitPlayback { it.trackId == queued[2].id && it.queueSize == 6 }
        main { controller.cycleTo(PlaybackOrderMode.SHUFFLE) { playback.get().playbackOrderMode } }
        awaitPlayback { it.shuffleEnabled && it.queue.firstOrNull()?.trackId == queued[2].id }
        val order = playback.get().queue.map(PlaybackQueueItem::trackId)
        val merged = queued.first { it.id == order[3] }
        val record = merged.copy(id = -2_000, title = "Record")
        val expected = order.map { if (it == merged.id) record.id else it }

        main { controller.syncLibrary(queued.map { if (it.id == merged.id) record else it }) }
        app.playbackTrackMerges.mergeCommitted(from = merged.id, into = record.id)
        awaitPlayback { snapshot -> snapshot.queue.map(PlaybackQueueItem::trackId) == expected }
        val position = playback.get().positionMs
        awaitPlayback { it.positionMs >= position + 500 }
        assertEquals(queued[2].id, playback.get().trackId)
        assertEquals(expected, playback.get().queue.map(PlaybackQueueItem::trackId))
    }

    /** Plays tracks 1, 2, 3, 2 from the first 2, merges track 2 into a record, and checks the queue. */
    private fun playMergedQueue(
        merge: (app: VesqenApplication, queued: List<AudioTrack>, record: AudioTrack) -> Unit,
        afterMerge: suspend (app: VesqenApplication, record: AudioTrack) -> Unit = { _, _ -> },
    ) = withPlayback { app ->
        val queued = (1L..3L).map { silentTrack(app, it) }
        // The record took over track 2's row: the same file under the record's catalog ID.
        val record = queued[1].copy(id = -2_000, title = "Record")
        main { controller.playQueue(queued + queued[1], 1) }
        awaitPlayback { it.trackId == queued[1].id && it.positionMs >= 1_000 }
        val position = playback.get().positionMs

        merge(app, queued, record)
        awaitPlayback { it.trackId == record.id && it.positionMs >= position + 1_000 }

        val after = playback.get()
        assertTrue(after.isPlaying)
        assertEquals(1, after.queueIndex)
        assertEquals(listOf(queued[0].id, record.id, queued[2].id, record.id), after.queue.map(PlaybackQueueItem::trackId))
        afterMerge(app, record)
    }

    private fun withPlayback(test: suspend (app: VesqenApplication) -> Unit): Unit = runBlocking {
        val app = isolatedPlaybackTestApplication()
        try {
            main { controller = PlaybackController(app) { playback.set(it) } }
            awaitPlayback { it.isControllerReady }
            test(app)
        } finally {
            main {
                if (playback.get().showsPauseAction) controller.togglePlayback()
                controller.cycleTo(PlaybackOrderMode.SEQUENTIAL) { playback.get().playbackOrderMode }
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
