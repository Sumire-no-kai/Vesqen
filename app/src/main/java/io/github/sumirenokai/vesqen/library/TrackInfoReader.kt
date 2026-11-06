package io.github.sumirenokai.vesqen.library

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Full information section of track details shows beyond the catalog (#240). */
data class TrackFullInfo(
    val tags: TrackTags = TrackTags(),
    val channelLayout: String? = null,
    val lossless: Boolean? = null,
)

/**
 * Reads the file when the section opens, so these fields cost nothing during a scan and need no
 * catalog column. Nothing here is stored or sent. A file that cannot be read shows fewer rows.
 */
internal class TrackInfoReader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun read(track: AudioTrack): TrackFullInfo = withContext(Dispatchers.IO) {
        readAudioFormat(track).copy(tags = readTags(track))
    }

    private fun readTags(track: AudioTrack): TrackTags = try {
        appContext.contentResolver.openAssetFileDescriptor(track.contentUri.toUri(), "r")?.use { descriptor ->
            descriptor.createInputStream().use { input ->
                val length = descriptor.length.takeIf { it > 0 } ?: track.fileSizeBytes.takeIf { it > 0 }
                extractBoundedTrackTags(input, length)
            }
        } ?: TrackTags()
    } catch (_: IOException) {
        TrackTags()
    } catch (_: RuntimeException) {
        // A provider's own failure, or a tag no reader has met; neither touches playback.
        TrackTags()
    }

    private fun readAudioFormat(track: AudioTrack): TrackFullInfo {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(appContext, track.contentUri.toUri(), null)
            val format = (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            TrackFullInfo(
                channelLayout = format
                    ?.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_MASK) }
                    ?.let { channelLayoutLabel(it.getInteger(MediaFormat.KEY_CHANNEL_MASK)) },
                lossless = codecIsLossless(format?.getString(MediaFormat.KEY_MIME)),
            )
        } catch (_: IOException) {
            TrackFullInfo()
        } catch (_: RuntimeException) {
            TrackFullInfo()
        } finally {
            extractor.release()
        }
    }
}

/**
 * From the codec the system reports for this file. A file type cannot say: a WAV can hold A-law, and
 * an .m4a either ALAC or AAC. Null for a codec not listed here: the row stays hidden rather than guessing.
 */
internal fun codecIsLossless(mimeType: String?): Boolean? = when (mimeType?.lowercase()) {
    "audio/raw", "audio/flac", "audio/alac" -> true
    "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2", "audio/mp4a-latm", "audio/vorbis", "audio/opus",
    "audio/g711-alaw", "audio/g711-mlaw", "audio/gsm", "audio/3gpp", "audio/amr-wb",
    "audio/ac3", "audio/eac3", "audio/ac4",
    -> false
    else -> null
}

/** Null for a mask without a common name: the row stays hidden rather than guessing. */
internal fun channelLayoutLabel(mask: Int): String? = when (mask) {
    AudioFormat.CHANNEL_OUT_MONO -> "C"
    AudioFormat.CHANNEL_OUT_STEREO -> "L R"
    AudioFormat.CHANNEL_OUT_QUAD -> "L R BL BR"
    AudioFormat.CHANNEL_OUT_5POINT1 -> "5.1"
    AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> "7.1"
    else -> null
}
