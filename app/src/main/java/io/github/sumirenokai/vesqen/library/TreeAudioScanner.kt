package io.github.sumirenokai.vesqen.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** Walks a persisted Storage Access Framework tree without resolving it to a filesystem path. */
internal class TreeAudioScanner(
    private val contentResolver: ContentResolver,
) {
    fun displayName(treeUri: Uri): String = try {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.takeIf(String::isNotBlank)
            } else {
                null
            }
        } ?: DEFAULT_FOLDER_NAME
    } catch (_: SecurityException) {
        DEFAULT_FOLDER_NAME
    } catch (_: IllegalArgumentException) {
        DEFAULT_FOLDER_NAME
    }

    fun scan(
        treeUri: Uri,
        shouldPause: () -> Boolean,
        onAudioDocument: (TreeAudioDocument) -> Unit,
    ): ScanIterationResult {
        val pendingDirectories = ArrayDeque<DirectoryToScan>().apply {
            add(
                DirectoryToScan(
                    documentId = DocumentsContract.getTreeDocumentId(treeUri),
                    displayPath = displayName(treeUri),
                ),
            )
        }
        val visitedDirectories = mutableSetOf<String>()
        var processedTrackCount = 0
        while (pendingDirectories.isNotEmpty()) {
            if (shouldPause()) {
                return ScanIterationResult(completed = false, processedTrackCount = processedTrackCount)
            }
            val directory = pendingDirectories.removeFirst()
            val parentDocumentId = directory.documentId
            if (!visitedDirectories.add(parentDocumentId)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            val cursor = contentResolver.query(
                childrenUri,
                DOCUMENT_PROJECTION,
                null,
                null,
                null,
            ) ?: throw IllegalStateException("Documents provider returned no cursor")
            val entries = cursor.use { cursor ->
                val documentIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val displayNameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val lastModifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                buildList {
                    while (cursor.moveToNext()) {
                        if (shouldPause()) {
                            return ScanIterationResult(completed = false, processedTrackCount = processedTrackCount)
                        }
                        val documentId = cursor.getString(documentIdIndex) ?: continue
                        val displayName = if (displayNameIndex < 0) "" else {
                            cursor.getString(displayNameIndex).orEmpty()
                        }
                        val mimeType = cursor.getString(mimeTypeIndex).orEmpty()
                        add(
                            ProviderDocument(
                                documentId = documentId,
                                displayName = displayName,
                                mimeType = mimeType,
                                lastModifiedMs = if (
                                    lastModifiedIndex < 0 || cursor.isNull(lastModifiedIndex)
                                ) 0 else cursor.getLong(lastModifiedIndex),
                                sizeBytes = if (sizeIndex < 0 || cursor.isNull(sizeIndex)) {
                                    0
                                } else {
                                    cursor.getLong(sizeIndex)
                                },
                            ),
                        )
                    }
                }
            }
            val artworkDocument = entries.firstOrNull { entry ->
                isSameDirectoryCover(entry.mimeType, entry.displayName)
            }
            val artworkUri = artworkDocument?.let { entry ->
                DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
            }
            val artworkFingerprint = artworkDocument?.let { entry ->
                libraryFingerprint(entry.documentId, entry.lastModifiedMs, entry.sizeBytes)
            }.orEmpty()
            entries.forEach { entry ->
                if (entry.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childPath = listOf(directory.displayPath, entry.displayName)
                        .filter(String::isNotBlank)
                        .joinToString("/")
                    pendingDirectories.add(
                        DirectoryToScan(
                            documentId = entry.documentId,
                            displayPath = childPath,
                        ),
                    )
                } else if (isSupportedAudioDocument(entry.mimeType, entry.displayName)) {
                    onAudioDocument(
                        TreeAudioDocument(
                            documentId = entry.documentId,
                            contentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId),
                            displayName = entry.displayName,
                            folderName = directory.displayPath,
                            mimeType = entry.mimeType,
                            lastModifiedMs = entry.lastModifiedMs,
                            sizeBytes = entry.sizeBytes,
                            albumArtworkUri = artworkUri,
                            albumArtworkFingerprint = artworkFingerprint,
                        ),
                    )
                    processedTrackCount++
                }
            }
        }
        return ScanIterationResult(completed = true, processedTrackCount = processedTrackCount)
    }

    companion object {
        private const val DEFAULT_FOLDER_NAME = "Music folder"
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}

internal data class TreeAudioDocument(
    val documentId: String,
    val contentUri: Uri,
    val displayName: String,
    val folderName: String,
    val mimeType: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
    val albumArtworkUri: Uri? = null,
    val albumArtworkFingerprint: String = "",
) {
    val fingerprint: String = libraryFingerprint(
        "tree",
        documentId,
        contentUri,
        displayName,
        mimeType,
        lastModifiedMs,
        sizeBytes,
        albumArtworkUri,
        albumArtworkFingerprint,
    )
}

internal fun TreeAudioDocument.toTrackCandidate(): LibraryTrackCandidate = LibraryTrackCandidate(
    remoteId = documentId,
    contentUri = contentUri.toString(),
    title = displayName.toTrackTitleFallback(),
    artist = "",
    album = "",
    durationMs = 0,
    albumArtworkUri = albumArtworkUri?.toString(),
    dateModifiedSeconds = lastModifiedMs / 1_000,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    fileName = displayName,
    folderName = folderName,
    fingerprint = fingerprint,
)

internal fun isSupportedAudioDocument(mimeType: String?, displayName: String): Boolean {
    if (mimeType?.startsWith("audio/", ignoreCase = true) == true) return true
    return displayName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .let(M1_AUDIO_EXTENSIONS::contains)
}

private fun String.toTrackTitleFallback(): String = substringBeforeLast('.', missingDelimiterValue = this)
    .ifBlank { this }

private fun isSameDirectoryCover(mimeType: String, displayName: String): Boolean {
    val extension = displayName.substringAfterLast('.', "").lowercase()
    if (!mimeType.startsWith("image/", ignoreCase = true) && extension !in COVER_EXTENSIONS) return false
    val baseName = displayName.substringBeforeLast('.', displayName).lowercase()
        .replace("_", "")
        .replace("-", "")
        .replace(" ", "")
    return baseName in COVER_FILE_NAMES
}

private data class DirectoryToScan(
    val documentId: String,
    val displayPath: String,
)

private data class ProviderDocument(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
)

private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
private val COVER_FILE_NAMES = setOf(
    "cover",
    "folder",
    "front",
    "albumart",
    "albumartsmall",
)
