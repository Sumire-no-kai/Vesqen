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
 * complete listing is kept as before. The folder comes from the deviceTest build's fake provider.
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
}
