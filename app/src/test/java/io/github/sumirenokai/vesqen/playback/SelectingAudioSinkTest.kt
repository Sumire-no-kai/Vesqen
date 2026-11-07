package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectingAudioSinkTest {
    private class Recorder {
        val calls = mutableListOf<String>()
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
            calls += method.name
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> true
                Long::class.javaPrimitiveType -> 0L
                Int::class.javaPrimitiveType -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                else -> null
            }
        } as AudioSink
    }

    private val normal = Recorder()
    private val strict = Recorder()
    private var useStrict = false
    private val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_FLOAT)
        .setSampleRate(48_000).setChannelCount(2).build()

    private fun sink() = SelectingAudioSink(normal.sink, strict.sink) { useStrict }

    @Test
    fun `it starts with the sink the selector asks for`() {
        useStrict = true
        sink().handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals(listOf("handleBuffer"), strict.calls)
        assertEquals(emptyList<String>(), normal.calls)
    }

    @Test
    fun `a changed selection waits for the next reset`() {
        val sink = sink()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
        useStrict = true
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals("A running stream stays where it is", listOf("configure", "handleBuffer"), normal.calls)
        sink.reset()
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals("The old sink is reset before the new one takes over", listOf("configure", "handleBuffer", "reset"), normal.calls)
        assertEquals(listOf("handleBuffer"), strict.calls)
    }

    /**
     * Media3 resets the sink only for a renderer that was enabled. A rebuild whose stop() came
     * before the renderer was enabled reaches the sink as a plain configure, which must still
     * pick the sink the selector asks for.
     */
    @Test
    fun `a changed selection is also taken at the next configure`() {
        val sink = sink()
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        useStrict = true
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
        assertEquals(listOf("handleBuffer", "reset"), normal.calls)
        assertEquals(listOf("configure"), strict.calls)
    }

    @Test
    fun `settings reach both sinks`() {
        val sink = sink()
        sink.setVolume(0.5f)
        sink.setAudioAttributes(AudioAttributes.DEFAULT)
        sink.setAudioSessionId(7)
        sink.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() = Unit
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = Unit
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
        })
        val settings = listOf("setVolume", "setAudioAttributes", "setAudioSessionId", "setListener")
        assertEquals(settings, normal.calls)
        assertEquals(settings, strict.calls)
    }

    @Test
    fun `stream calls reach only the active sink`() {
        val sink = sink()
        sink.play()
        sink.pause()
        sink.flush()
        sink.playToEndOfStream()
        assertEquals(listOf("play", "pause", "flush", "playToEndOfStream"), normal.calls)
        assertEquals(emptyList<String>(), strict.calls)
    }

    @Test
    fun `release releases both`() {
        sink().release()
        assertEquals(listOf("release"), normal.calls)
        assertEquals(listOf("release"), strict.calls)
    }
}
