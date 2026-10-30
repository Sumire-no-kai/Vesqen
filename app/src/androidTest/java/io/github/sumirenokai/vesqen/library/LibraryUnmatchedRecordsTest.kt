package io.github.sumirenokai.vesqen.library

import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.lyrics.LyricsStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #139: records the scans cannot give to one file wait hidden until the user says where they belong. */
class LibraryUnmatchedRecordsTest {
    // Before Android 10 a row knows only its parent directory's name, so Album A/CD1/01.flac and
    // Album B/CD1/01.flac of the same size have one file key.
    private val aira = Android9File(title = "Aira", album = "Album A")
    private val tide = Android9File(title = "Tide", album = "Album B")

    @Test
    fun twoRowsWithOneFileKeyWaitHiddenAndFollowTheChoice() = withStore("library-unmatched-choice") { store ->
        store.scan(aira.at(1), tide.at(2))
        val a = store.track("Aira")
        val b = store.track("Tide")
        store.setFavorite(a.id, true)
        store.recordPlayback(b.id, playedAtMs = 100)
        val playlistId = requireNotNull(store.createPlaylist("Mix"))
        store.addTrackToPlaylist(playlistId, a.id)
        store.addTrackToPlaylist(playlistId, b.id)

        // MediaStore was rebuilt and lists both files again under new _IDs.
        store.scan(aira.at(3), tide.at(4))

        assertEquals(setOf(a.id, b.id), store.readDisplacedTrackIds())
        // A rebuilt MediaStore may still be listing its files, so one scan is not enough to ask.
        assertTrue(store.readUnmatchedRecords().isEmpty())

        store.scan(aira.at(3), tide.at(4))

        val listed = store.readTracks(listOf(LibrarySourceId.DEVICE)).filterNot { it.id in setOf(a.id, b.id) }
        val newAira = listed.single { it.title == "Aira" }
        val newTide = listed.single { it.title == "Tide" }
        assertEquals(
            mapOf(a.id to setOf(newAira.id, newTide.id), b.id to setOf(newAira.id, newTide.id)),
            store.readUnmatchedRecords().mapValues { it.value.toSet() },
        )

        // The user gives A's record to the new Aira and discards B's.
        assertTrue(store.assignUnmatchedRecord(a.id, newAira.id))
        assertTrue(store.discardUnmatchedRecord(b.id))

        val after = store.readTracks(listOf(LibrarySourceId.DEVICE)).associateBy(AudioTrack::id)
        assertEquals(setOf(a.id, newTide.id), after.keys)
        assertEquals(aira.at(3).contentUri, after.getValue(a.id).contentUri)
        assertTrue(after.getValue(a.id).isFavorite)
        assertFalse(after.getValue(newTide.id).isFavorite)
        assertEquals(0, after.getValue(newTide.id).playCount)
        assertEquals(listOf(a.id), store.readPlaylists().single().trackIds)
        assertTrue(store.readDisplacedTrackIds().isEmpty())
        assertTrue(store.readUnmatchedRecords().isEmpty())

        // The record now names its file, so the next scan keeps it there.
        store.scan(aira.at(3), tide.at(4))

        assertEquals(a.id, store.track("Aira").id)
        assertTrue(store.track("Aira").isFavorite)
    }

    @Test
    fun aChosenSongsOwnDataJoinsTheRecord() = withStore("library-unmatched-merge") { store ->
        store.scan(aira.at(1), tide.at(2))
        val a = store.track("Aira")
        val b = store.track("Tide")
        store.recordPlayback(a.id, playedAtMs = 100)
        store.setFavorite(b.id, true)
        store.scan(aira.at(3), tide.at(4))
        store.scan(aira.at(3), tide.at(4))
        val newAira = store.readTracks(listOf(LibrarySourceId.DEVICE)).single { it.title == "Aira" && it.id != a.id }
        // The new row was played and put in a playlist before the user chose.
        store.recordPlayback(newAira.id, playedAtMs = 300)
        store.recordPlayback(newAira.id, playedAtMs = 400)
        val playlistId = requireNotNull(store.createPlaylist("New"))
        store.addTrackToPlaylist(playlistId, newAira.id)

        assertTrue(store.assignUnmatchedRecord(a.id, newAira.id))

        val merged = store.readTracks(listOf(LibrarySourceId.DEVICE)).single { it.id == a.id }
        assertEquals(3, merged.playCount)
        assertEquals(400, merged.lastPlayedAtMs)
        assertEquals(listOf(a.id), store.readPlaylists().single().trackIds)
        // B's record still waits; its songs are now A's record and the new Tide.
        assertEquals(setOf(b.id), store.readUnmatchedRecords().keys)
        // A song that is no longer one of the record's choices is refused without a change.
        assertFalse(store.assignUnmatchedRecord(b.id, newAira.id))
        assertFalse(store.discardUnmatchedRecord(a.id))
    }

