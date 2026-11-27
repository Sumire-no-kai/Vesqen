package io.github.sumirenokai.vesqen.library

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #249: the whole-storage scan holds only the files no other source has (#359): an added folder and
 * MediaStore rank above it, and a removed card or drive keeps its rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageScanCatalogTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val databaseName = "storage-scan-${System.nanoTime()}.db"

    @After
    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `the storage scan leaves a file another source plays, unread, and takes one that cannot play`() = withStore { store ->
        scanDevice(store, deviceSong(1, "Dawn"))
        val device = store.readTracks(listOf(LibrarySourceId.DEVICE)).single()
        store.setFavorite(device.id, true)
        var read = 0

        scanStorage(store, storageSong("Music/Dawn.flac"), isPlayable = { _, _ -> true }) { read++ }
        assertEquals(0, read)
        assertTrue(store.readTracks(listOf(LibrarySourceId.STORAGE)).isEmpty())

        // Without the music permission the device library plays nothing.
        scanStorage(store, storageSong("Music/Dawn.flac"), isPlayable = { sourceId, _ -> sourceId != LibrarySourceId.DEVICE }) { read++ }
        assertEquals(1, read)
        val kept = store.readTracks(listOf(LibrarySourceId.STORAGE)).single()
        assertEquals(device.id, kept.id)
        assertTrue(kept.isFavorite)
    }

    @Test
    fun `an added folder and MediaStore take a file over from the storage scan, keeping its ID`() = withStore { store ->
        scanStorage(store, storageSong("Music/Dawn.flac"), storageSong("Music/Dusk.flac"), isPlayable = { _, _ -> true })
        val rows = store.readTracks(listOf(LibrarySourceId.STORAGE)).associateBy(AudioTrack::title)

        val folder = addFolder(store, "primary:Music")
        val session = store.beginSourceScan(folder)
        store.writeSourceTrack(session, "primary:Music/Dawn.flac", "primary:music/dawn.flac", store.readLocationOwners(folder) { _, _ -> true }) {
            folderSong(folder, "primary:Music/Dawn.flac", "Dawn")
        }
        store.completeSourceScan(session)
        scanDevice(store, deviceSong(2, "Dusk"), owners = store.readLocationOwners(LibrarySourceId.DEVICE) { _, _ -> true })

        assertEquals(rows.getValue("Dawn").id, store.readTracks(listOf(folder)).single().id)
        assertEquals(rows.getValue("Dusk").id, store.readTracks(listOf(LibrarySourceId.DEVICE)).single().id)
        assertTrue(store.readTracks(listOf(LibrarySourceId.STORAGE)).isEmpty())
    }

    @Test
    fun `a completed storage scan prunes only on the volumes it walked`() = withStore { store ->
        scanStorage(store, storageSong("Music/a.flac"), storageSong("Music/b.flac", volume = "1a2b-3c4d", root = "/storage/1A2B-3C4D"), isPlayable = { _, _ -> true })

        // The card is out: only the phone's storage is walked, and it no longer holds a.flac.
        val session = store.beginSourceScan(LibrarySourceId.STORAGE)
        store.completeStorageScan(session, listOf("primary"))

        assertEquals(listOf("b"), store.readTracks(listOf(LibrarySourceId.STORAGE)).map(AudioTrack::title))
    }

    @Test
    fun `removing a folder hands a file still on its volume to the storage scan`() = withStore { store ->
        val root = temporary.newFolder("primary")
        File(root, "Music").mkdirs()
        File(root, "Music/Dawn.flac").writeText("x")
        File(root, "Music/cover.jpg").writeText("x")
        store.ensureStorageSource()
        val folder = addFolder(store, "primary:Music")
        val session = store.beginSourceScan(folder)
        store.upsertTrack(
            session,
            folderSong(folder, "primary:Music/Dawn.flac", "Dawn")
                .copy(albumArtworkUri = DocumentsContract.buildDocumentUriUsingTree(tree("primary:Music"), "primary:Music/cover.jpg").toString()),
        )
        store.upsertTrack(session, folderSong(folder, "primary:Music/Gone.flac", "Gone"))
        store.completeSourceScan(session)
        val dawn = store.readTracks(listOf(folder)).single { it.title == "Dawn" }
        store.setFavorite(dawn.id, true)

        store.removeFolderSource(folder, storageRoots = mapOf("primary" to root))

        val kept = store.readTracks(listOf(LibrarySourceId.STORAGE)).single()
        assertEquals(dawn.id, kept.id)
        assertTrue(kept.isFavorite)
        assertEquals(Uri.fromFile(File(root, "Music/Dawn.flac")).toString(), kept.contentUri)
        assertEquals(Uri.fromFile(File(root, "Music/cover.jpg")).toString(), kept.albumArtworkUri)
    }

    @Test
    fun `a duplicate of the storage scan's row keeps MediaStore's`() = withStore { store ->
        scanStorage(store, storageSong("Music/Dawn.flac"), isPlayable = { _, _ -> false })
        val storage = store.readTracks(listOf(LibrarySourceId.STORAGE)).single()
        scanDevice(store, deviceSong(1, "Dawn"))
        // Both rows hold the file only when the device row was written beside it, as before #359.
        assertEquals(2, store.readTracks(listOf(LibrarySourceId.DEVICE, LibrarySourceId.STORAGE)).size)

        store.mergeDuplicateLocations { _, _ -> true }

        val kept = store.readTracks(listOf(LibrarySourceId.DEVICE, LibrarySourceId.STORAGE)).single()
        assertTrue(kept.contentUri.startsWith("content://media/"))
        assertTrue(kept.id != storage.id)
    }

    @Test
    fun `removing the storage source keeps what MediaStore lists and drops the rest`() = withStore { store ->
        scanStorage(store, storageSong("Music/Dawn.flac"), storageSong("Music/Dusk.dsf"), isPlayable = { _, _ -> true })
        val rows = store.readTracks(listOf(LibrarySourceId.STORAGE)).associateBy(AudioTrack::title)
        store.setFavorite(rows.getValue("Dawn").id, true)

        assertTrue(store.removeStorageSource(listOf(deviceSong(1, "Dawn").listed())))

        val kept = store.readTracks(listOf(LibrarySourceId.DEVICE)).single()
        assertEquals(rows.getValue("Dawn").id, kept.id)
        assertTrue(kept.isFavorite)
        assertTrue(store.readSources().none { it.kind == LibrarySourceKind.STORAGE })
    }

    private fun withStore(block: (LibraryCatalogStore) -> Unit) {
        LibraryCatalogStore(context, databaseName).use { store ->
            store.ensureDeviceSource()
            block(store)
        }
    }

    private fun scanStorage(
        store: LibraryCatalogStore,
        vararg files: StorageAudioFile,
        isPlayable: (String, String) -> Boolean,
        onRead: () -> Unit = {},
    ) {
        store.ensureStorageSource()
        val session = store.beginSourceScan(LibrarySourceId.STORAGE)
        val owners = store.readLocationOwners(LibrarySourceId.STORAGE, isPlayable)
        files.forEach { file ->
            if (!store.markSeenIfFingerprintMatches(session, file.path, file.fingerprint)) {
                store.writeSourceTrack(session, file.path, file.locationKey, owners) {
                    onRead()
                    file.toTrackCandidate()
                }
            }
        }
        store.completeStorageScan(session, files.map(StorageAudioFile::volumeId).distinct())
    }

    private fun storageSong(path: String, volume: String = "primary", root: String = "/storage/emulated/0") = StorageAudioFile(
        file = File(root, path),
        volumeId = volume,
        folderName = path.substringBeforeLast('/'),
        lastModifiedMs = 1_000,
        sizeBytes = 1_000,
    )

    private fun scanDevice(store: LibraryCatalogStore, vararg songs: LibraryTrackCandidate, owners: Map<String, LocationOwner> = emptyMap()) {
        val scan = store.beginMediaStoreScan(listedFiles = songs.map { it.listed() })
        songs.forEach { song -> if (!store.markSeenIfUnchanged(scan, song)) store.writeMediaStoreTrack(scan, song, owners) { song } }
        store.completeMediaStoreScan(scan.session, generation = null, scannedVolumeNames = listOf(VOLUME))
    }

    private fun LibraryTrackCandidate.listed() = ListedMediaStoreFile(remoteId, contentUri, folderName, fileName, sizeBytes, locationKey)

    private fun deviceSong(mediaId: Long, title: String) = LibraryTrackCandidate(
        remoteId = mediaStoreRemoteId(VOLUME, mediaId),
        contentUri = "content://media/$VOLUME/audio/media/$mediaId",
        title = title,
        artist = "",
        album = "",
        durationMs = 1_000,
        sizeBytes = 1_000,
        fileName = "$title.flac",
        folderName = "Music",
        fingerprint = "media-$mediaId",
        locationKey = mediaStoreLocationKey(VOLUME, "Music", "$title.flac"),
    )

    private fun addFolder(store: LibraryCatalogStore, treeId: String): String {
        val treeUri = tree(treeId).toString()
        store.upsertFolderSource(treeUri, treeId.substringAfterLast('/'))
        return LibrarySourceId.forTree(treeUri)
    }

    private fun tree(treeId: String): Uri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, treeId)

    private fun folderSong(folder: String, documentId: String, title: String) = LibraryTrackCandidate(
        remoteId = documentId,
        contentUri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(folder.removePrefix("tree:")), documentId).toString(),
        title = title,
        artist = "",
        album = "",
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
