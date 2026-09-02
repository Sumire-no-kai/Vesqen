package io.github.sumirenokai.vesqen.library

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri

/**
 * Reads bounded textual and technical metadata for a new or changed local item.
 *
 * Artwork bytes are deliberately never requested here: MediaMetadataRetriever's embedded-picture
 * API allocates the complete APIC payload before callers can enforce a size limit. MediaStore
 * provider artwork and same-directory SAF cover URIs remain the safe M1 paths.
 */
internal class LocalAudioMetadataReader(private val context: Context) {
    fun enrich(candidate: LibraryTrackCandidate): LibraryTrackCandidate {
        val uri = runCatching { candidate.contentUri.toUri() }.getOrNull() ?: return candidate
        val tagMetadata = readTags(uri)
        val streamMetadata = readStream(uri)
        val resolvedMime = streamMetadata.mimeType.ifBlank {
            tagMetadata.mimeType.ifBlank { candidate.mimeType }
        }
        return candidate.copy(
            title = tagMetadata.title.ifBlank { candidate.title },
            artist = tagMetadata.artist.ifBlank { candidate.artist },
            album = tagMetadata.album.ifBlank { candidate.album },
            durationMs = tagMetadata.durationMs.takeIf { it > 0 } ?: candidate.durationMs,
            mimeType = resolvedMime,
            albumArtist = tagMetadata.albumArtist.ifBlank { candidate.albumArtist },
            trackNumber = tagMetadata.trackNumber ?: candidate.trackNumber,
            discNumber = tagMetadata.discNumber ?: candidate.discNumber,
            year = tagMetadata.year ?: candidate.year,
            genre = tagMetadata.genre.ifBlank { candidate.genre },
            codec = codecLabel(
                mimeType = resolvedMime,
                fileName = candidate.fileName,
            ),
            channelCount = streamMetadata.channelCount ?: candidate.channelCount,
            bitDepth = streamMetadata.bitDepth ?: candidate.bitDepth,
            sampleRateHz = streamMetadata.sampleRateHz ?: candidate.sampleRateHz,
            bitrate = streamMetadata.bitrate ?: tagMetadata.bitrate ?: candidate.bitrate,
        )
    }

    private fun readTags(uri: Uri): TagMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            TagMetadata(
                title = retriever.text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                trackNumber = retriever.text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).leadingNumber(),
                discNumber = retriever.text(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).leadingNumber(),
                year = retriever.text(MediaMetadataRetriever.METADATA_KEY_YEAR).leadingNumber(),
                genre = retriever.text(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = retriever.text(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    .toLongOrNull()
                    ?.coerceAtLeast(0)
                    ?: 0,
                mimeType = retriever.text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                bitrate = retriever.text(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    .toIntOrNull()
                    ?.takeIf { it > 0 },
            )
        } catch (_: SecurityException) {
            TagMetadata()
        } catch (_: IllegalArgumentException) {
            TagMetadata()
        } catch (_: RuntimeException) {
            TagMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readStream(uri: Uri): StreamMetadata {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount)
                .asSequence()
                .map(extractor::getTrackFormat)
                .firstOrNull { format ->
                    format.stringOrEmpty(MediaFormat.KEY_MIME).startsWith("audio/", ignoreCase = true)
                }
                ?.toStreamMetadata()
                ?: StreamMetadata()
        } catch (_: Exception) {
            StreamMetadata()
        } finally {
            runCatching { extractor.release() }
        }
    }
}

private data class TagMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String = "",
    val durationMs: Long = 0,
    val mimeType: String = "",
    val bitrate: Int? = null,
)

private data class StreamMetadata(
    val mimeType: String = "",
    val channelCount: Int? = null,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val bitrate: Int? = null,
)

private fun MediaMetadataRetriever.text(key: Int): String = extractMetadata(key).orEmpty().trim()

private fun String.leadingNumber(): Int? = substringBefore('/').trim().toIntOrNull()?.takeIf { it > 0 }

private fun MediaFormat.toStreamMetadata(): StreamMetadata = StreamMetadata(
    mimeType = stringOrEmpty(MediaFormat.KEY_MIME),
    channelCount = positiveInt(MediaFormat.KEY_CHANNEL_COUNT),
    bitDepth = positiveInt(BITS_PER_SAMPLE_KEY) ?: pcmEncodingBitDepth(positiveInt(MediaFormat.KEY_PCM_ENCODING)),
    sampleRateHz = positiveInt(MediaFormat.KEY_SAMPLE_RATE),
    bitrate = positiveInt(MediaFormat.KEY_BIT_RATE),
)

private fun MediaFormat.stringOrEmpty(key: String): String =
    if (containsKey(key)) getString(key).orEmpty() else ""

private fun MediaFormat.positiveInt(key: String): Int? = runCatching {
    if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null
}.getOrNull()

private fun pcmEncodingBitDepth(encoding: Int?): Int? = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> 8
    AudioFormat.ENCODING_PCM_16BIT -> 16
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
    AudioFormat.ENCODING_PCM_32BIT,
    AudioFormat.ENCODING_PCM_FLOAT,
    -> 32
    else -> null
}

internal fun codecLabel(mimeType: String, fileName: String): String {
    m1FormatFor(mimeType, fileName)?.let { return it.displayName }
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    return when {
        normalizedMime.contains("flac") -> "FLAC"
        normalizedMime.contains("alac") -> "ALAC"
        normalizedMime.contains("wav") -> "WAV"
        normalizedMime.contains("aiff") || normalizedMime.contains("aif") -> "AIFF"
        normalizedMime.contains("mpeg") || normalizedMime.contains("mp3") -> "MP3"
        normalizedMime.contains("mp4a") || normalizedMime.contains("aac") -> "AAC"
        normalizedMime.contains("vorbis") || normalizedMime.contains("ogg") -> "Ogg Vorbis"
        normalizedMime.contains("opus") -> "Opus"
        else -> when (fileName.substringAfterLast('.', "").lowercase()) {
            "flac" -> "FLAC"
            "alac" -> "ALAC"
            "wav" -> "WAV"
            "aif", "aiff" -> "AIFF"
            "mp3" -> "MP3"
            "aac", "m4a" -> "AAC"
            "ogg" -> "Ogg Vorbis"
            "opus" -> "Opus"
            else -> normalizedMime.substringAfter('/', "").uppercase()
        }
    }
}

private const val BITS_PER_SAMPLE_KEY = "bits-per-sample"
