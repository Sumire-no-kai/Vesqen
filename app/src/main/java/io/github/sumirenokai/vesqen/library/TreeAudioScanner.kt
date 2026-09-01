package io.github.sumirenokai.vesqen.library

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
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
        val pendingDirectories = ArrayDeque<String>().apply {
            add(DocumentsContract.getTreeDocumentId(treeUri))
        }
        val visitedDirectories = mutableSetOf<String>()
        var processedTrackCount = 0
        while (pendingDirectories.isNotEmpty()) {
            if (shouldPause()) {
                return ScanIterationResult(completed = false, processedTrackCount = processedTrackCount)
            }
            val parentDocumentId = pendingDirectories.removeFirst()
            if (!visitedDirectories.add(parentDocumentId)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            val cursor = contentResolver.query(
                childrenUri,
                DOCUMENT_PROJECTION,
                null,
                null,
                null,
            ) ?: throw IllegalStateException("Documents provider returned no cursor")
            cursor.use { cursor ->
                val documentIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val displayNameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val lastModifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    if (shouldPause()) {
                        return ScanIterationResult(completed = false, processedTrackCount = processedTrackCount)
                    }
                    val documentId = cursor.getString(documentIdIndex) ?: continue
                    val displayName = if (displayNameIndex < 0) "" else {
                        cursor.getString(displayNameIndex).orEmpty()
                    }
                    val mimeType = cursor.getString(mimeTypeIndex).orEmpty()
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pendingDirectories.add(documentId)
                    } else if (isSupportedAudioDocument(mimeType, displayName)) {
                        val lastModifiedMs = if (lastModifiedIndex < 0 || cursor.isNull(lastModifiedIndex)) 0 else {
                            cursor.getLong(lastModifiedIndex)
                        }
                        val sizeBytes = if (sizeIndex < 0 || cursor.isNull(sizeIndex)) 0 else {
                            cursor.getLong(sizeIndex)
                        }
                        onAudioDocument(
                            TreeAudioDocument(
                                documentId = documentId,
                                contentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                                displayName = displayName,
                                mimeType = mimeType,
                                lastModifiedMs = lastModifiedMs,
                                sizeBytes = sizeBytes,
                            ),
                        )
                        processedTrackCount++
                    }
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
    val mimeType: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
) {
    val fingerprint: String = libraryFingerprint(
        "tree",
        documentId,
        contentUri,
        displayName,
        mimeType,
        lastModifiedMs,
        sizeBytes,
    )
}

/** Reads only metadata for a new or changed SAF document; playback remains independent of tags. */
internal class SafAudioMetadataReader(private val context: Context) {
    fun read(document: TreeAudioDocument): LibraryTrackCandidate {
        val retriever = MediaMetadataRetriever()
        val metadata = try {
            retriever.setDataSource(context, document.contentUri)
            RetrievedMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0)
                    ?: 0,
            )
        } catch (_: SecurityException) {
            RetrievedMetadata()
        } catch (_: IllegalArgumentException) {
            RetrievedMetadata()
        } catch (_: RuntimeException) {
            RetrievedMetadata()
        } finally {
            runCatching { retriever.release() }
        }
        return LibraryTrackCandidate(
            remoteId = document.documentId,
            contentUri = document.contentUri.toString(),
            title = metadata.title?.takeIf(String::isNotBlank) ?: document.displayName.toTrackTitleFallback(),
            artist = metadata.artist.orEmpty(),
            album = metadata.album.orEmpty(),
            durationMs = metadata.durationMs,
            dateModifiedSeconds = document.lastModifiedMs / 1_000,
            sizeBytes = document.sizeBytes,
            mimeType = document.mimeType,
            fingerprint = document.fingerprint,
        )
    }
}

private data class RetrievedMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
)

internal fun isSupportedAudioDocument(mimeType: String?, displayName: String): Boolean {
    if (mimeType?.startsWith("audio/", ignoreCase = true) == true) return true
    return displayName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .let(SUPPORTED_AUDIO_EXTENSIONS::contains)
}

private fun String.toTrackTitleFallback(): String = substringBeforeLast('.', missingDelimiterValue = this)
    .ifBlank { this }

private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
    "aac",
    "aif",
    "aiff",
    "alac",
    "flac",
    "m4a",
    "mp3",
    "ogg",
    "opus",
    "wav",
)
