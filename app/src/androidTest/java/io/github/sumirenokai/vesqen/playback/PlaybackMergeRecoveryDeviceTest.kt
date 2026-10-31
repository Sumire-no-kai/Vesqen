package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.LibraryCatalogStore
import io.github.sumirenokai.vesqen.library.LibrarySourceId
import io.github.sumirenokai.vesqen.library.LibraryTrackCandidate
import io.github.sumirenokai.vesqen.library.TrackMergeFollower
import io.github.sumirenokai.vesqen.library.mediaStoreRemoteId
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlaybackMergeRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefix = "merge-recovery-${UUID.randomUUID()}"
    private val databaseName = "$prefix.db"
    private val preferencesNames = mutableSetOf<String>()
    private val isolated = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("$prefix-$name".also(preferencesNames::add), mode)
    }

    @After
    fun cleanup() {
        instrumentation.waitForIdleSync()
        context.deleteDatabase(databaseName)
        preferencesNames.forEach(context::deleteSharedPreferences)
    }

    @Test
    fun committedMergeWithoutNotificationRecoversBeforeCatalogRead() {
        val follower = PlaybackTrackMerges(isolated)
        // SQLite commits, but the process dies before the commit notification can reach playback.
        val interrupted = object : TrackMergeFollower by follower {
            override fun prepareCommit(merges: List<Pair<Long, Long>>) {
                follower.prepareCommit(merges)
                val xml = File(context.applicationInfo.dataDir, "shared_prefs/$prefix-playback-track-merges.xml").readText()
                merges.forEach { (from, _) -> assertTrue(xml.contains("name=\"$from\"")) }
            }
            override fun mergesCommitted(merges: List<Pair<Long, Long>>) = Unit
        }
        lateinit var pair: Pair<Long, Long>
        LibraryCatalogStore(context, databaseName, mergeFollower = interrupted).use { store ->
            pair = seedUnmatched(store)
            saveQueue(pair.first)
            assertTrue(store.assignUnmatchedRecord(pair.second, pair.first))
            assertEquals(pair.first, PlaybackStateStore(isolated).load()!!.currentTrackId)
            assertFalse(store.readTrackIdsForSweep().trackIds.contains(pair.first))
        }
        assertTrue(journal().all.isNotEmpty())
        // A new helper/follower, with no in-process notification or merge cache, opens the same files.
        val restarted = PlaybackTrackMerges(isolated)
        LibraryCatalogStore(context, databaseName, mergeFollower = restarted).use { store ->
            assertTrue(store.readTrackIdsForSweep().trackIds.contains(pair.second))
            assertRepairedQueue(pair.second)
            // commit(), rather than apply(), must have written the queue to disk before acknowledging.
            val xml = File(context.applicationInfo.dataDir, "shared_prefs/$prefix-playback-state.xml").readText()
            assertTrue(xml.contains("${pair.second},${pair.second}"))
        }
        instrumentation.waitForIdleSync()
        assertTrue(journal().all.isEmpty())
        LibraryCatalogStore(context, databaseName, mergeFollower = PlaybackTrackMerges(isolated)).use { store ->
            store.readTrackIdsForSweep()
            assertRepairedQueue(pair.second) // replay is idempotent across another open
        }
    }

    @Test
    fun intentPersistedBeforeAnAbortedCommitDoesNotChangeTheQueue() {
        val follower = PlaybackTrackMerges(isolated)
        val interrupted = object : TrackMergeFollower by follower {
            override fun prepareCommit(merges: List<Pair<Long, Long>>) {
                follower.prepareCommit(merges)
                throw InterruptedCommit()
            }
            // Model death before rollback cleanup, leaving the durable intent behind.
            override fun mergeRolledBack(from: Long, into: Long) = Unit
        }
        lateinit var pair: Pair<Long, Long>
        LibraryCatalogStore(context, databaseName, mergeFollower = interrupted).use { store ->
            pair = seedUnmatched(store)
            saveQueue(pair.first)
            try {
                store.assignUnmatchedRecord(pair.second, pair.first)
                fail("The injected pre-commit failure must abort SQLite")
            } catch (_: InterruptedCommit) { }
            assertTrue(store.readTrackIdsForSweep().trackIds.contains(pair.first))
        }
        assertTrue(journal().all.isNotEmpty())
        LibraryCatalogStore(context, databaseName, mergeFollower = PlaybackTrackMerges(isolated)).use { store ->
            store.readTrackIdsForSweep()
            assertRepairedQueue(pair.first)
        }
        instrumentation.waitForIdleSync()
        assertTrue(journal().all.isEmpty())
    }

    @Test
    fun failedQueueWriteKeepsRecoveryForTheNextOpen() {
        PlaybackTrackMerges(isolated).prepareCommit(listOf(2L to 7L))
        saveQueue(2)
        try {
            PlaybackTrackMerges(failingCommit("playback-state")).recoverMerges { it == 7L }
            fail("Queue write failure must remain visible")
        } catch (_: IllegalStateException) { }
        assertTrue(journal().all.isNotEmpty())
        PlaybackTrackMerges(isolated).recoverMerges { it == 7L }
        assertRepairedQueue(7)
        instrumentation.waitForIdleSync()
        assertTrue(journal().all.isEmpty())
    }

    @Test
    fun failedRecoveryIntentWriteRollsBackTheCatalogMerge() {
        val follower = PlaybackTrackMerges(failingCommit("playback-track-merges"))
        LibraryCatalogStore(context, databaseName, mergeFollower = follower).use { store ->
            val (from, into) = seedUnmatched(store)
            try {
                store.assignUnmatchedRecord(into, from)
                fail("Without durable recovery the catalog must not commit")
            } catch (_: IllegalStateException) { }
            assertTrue(store.readTrackIdsForSweep().trackIds.contains(from))
            assertTrue(store.readUnmatchedRecords().containsKey(into))
        }
    }

    @Test
    fun rolledBackRecoveryCannotAcknowledgeAnIdenticalLaterRetry() {
        val follower = PlaybackTrackMerges(isolated)
        instrumentation.runOnMainSync {
            follower.prepareCommit(listOf(2L to 7L))
            follower.recoverMerges { it == 2L || it == 7L }
            // The catalog is open; the same merge is retried, then commits without a notification.
            follower.prepareCommit(listOf(2L to 7L))
        }
        instrumentation.waitForIdleSync()
        assertEquals(7L, journal().getLong("2", -1))
    }

    @Test
    fun catalogRebuildInvalidatesQueuedMergeNotificationsAndTheJournal() {
        saveQueue(2)
        val follower = PlaybackTrackMerges(isolated)
        instrumentation.runOnMainSync {
            follower.prepareCommit(listOf(2L to 7L))
            follower.mergeCommitted(2, 7)
            follower.forgetMerges()
        }
        instrumentation.waitForIdleSync()
        assertTrue(journal().all.isEmpty())
        assertEquals(2L, PlaybackStateStore(isolated).load()!!.currentTrackId)
        assertEquals(2L, follower.mergedTrackId(2))
    }

    private fun failingCommit(preferenceName: String): Context = object : ContextWrapper(isolated) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val actual = isolated.getSharedPreferences(name, mode)
            if (name != preferenceName) return actual
            return object : SharedPreferences by actual {
                override fun edit(): SharedPreferences.Editor {
                    val editor = actual.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit(): Boolean = false
                    }
                }
            }
        }
    }

    private fun journal() = isolated.getSharedPreferences("playback-track-merges", Context.MODE_PRIVATE)

    private fun saveQueue(id: Long) {
        PlaybackStateStore(isolated).apply {
            save(PersistedPlaybackState(listOf(id, id), id, 0, true, PlaybackRepeatMode.ONE,
                currentQueueIndex = 1, queueEnded = false, countedOccurrenceId = "counted", playOrder = listOf(1, 0)))
            saveProgress(id, 12_345, true, PlaybackRepeatMode.ONE, 1, false, "counted")
            holdBack(setOf(id))
        }
    }

    private fun assertRepairedQueue(id: Long) {
        val store = PlaybackStateStore(isolated)
        val saved = store.load()!!
        assertEquals(listOf(id, id), saved.queueTrackIds)
        assertEquals(id, saved.currentTrackId)
        assertEquals(1, saved.currentQueueIndex)
        assertEquals(12_345L, saved.positionMs)
        assertEquals(listOf(1, 0), saved.playOrder)
        assertEquals("counted", saved.countedOccurrenceId)
        assertEquals(CountedPlayback(id, "counted"), store.loadCountedPlayback())
        assertEquals(id.toString(), isolated.getSharedPreferences("playback-state", 0).getString("held_back_track_ids", null))
    }

    // Android 9's ambiguous file keys produce the same unmatched records as #139's existing tests.
    private fun seedUnmatched(store: LibraryCatalogStore): Pair<Long, Long> {
        fun candidate(id: Long, title: String) = LibraryTrackCandidate(
            remoteId = mediaStoreRemoteId("external", id), contentUri = "content://media/external/audio/media/$id",
            title = title, artist = "Artist", album = title, durationMs = if (title == "A") 200_000 else 300_000,
            sizeBytes = 10_000, fileName = "01.flac", folderName = "CD1", fingerprint = "$id-$title",
        )
        fun scan(first: Long, second: Long) {
            val scan = store.beginMediaStoreScan(pathIdentifiesFile = false)
            listOf(candidate(first, "A"), candidate(second, "B")).forEach { track ->
                if (scan.firstOffer(track) && !store.markSeenIfUnchanged(scan, track)) store.upsertMediaStoreTrack(scan, track)
            }
            store.completeMediaStoreScan(scan.session, null, listOf("external"))
        }
        store.ensureDeviceSource()
        scan(1, 2)
        val record = store.readTracks(listOf(LibrarySourceId.DEVICE)).first { it.title == "A" }
        store.setFavorite(record.id, true)
        scan(3, 4)
        scan(3, 4)
        val listedId = store.readUnmatchedRecords().getValue(record.id).first()
        return listedId to record.id
    }

    private class InterruptedCommit : RuntimeException()
}
