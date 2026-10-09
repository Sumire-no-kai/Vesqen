package io.github.sumirenokai.vesqen.playback

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #107: MediaCodecAudioRenderer asks a decoder for float PCM only when the sink plays float
 * directly. On Android 9 that request crashed the app in ACodec for MP3, so it is made only from
 * Android 14, where strict USB output can use it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VesqenAudioSinkDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val floatPcm = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_FLOAT)
        .setChannelCount(2)
        .setSampleRate(48_000)
        .build()

    @Test
    fun decodersAreAskedForFloatOnlyFromAndroid14() {
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, floatSupport(Build.VERSION_CODES.P))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, floatSupport(Build.VERSION_CODES.TIRAMISU))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, floatSupport(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }

    private fun floatSupport(sdkInt: Int): Int {
        val sink = vesqenAudioSink(context, AudioTrackAudioOutputProvider.Builder(context).build(), sdkInt)
        try {
            return sink.getFormatSupport(floatPcm)
        } finally {
            sink.release()
        }
    }
}
