package io.github.sumirenokai.vesqen.usb.chain

import io.github.sumirenokai.vesqen.playback.PcmFiles
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

internal class ChainTrack(val file: File, val durationMs: Long)

/** Three quiet two-tone files, each with its own pitch so a change of track can be heard. */
internal object ChainTracks {
    private const val SAMPLE_RATE_HZ = 48_000
    private const val CHANNELS = 2
    private const val LEVEL = 0.1

    fun write(directory: File, bits: Int): List<ChainTrack> {
        directory.mkdirs()
        return listOf(
            track(directory, "a-$bits.wav", bits, durationMs = 30_000, leftPeriod = 100, rightPeriod = 80),
            track(directory, "b-$bits.flac", bits, durationMs = 30_000, leftPeriod = 120, rightPeriod = 96),
            track(directory, "c-$bits.flac", bits, durationMs = 20_000, leftPeriod = 75, rightPeriod = 60),
        )
    }

    private fun track(directory: File, name: String, bits: Int, durationMs: Long, leftPeriod: Int, rightPeriod: Int): ChainTrack {
        val file = File(directory, name)
        if (!file.exists()) {
            val frames = (durationMs * SAMPLE_RATE_HZ / 1000).toInt()
            val amplitude = (1 shl (bits - 1)) * LEVEL
            val samples = IntArray(frames * CHANNELS) { index ->
                val period = if (index % CHANNELS == 0) leftPeriod else rightPeriod
                (amplitude * sin(2 * PI * (index / CHANNELS) / period)).roundToInt()
            }
            file.writeBytes(
                if (name.endsWith(".wav")) {
                    PcmFiles.wavBytes(samples, SAMPLE_RATE_HZ, bits, CHANNELS)
                } else {
                    PcmFiles.flacBytes(samples, SAMPLE_RATE_HZ, bits, CHANNELS)
                },
            )
        }
        return ChainTrack(file, durationMs)
    }
}
