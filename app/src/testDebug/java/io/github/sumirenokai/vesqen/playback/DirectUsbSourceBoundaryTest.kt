package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import io.github.sumirenokai.vesqen.usb.session.UsbOutputHandle
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #282: two tracks that decode to the same float format share one output, so the second track's
 * samples must be checked against its own source depth from its first frame on, not the first's.
 * The strict sink, the provider and the converter are the real ones; only the USB handle is recorded.
 */
@RunWith(RobolectricTestRunner::class)
class DirectUsbSourceBoundaryTest {
    private val plan = StreamingPlan(
        interfaceNumber = 2,
        alternateSetting = 2,
        endpointAddress = 3,
        endpointSetsSampleRate = true,
        sampleRateHz = 48_000,
        channels = 2,
        subslotBytes = 3,
        bitResolution = 24,
        serviceIntervalUs = 1000,
        maxPacketBytes = 288,
        packetsPerUrb = 32,
        urbCount = 3,
        ringFrames = 24_000,
    )
    private val decoded = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_FLOAT)
        .setSampleRate(48_000)
        .setChannelCount(2)
        .build()

    private class RecordingHandle : UsbOutputHandle {
        val received = ByteArrayOutputStream()
        override val epoch = 0L
        override val failure: DirectUsbFailure? = null

        override fun write(buffer: ByteBuffer, offset: Int, size: Int): Int {
            val bytes = ByteArray(size)
            buffer.duplicate().apply { position(offset) }.get(bytes)
            received.write(bytes)
            return size
        }

        override fun setRunning(running: Boolean) = Unit

        override fun markEndOfStream() = Unit

        override fun readStats() = UsbStreamStats(
            mediaFramesWritten = 0, mediaFramesSubmitted = 0, mediaFramesProcessed = 0, mediaFramesDelivered = 0,
            mediaFramesLost = 0, silenceFramesInserted = 0, usbFramesDelivered = 0, underruns = 0, urbsCompleted = 0,
            packetsFailed = 0, failure = UsbStreamStats.Failure.NONE, failureErrno = 0, threadNice = 0,
            threadSchedulerPolicy = 0, epoch = 0, requestedEpoch = 0,
        )

        override fun release() = Unit
    }

    private class Run(val sink: StrictPcmAudioSink, val handle: RecordingHandle, val failures: List<DirectUsbFailure>, val outputsOpened: () -> Int)

    private fun flac(bits: Int) = TelemetrySourceFacts(codecMime = "audio/flac", sampleRateHz = 48_000, bitDepth = bits, channelCount = 2)

    private fun floats(vararg values: Float): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach(::putFloat)
            flip()
        }

    /** The first track is configured and one silent frame of it written; then the next track is configured, as a natural track change does. */
    private fun naturalChange(firstBits: Int, secondBits: Int): Run {
        var source = flac(firstBits)
        val handle = RecordingHandle()
        val failures = mutableListOf<DirectUsbFailure>()
        var opened = 0
        val provider = DirectUsbAudioOutputProvider(
            plan,
            openOutput = {
                opened++
                handle
            },
            source = { source },
            onFailure = { failures += it },
        )
        val sink = StrictPcmAudioSink(provider)
        sink.configure(AudioSink.AudioSinkConfig.Builder(decoded).build())
        assertTrue(sink.handleBuffer(floats(0f, 0f), 0, 1))
        source = flac(secondBits)
        sink.configure(AudioSink.AudioSinkConfig.Builder(decoded).build())
        return Run(sink, handle, failures, { opened })
    }

    /** One least significant step of a 24-bit source: exact in 24 bits, below what a 16-bit source can hold. */
    private val step24 = 1f / 8_388_608f

    @Test
    fun `a 24-bit track after a 16-bit one keeps its low bits`() {
        val run = naturalChange(firstBits = 16, secondBits = 24)
        assertTrue(run.sink.handleBuffer(floats(step24, step24), 21, 1))
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0, 0x01, 0, 0, 0x01, 0, 0), run.handle.received.toByteArray())
        assertEquals(emptyList<DirectUsbFailure>(), run.failures)
        assertEquals("The output and its USB session are kept", 1, run.outputsOpened())
    }

    @Test
    fun `a 16-bit track after a 24-bit one is held to the 16-bit lattice`() {
        val run = naturalChange(firstBits = 24, secondBits = 16)
        val thrown = assertThrows(AudioSink.WriteException::class.java) { run.sink.handleBuffer(floats(step24, step24), 21, 1) }
        assertEquals(DirectUsbAudioOutput.ERROR_INEXACT_PCM, thrown.errorCode)
        assertEquals(listOf(DirectUsbFailure.Reason.PCM_NOT_EXACT), run.failures.map { it.reason })
        assertArrayEquals("Nothing off the lattice reaches the wire", ByteArray(6), run.handle.received.toByteArray())
    }

    @Test
    fun `two 24-bit tracks accept the 24-bit step`() {
        val run = naturalChange(firstBits = 24, secondBits = 24)
        assertTrue(run.sink.handleBuffer(floats(step24, step24), 21, 1))
        assertEquals(1, run.outputsOpened())
    }

    @Test
    fun `two 16-bit tracks refuse the 24-bit step`() {
        val run = naturalChange(firstBits = 16, secondBits = 16)
        val thrown = assertThrows(AudioSink.WriteException::class.java) { run.sink.handleBuffer(floats(step24, step24), 21, 1) }
        assertEquals(DirectUsbAudioOutput.ERROR_INEXACT_PCM, thrown.errorCode)
    }

    @Test
    fun `a 16-bit track after a 24-bit one still plays its own samples`() {
        val run = naturalChange(firstBits = 24, secondBits = 16)
        assertTrue(run.sink.handleBuffer(floats(1f / 32_768f, -1f / 32_768f), 21, 1))
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0, 0, 0x01, 0, 0, 0xFF, 0xFF), run.handle.received.toByteArray())
        assertEquals(1, run.outputsOpened())
    }

    @Test
    fun `a third track goes back to the 16-bit lattice`() {
        var source = flac(16)
        val provider = DirectUsbAudioOutputProvider(plan, openOutput = { RecordingHandle() }, source = { source })
        val sink = StrictPcmAudioSink(provider)
        val config = AudioSink.AudioSinkConfig.Builder(decoded).build()
        sink.configure(config)
        assertTrue(sink.handleBuffer(floats(0f, 0f), 0, 1))
        source = flac(24)
        sink.configure(config)
        assertTrue(sink.handleBuffer(floats(step24, step24), 21, 1))
        source = flac(16)
        sink.configure(config)
        val thrown = assertThrows(AudioSink.WriteException::class.java) { sink.handleBuffer(floats(step24, step24), 42, 1) }
        assertEquals(DirectUsbAudioOutput.ERROR_INEXACT_PCM, thrown.errorCode)
    }

    @Test
    fun `a refused configuration leaves no lattice for a kept output to reuse`() {
        var source: TelemetrySourceFacts? = flac(16)
        val provider = DirectUsbAudioOutputProvider(plan, openOutput = { RecordingHandle() }, source = { source })
        val format = AudioOutputProvider.FormatConfig.Builder(decoded).build()
        val config = provider.getOutputConfig(format)
        val output = provider.getAudioOutput(config)
        source = null
        assertThrows(AudioOutputProvider.ConfigurationException::class.java) { provider.getOutputConfig(format) }
        assertFalse(output.canReuseAudioOutput(config, format, config))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
