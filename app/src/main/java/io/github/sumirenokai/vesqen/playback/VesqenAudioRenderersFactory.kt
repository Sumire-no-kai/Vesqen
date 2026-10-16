package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.sumirenokai.vesqen.telemetry.DecoderOutputFormat

/** Media3 stays the engine; this factory exposes its exact AudioTrack request to the M3 adapter. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VesqenAudioRenderersFactory(
    context: Context,
    private val audioOutputProvider: AudioOutputProvider,
    /** Receives what each decoder actually produced (#72); called on the playback thread. */
    private val onDecoderOutputFormat: (Format, DecoderOutputFormat) -> Unit = { _, _ -> },
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        // Media3 requests float PCM for high-resolution integer sources. A strict route is only
        // activated when the USB mixer's advertised profile matches that actual request exactly.
        .setEnableFloatOutput(true)
        .setEnableAudioOutputPlaybackParameters(false)
        .setAudioOutputProvider(audioOutputProvider)
        .build()

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // Vesqen ships no decoder extensions, so this is the whole list Media3 would build.
        out.add(
            VesqenAudioRenderer(
                context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, audioSink, onDecoderOutputFormat,
            ),
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class VesqenAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink,
    private val onDecoderOutputFormat: (Format, DecoderOutputFormat) -> Unit,
) : MediaCodecAudioRenderer(
    context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback,
    eventHandler, eventListener, audioSink,
) {
    override fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo,
        format: Format,
        crypto: MediaCrypto?,
        codecOperatingRate: Float,
    ): MediaCodecAdapter.Configuration =
        super.getMediaCodecConfiguration(codecInfo, format, crypto, codecOperatingRate).also {
            it.mediaFormat.keepIntegerPcmForOmx(codecInfo.name)
        }

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        super.onOutputFormatChanged(format, mediaFormat)
        decoderOutputFormat(format, mediaFormat)?.let { onDecoderOutputFormat(format, it) }
    }
}

/**
 * The PCM the decoder produced. Media3 asks every Codec2 decoder for float, but AOSP's MP3, AAC,
 * Opus and Vorbis decoders only output 16-bit, so the request says nothing about the result
 * (#72). Without a codec (raw PCM) the stream is passed on as it is.
 */
internal fun decoderOutputFormat(format: Format, mediaFormat: MediaFormat?): DecoderOutputFormat? =
    if (mediaFormat == null) {
        format.pcmEncoding.takeIf { it != Format.NO_VALUE }?.let { encoding ->
            DecoderOutputFormat(encoding, format.sampleRate.takeIf { it != Format.NO_VALUE }, fromCodec = false)
        }
    } else {
        DecoderOutputFormat(
            // MediaFormat documents 16-bit PCM when a codec states no encoding.
            pcmEncoding = if (mediaFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                mediaFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            },
            sampleRateHz = if (mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                null
            },
            fromCodec = true,
        )
    }

/**
 * With float output on, Media3 asks every decoder for float PCM. Codec2 decoders (`c2.*`) handle
 * that request themselves. For an OMX decoder that cannot produce float, ACodec converts its
 * output, and on Android 9 the app crashed there the moment an MP3 started (#107). The same ACodec
 * code still ships in later versions for vendor OMX decoders, so OMX decoders keep 16-bit PCM.
 */
internal fun MediaFormat.keepIntegerPcmForOmx(codecName: String) {
    if (!codecName.startsWith("c2.") && containsKey(MediaFormat.KEY_PCM_ENCODING) &&
        getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
    ) {
        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    }
}
