package io.github.sumirenokai.vesqen.playback

import android.media.AudioDeviceInfo
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * M7-4: the player's one sink. It plays through [normal] (Media3's `DefaultAudioSink`) and through
 * [strict] while Vesqen's own USB output is in use. The choice changes in [reset] and in [configure],
 * which is where a stream starts after the coordinator rebuilds the player with `stop()` and
 * `prepare()`: Media3 resets the sink only for a renderer that was enabled, so a rebuild that came
 * before the renderer was enabled reaches the sink as a plain configure. Between those calls a
 * stream stays on its sink. Settings reach both sinks, so the next one starts as the last one ended.
 */
@UnstableApi
internal class SelectingAudioSink(
    private val normal: AudioSink,
    private val strict: AudioSink,
    /** Read on the playback thread whenever the sink is reset. */
    private val useStrict: () -> Boolean,
) : AudioSink {
    private var active: AudioSink = choose()

    private fun choose(): AudioSink = if (useStrict()) strict else normal

    private inline fun both(action: (AudioSink) -> Unit) {
        action(normal)
        action(strict)
    }

    override fun setListener(listener: AudioSink.Listener) = both { it.setListener(listener) }

    override fun setPlayerId(playerId: PlayerId?) = both { it.setPlayerId(playerId) }

    override fun setClock(clock: Clock) = both { it.setClock(clock) }

    override fun supportsFormat(format: Format) = active.supportsFormat(format)

    override fun getFormatSupport(format: Format) = active.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = active.getFormatOffloadSupport(format)

    override fun getCurrentPositionUs(sourceEnded: Boolean) = active.getCurrentPositionUs(sourceEnded)

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val wanted = choose()
        if (wanted !== active) {
            active.reset()
            active = wanted
        }
        active.configure(audioSinkConfig)
    }

    override fun play() = active.play()

    override fun handleDiscontinuity() = active.handleDiscontinuity()

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int) =
        active.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun playToEndOfStream() = active.playToEndOfStream()

    override fun isEnded() = active.isEnded

    override fun hasPendingData() = active.hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        both { it.setPlaybackParameters(playbackParameters) }

    override fun getPlaybackParameters(): PlaybackParameters = active.playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = both { it.setSkipSilenceEnabled(skipSilenceEnabled) }

    override fun getSkipSilenceEnabled() = active.skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) = both { it.setAudioAttributes(audioAttributes) }

    override fun getAudioAttributes(): AudioAttributes? = active.audioAttributes

    override fun getAudioCapabilities(): AudioCapabilities? = active.audioCapabilities

    override fun setAudioSessionId(audioSessionId: Int) = both { it.setAudioSessionId(audioSessionId) }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = both { it.setAuxEffectInfo(auxEffectInfo) }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) = both { it.setPreferredDevice(audioDeviceInfo) }

    override fun setVirtualDeviceId(virtualDeviceId: Int) = both { it.setVirtualDeviceId(virtualDeviceId) }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) = both { it.setOutputStreamOffsetUs(outputStreamOffsetUs) }

    override fun getAudioTrackBufferSizeUs() = active.audioTrackBufferSizeUs

    override fun enableTunnelingV21() = active.enableTunnelingV21()

    override fun disableTunneling() = both { it.disableTunneling() }

    @RequiresApi(29)
    override fun setOffloadMode(offloadMode: Int) = both { it.setOffloadMode(offloadMode) }

    @RequiresApi(29)
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) =
        active.setOffloadDelayPadding(delayInFrames, paddingInFrames)

    /** Each sink was given its own provider when it was built. */
    override fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) =
        throw UnsupportedOperationException("The selecting sink's providers are fixed")

    override fun setVolume(volume: Float) = both { it.setVolume(volume) }

    override fun pause() = active.pause()

    override fun flush() = active.flush()

    override fun reset() {
        active.reset()
        active = choose()
    }

    override fun release() = both { it.release() }
}