    /**
     * #169: imported lyrics follow the choice. The record keeps its own; the chosen song's move over
     * only when the record has none, copied before the merge commits. The clean-up's ID read
     * includes hidden records, so their lyrics wait with them.
     */
    @Test
    fun importedLyricsFollowTheChoice() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "lyrics-${System.nanoTime()}")
        val lyrics = LyricsStore(directory)
        try {
            withStore("library-unmatched-lyrics", lyrics) { store ->
                store.scan(aira.at(1), tide.at(2))
                val a = store.track("Aira")
                val b = store.track("Tide")
                store.setFavorite(a.id, true)
                store.setFavorite(b.id, true)
                lyrics.import(b.id, "[00:01.00]Tide's own".byteInputStream(), lyrics.generation())
                store.scan(aira.at(3), tide.at(4))
                store.scan(aira.at(3), tide.at(4))
                val listed = store.readTracks(listOf(LibrarySourceId.DEVICE)).filterNot { it.id in setOf(a.id, b.id) }
                val newAira = listed.single { it.title == "Aira" }
                val newTide = listed.single { it.title == "Tide" }
                lyrics.import(newAira.id, "[00:01.00]Aira, imported on the new row".byteInputStream(), lyrics.generation())
                lyrics.import(newTide.id, "[00:01.00]Tide, imported on the new row".byteInputStream(), lyrics.generation())
                assertTrue(store.readTrackIdsForSweep().trackIds.containsAll(listOf(a.id, b.id, newAira.id, newTide.id)))

                assertTrue(store.assignUnmatchedRecord(a.id, newAira.id))
                assertTrue(store.assignUnmatchedRecord(b.id, newTide.id))

                assertEquals("[00:01.00]Aira, imported on the new row", lyrics.read(a.id)?.decodeToString())
                assertEquals("[00:01.00]Tide's own", lyrics.read(b.id)?.decodeToString())
                assertEquals(setOf(a.id, b.id), lyrics.importedLyrics.value.trackIds)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class Android9File(val title: String, val album: String) {
        fun at(mediaId: Long): LibraryTrackCandidate {
            val contentUri = "content://media/external/audio/media/$mediaId"
            return LibraryTrackCandidate(
                remoteId = mediaStoreRemoteId("external", mediaId),
                contentUri = contentUri,
                title = title,
                artist = "Artist",
                album = album,
                durationMs = if (title == "Aira") 200_000 else 300_000,
                sizeBytes = 10_000,
                fileName = "01.flac",
                folderName = "CD1",
                fingerprint = libraryFingerprint(contentUri, title, album),
            )
        }
    }

    /** Runs a complete device scan the way `AndroidLibraryCatalog` does on Android 9. */
    private fun LibraryCatalogStore.scan(vararg candidates: LibraryTrackCandidate) {
        val scan = beginMediaStoreScan(pathIdentifiesFile = false)
        candidates.forEach { candidate ->
            if (scan.firstOffer(candidate) && !markSeenIfUnchanged(scan, candidate)) upsertMediaStoreTrack(scan, candidate)
        }
        completeMediaStoreScan(scan.session, generation = null, scannedVolumeNames = listOf("external"))
    }

    private fun LibraryCatalogStore.track(title: String): AudioTrack {
        val hidden = readDisplacedTrackIds()
        return readTracks(listOf(LibrarySourceId.DEVICE)).single { it.title == title && it.id !in hidden }
    }

    private fun withStore(
        name: String,
        mergeFollower: TrackMergeFollower = TrackMergeFollower.None,
        block: (LibraryCatalogStore) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "$name-${System.nanoTime()}.db"
        try {
            LibraryCatalogStore(context, databaseName, mergeFollower = mergeFollower).use { store ->
                store.ensureDeviceSource()
                block(store)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
