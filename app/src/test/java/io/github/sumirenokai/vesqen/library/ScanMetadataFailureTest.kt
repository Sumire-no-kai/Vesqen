package io.github.sumirenokai.vesqen.library

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.sumirenokai.vesqen.playback.PcmFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
class ScanMetadataFailureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a provider failing to open a DSD file leaves its row and the rest of the folder scan intact`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val wav = temporary.newFile("readable.wav").apply {
            writeBytes(PcmFiles.wavBytes(IntArray(882), 44_100, 16, 2))
        }
        val provider = MusicProvider(wav).apply {
            attachInfo(context, ProviderInfo().apply { authority = AUTHORITY; exported = true })
        }
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        val databaseName = "metadata-failure.db"
        try {
            LibraryCatalogStore(context, databaseName).use { store ->
                store.upsertFolderSource(provider.tree.toString(), "Test music")
                AndroidLibraryCatalog(context, store, folderGrants = { listOf(provider.tree) }).use { catalog ->
                    val result = catalog.refresh(includeDeviceLibrary = false) {}
                    assertEquals(LibrarySourceScanStatus.COMPLETED, result.sourceResults.single().status)
                    val tracks = result.snapshot.tracks.associateBy(AudioTrack::fileName)
                    assertEquals(setOf("unavailable.dsf", "readable.wav"), tracks.keys)
                    assertEquals(DSD_CODEC_LABEL, tracks.getValue("unavailable.dsf").codec)
                    assertEquals(44_100, tracks.getValue("readable.wav").sampleRateHz)
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private class MusicProvider(private val wav: File) : ContentProvider() {
        val tree: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")

        override fun onCreate() = true
        override fun getType(uri: Uri): String = if (uri.lastPathSegment == "unavailable") "audio/dsf" else "audio/wav"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only provider")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read-only provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read-only provider")

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(uri.lastPathSegment == "readable") { "The provider cannot open this track" }
            return ParcelFileDescriptor.open(wav, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val columns = checkNotNull(projection)
            return MatrixCursor(columns).apply {
                fun addDocument(id: String, name: String, mime: String, size: Long) {
                    addRow(columns.map { column ->
                        when (column) {
                            Document.COLUMN_DOCUMENT_ID -> id
                            Document.COLUMN_DISPLAY_NAME -> name
                            Document.COLUMN_MIME_TYPE -> mime
                            Document.COLUMN_LAST_MODIFIED -> 1_000L
                            Document.COLUMN_SIZE -> size
                            else -> null
                        }
                    })
                }
                if (uri.lastPathSegment == "children") {
                    addDocument("unavailable", "unavailable.dsf", "audio/dsf", 128)
                    addDocument("readable", "readable.wav", "audio/wav", wav.length())
                } else {
                    addDocument("root", "Test music", Document.MIME_TYPE_DIR, 0)
                }
            }
        }
    }

    private companion object {
        const val AUTHORITY = "io.github.sumirenokai.vesqen.test.metadata"
    }
}
