package io.github.sumirenokai.vesqen.lyrics

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.core.net.toUri
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.extractBoundedEmbeddedLyrics
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What Now shows for a track (#169). */
sealed interface TrackLyrics {
    data class Found(val source: LyricsSource, val lyrics: Lyrics) : TrackLyrics

    /**
     * No source has lyrics. [folderHint]: the track is known only through MediaStore, where Android
     * shows an app no file beside it, so same-name `.lrc` files are not read (owner decision 5).
     */
    data class None(val folderHint: Boolean) : TrackLyrics

    data object Unreadable : TrackLyrics
}

/**
 * The fields a track's lyrics depend on. A play count or a favorite rewrites the track, so keying
 * on the whole [AudioTrack] would read the lyrics again on every play (#51).
 */
internal data class LyricsTrackKey(
    val trackId: Long,
    val contentUri: String,
    val fileName: String,
    val dateModifiedSeconds: Long,
    val fileSizeBytes: Long,
    val importRevision: Long,
)

internal fun AudioTrack.lyricsKey(importRevision: Long) = LyricsTrackKey(
    trackId = id,
    contentUri = contentUri,
    fileName = fileName,
    dateModifiedSeconds = dateModifiedSeconds,
    fileSizeBytes = fileSizeBytes,
    importRevision = importRevision,
)

/**
 * Finds a track's lyrics only when they are asked for (owner decision 12): the import, then a
 * same-name `.lrc` in an added music folder, then the file's own tags. Results are cached per
 * [LyricsTrackKey]; a completed Library refresh clears the cache, since editing a `.lrc` beside a
 * track sends no signal. Read failures stay out of error history and device reports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LocalLyricsResolver(
    private val contentResolver: ContentResolver,
    private val store: LyricsStore,
) {
    private val results = LruCache<LyricsTrackKey, TrackLyrics>(RESULT_CACHE_SIZE)
    private val folderLyricsFiles = LruCache<String, List<LyricsFileCandidate>>(FOLDER_CACHE_SIZE)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val forgottenListings = MutableStateFlow(0L)

    /** Grows when same-name files may have changed, so a shown pane reads its lyrics again. */
    val sourcesRevision: StateFlow<Long> = forgottenListings.asStateFlow()

    suspend fun load(track: AudioTrack): TrackLyrics = withContext(dispatcher) {
        val key = track.lyricsKey(store.importedLyrics.value.revision)
        results[key] ?: resolve(track).let { resolved ->
            // A folder the provider could not list completely is asked again on the next load (#191 D13).
            if (resolved.folderListed) results.put(key, resolved.lyrics)
            resolved.lyrics
        }
    }

    fun clearCache() {
        results.evictAll()
        folderLyricsFiles.evictAll()
    }

    /**
     * When the app returns to the foreground: a `.lrc` may have been put beside the music meanwhile,
     * and Android sends no signal for it. Folder listings and "no lyrics" results go; lyrics that
     * were found stay cached until the next completed refresh.
     */
    fun forgetFolderListings() {
        folderLyricsFiles.evictAll()
        results.snapshot().forEach { (key, lyrics) -> if (lyrics is TrackLyrics.None) results.remove(key) }
        forgottenListings.value++
    }

    private fun resolve(track: AudioTrack): ResolvedLyrics {
        val uri = track.contentUri.toUri()
        val inAddedFolder = DocumentsContract.isTreeUri(uri)
        var folderListed = true
        val picked = pickLyrics { source ->
            when (source) {
                LyricsSource.Imported -> readImported(track.id)
                LyricsSource.SameNameFile -> when {
                    !inAddedFolder -> SourceLyrics.Absent
                    else -> readSameNameFile(uri, track.fileName) ?: SourceLyrics.Absent.also { folderListed = false }
                }
                LyricsSource.Embedded -> readEmbedded(uri, track.fileSizeBytes)
            }
        }
        val lyrics = when (picked) {
            is PickedLyrics.Found -> TrackLyrics.Found(picked.source, picked.lyrics)
            PickedLyrics.Absent -> TrackLyrics.None(folderHint = !inAddedFolder)
            PickedLyrics.Unreadable -> TrackLyrics.Unreadable
        }
        return ResolvedLyrics(lyrics, folderListed)
    }

    private fun readImported(trackId: Long): SourceLyrics = try {
        store.read(trackId)?.let(::readLyricsFile) ?: SourceLyrics.Absent
    } catch (_: IOException) {
        SourceLyrics.Unreadable
    }

    /**
     * Each provider call below treats any failure of the provider as "no same-name file", so a
     * misbehaving documents provider falls through to the embedded tags (owner decision 8) instead
     * of failing Now. With targetSdk 26+ DocumentsContract rethrows what the provider threw, and a
     * provider that died answers with a NullPointerException. Null when the folder could not be
     * listed completely now, so the result is not kept.
     */
    private fun readSameNameFile(trackUri: Uri, fileName: String): SourceLyrics? {
        val parentId = parentDocumentId(trackUri) ?: return SourceLyrics.Absent
        // One listing serves every track of the folder.
        val folderUri = DocumentsContract.buildChildDocumentsUriUsingTree(trackUri, parentId)
        val folderKey = folderUri.toString()
        val candidates = folderLyricsFiles[folderKey] ?: (listLyricsFiles(folderUri) ?: return null)
            .also { folderLyricsFiles.put(folderKey, it) }
        val documentId = sameNameLyricsDocumentId(fileName, candidates) ?: return SourceLyrics.Absent
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(trackUri, documentId)
        val bytes = readProviderLyrics { contentResolver.openInputStream(documentUri) } ?: return SourceLyrics.Absent
        return if (bytes.size > MAX_LYRICS_TEXT_BYTES) SourceLyrics.Unreadable else readLyricsFile(bytes)
    }

    /** The folder holding a track inside an added tree. Document IDs are opaque, so the provider is asked. */
    private fun parentDocumentId(trackUri: Uri): String? = try {
        DocumentsContract.findDocumentPath(contentResolver, trackUri)?.path?.let { path -> path.getOrNull(path.size - 2) }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /**
     * The folder's `.lrc` files, or null when the provider cannot list it completely now: a cloud
     * provider can answer with a partial list while it loads, or with an error, as TreeAudioScanner
     * also treats them.
     */
    private fun listLyricsFiles(folderUri: Uri): List<LyricsFileCandidate>? = try {
        contentResolver.query(
            folderUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val extras = cursor.extras
            if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) || extras.containsKey(DocumentsContract.EXTRA_ERROR)) {
                return null
            }
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (name.endsWith(LRC_EXTENSION, ignoreCase = true)) add(LyricsFileCandidate(cursor.getString(0), name))
                }
            }
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun readEmbedded(trackUri: Uri, fileSizeBytes: Long): SourceLyrics {
        val descriptor = try {
            contentResolver.openAssetFileDescriptor(trackUri, "r")
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // The same provider boundary as above: SecurityException, or a provider's own failure.
            null
        } ?: return SourceLyrics.Absent
        return try {
            descriptor.use {
                descriptor.createInputStream().use { input ->
                    extractBoundedEmbeddedLyrics(
                        input = input,
                        declaredLength = descriptor.length.takeIf { it > 0 } ?: fileSizeBytes.takeIf { it > 0 },
                    )
                }
            }
        } catch (_: IOException) {
            SourceLyrics.Absent
        }
    }

    private companion object {
        const val RESULT_CACHE_SIZE = 8
        const val FOLDER_CACHE_SIZE = 4
    }
}

