package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.media.MediaCrypto
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** The debug probes' factory with a given sink; the renderer keeps #107's OMX protection. */
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
