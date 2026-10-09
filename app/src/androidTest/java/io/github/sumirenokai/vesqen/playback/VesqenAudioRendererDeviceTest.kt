package io.github.sumirenokai.vesqen.playback

import android.media.AudioFormat
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** #107: OMX decoders are never asked for float PCM; Codec2 decoders still are. */
@androidx.annotation.OptIn(UnstableApi::class)
class VesqenAudioRendererDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun omxDecodersKeep16BitPcm() {
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, requestedEncoding("OMX.google.mp3.decoder"))
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, requestedEncoding("OMX.vivo.mp3.decoder"))
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, requestedEncoding("c2.android.mp3.decoder"))
        val withoutRequest = MediaFormat.createAudioFormat("audio/mpeg", 44_100, 2)
        withoutRequest.keepIntegerPcmForOmx("OMX.google.mp3.decoder")
        assertFalse(withoutRequest.containsKey(MediaFormat.KEY_PCM_ENCODING))
    }

    @Test
    fun playerUsesVesqenAudioRenderer() {
        val renderers = VesqenAudioRenderersFactory(context, AudioTrackAudioOutputProvider.Builder(context).build())
            .createRenderers(
                Handler(Looper.getMainLooper()),
                object : VideoRendererEventListener {},
                object : AudioRendererEventListener {},
                {},
                {},
            )
        val audio = renderers.filterIsInstance<MediaCodecAudioRenderer>().single()
        assertNotEquals(MediaCodecAudioRenderer::class.java, audio.javaClass)
    }

    private fun requestedEncoding(codecName: String): Int {
        val format = MediaFormat.createAudioFormat("audio/mpeg", 44_100, 2)
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        format.keepIntegerPcmForOmx(codecName)
        return format.getInteger(MediaFormat.KEY_PCM_ENCODING)
    }
}
