package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.VesqenApplication
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibrarySourceId
import io.github.sumirenokai.vesqen.library.LibraryTrackCandidate
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

        store.replaceTrackIds(mapOf(2L to 7L))

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

        store.replaceTrackIds(mapOf(2L to 7L))

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
    fun aMergeOfThePlayingSongContinuesItsCountedListen() = withPlayback { app ->
        // Two catalog rows of one file in a folder of the test's own: the song that plays and the row
        // it merges into. Listens are counted only for rows the catalog holds.
        val store = app.libraryCatalogStore
        val treeUri = "content://io.github.sumirenokai.vesqen.test/tree/merge-count-${System.nanoTime()}"
        store.upsertFolderSource(treeUri, "Merge count")
        val sourceId = LibrarySourceId.forTree(treeUri)
        try {
            val file = File(File(app.cacheDir, FIXTURE_DIR), "VESQEN_QA_PRIVATE_merge.wav").also { writeSilentWav(it, durationMs = 20_000) }
            val session = store.beginSourceScan(sourceId)
            for (name in listOf("played", "record")) {
                store.upsertTrack(
                    session,
                    LibraryTrackCandidate(remoteId = name, contentUri = file.toURI().toString(), title = name, artist = "", album = "",
                        durationMs = 20_000, fingerprint = name),
                )
            }
            store.completeSourceScan(session)
            val rows = store.readTracks(listOf(sourceId))
            val played = rows.single { it.title == "played" }
            val record = rows.single { it.title == "record" }
            fun plays(trackId: Long) = store.readTracks(listOf(sourceId)).single { it.id == trackId }.playCount

            main { controller.playQueue(listOf(played), 0) }
            awaitPlayback { it.trackId == played.id && it.isPlaying }
            withTimeout(15_000) {
                while (plays(played.id) != 1 || PlaybackStateStore(app).loadCountedPlayback()?.trackId != played.id) delay(50)
            }
            val occurrence = requireNotNull(PlaybackStateStore(app).loadCountedPlayback()).occurrenceId

            // #191 A2: Media3 updates the entry in place without a transition, so the start gate kept the
            // old ID: the saved cursor lost the counted listen, and the next Play counted a second one.
            app.playbackTrackMerges.mergeCommitted(from = played.id, into = record.id)
            awaitPlayback { it.trackId == record.id }
            main { controller.togglePlayback() }
            awaitPlayback { !it.isPlaying }
            val position = playback.get().positionMs
            main { controller.togglePlayback() }
            awaitPlayback { it.isPlaying && it.positionMs >= position + 1_000 }
            main { controller.togglePlayback() }
            awaitPlayback { !it.isPlaying }
            // The history is written on its own thread.
            delay(1_000)

            assertEquals(1, plays(played.id))
            assertEquals(0, plays(record.id))
            assertEquals(CountedPlayback(record.id, occurrence), PlaybackStateStore(app).loadCountedPlayback())
        } finally {
            store.removeFolderSource(sourceId)
        }
    }

    @Test
    fun undoAfterAMergePutsBackTheRowItMergedInto() = withPlayback { app ->
        val queued = (1L..3L).map { silentTrack(app, it) }
        val record = queued[1].copy(id = -2_000, title = "Record")
        main { controller.playQueue(queued, 0) }
        awaitPlayback { it.trackId == queued[0].id && it.queueSize == 3 }
        main { controller.clearQueue() }
        awaitPlayback { it.queueSize == 0 }

        // #191 A3: a merge while Undo is offered found no entry to rename, so Undo put back the ID of the
        // deleted row. The queue takes the merged ID as it enters the service's player.
        app.playbackTrackMerges.mergeCommitted(from = queued[1].id, into = record.id)
        instrumentation.waitForIdleSync()
        main { controller.undoClearQueue() }
        awaitPlayback { snapshot -> snapshot.queue.map(PlaybackQueueItem::trackId) == listOf(queued[0].id, record.id, queued[2].id) }
    }

    @Test
    fun aTransactionsMergesRenameTheQueueInOneChange() {
        // #191 A4: each merge used to walk the whole queue and change the timeline on its own.
        val changes = mutableListOf<Int>()
        var entries = emptyList<String>()
        var occurrences = emptyList<String>()
        main {
            val player = ExoPlayer.Builder(context).build()
            try {
                player.setMediaItems(listOf(1, 2, 3, 2, 4).mapIndexed { index, id -> entry(id, "o$index") })
                player.addListener(object : Player.Listener {
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        changes += reason
                    }
                })
                player.replaceTrackEntries(mapOf(2L to 7L, 4L to 8L, 6L to 9L))
                entries = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
                occurrences = List(player.mediaItemCount) { player.getMediaItemAt(it).queueEntryId() }
            } finally {
                player.release()
            }
        }
        assertEquals(listOf("1", "7", "3", "7", "8"), entries)
        assertEquals(List(5) { "o$it" }, occurrences)
        assertEquals(listOf(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED), changes)

        // The saved queue too, in one pass; a chain within the transaction ends at its last row.
        val store = PlaybackStateStore(isolatedContext)
        store.save(
            PersistedPlaybackState(
                queueTrackIds = listOf(1, 2, 3, 4),
                currentTrackId = 4,
                currentQueueIndex = 3,
                positionMs = 0,
                shuffleEnabled = false,
                repeatMode = PlaybackRepeatMode.OFF,
            ),
        )
        PlaybackTrackMerges(isolatedContext).mergesCommitted(listOf(2L to 7L, 4L to 8L, 8L to 9L))
        instrumentation.waitForIdleSync()
        val restored = requireNotNull(PlaybackStateStore(isolatedContext).load())
        assertEquals(listOf(1L, 7L, 3L, 9L), restored.queueTrackIds)
        assertEquals(9L, restored.currentTrackId)
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
                // The fixtures' IDs are reused by later tests, which a merge kept for this process would rename.
                app.playbackTrackMerges.forgetMerges()
            }
            instrumentation.waitForIdleSync()
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

    private fun entry(trackId: Int, occurrence: String) = MediaItem.Builder()
        .setMediaId(trackId.toString())
        .setUri("content://track/$trackId")
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putString(PLAYBACK_OCCURRENCE_EXTRA, occurrence) }).build())
        .build()

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
