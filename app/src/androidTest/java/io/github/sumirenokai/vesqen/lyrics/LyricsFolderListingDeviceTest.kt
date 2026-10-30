package io.github.sumirenokai.vesqen.lyrics

import android.provider.DocumentsContract
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.testing.FakeDocumentsProvider
import io.github.sumirenokai.vesqen.testing.FakeDocumentsProvider.Listing
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #191 D13: a folder whose provider is still loading its listing, or reports an error, has not been
 * listed. "No lyrics" from such a listing is not kept, so the next load asks the folder again; a
 * complete listing is kept as before. B2: the same holds when the listed same-name file fails to
 * open or the folder's path lookup fails for now, which would otherwise keep the embedded lyrics in
 * its place. The folder comes from the deviceTest build's fake provider.
 */
class LyricsFolderListingDeviceTest {
    private val app = isolatedPlaybackTestApplication()
    private val lyricsDirectory = File(app.cacheDir, "folder-listing-lyrics-${System.nanoTime()}")
    private val resolver = LocalLyricsResolver(app.contentResolver, LyricsStore(lyricsDirectory))
    private val tree = DocumentsContract.buildTreeDocumentUri(
        FakeDocumentsProvider.authority(app.packageName),
        FakeDocumentsProvider.FOLDER,
    )
    private val track = AudioTrack(
        id = 9_001,
        contentUri = DocumentsContract.buildDocumentUriUsingTree(tree, "music/song.flac").toString(),
        title = "Song",
        artist = "",
        album = "",
        durationMs = 60_000,
        fileName = "song.flac",
        fileSizeBytes = 1_000,
    )

    @After
    fun reset() {
        FakeDocumentsProvider.reset()
        lyricsDirectory.deleteRecursively()
    }

    @Test
    fun aListingStillLoadingIsAskedForAgain() = assertListedAgainAfter(Listing.LOADING)

    @Test
    fun aListingWithAnErrorIsAskedForAgain() = assertListedAgainAfter(Listing.ERROR)

    @Test
    fun aCompleteListingIsKept() = runBlocking {
        FakeDocumentsProvider.files["music/song.flac"] = ByteArray(0)

        assertEquals(TrackLyrics.None(folderHint = false), resolver.load(track))
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()
        assertEquals(TrackLyrics.None(folderHint = false), resolver.load(track))
        assertEquals(1, FakeDocumentsProvider.childQueries)
    }

    @Test
    fun aSameNameFileThatFailsToOpenIsReadAgainInsteadOfTheEmbeddedLyrics() = runBlocking {
        FakeDocumentsProvider.files["music/song.flac"] = withEmbeddedLyrics("[00:01.00]Embedded in the song")
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()
        FakeDocumentsProvider.openFailures["music/song.lrc"] = IllegalStateException("The provider restarted")

        assertEquals(LyricsSource.Embedded, (resolver.load(track) as TrackLyrics.Found).source)
        FakeDocumentsProvider.openFailures.clear()
        assertEquals(LyricsSource.SameNameFile, (resolver.load(track) as TrackLyrics.Found).source)
    }

    @Test
    fun aPathLookupThatFailsForNowIsTriedAgain() = runBlocking {
        FakeDocumentsProvider.files["music/song.flac"] = withEmbeddedLyrics("[00:01.00]Embedded in the song")
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()
        FakeDocumentsProvider.pathFailure = NullPointerException("The provider died")

        assertEquals(LyricsSource.Embedded, (resolver.load(track) as TrackLyrics.Found).source)
        FakeDocumentsProvider.pathFailure = null
        assertEquals(LyricsSource.SameNameFile, (resolver.load(track) as TrackLyrics.Found).source)
    }

    // A provider without a path lookup never has a same-name file for Vesqen, so that result is kept.
    @Test
    fun aProviderWithoutPathLookupIsKept() = runBlocking {
        FakeDocumentsProvider.files["music/song.flac"] = withEmbeddedLyrics("[00:01.00]Embedded in the song")
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()
        FakeDocumentsProvider.pathFailure = UnsupportedOperationException("findDocumentPath not supported")

        assertEquals(LyricsSource.Embedded, (resolver.load(track) as TrackLyrics.Found).source)
        FakeDocumentsProvider.pathFailure = null
        assertEquals(LyricsSource.Embedded, (resolver.load(track) as TrackLyrics.Found).source)
    }

    private fun assertListedAgainAfter(incomplete: Listing) = runBlocking {
        FakeDocumentsProvider.files["music/song.flac"] = ByteArray(0)
        FakeDocumentsProvider.files["music/song.lrc"] = "[00:01.00]Beside the song".toByteArray()
        FakeDocumentsProvider.listing = incomplete

        assertEquals(TrackLyrics.None(folderHint = false), resolver.load(track))
        FakeDocumentsProvider.listing = Listing.COMPLETE
        val found = resolver.load(track) as TrackLyrics.Found
        assertEquals(LyricsSource.SameNameFile, found.source)
        assertEquals(2, FakeDocumentsProvider.childQueries)
    }

    /** An ID3v2.3 tag with one Latin-1 USLT frame, before some silent audio bytes. */
    private fun withEmbeddedLyrics(text: String): ByteArray {
        val body = byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        val frame = "USLT".toByteArray() + byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0) + body
        check(frame.size < 0x80) { "The tag size below is one syncsafe byte" }
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, frame.size.toByte()) + frame + ByteArray(1_024)
    }
}
