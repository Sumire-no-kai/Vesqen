package io.github.sumirenokai.vesqen.lyrics

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PcmFiles
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LyricsCacheInvalidationTest {
    @get:Rule val temporary = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Test fun forgettingListingsPreventsAnOldLookupFromRefillingBothCaches() = checkLyricsRace { it.forgetFolderListings() }

    @Test fun clearingCachePreventsAnOldLookupFromRefillingBothCaches() = checkLyricsRace { it.clearCache() }

    private fun checkLyricsRace(clear: (LocalLyricsResolver) -> Unit) = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val releaseListing = CountDownLatch(1)
        val provider = fixture().apply {
            captureListing = {
                entered.complete(Unit)
                check(releaseListing.await(6, TimeUnit.SECONDS))
            }
        }
        val track = AudioTrack(
            id = 42L, contentUri = provider.document("music").toString(), title = "Music",
            artist = "", album = "", durationMs = 0, fileName = "music.wav",
        )
        val resolver = LocalLyricsResolver(context.contentResolver, LyricsStore(temporary.newFolder("imports")))
        val revision = resolver.sourcesRevision.value
        val loading = async { resolver.load(track) }
        try {
            withTimeout(5_000) { entered.await() }
            provider.lyricsVisible = true
            clear(resolver)
        } finally {
            releaseListing.countDown()
        }
        assertTrue(loading.await() is TrackLyrics.None)
        assertEquals(revision + 1, resolver.sourcesRevision.value)
        val updated = resolver.load(track) as TrackLyrics.Found
        assertEquals(LyricsSource.SameNameFile, updated.source)
    }

    private fun fixture(): LocalProvider {
        val audio = temporary.newFile("music.wav").apply {
            writeBytes(PcmFiles.wavBytes(IntArray(882), 44_100, 16, 2))
        }
        val lrc = temporary.newFile("music.lrc").apply { writeText("[00:01.00]Local verification") }
        val provider = LocalProvider(audio, lrc)
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY; exported = true })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        return provider
    }

    private class LocalProvider(private val audio: File, private val lrc: File) : ContentProvider() {
        val tree: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        fun document(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        @Volatile var lyricsVisible = false
        var captureListing: (() -> Unit)? = null

        override fun onCreate() = true
        override fun getType(uri: Uri): String = if (uri.lastPathSegment == "lyrics") "text/plain" else "audio/wav"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("read only")

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            return if (method == "android:findDocumentPath") Bundle().apply {
                putParcelable("result", DocumentsContract.Path("root", listOf("root", "music")))
            } else null
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = when (uri.lastPathSegment) {
                "lyrics" -> lrc
                else -> audio
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val columns = checkNotNull(projection)
            val children = uri.pathSegments.last() == "children"
            val includeLyrics = lyricsVisible
            if (children) captureListing?.let { captureListing = null; it() }
            return MatrixCursor(columns).apply {
                fun row(id: String, title: String, mime: String, size: Long) {
                    addRow(columns.map { column ->
                        when (column) {
                            Document.COLUMN_DOCUMENT_ID -> id
                            Document.COLUMN_DISPLAY_NAME -> title
                            Document.COLUMN_MIME_TYPE -> mime
                            Document.COLUMN_LAST_MODIFIED -> 1000L
                            Document.COLUMN_SIZE -> size
                            else -> null
                        }
                    })
                }
                if (children) {
                    row("music", "music.wav", "audio/wav", audio.length())
                    if (includeLyrics) row("lyrics", "music.lrc", "text/plain", lrc.length())
                } else {
                    row("root", "Local verification", Document.MIME_TYPE_DIR, 0L)
                }
            }
        }
    }

    private companion object {
        const val AUTHORITY = "io.github.sumirenokai.vesqen.test.lyrics"
    }
}