private class ResolvedLyrics(val lyrics: TrackLyrics, val folderListed: Boolean)

internal class LyricsFileCandidate(val documentId: String, val displayName: String)

/**
 * The `.lrc` named like the audio file: "Aira.flac" finds "Aira.lrc", or else "aira.LRC". The name
 * decides, not the MIME type, which providers rarely know for `.lrc`.
 */
internal fun sameNameLyricsDocumentId(audioFileName: String, candidates: List<LyricsFileCandidate>): String? {
    val baseName = audioFileName.substringBeforeLast('.', missingDelimiterValue = "").ifEmpty { return null }
    val wanted = baseName + LRC_EXTENSION
    candidates.firstOrNull { it.displayName == wanted }?.let { return it.documentId }
    return candidates
        .filter { it.displayName.equals(wanted, ignoreCase = true) }
        .minByOrNull(LyricsFileCandidate::displayName)
        ?.documentId
}

/**
 * A lyrics file from a provider, up to one byte past the size limit so an oversized file is
 * recognised; null when it cannot be read. Any failure of the provider is caught here: with
 * targetSdk 26+ ContentResolver rethrows what the provider threw, such as IllegalStateException,
 * and a provider that died answers with a NullPointerException (#191 D3).
 */
internal fun readProviderLyrics(open: () -> InputStream?): ByteArray? = try {
    open()?.use { input -> input.readAtMost(MAX_LYRICS_TEXT_BYTES + 1) }
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    null
}

internal fun InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    while (buffer.size() < limit) {
        val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
        if (read < 0) break
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

private const val LRC_EXTENSION = ".lrc"
