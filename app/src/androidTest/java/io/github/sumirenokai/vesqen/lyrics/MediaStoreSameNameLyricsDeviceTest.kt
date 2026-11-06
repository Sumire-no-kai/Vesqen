package io.github.sumirenokai.vesqen.lyrics

import android.net.Uri
import android.provider.DocumentsContract
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.testing.FakeDocumentsProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #208: a MediaStore track finds its same-name `.lrc` through an added folder that covers it. The
 * track keeps its own URI and ID. The covering lookup is faked here, because no test can hold a
 * real grant; `SameNameLyricsDeviceTest` covers the real one on a phone with the QA folder.
 */
class MediaStoreSameNameLyricsDeviceTest {
    private val app = isolatedPlaybackTestApplication()
    private val directory = File(app.cacheDir, "media-store-lyrics-${System.nanoTime()}")
    private val audio = File(directory, "song.flac")
    private val tree = DocumentsContract.buildTreeDocumentUri(
        FakeDocumentsProvider.authority(app.packageName),
        FakeDocumentsProvider.FOLDER,
    )
    private var covering: (Uri) -> Uri? = { document("music/song.flac") }
    private val store = LyricsStore(File(directory, "imported"))
    private val resolver = LocalLyricsResolver(app.contentResolver, store) { covering(it) }
    private val track by lazy {
        AudioTrack(
            id = 9_101,
            contentUri = Uri.fromFile(audio).toString(),
            title = "A title unlike the file",
            artist = "",
            album = "",
            durationMs = 60_000,
            fileName = "song.flac",
            fileSizeBytes = audio.length(),
        )
    }

    private fun document(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** An ID3v2.3 tag with one Latin-1 USLT frame, before some silent audio bytes. */
    private fun withEmbeddedLyrics(text: String): ByteArray {
        val body = byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        val frame = "USLT".toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0) + body
        check(frame.size < 0x80) { "The tag size below is one syncsafe byte" }
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, frame.size.toByte()) + frame + ByteArray(1_024)
    }

    @Before
    fun setUp() {
        directory.mkdirs()
        audio.writeBytes(withEmbeddedLyrics("[00:01.00]Embedded in the song"))
        FakeDocumentsProvider.files["music/song.flac"] = ByteArray(0)
    }

    @After
    fun reset() {
        FakeDocumentsProvider.reset()
        directory.deleteRecursively()
    }

    private suspend fun source() = (resolver.load(track) as TrackLyrics.Found).source

    @Test
    fun anImportNeedsNoLookupOfTheFolder() = runBlocking {
        var lookups = 0
        covering = {
            lookups++
            error("Not asked for")
        }
        store.import(track.id, "[00:01.00]Imported".toByteArray(), store.generation())

        assertEquals(LyricsSource.Imported, source())
        assertEquals(0, lookups)
    }

    @Test
    fun aCoveredTrackReadsTheFileBesideIt() = runBlocking {
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()

        assertEquals(LyricsSource.SameNameFile, source())
    }

    @Test
    fun theFileNameDecidesInTheTracksOwnFolder() = runBlocking {
        FakeDocumentsProvider.files["other/song.lrc"] = "[00:01.00]In another folder".toByteArray()
        FakeDocumentsProvider.files["music/A title unlike the file.lrc"] = "[00:01.00]Named after the title".toByteArray()

        assertEquals(LyricsSource.Embedded, source())
    }

    @Test
    fun theImportStillComesFirst() = runBlocking {
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()
        store.import(track.id, "[00:01.00]Imported".toByteArray(), store.generation())

        assertEquals(LyricsSource.Imported, source())
    }

    @Test
    fun noCoveringGrantSaysWhereSameNameFilesAreRead() = runBlocking {
        covering = { throw SecurityException("No grant covers this file") }
        audio.writeBytes(ByteArray(0))

        assertEquals(TrackLyrics.None(folderHint = true), resolver.load(track))
    }

    @Test
    fun aLookupThatFailsForNowIsTriedAgainAndARefusalIsKept() = runBlocking {
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()
        covering = { throw IllegalStateException("The media provider restarted") }
        assertEquals(LyricsSource.Embedded, source())

        covering = { document("music/song.flac") }
        assertEquals(LyricsSource.SameNameFile, source())

        // A refusal is an answer: it stays until the next refresh, as a complete listing does.
        resolver.clearCache()
        covering = { throw SecurityException("No grant covers this file") }
        assertEquals(LyricsSource.Embedded, source())
        covering = { document("music/song.flac") }
        assertEquals(LyricsSource.Embedded, source())
    }

    @Test
    fun aCoveredTrackWithoutLyricsHasNoHint() = runBlocking {
        audio.writeBytes(ByteArray(0))

        assertEquals(TrackLyrics.None(folderHint = false), resolver.load(track))
    }

    @Test
    fun aRevokedGrantFallsBackAfterARefresh() = runBlocking {
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()
        assertEquals(LyricsSource.SameNameFile, source())

        covering = { throw SecurityException("The folder was removed") }
        resolver.clearCache()

        assertEquals(LyricsSource.Embedded, source())
    }

    @Test
    fun aGrantAddedLaterIsUsedAfterARefresh() = runBlocking {
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()
        covering = { null }
        assertEquals(LyricsSource.Embedded, source())

        covering = { document("music/song.flac") }
        resolver.clearCache()

        assertEquals(LyricsSource.SameNameFile, source())
    }

    @Test
    fun anAnswerThatIsNotUnderAFolderIsNotUsed() = runBlocking {
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()
        covering = { Uri.parse("content://media/external/audio/media/1") }

        assertEquals(LyricsSource.Embedded, source())
    }
}
