package io.github.sumirenokai.vesqen.library

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #249: what the whole-storage scan reads, and what it leaves alone. */
@RunWith(RobolectricTestRunner::class)
class StorageAudioScannerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `it reports supported audio with the cover beside it and skips what the owner excluded`() = runBlocking {
        val root = temporary.newFolder("primary")
        root.write("Music/Album/01 Dawn.flac", "Music/Album/cover.jpg", "Music/Album/01 Dawn.lrc", "Music/Album/notes.txt")
        // macOS leaves "._" copies beside music; they are not music.
        root.write("Music/Album/._01 Dawn.flac", "Music/.hidden/a.flac")
        root.write("Podcasts/.nomedia", "Podcasts/episode.mp3", "Podcasts/Old/b.mp3")
        root.write("Android/data/app/c.flac", "Music/Android/d.flac", "Music/old.ape")
        val found = mutableListOf<StorageAudioFile>()
        val skipped = mutableListOf<UnsupportedAudioFormat>()

        val result = StorageAudioScanner().scan(listOf(StorageVolumeRoot("primary", root)), shouldPause = { false }, onSkippedAudio = { skipped += it }) {
            found += it
        }

        assertTrue(result.completed)
        assertEquals(listOf("Music/Album/01 Dawn.flac", "Music/Android/d.flac"), found.map { it.file.relativeTo(root).path }.sorted())
        val dawn = found.single { it.file.name == "01 Dawn.flac" }
        assertEquals("Music/Album", dawn.folderName)
        assertEquals("primary:music/album/01 dawn.flac", dawn.locationKey)
        assertEquals(File(root, "Music/Album/cover.jpg"), dawn.albumArtwork)
        assertEquals(listOf(UnsupportedAudioFormat.APE), skipped)
        val candidate = dawn.toTrackCandidate()
        assertEquals(dawn.file.path, candidate.remoteId)
        assertEquals("file://" + dawn.file.path.replace(" ", "%20"), candidate.contentUri)
    }

    @Test
    fun `a folder it cannot list keeps the scan from completing, after the others were read`() = runBlocking {
        val root = temporary.newFolder("primary")
        root.write("Music/a.flac", "Locked/b.flac")
        val locked = File(root, "Locked")
        assertTrue(locked.setReadable(false))
        val found = mutableListOf<String>()
        try {
            StorageAudioScanner().scan(listOf(StorageVolumeRoot("primary", root)), shouldPause = { false }) { found += it.file.name }
            fail("An unlisted folder must not let the scan complete")
        } catch (_: IncompleteLibraryProviderException) {
            assertEquals(listOf("a.flac"), found)
        } finally {
            locked.setReadable(true)
        }
    }

    @Test
    fun `a pause stops the walk without completing it`() = runBlocking {
        val root = temporary.newFolder("primary")
        root.write("Music/a.flac", "Music/b.flac")

        val result = StorageAudioScanner().scan(listOf(StorageVolumeRoot("primary", root)), shouldPause = { true }) { fail("Nothing is read while paused") }

        assertFalse(result.completed)
    }

    @Test
    fun `a whole-storage row plays only while its volume is mounted`() {
        val phone = File("/storage/emulated/0")
        assertTrue(storageFileIsMounted("file:///storage/emulated/0/Music/a.flac", listOf(phone)))
        assertFalse(storageFileIsMounted("file:///storage/1A2B-3C4D/Music/a.flac", listOf(phone)))
        // A volume whose path only starts like the mounted one's is another volume.
        assertFalse(storageFileIsMounted("file:///storage/emulated/01/a.flac", listOf(phone)))
    }

    @Test
    fun `one directory is one folder whichever source plays each of its files`() {
        val flac = AudioTrack(
            id = 1, contentUri = "content://media/external_primary/audio/media/1", title = "Dawn", artist = "", album = "", durationMs = 1,
            folderName = "Music/SACD", locationKey = "primary:music/sacd/dawn.flac",
        )
        val dsf = AudioTrack(
            id = 2, contentUri = "file:///storage/emulated/0/Music/SACD/Dusk.dsf", title = "Dusk", artist = "", album = "", durationMs = 1,
            folderName = "Music/SACD", locationKey = "primary:music/sacd/dusk.dsf",
        )

        val folders = buildLibraryCollections(LibraryBrowseMode.FOLDERS, listOf(flac, dsf), emptyList(), String.CASE_INSENSITIVE_ORDER)

        assertEquals(listOf(listOf(1L, 2L)), folders.map { folder -> folder.tracks.map(AudioTrack::id).sorted() })
    }

    private fun File.write(vararg paths: String) = paths.forEach { path ->
        File(this, path).apply { parentFile?.mkdirs() }.writeText("x")
    }
}
