package io.github.sumirenokai.vesqen.telemetry

import android.os.Bundle
import io.github.sumirenokai.vesqen.library.AudioTrack

internal object TelemetryMediaItemExtras {
    private const val PREFIX = "io.github.sumirenokai.vesqen.telemetry."
    private const val CONTAINER = PREFIX + "container"
    private const val CODEC_MIME = PREFIX + "codec_mime"
    private const val CODEC_LABEL = PREFIX + "codec_label"
    private const val SAMPLE_RATE = PREFIX + "sample_rate"
    private const val BIT_DEPTH = PREFIX + "bit_depth"
    private const val CHANNEL_COUNT = PREFIX + "channel_count"
    private const val AVERAGE_BITRATE = PREFIX + "average_bitrate"
    private const val FILE_SIZE = PREFIX + "file_size"

    fun from(track: AudioTrack): Bundle = Bundle().apply {
        containerFromFileName(track.fileName)?.let { putString(CONTAINER, it) }
        track.mimeType.takeIf(String::isNotBlank)?.let { putString(CODEC_MIME, it) }
        track.codec.takeIf(String::isNotBlank)?.let { putString(CODEC_LABEL, it) }
        track.sampleRateHz?.takeIf { it > 0 }?.let { putInt(SAMPLE_RATE, it) }
        track.bitDepth?.takeIf { it > 0 }?.let { putInt(BIT_DEPTH, it) }
        track.channelCount?.takeIf { it > 0 }?.let { putInt(CHANNEL_COUNT, it) }
        track.bitrate?.takeIf { it > 0 }?.let { putInt(AVERAGE_BITRATE, it) }
        track.fileSizeBytes.takeIf { it > 0 }?.let { putLong(FILE_SIZE, it) }
    }

    fun read(bundle: Bundle?): TelemetrySourceFacts = TelemetrySourceFacts(
        container = bundle?.getString(CONTAINER),
        codecMime = bundle?.getString(CODEC_MIME),
        codecLabel = bundle?.getString(CODEC_LABEL),
        sampleRateHz = bundle?.getInt(SAMPLE_RATE)?.takeIf { it > 0 },
        bitDepth = bundle?.getInt(BIT_DEPTH)?.takeIf { it > 0 },
        channelCount = bundle?.getInt(CHANNEL_COUNT)?.takeIf { it > 0 },
        averageBitrate = bundle?.getInt(AVERAGE_BITRATE)?.takeIf { it > 0 },
        fileSizeBytes = bundle?.getLong(FILE_SIZE)?.takeIf { it > 0 },
    )

    private fun containerFromFileName(fileName: String): String? = when (
        fileName.substringAfterLast('.', "").lowercase()
    ) {
        "flac" -> "flac"
        "wav" -> "wave"
        "aif", "aiff" -> "aiff"
        "mp3" -> "mpeg_audio"
        "m4a", "mp4" -> "mp4"
        "aac" -> "adts"
        "ogg", "opus" -> "ogg"
        else -> null
    }
}

internal data class TelemetrySourceFacts(
    val container: String? = null,
    val codecMime: String? = null,
    val codecLabel: String? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val averageBitrate: Int? = null,
    val fileSizeBytes: Long? = null,
)
