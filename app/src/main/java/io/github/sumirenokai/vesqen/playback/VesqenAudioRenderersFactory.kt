package io.github.sumirenokai.vesqen.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Media3 stays the engine; this factory exposes its exact AudioTrack request to the M3 adapter. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VesqenAudioRenderersFactory(
    context: Context,
    private val audioOutputProvider: AudioOutputProvider,
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
}
