package io.github.sumirenokai.vesqen.lyrics

import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AndroidLibraryCatalog
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #169, staged (owner decision 13): same-name `.lrc` files are found through the phone's own
 * documents provider, which no test can grant a folder for. Once per phone the operator adds the QA
 * folder through "Add music folder" in the Vesqen Test app (the `devicetest` package, not the
 * installed Vesqen), then runs this class with `--instrumentation-arg lyricsFolder=true`.
 *
 * The folder's audio files say what they expect by name: `sidecar-*` has a same-name `.lrc` (any
 * case of the extension), `unreadable-*` has one in an encoding Vesqen does not read and no tags,
 * and `none-*` has neither. Every file is checked; the results are written next to the app's files.
 */
class SameNameLyricsDeviceTest {
    @Test
    fun sameNameFilesInAnAddedFolderAreRead() = runBlocking {
        assumeTrue(
            "Same-name lyrics need a QA folder added in Vesqen Test",
            InstrumentationRegistry.getArguments().getString("lyricsFolder") == "true",
        )
        val app = isolatedPlaybackTestApplication()
        val catalog = AndroidLibraryCatalog(app, app.libraryCatalogStore)
        val lyricsDirectory = File(app.cacheDir, "same-name-lyrics-${System.nanoTime()}")
        try {
            catalog.refresh(includeDeviceLibrary = false, onProgress = {})
            val tracks = catalog.snapshot(includeDeviceLibrary = false).tracks
                .filter { DocumentsContract.isTreeUri(it.contentUri.toUri()) }
            assertTrue("No added folder holds audio; add the QA folder in Vesqen Test", tracks.isNotEmpty())
            val resolver = LocalLyricsResolver(app.contentResolver, LyricsStore(lyricsDirectory))
            val results = tracks.associate { it.fileName to resolver.load(it) }
            File(app.filesDir, "same-name-lyrics.txt").writeText(
                results.entries.joinToString("\n") { (name, lyrics) -> "$name\t${lyrics.describe()}" },
            )
            results.forEach { (name, lyrics) ->
                when {
                    name.startsWith("sidecar-") ->
                        assertEquals(name, LyricsSource.SameNameFile, (lyrics as? TrackLyrics.Found)?.source)
                    name.startsWith("unreadable-") -> assertEquals(name, TrackLyrics.Unreadable(UnreadableReason.Encoding), lyrics)
                    name.startsWith("none-") -> assertEquals(name, TrackLyrics.None(folderHint = false), lyrics)
                }
            }
            assertTrue("The QA folder has no sidecar-* file", results.keys.any { it.startsWith("sidecar-") })
        } finally {
            catalog.close()
            lyricsDirectory.deleteRecursively()
        }
    }

    private fun TrackLyrics.describe(): String = when (this) {
        is TrackLyrics.Found -> "${source.name} ${lyrics::class.simpleName}"
        is TrackLyrics.None -> "None folderHint=$folderHint"
        is TrackLyrics.Unreadable -> "Unreadable ${reason.name}"
    }
}
