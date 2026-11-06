package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import io.github.sumirenokai.vesqen.library.codecIsLossless
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure.Reason

/**
 * D10 for Vesqen's own USB output, checked when Media3 configures an output and before anything is
 * written (architecture "格式与承诺"). It compares the file's metadata, what the decoder hands over,
 * and the DAC's setting. Accepted, the source's bit depth is the lattice every decoded sample must
 * then lie on, which [io.github.sumirenokai.vesqen.usb.pcm.ExactPcmConverter] checks sample by sample.
 */
@UnstableApi
internal object DirectUsbFormatGate {
    sealed interface Decision {
        data class Accepted(val sourceBits: Int) : Decision

        data class Rejected(val failure: DirectUsbFailure) : Decision
    }

    fun check(source: TelemetrySourceFacts?, decoded: Format, plan: StreamingPlan): Decision {
        val mime = source?.codecMime
        val bits = source?.bitDepth
        val rate = source?.sampleRateHz
        val channels = source?.channelCount
        if (mime == null || bits == null || rate == null || channels == null) {
            val missing = listOfNotNull(
                "codec".takeIf { mime == null },
                "bit depth".takeIf { bits == null },
                "sample rate".takeIf { rate == null },
                "channel count".takeIf { channels == null },
            )
            return rejected(Reason.SOURCE_FORMAT_UNKNOWN, "the library has no ${missing.joinToString()} for this file")
        }
        if (codecIsLossless(mime) != true && mime.lowercase() !in AIFF_MIMES) {
            return rejected(Reason.SOURCE_FORMAT_UNSUPPORTED, "$mime is not a lossless codec")
        }
        if (bits !in SUPPORTED_SOURCE_BITS) return rejected(Reason.SOURCE_FORMAT_UNSUPPORTED, "a $bits-bit source")
        // Strict output does not trim samples, so a source that needs trimming cannot play unchanged.
        if (decoded.encoderDelay != 0 || decoded.encoderPadding != 0) {
            return rejected(
                Reason.SOURCE_FORMAT_UNSUPPORTED,
                "encoder delay ${decoded.encoderDelay} and padding ${decoded.encoderPadding} frames",
            )
        }
        if (decoded.sampleRate != rate || decoded.channelCount != channels) {
            return rejected(
                Reason.NO_MATCHING_DIRECT_FORMAT,
                "decoded at ${decoded.sampleRate} Hz with ${decoded.channelCount} channels from a $rate Hz, $channels channel source",
            )
        }
        val decodedBits = when (decoded.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            // Float can carry 24 bits exactly; whether it does is checked sample by sample.
            C.ENCODING_PCM_FLOAT -> null
            else -> 0
        }
        if (decodedBits != null && decodedBits < bits) {
            return rejected(Reason.DECODER_PRECISION_LOST, "a $bits-bit source decoded to ${decodedBits.takeIf { it > 0 } ?: "unsupported"} bits")
        }
        if (plan.sampleRateHz != rate || plan.channels != channels || plan.bitResolution < bits) {
            return rejected(
                Reason.NO_MATCHING_DIRECT_FORMAT,
                "the DAC's setting is ${plan.sampleRateHz} Hz, ${plan.channels} channels, ${plan.bitResolution} bits for a $rate Hz, $channels channel, $bits-bit source",
            )
        }
        return Decision.Accepted(bits)
    }

    private fun rejected(reason: Reason, detail: String) = Decision.Rejected(DirectUsbFailure(reason, detail))

    /** D10: the first version plays 16- and 24-bit lossless sources only. */
    private val SUPPORTED_SOURCE_BITS = setOf(16, 24)

    /** Vesqen's AIFF extractor reads uncompressed PCM only (`parseAiffComm`), so these are lossless. */
    private val AIFF_MIMES = setOf("audio/x-aiff", "audio/aiff")
}
