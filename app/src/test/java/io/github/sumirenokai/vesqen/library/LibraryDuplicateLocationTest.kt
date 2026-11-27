package io.github.sumirenokai.vesqen.library

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #359: a song in an added folder that MediaStore lists as well was two catalog rows and showed
 * twice. One file is now one row, whichever source found it, with one ID and the user's data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryDuplicateLocationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val databaseName = "duplicate-location-${System.nanoTime()}.db"
    private val merges = mutableListOf<Pair<Long, Long>>()
    private val follower = object : TrackMergeFollower {
        override fun prepareMerge(from: Long, into: Long) = Unit
        override fun mergeCommitted(from: Long, into: Long) {
            merges += from to into
        }
        override fun mergeRolledBack(from: Long, into: Long) = Unit
    }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `one file has one key from MediaStore, Android's storage provider and a legacy path`() {
        val phone = "primary:music/album/01 dawn.flac"
        assertEquals(phone, mediaStoreLocationKey("external_primary", "Music/Album/", "01 Dawn.flac"))
        assertEquals(phone, documentLocationKey(EXTERNAL_STORAGE_AUTHORITY, "primary:Music/Album/01 Dawn.flac"))
        assertEquals(phone, legacyDataLocationKey("/storage/emulated/0/Music/Album/01 Dawn.flac"))
        val card = "1a2b-3c4d:music/a.dsf"
        assertEquals(card, mediaStoreLocationKey("1a2b-3c4d", "Music", "a.dsf"))
        assertEquals(card, documentLocationKey(EXTERNAL_STORAGE_AUTHORITY, "1A2B-3C4D:Music/a.dsf"))
        assertEquals(card, legacyDataLocationKey("/storage/1A2B-3C4D/Music/a.dsf"))

        // No volume, another provider, the Documents view and an empty name give no key.
        assertNull(mediaStoreLocationKey("external", "Music", "a.flac"))
        assertNull(mediaStoreLocationKey("external_primary", "Music", ""))
        assertNull(documentLocationKey("com.example.cloud", "primary:Music/a.flac"))
        assertNull(documentLocationKey(EXTERNAL_STORAGE_AUTHORITY, "home:a.flac"))
        assertNull(documentLocationKey(EXTERNAL_STORAGE_AUTHORITY, "primary:"))
        assertNull(legacyDataLocationKey("/storage/emulated/0/"))
        assertNull(legacyDataLocationKey("/data/media/a.flac"))
    }

    @Test
    fun `an added folder's key prefix covers exactly its own files`() {
        assertEquals("primary:music/", documentTreeLocationPrefix(tree("primary:Music")))
        assertEquals("primary:", documentTreeLocationPrefix(tree("primary:")))
        assertEquals("1a2b-3c4d:music/jazz/", documentTreeLocationPrefix(tree("1A2B-3C4D:Music/Jazz")))
        assertNull(documentTreeLocationPrefix(DocumentsContract.buildTreeDocumentUri("com.example.cloud", "root")))

        val tracks = listOf("primary:music/a.flac", "primary:music/jazz/b.flac", "primary:musical/c.flac", null)
            .mapIndexed { index, key ->
                AudioTrack(id = index.toLong(), contentUri = "content://x/$index", title = "T", artist = "", album = "", durationMs = 1, locationKey = key)
            }
        assertEquals(2, folderTrackCount(tree("primary:Music").toString(), tracks))
        assertEquals(1, folderTrackCount(tree("primary:Music/Jazz").toString(), tracks))
        assertNull(folderTrackCount(DocumentsContract.buildTreeDocumentUri("com.example.cloud", "root").toString(), tracks))
    }

    @Test
    fun `existing duplicates of a MediaStore song and a folder song become one row with both users' data`() = withStore { store ->
        // The state every catalog before #359 reached: each source wrote its own row for the file.
        scanDevice(store, deviceSong(1, "Dawn"))
        val folder = addFolder(store, "primary:Music")
        val session = store.beginSourceScan(folder)
        store.upsertTrack(session, folderSong(folder, "primary:Music/Dawn.flac", "Dawn"))
        store.completeSourceScan(session)
        val tracks = store.allTracks()
        val device = tracks.single { it.contentUri.startsWith("content://media/") }
        val copy = tracks.single { it.id != device.id }
        store.setFavorite(device.id, true)
        store.recordPlayback(copy.id, playedAtMs = 100)
        store.recordPlayback(copy.id, playedAtMs = 300)
        val mix = requireNotNull(store.createPlaylist("Mix"))
        store.addTrackToPlaylist(mix, copy.id)

        assertEquals(1, store.mergeDuplicateLocations { _, _ -> true })

        // The added folder holds its own files, so its row stays and takes the device row's data.
        val kept = store.allTracks().single()
        assertEquals(copy.id, kept.id)
        assertEquals(copy.contentUri, kept.contentUri)
        assertTrue(kept.isFavorite)
        assertEquals(2, kept.playCount)
        assertEquals(300, kept.lastPlayedAtMs)
        assertEquals(listOf(copy.id), store.readPlaylists().single().trackIds)
        // The saved queue, listening history and imported lyrics follow the merge.
        assertEquals(listOf(device.id to copy.id), merges)
        assertEquals(0, store.mergeDuplicateLocations { _, _ -> true })
    }

    @Test
    fun `a duplicate whose source cannot play now is left as it is`() = withStore { store ->
        scanDevice(store, deviceSong(1, "Dawn"))
        val folder = addFolder(store, "primary:Music")
        val session = store.beginSourceScan(folder)
        store.upsertTrack(session, folderSong(folder, "primary:Music/Dawn.flac", "Dawn"))
        store.completeSourceScan(session)

        assertEquals(0, store.mergeDuplicateLocations { sourceId, _ -> sourceId != folder })
        assertEquals(2, store.allTracks().size)
    }

    @Test
    fun `an added folder takes over MediaStore's row for its file, with its ID and data`() = withStore { store ->
        scanDevice(store, deviceSong(1, "Dawn"))
        val device = store.allTracks().single()
        store.setFavorite(device.id, true)
        val folder = addFolder(store, "primary:Music")
        val session = store.beginSourceScan(folder)
        val owners = store.readLocationOwners(folder) { _, _ -> true }
        store.writeFolderTrack(session, "primary:Music/Dawn.flac", "primary:music/dawn.flac", owners) {
            folderSong(folder, "primary:Music/Dawn.flac", "Dawn")
        }
        store.completeSourceScan(session)

        val kept = store.readTracks(listOf(folder)).single()
        assertEquals(device.id, kept.id)
        assertTrue(kept.isFavorite)
        assertTrue(kept.contentUri.startsWith("content://$EXTERNAL_STORAGE_AUTHORITY/tree/"))
        assertTrue(store.readTracks(listOf(LibrarySourceId.DEVICE)).isEmpty())
    }

    @Test
    fun `a folder leaves a file another added folder plays to it, and takes it when that folder cannot`() = withStore { store ->
        val music = addFolder(store, "primary:Music")
        val first = store.beginSourceScan(music)
        store.upsertTrack(first, folderSong(music, "primary:Music/Jazz/Blue.flac", "Blue"))
        store.completeSourceScan(first)
        val blue = store.allTracks().single()
        val jazz = addFolder(store, "primary:Music/Jazz")
        var read = 0
        fun scanJazz(musicPlays: Boolean) {
            val session = store.beginSourceScan(jazz)
            val owners = store.readLocationOwners(jazz) { sourceId, _ -> musicPlays || sourceId != music }
            store.writeFolderTrack(session, "primary:Music/Jazz/Blue.flac", "primary:music/jazz/blue.flac", owners) {
                read++
                folderSong(jazz, "primary:Music/Jazz/Blue.flac", "Blue")
            }
            store.completeSourceScan(session)
        }

        scanJazz(musicPlays = true)
        assertEquals(0, read)
        assertEquals(listOf(blue.id), store.readTracks(listOf(music)).map(AudioTrack::id))

        // Android no longer grants the first folder.
        scanJazz(musicPlays = false)
        assertEquals(1, read)
        assertEquals(listOf(blue.id), store.readTracks(listOf(jazz)).map(AudioTrack::id))
        assertTrue(store.readTracks(listOf(music)).isEmpty())
    }

    @Test
    fun `MediaStore leaves a file an added folder plays to it and does not read it`() = withStore { store ->
        val folder = addFolder(store, "primary:Music")
        val session = store.beginSourceScan(folder)
        store.upsertTrack(session, folderSong(folder, "primary:Music/Dawn.flac", "Dawn"))
        store.completeSourceScan(session)
        val fromFolder = store.allTracks().single()

        var read = 0
        scanDevice(store, deviceSong(1, "Dawn"), deviceSong(2, "Dusk"), owners = store.readLocationOwners(LibrarySourceId.DEVICE) { _, _ -> true }) {
            read++
            it
        }

        assertEquals(1, read)
        assertEquals(fromFolder.id, store.readTracks(listOf(folder)).single().id)
        assertEquals(listOf("Dusk"), store.readTracks(listOf(LibrarySourceId.DEVICE)).map(AudioTrack::title))
    }

    @Test
    fun `MediaStore takes over the row of a folder Android no longer grants, and its scan keeps it`() = withStore { store ->
        val folder = addFolder(store, "primary:Music")
        val session = store.beginSourceScan(folder)
        store.upsertTrack(session, folderSong(folder, "primary:Music/Dusk.dsf", "Dusk"))
        store.completeSourceScan(session)
        val fromFolder = store.allTracks().single()
        store.recordPlayback(fromFolder.id, playedAtMs = 100)

        val listed = deviceSong(7, "Dusk", fileName = "Dusk.dsf")
        scanDevice(store, listed, owners = store.readLocationOwners(LibrarySourceId.DEVICE) { sourceId, _ -> sourceId != folder })

        val kept = store.allTracks().single()
        assertEquals(fromFolder.id, kept.id)
        assertEquals(listed.contentUri, kept.contentUri)
        assertEquals(1, kept.playCount)
        assertEquals(1, store.readTracks(listOf(LibrarySourceId.DEVICE)).size)
        assertTrue(merges.isEmpty())
    }

    @Test
    fun `removing a folder gives the files MediaStore lists to the device library, with their data`() = withStore { store ->
        val folder = addFolder(store, "primary:Music")
        val session = store.beginSourceScan(folder)
        store.upsertTrack(session, folderSong(folder, "primary:Music/Dawn.flac", "Dawn").copy(albumArtworkUri = "content://tree/cover.jpg"))
        store.upsertTrack(session, folderSong(folder, "primary:Music/Dusk.dsf", "Dusk"))
        store.completeSourceScan(session)
        val dawn = store.allTracks().single { it.title == "Dawn" }
        store.setFavorite(dawn.id, true)
        val listed = deviceSong(1, "Dawn")

        store.removeFolderSource(folder, listedDeviceFiles = listOf(listed.listed()))

        val kept = store.allTracks().single()
        assertEquals(dawn.id, kept.id)
        assertEquals(listed.contentUri, kept.contentUri)
        assertTrue(kept.isFavorite)
        assertNull(kept.albumArtworkUri)
        // MediaStore's next scan finds it as the device library's own row.
        scanDevice(store, listed)
        assertEquals(dawn.id, store.readTracks(listOf(LibrarySourceId.DEVICE)).single().id)
    }

    @Test
    fun `removing a folder gives no data to another song MediaStore stored under the same ID`() = withStore { store ->
        // MediaStore's rebuilt database lists the folder's song under the `_ID` an older song had.
        scanDevice(store, deviceSong(5, "Other"))
        val other = store.allTracks().single()
        store.recordPlayback(other.id, playedAtMs = 100)
        val folder = addFolder(store, "primary:Music")
        val session = store.beginSourceScan(folder)
        store.upsertTrack(session, folderSong(folder, "primary:Music/Dawn.flac", "Dawn"))
        store.completeSourceScan(session)
        val dawn = store.readTracks(listOf(folder)).single()
        store.setFavorite(dawn.id, true)
        val renumbered = deviceSong(5, "Dawn")

        store.removeFolderSource(folder, listedDeviceFiles = listOf(renumbered.listed()))

        val rows = store.readTracks(listOf(LibrarySourceId.DEVICE)).associateBy(AudioTrack::id)
        assertEquals(renumbered.contentUri, rows.getValue(dawn.id).contentUri)
        assertTrue(rows.getValue(dawn.id).isFavorite)
        assertEquals(0, rows.getValue(dawn.id).playCount)
        // The other song keeps its own data and waits, hidden, for its own file.
        assertEquals(1, rows.getValue(other.id).playCount)
        assertEquals(setOf(other.id), store.readDisplacedTrackIds())
        assertTrue(merges.isEmpty())
    }

    @Test
    fun `MediaStore still writes its row for a file a folder holds when that row has no key yet`() =
        assertDeviceRowKeepsItsData(stored = deviceSong(1, "Dawn").copy(folderName = "Music/New", locationKey = null))

    @Test
    fun `MediaStore still writes its row for a file a folder holds when that row's key names its old place`() =
        assertDeviceRowKeepsItsData(
            stored = deviceSong(1, "Dawn").copy(folderName = "Music/Old", locationKey = mediaStoreLocationKey(VOLUME, "Music/Old", "Dawn.flac")),
        )

    @Test
    fun `an unchanged MediaStore is listed only while a row waits for it to take over`() = runBlocking {
        var listings = 0
        val listed = listOf(deviceSong(1, "Dawn").listed())
        val listFiles: suspend () -> List<ListedMediaStoreFile> = { listings++; listed }
        val folderPlays = mapOf("primary:music/dawn.flac" to LocationOwner(1, "tree:a", playable = true))
        assertFalse(mediaStoreListsUnplayableRow(folderPlays, listFiles))
        assertEquals(0, listings)

        // The folder's grant went: MediaStore lists the file, so the device scan must run.
        assertTrue(mediaStoreListsUnplayableRow(mapOf("primary:music/dawn.flac" to LocationOwner(1, "tree:a", playable = false)), listFiles))
        // A DSD file MediaStore does not list leaves the unchanged scan as it is.
        assertFalse(mediaStoreListsUnplayableRow(mapOf("primary:music/dusk.dsf" to LocationOwner(2, "tree:a", playable = false)), listFiles))
        assertEquals(2, listings)
    }

    @Test
    fun `an unchanged MediaStore row without a key gets one from the next scan`() = withStore { store ->
        // An Android 9 row upgraded to version 5 stores no key; the scan cache still finds it unchanged.
        scanDevice(store, deviceSong(1, "Dawn").copy(locationKey = null))
        assertNull(store.allTracks().single().locationKey)

        scanDevice(store, deviceSong(1, "Dawn"))

        assertEquals("primary:music/dawn.flac", store.allTracks().single().locationKey)
    }

    @Test
    fun `where two rows still share a location the one that plays is its owner`() = withStore { store ->
        scanDevice(store, deviceSong(1, "Dawn"))
        val folder = addFolder(store, "primary:Music")
        val session = store.beginSourceScan(folder)
        store.upsertTrack(session, folderSong(folder, "primary:Music/Dawn.flac", "Dawn"))
        store.completeSourceScan(session)
        val folderRow = store.readTracks(listOf(folder)).single()

        val owners = store.readLocationOwners("tree:other") { sourceId, _ -> sourceId == folder }

        assertEquals(LocationOwner(folderRow.id, folder, playable = true), owners.getValue("primary:music/dawn.flac"))
    }

    @Test
    fun `removing a folder hands the files another added folder holds to it`() = withStore { store ->
        val music = addFolder(store, "primary:Music")
        val jazz = addFolder(store, "primary:Music/Jazz")
        val session = store.beginSourceScan(music)
        val cover = "primary:Music/Jazz/cover.jpg"
        store.upsertTrack(
            session,
            folderSong(music, "primary:Music/Jazz/Blue.flac", "Blue")
                .copy(albumArtworkUri = DocumentsContract.buildDocumentUriUsingTree(tree("primary:Music"), cover).toString()),
        )
        store.upsertTrack(session, folderSong(music, "primary:Music/Pop.flac", "Pop"))
        store.completeSourceScan(session)
        val blue = store.allTracks().single { it.title == "Blue" }
        store.setFavorite(blue.id, true)

        store.removeFolderSource(music, readableTreeUris = setOf(tree("primary:Music/Jazz").toString()))

        val kept = store.readTracks(listOf(jazz)).single()
        assertEquals(blue.id, kept.id)
        assertTrue(kept.isFavorite)
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree("primary:Music/Jazz"), "primary:Music/Jazz/Blue.flac").toString(), kept.contentUri)
        // The cover beside it opens through the folder that still has a grant.
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree("primary:Music/Jazz"), cover).toString(), kept.albumArtworkUri)
        assertEquals(listOf("Blue"), store.allTracks().map(AudioTrack::title))
    }

    @Test
    fun `a row stored before #359 takes its key from what it holds`() {
        val device = "content://media/external_primary/audio/media/4"
        val q = Build.VERSION_CODES.Q
        assertEquals("primary:music/a.flac", LibraryCatalogStore.storedLocationKey(LibrarySourceId.DEVICE, "r", device, "Music", "a.flac", q))
        // Android 9 stored the folder's own name only.
        assertNull(LibraryCatalogStore.storedLocationKey(LibrarySourceId.DEVICE, "r", device, "Music", "a.flac", Build.VERSION_CODES.P))
        assertNull(LibraryCatalogStore.storedLocationKey(LibrarySourceId.DEVICE, "mediastore-displaced:4", device, "Music", "a.flac", q))
        val document = DocumentsContract.buildDocumentUriUsingTree(tree("primary:Music"), "primary:Music/a.flac").toString()
        assertEquals("primary:music/a.flac", LibraryCatalogStore.storedLocationKey("tree:x", "primary:Music/a.flac", document, "Music", "a.flac", q))
        assertNull(LibraryCatalogStore.storedLocationKey("tree:x", "folder-hidden:9", document, "Music", "a.flac", q))
    }

    @Test
    fun `upgrading a version 4 catalog keys its rows and then merges their duplicates`() {
        withStore { store ->
            scanDevice(store, deviceSong(1, "Dawn"))
            val folder = addFolder(store, "primary:Music")
            val session = store.beginSourceScan(folder)
            store.upsertTrack(session, folderSong(folder, "primary:Music/Dawn.flac", "Dawn"))
            store.completeSourceScan(session)
        }
        // Back to version 4: the same rows without the location column.
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("DROP INDEX index_library_tracks_location")
            database.execSQL("ALTER TABLE library_tracks DROP COLUMN location_key")
            database.version = 4
        }

        withStore { store ->
            assertEquals(listOf("primary:music/dawn.flac", "primary:music/dawn.flac"), store.allTracks().map(AudioTrack::locationKey))
            assertEquals(1, store.mergeDuplicateLocations { _, _ -> true })
        }
    }

    /**
     * A device row whose key does not show it holds the file (an Android 9 row from before #359, or a
     * file moved under the same `_ID`) beside a folder's row for that file: the scan must still write
     * the device row, so that its data joins the folder's instead of staying on a hidden row.
     */
    private fun assertDeviceRowKeepsItsData(stored: LibraryTrackCandidate) = withStore { store ->
        scanDevice(store, stored)
        store.setFavorite(store.allTracks().single().id, true)
        val folder = addFolder(store, "primary:Music/New")
        val session = store.beginSourceScan(folder)
        store.upsertTrack(session, folderSong(folder, "primary:Music/New/Dawn.flac", "Dawn"))
        store.completeSourceScan(session)
        val now = deviceSong(1, "Dawn").copy(
            folderName = "Music/New",
            locationKey = mediaStoreLocationKey(VOLUME, "Music/New", "Dawn.flac"),
            fingerprint = "media-1-edited",
        )

        scanDevice(store, now, owners = store.readLocationOwners(LibrarySourceId.DEVICE) { _, _ -> true })
        store.mergeDuplicateLocations { _, _ -> true }

        assertTrue(store.allTracks().single().isFavorite)
        assertTrue(store.readDisplacedTrackIds().isEmpty())
    }

    private fun withStore(block: (LibraryCatalogStore) -> Unit) {
        LibraryCatalogStore(context, databaseName, mergeFollower = follower).use { store ->
            store.ensureDeviceSource()
            block(store)
        }
    }

    private fun LibraryCatalogStore.allTracks(): List<AudioTrack> = readTracks(readSources().map(StoredLibrarySource::id))

    private fun addFolder(store: LibraryCatalogStore, treeId: String): String {
        val treeUri = tree(treeId).toString()
        store.upsertFolderSource(treeUri, treeId.substringAfterLast('/'))
        return LibrarySourceId.forTree(treeUri)
    }

    private fun tree(treeId: String): Uri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, treeId)

    private fun scanDevice(
        store: LibraryCatalogStore,
        vararg songs: LibraryTrackCandidate,
        owners: Map<String, LocationOwner> = emptyMap(),
        enrich: (LibraryTrackCandidate) -> LibraryTrackCandidate = { it },
    ) {
        val scan = store.beginMediaStoreScan(listedFiles = songs.map { it.listed() })
        songs.forEach { song ->
            if (!store.markSeenIfUnchanged(scan, song)) store.writeMediaStoreTrack(scan, song, owners) { enrich(song) }
        }
        store.completeMediaStoreScan(scan.session, generation = null, scannedVolumeNames = listOf(VOLUME))
    }

    private fun LibraryTrackCandidate.listed() = ListedMediaStoreFile(remoteId, contentUri, folderName, fileName, sizeBytes, locationKey)

    private fun deviceSong(mediaId: Long, title: String, fileName: String = "$title.flac") = LibraryTrackCandidate(
        remoteId = mediaStoreRemoteId(VOLUME, mediaId),
        contentUri = "content://media/$VOLUME/audio/media/$mediaId",
        title = title,
        artist = "Mori",
        album = "Quiet Rooms",
        durationMs = 1_000,
        sizeBytes = 1_000,
        fileName = fileName,
        folderName = "Music",
        fingerprint = "media-$mediaId",
        locationKey = mediaStoreLocationKey(VOLUME, "Music", fileName),
    )

    private fun folderSong(folder: String, documentId: String, title: String) = LibraryTrackCandidate(
        remoteId = documentId,
        contentUri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(folder.removePrefix("tree:")), documentId).toString(),
        title = title,
        artist = "Mori",
        album = "Quiet Rooms",
        durationMs = 1_000,
        sizeBytes = 1_000,
        fileName = documentId.substringAfterLast('/'),
        folderName = "Music",
        fingerprint = "tree-$documentId",
        locationKey = documentLocationKey(EXTERNAL_STORAGE_AUTHORITY, documentId),
    )

    private companion object {
        const val VOLUME = "external_primary"
    }
}
