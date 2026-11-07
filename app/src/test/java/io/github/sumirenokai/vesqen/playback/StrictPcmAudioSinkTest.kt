package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The player only understands the sink's own exceptions: anything else would crash its thread. */
@RunWith(RobolectricTestRunner::class)
class StrictPcmAudioSinkTest {
    private val format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(48_000)
        .setChannelCount(2)
        .build()

    private fun config(sink: AudioSink.AudioSinkConfig.Builder.() -> Unit = {}) =
        AudioSink.AudioSinkConfig.Builder(format).apply(sink).build()

    private val outputConfig = AudioOutputProvider.OutputConfig.Builder()
        .setEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(48_000)
        .setChannelMask(Util.getAudioTrackChannelConfig(2))
        .setBufferSize(96_000)
        .build()

    private fun provider(
        onConfigure: () -> AudioOutputProvider.OutputConfig = { outputConfig },
        onOutput: () -> AudioOutput,
    ) = Proxy.newProxyInstance(
        AudioOutputProvider::class.java.classLoader,
        arrayOf(AudioOutputProvider::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getOutputConfig" -> onConfigure()
            "getAudioOutput" -> onOutput()
            else -> null
        }
    } as AudioOutputProvider

    private fun output(onWrite: () -> Boolean) = Proxy.newProxyInstance(
        AudioOutput::class.java.classLoader,
        arrayOf(AudioOutput::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "write" -> onWrite()
            "getPositionUs", "getBufferSizeInFrames" -> 0L
            "isStalled", "isOffloadedPlayback" -> false
            else -> null
        }
    } as AudioOutput

    private fun pcm() = ByteBuffer.allocate(4)

    @Test
    fun `a format the provider refuses is a configuration error`() {
        val cause = AudioOutputProvider.ConfigurationException("no")
        val sink = StrictPcmAudioSink(provider(onConfigure = { throw cause }, onOutput = { error("unused") }))
        val thrown = assertThrows(AudioSink.ConfigurationException::class.java) { sink.configure(config()) }
        assertSame(cause, thrown.cause)
        assertEquals(format, thrown.format)
    }

    @Test
    fun `an output that cannot be built is an initialization error`() {
        val cause = AudioOutputProvider.InitializationException()
        val sink = StrictPcmAudioSink(provider(onOutput = { throw cause }))
        sink.configure(config())
        val thrown = assertThrows(AudioSink.InitializationException::class.java) { sink.handleBuffer(pcm(), 0, 1) }
        assertSame(cause, thrown.cause)
        assertFalse(thrown.isRecoverable)
    }

    @Test
    fun `a failed write is a write error with its code`() {
        val sink = StrictPcmAudioSink(provider(onOutput = { output { throw AudioOutput.WriteException(5, false) } }))
        sink.configure(config())
        val thrown = assertThrows(AudioSink.WriteException::class.java) { sink.handleBuffer(pcm(), 0, 1) }
        assertEquals(5, thrown.errorCode)
        assertFalse(thrown.isRecoverable)
        assertEquals(format, thrown.format)
    }
}
