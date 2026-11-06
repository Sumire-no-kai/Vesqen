package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.media.AudioTrack
import android.media.MediaCrypto
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * M7 D9. It lives with the debug builds until M7-4 moves it to production code.
 * PCM goes straight to the output with no processing, so there is nothing to undo.
 * Strict mode already requires neutral speed and skip-silence, and rejects what would need
 * trimming, so those parts of the `AudioSink` contract have nothing to do here. Volume goes to the
 * output, which decides: the official path fails closed, and the direct path ignores it (D11).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class StrictPcmAudioSink(private val provider: AudioOutputProvider) : AudioSink {
    private var listener: AudioSink.Listener? = null
    private var audioAttributes = AudioAttributes.DEFAULT
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var volume = 1f
    private var playing = false
    private var configuration: Configuration? = null
    private var pendingConfiguration: Configuration? = null
    private var output: AudioOutput? = null
    private var startMediaTimeUs = 0L
    private var startNeedsInit = true
    private var startNeedsSync = false
    private var writtenBytes = 0L
    private var submittedBytes = 0L
    private var inputBuffer: ByteBuffer? = null
    private var handledEndOfStream = false

    private class Configuration(val formatConfig: AudioOutputProvider.FormatConfig, val outputConfig: AudioOutputProvider.OutputConfig) {
        val frameSize = Util.getPcmFrameSize(formatConfig.format.pcmEncoding, formatConfig.format.channelCount)
        val sampleRate = formatConfig.format.sampleRate

        fun durationUs(frames: Long) = Util.sampleCountToDurationUs(frames, sampleRate)
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int = when {
        format.sampleMimeType != MimeTypes.AUDIO_RAW -> AudioSink.SINK_FORMAT_UNSUPPORTED
        format.pcmEncoding in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT) ->
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else -> AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        if (format.encoderDelay > 0 || format.encoderPadding > 0) {
            throw AudioSink.ConfigurationException("Strict output does not trim samples", format)
        }
        val formatConfig = AudioOutputProvider.FormatConfig.Builder(format)
            .setAudioAttributes(audioAttributes)
            .setAudioSessionId(audioSessionId)
            .setEnableHighResolutionPcmOutput(true)
            .build()
        val outputConfig = try {
            provider.getOutputConfig(formatConfig)
        } catch (exception: AudioOutputProvider.ConfigurationException) {
            throw AudioSink.ConfigurationException(exception, format)
        }
        val next = Configuration(formatConfig, outputConfig)
        if (output == null) configuration = next else pendingConfiguration = next
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        check(inputBuffer == null || inputBuffer === buffer)
        pendingConfiguration?.let { pending ->
            val current = output
            if (current != null && !current.canReuseAudioOutput(
                    checkNotNull(configuration).outputConfig, pending.formatConfig, pending.outputConfig,
                )
            ) {
                current.stop()
                if (hasPendingData()) return false
                flush()
            } else {
                configuration = pending
                pendingConfiguration = null
            }
        }
        val active = checkNotNull(configuration) { "handleBuffer before configure" }
        val currentOutput = output ?: openOutput(active)
        if (startNeedsInit) {
            startMediaTimeUs = maxOf(0, presentationTimeUs)
            startNeedsInit = false
            startNeedsSync = false
        }
        if (inputBuffer == null) {
            if (!buffer.hasRemaining()) return true
            val expectedUs = startMediaTimeUs + active.durationUs(submittedBytes / active.frameSize)
            if (startNeedsSync || abs(expectedUs - presentationTimeUs) > MAX_TIMESTAMP_DRIFT_US) {
                startMediaTimeUs += presentationTimeUs - expectedUs
                startNeedsSync = false
                listener?.onPositionDiscontinuity()
            }
            submittedBytes += buffer.remaining()
            inputBuffer = buffer
        }
        val before = buffer.remaining()
        val fullyHandled = try {
            currentOutput.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        } catch (exception: AudioOutput.WriteException) {
            throw AudioSink.WriteException(exception.errorCode, active.formatConfig.format, exception.isRecoverable)
        }
        writtenBytes += before - buffer.remaining()
        if (fullyHandled) inputBuffer = null
        return fullyHandled
    }

    private fun openOutput(active: Configuration): AudioOutput {
        val opened = try {
            provider.getAudioOutput(active.outputConfig)
        } catch (exception: AudioOutputProvider.InitializationException) {
            throw AudioSink.InitializationException(
                "No output for the strict sink",
                AudioTrack.STATE_UNINITIALIZED,
                active.formatConfig.format,
                false,
                exception,
            )
        }
        opened.setVolume(volume)
        if (playing) opened.play()
        output = opened
        return opened
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val current = output ?: return AudioSink.CURRENT_POSITION_NOT_SET
        val active = checkNotNull(configuration)
        if (startNeedsInit) return AudioSink.CURRENT_POSITION_NOT_SET
        return startMediaTimeUs + minOf(current.positionUs, active.durationUs(writtenBytes / active.frameSize))
    }

    override fun playToEndOfStream() {
        if (!handledEndOfStream && output != null) {
            checkNotNull(output).stop()
            handledEndOfStream = true
        }
    }

    override fun isEnded() = output == null || (handledEndOfStream && !hasPendingData())

    override fun hasPendingData(): Boolean {
        val current = output ?: return false
        val active = checkNotNull(configuration)
        return writtenBytes / active.frameSize > Util.durationUsToSampleCount(current.positionUs, active.sampleRate)
    }

    override fun play() {
        playing = true
        output?.play()
    }

    override fun pause() {
        playing = false
        output?.pause()
    }

    override fun handleDiscontinuity() {
        startNeedsSync = true
    }

    override fun flush() {
        output?.release()
        output = null
        pendingConfiguration?.let { configuration = it }
        pendingConfiguration = null
        inputBuffer = null
        writtenBytes = 0
        submittedBytes = 0
        startNeedsInit = true
        startNeedsSync = false
        handledEndOfStream = false
    }

    override fun reset() {
        flush()
        playing = false
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit

    override fun getSkipSilenceEnabled() = false

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit

    override fun getAudioTrackBufferSizeUs(): Long {
        val current = output ?: return C.TIME_UNSET
        return checkNotNull(configuration).durationUs(current.bufferSizeInFrames)
    }

    override fun enableTunnelingV21() = throw UnsupportedOperationException("Strict output has no tunneling")

    override fun disableTunneling() = Unit

    /** Only the focus duck factor can differ from 1, since strict mode pins the user volume. */
    override fun setVolume(volume: Float) {
        this.volume = volume
        output?.setVolume(volume)
    }

    private companion object {
        const val MAX_TIMESTAMP_DRIFT_US = 200_000L
    }
}

/** The production factory with the strict sink; the renderer keeps #107's OMX protection. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class GuardedRenderersFactory(
    context: Context,
    private val sink: AudioSink,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = sink

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
        out.add(
            object : MediaCodecAudioRenderer(
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
            },
        )
    }
}
