package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Media3 stays the engine; this factory exposes its exact AudioTrack request to the M3 adapter. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VesqenAudioRenderersFactory(
    context: Context,
    private val audioOutputProvider: AudioOutputProvider,
    /** Receives what each decoder actually produced (#72); called on the playback thread. */
    private val onDecoderOutputFormat: (Format, DecoderOutputFormat) -> Unit = { _, _ -> },
    /** M7-4: while it streams, the player plays through the strict sink into Vesqen's own USB output. */
    private val directRoute: DirectUsbRoute? = null,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val normal = DefaultAudioSink.Builder(context)
            // Media3 requests float PCM for high-resolution integer sources. A strict route is only
            // activated when the USB mixer's advertised profile matches that actual request exactly.
            .setEnableFloatOutput(true)
            .setEnableAudioOutputPlaybackParameters(false)
            .setAudioOutputProvider(audioOutputProvider)
            .build()
        val route = directRoute ?: return normal
        return SelectingAudioSink(normal, StrictPcmAudioSink(route.provider, route::onSinkConfigured)) { route.streaming }
    }

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
                context, EmptyOutputDroppingAdapterFactory(codecAdapterFactory),
                mediaCodecSelector.withoutMislabellingDecoders(), enableDecoderFallback,
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
            it.mediaFormat.adaptToDecoder(codecInfo.name)
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
 * #270: whatever PCM encoding it is asked for, Qualcomm's ALAC hardware decoder writes the file's
 * own bit depth and reports the requested encoding: float for a 16-bit file, 16-bit for a 24-bit one
 * (iQOO 9, Android 15). Media3 then reads noise of the wrong length. The software decoder Qualcomm
 * lists beside it decodes exactly, so the hardware one is never offered to a Vesqen renderer.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun MediaCodecSelector.withoutMislabellingDecoders() = MediaCodecSelector { mimeType, secure, tunneling ->
    getDecoderInfos(mimeType, secure, tunneling).filterNot { it.name == QUALCOMM_ALAC_HARDWARE_DECODER }
}

/**
 * #270: right after starting, before any input, Qualcomm's ALAC decoders answer the codec config
 * with an empty output buffer (no data, no flags, time 0; 30 of 30 starts on an iQOO 9). When
 * Media3 drains it before queueing the first sample, it has no format for it and fails with a
 * NullPointerException, about one start in twenty. Such a buffer carries nothing, so it is released
 * before Media3 sees it. Other decoders keep their adapters as they are.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class EmptyOutputDroppingAdapterFactory(private val delegate: MediaCodecAdapter.Factory) : MediaCodecAdapter.Factory {
    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val adapter = delegate.createAdapter(configuration)
        return if (configuration.codecInfo.name.startsWith(QUALCOMM_ALAC_DECODER_PREFIX)) EmptyOutputDroppingAdapter(adapter) else adapter
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class EmptyOutputDroppingAdapter(private val delegate: MediaCodecAdapter) : MediaCodecAdapter by delegate {
    override fun dequeueOutputBufferIndex(bufferInfo: MediaCodec.BufferInfo): Int {
        while (true) {
            val index = delegate.dequeueOutputBufferIndex(bufferInfo)
            if (index < 0 || bufferInfo.size > 0 || bufferInfo.flags != 0) return index
            delegate.releaseOutputBuffer(index, false)
        }
    }

    // Kotlin delegation does not forward Java default methods.
    override fun useInputBuffer(runnable: Runnable) = delegate.useInputBuffer(runnable)

    override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener) =
        delegate.registerOnBufferAvailableListener(listener)
}

/** What every Vesqen audio renderer changes in Media3's codec configuration for the chosen decoder. */
internal fun MediaFormat.adaptToDecoder(codecName: String) {
    keepIntegerPcmForOmx(codecName)
    useQualcommAlacCookie(codecName)
}

/**
 * #270: Media3 passes an ALAC track's 24-byte ALACSpecificConfig as csd-0 the way the file stores
 * it, big-endian, as AOSP's MP4 extractor does. Qualcomm's Codec2 ALAC decoders reject that and
 * fail right after starting: they take the config only with its multi-byte fields little-endian,
 * as that phone's own extractor hands it over. Other decoders keep the config as it is.
 */
internal fun MediaFormat.useQualcommAlacCookie(codecName: String) {
    if (!codecName.startsWith(QUALCOMM_ALAC_DECODER_PREFIX) || getString(MediaFormat.KEY_MIME) != MimeTypes.AUDIO_ALAC) return
    if (!containsKey(MediaFormat.KEY_SAMPLE_RATE)) return
    val csd = getByteBuffer(CSD_0) ?: return
    val config = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
    alacLittleEndianCookie(config, getInteger(MediaFormat.KEY_SAMPLE_RATE))?.let { setByteBuffer(CSD_0, ByteBuffer.wrap(it)) }
}

/**
 * A big-endian ALACSpecificConfig with frameLength, maxRun, maxFrameBytes, avgBitRate and
 * sampleRate made little-endian. Null unless it is 24 bytes and states [sampleRateHz] big-endian,
 * so a config that is already little-endian, or anything else, passes on unchanged.
 */
internal fun alacLittleEndianCookie(config: ByteArray, sampleRateHz: Int): ByteArray? {
    if (config.size != ALAC_CONFIG_BYTES) return null
    val source = ByteBuffer.wrap(config).order(ByteOrder.BIG_ENDIAN)
    if (source.getInt(20) != sampleRateHz) return null
    return ByteBuffer.allocate(ALAC_CONFIG_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(source.getInt(0)) // frameLength
        put(config, 4, 6) // compatibleVersion, bitDepth, pb, mb, kb, numChannels
        putShort(source.getShort(10)) // maxRun
        putInt(source.getInt(12)) // maxFrameBytes
        putInt(source.getInt(16)) // avgBitRate
        putInt(source.getInt(20)) // sampleRate
    }.array()
}

private const val QUALCOMM_ALAC_DECODER_PREFIX = "c2.qti.alac."
private const val QUALCOMM_ALAC_HARDWARE_DECODER = "c2.qti.alac.hw.decoder"
private const val CSD_0 = "csd-0"
private const val ALAC_CONFIG_BYTES = 24

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
