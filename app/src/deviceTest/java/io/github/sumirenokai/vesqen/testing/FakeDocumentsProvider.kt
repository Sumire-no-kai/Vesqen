package io.github.sumirenokai.vesqen.testing

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Device tests only (#191 D3, D13): an added music folder whose provider answers as the test says.
 * It runs in the app's process, so a test sets the fields of the companion object directly and calls
 * [reset] when it ends. Document IDs are paths such as `music/song.flac`; `music` is the folder.
 */
class FakeDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(this, documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        childQueries++
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        files.keys.filter { it.substringBeforeLast('/') == parentDocumentId }.forEach { addDocument(cursor, it) }
        cursor.extras = Bundle().apply {
            when (listing) {
                Listing.COMPLETE -> Unit
                Listing.LOADING -> putBoolean(DocumentsContract.EXTRA_LOADING, true)
                Listing.ERROR -> putString(DocumentsContract.EXTRA_ERROR, "The folder is offline")
            }
        }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        openFailure?.let { throw it }
        val bytes = files[documentId] ?: throw FileNotFoundException(documentId)
        val copy = File.createTempFile("fake-document", null, requireNotNull(context).cacheDir)
        copy.writeBytes(bytes)
        return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY).also { copy.delete() }
    }

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path =
        DocumentsContract.Path(null, listOf(childDocumentId.substringBeforeLast('/'), childDocumentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/")

    private fun addDocument(cursor: MatrixCursor, documentId: String) {
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, documentId.substringAfterLast('/'))
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, "application/octet-stream")
            .add(DocumentsContract.Document.COLUMN_FLAGS, 0)
    }

    enum class Listing { COMPLETE, LOADING, ERROR }

    companion object {
        const val FOLDER = "music"

        /** Contents by document ID; a folder lists the IDs under it. */
        val files: MutableMap<String, ByteArray> = java.util.concurrent.ConcurrentHashMap()

        @Volatile var listing = Listing.COMPLETE

        /** Thrown by every open, as a provider's own failure reaches the app. */
        @Volatile var openFailure: RuntimeException? = null

        @Volatile var childQueries = 0

        fun authority(packageName: String) = "$packageName.fake-documents"

        fun reset() {
            files.clear()
            listing = Listing.COMPLETE
            openFailure = null
            childQueries = 0
        }

        private val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
