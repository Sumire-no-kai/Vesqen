package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.os.Build
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
    ): AudioSink = vesqenAudioSink(context, audioOutputProvider)
}

/**
 * Media3 requests float PCM for high-resolution integer sources when float output is on. A strict
 * route is only activated when the USB mixer's advertised profile matches that actual request
 * exactly, and strict output needs Android 14. Older versions keep Media3's integer default:
 * with float on, Media3 asks every decoder for float, and on Android 9 the OMX MP3 decoder, which
 * cannot produce it, crashes the app inside ACodec (#107).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun vesqenAudioSink(
    context: Context,
    audioOutputProvider: AudioOutputProvider,
    sdkInt: Int = Build.VERSION.SDK_INT,
): AudioSink = DefaultAudioSink.Builder(context)
    .setEnableFloatOutput(sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    .setEnableAudioOutputPlaybackParameters(false)
    .setAudioOutputProvider(audioOutputProvider)
    .build()
