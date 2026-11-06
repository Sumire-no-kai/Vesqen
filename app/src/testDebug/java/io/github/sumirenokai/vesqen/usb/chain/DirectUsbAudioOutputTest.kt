package io.github.sumirenokai.vesqen.usb.chain

import androidx.media3.exoplayer.audio.AudioOutput
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.pcm.UsbPcmEncoding
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
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

@RunWith(RobolectricTestRunner::class)
class DirectUsbAudioOutputTest {
    private val plan = StreamingPlan(
        interfaceNumber = 2,
        alternateSetting = 1,
        endpointAddress = 3,
        endpointSetsSampleRate = true,
        sampleRateHz = 48_000,
        channels = 2,
        subslotBytes = 2,
        bitResolution = 16,
        serviceIntervalUs = 1000,
        maxPacketBytes = 192,
        packetsPerUrb = 32,
        urbCount = 3,
        ringFrames = 24_000,
    )
    private val prefillFrames = 4800

    private class FakeStream(private val acceptAtMost: () -> Int = { Int.MAX_VALUE }) : UsbOutputStream {
        val received = ByteArrayOutputStream()
        val runningCalls = mutableListOf<Boolean>()
        var endMarks = 0
        var ended = false
        var processed = 0L
        var submitted = 0L
        var failure = UsbStreamStats.Failure.NONE

        override fun write(buffer: ByteBuffer, offset: Int, size: Int): Int {
            if (ended) return -1
            val accepted = minOf(size, acceptAtMost())
            val bytes = ByteArray(accepted)
            buffer.duplicate().apply { position(offset) }.get(bytes)
            received.write(bytes)
            return accepted
        }

        override fun setRunning(running: Boolean) {
            runningCalls += running
        }

        override fun markEndOfStream() {
            endMarks++
        }

        override fun readStats() = UsbStreamStats(
            mediaFramesWritten = 0,
            mediaFramesSubmitted = submitted,
            mediaFramesProcessed = processed,
            mediaFramesDelivered = 0,
            mediaFramesLost = 0,
            silenceFramesInserted = 0,
            usbFramesDelivered = 0,
            underruns = 0,
            urbsCompleted = 0,
            packetsFailed = 0,
            failure = failure,
            failureErrno = 0,
            threadNice = 0,
            threadSchedulerPolicy = 0,
            epoch = 0,
            requestedEpoch = 0,
        )
    }

    private class FakeStreams(private val answerAtOnce: Boolean = true, private val stream: UsbOutputStream? = FakeStream()) : UsbOutputStreams {
        val closed = mutableListOf<UsbOutputStream>()
        private var pending: ((UsbOutputStream?) -> Unit)? = null

        override fun open(onOpened: (UsbOutputStream?) -> Unit) {
            if (answerAtOnce) onOpened(stream) else pending = onOpened
        }

        fun answer() = checkNotNull(pending).invoke(stream)

        override fun close(stream: UsbOutputStream) {
            closed += stream
        }
    }

    private fun pcm(frames: Int) = ByteBuffer.wrap(ByteArray(frames * 4) { it.toByte() })

    private fun output(
        streams: UsbOutputStreams,
        encoding: UsbPcmEncoding = UsbPcmEncoding.INT16,
        onFailure: (UsbStreamStats) -> Unit = {},
        onVolumeIgnored: (Float) -> Unit = {},
        nanoTime: () -> Long = { 0 },
    ) = DirectUsbAudioOutput(plan, encoding, streams, onFailure, onVolumeIgnored, nanoTime)

    @Test
    fun `nothing is taken until the stream is open`() {
        val stream = FakeStream()
        val streams = FakeStreams(answerAtOnce = false, stream = stream)
        val output = output(streams)
        val data = pcm(16)
        assertFalse(output.write(data, 1, 0))
        assertEquals(64, data.remaining())
        streams.answer()
        assertTrue(output.write(data, 1, 0))
        assertEquals(64, stream.received.size())
    }

    @Test
    fun `everything arrives unchanged and in order however little the ring takes`() {
        val stream = FakeStream(acceptAtMost = { 12 })
        val output = output(FakeStreams(stream = stream))
        val data = pcm(10_000)
        val expected = data.array().copyOf()
        var calls = 0
        while (!output.write(data, 1, 0)) {
            assertEquals("Only whole frames", 0, stream.received.size() % 4)
            calls++
        }
        assertTrue(calls > 1)
        assertArrayEquals(expected, stream.received.toByteArray())
    }

    @Test
    fun `a full ring keeps the rest for the next call`() {
        var room = 8
        val stream = FakeStream(acceptAtMost = { room })
        val output = output(FakeStreams(stream = stream))
        val data = pcm(16)
        assertFalse(output.write(data, 1, 0))
        room = Int.MAX_VALUE
        assertTrue("An empty buffer still drains what was converted", output.write(data, 1, 0))
        assertEquals(64, stream.received.size())
    }

    @Test
    fun `playing waits for a little audio in the ring`() {
        val stream = FakeStream()
        val output = output(FakeStreams(stream = stream))
        output.play()
        output.write(pcm(prefillFrames - 1), 1, 0)
        assertEquals(emptyList<Boolean>(), stream.runningCalls)
        output.write(pcm(1), 1, 0)
        assertEquals(listOf(true), stream.runningCalls)
    }

    @Test
    fun `a track too short to fill it still plays once it ends`() {
        val stream = FakeStream()
        val output = output(FakeStreams(stream = stream))
        output.play()
        output.write(pcm(10), 1, 0)
        assertEquals(emptyList<Boolean>(), stream.runningCalls)
        output.stop()
        assertEquals(listOf(true), stream.runningCalls)
        assertEquals(1, stream.endMarks)
    }

    @Test
    fun `written while paused, it does not start until asked`() {
        val stream = FakeStream()
        val output = output(FakeStreams(stream = stream))
        output.write(pcm(prefillFrames), 1, 0)
        assertEquals(emptyList<Boolean>(), stream.runningCalls)
        output.play()
        assertEquals(listOf(true), stream.runningCalls)
    }

    @Test
    fun `pause stops it at once and play starts it again`() {
        val stream = FakeStream()
        val output = output(FakeStreams(stream = stream))
        output.play()
        output.write(pcm(prefillFrames), 1, 0)
        output.pause()
        output.play()
        assertEquals(listOf(true, false, true), stream.runningCalls)
    }

    @Test
    fun `an end requested before the stream opens is still delivered`() {
        val stream = FakeStream()
        val streams = FakeStreams(answerAtOnce = false, stream = stream)
        val output = output(streams)
        output.stop()
        streams.answer()
        output.positionUs
        assertEquals(1, stream.endMarks)
    }

    @Test
    fun `the end is marked once`() {
        val stream = FakeStream()
        val output = output(FakeStreams(stream = stream))
        output.stop()
        output.stop()
        output.positionUs
        assertEquals(1, stream.endMarks)
    }

    @Test
    fun `audio that is not exact stops the stream instead of being changed`() {
        val stream = FakeStream()
        val output = output(FakeStreams(stream = stream), UsbPcmEncoding.FLOAT)
        val floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f / 65536f).putFloat(0f)
        floats.flip()
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(floats, 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_INEXACT_PCM, thrown.errorCode)
        assertFalse(thrown.isRecoverable)
        assertEquals(0, stream.received.size())
    }

    @Test
    fun `audio that stops being exact partway through stops the stream too`() {
        val stream = FakeStream()
        val output = output(FakeStreams(stream = stream), UsbPcmEncoding.FLOAT)
        val floats = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.5f).putFloat(0.5f).putFloat(1f / 65536f).putFloat(0f)
        floats.flip()
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(floats, 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_INEXACT_PCM, thrown.errorCode)
        assertEquals("The frames before it never reach the ring", 0, stream.received.size())
    }

    @Test
    fun `less than a frame cannot wait forever`() {
        val output = output(FakeStreams())
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(ByteBuffer.wrap(ByteArray(3)), 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_INEXACT_PCM, thrown.errorCode)
    }

    @Test
    fun `a failed stream stops the writes and is reported once`() {
        val stream = FakeStream().apply { failure = UsbStreamStats.Failure.DEVICE_GONE }
        val failures = mutableListOf<UsbStreamStats.Failure>()
        val output = output(FakeStreams(stream = stream), onFailure = { failures += it.failure })
        repeat(2) {
            val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(pcm(1), 1, 0) }
            assertEquals(DirectUsbAudioOutput.ERROR_STREAM_FAILED, thrown.errorCode)
        }
        output.positionUs
        assertEquals(listOf(UsbStreamStats.Failure.DEVICE_GONE), failures)
    }

    @Test
    fun `a failure while only waiting for the end is reported from the position`() {
        val stream = FakeStream()
        val failures = mutableListOf<UsbStreamStats.Failure>()
        val output = output(FakeStreams(stream = stream), onFailure = { failures += it.failure })
        output.stop()
        stream.failure = UsbStreamStats.Failure.REAP
        output.positionUs
        assertEquals(listOf(UsbStreamStats.Failure.REAP), failures)
    }

    @Test
    fun `a stream that could not open stops the writes`() {
        val output = output(FakeStreams(stream = null))
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(pcm(1), 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_NO_STREAM, thrown.errorCode)
    }

    @Test
    fun `a stream that says it has ended stops the writes`() {
        val stream = FakeStream().apply { ended = true }
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output(FakeStreams(stream = stream)).write(pcm(1), 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_STREAM_ENDED, thrown.errorCode)
    }

    @Test
    fun `release closes the stream once and tells listeners once`() {
        val stream = FakeStream()
        val streams = FakeStreams(stream = stream)
        val output = output(streams)
        var released = 0
        output.addListener(
            object : AudioOutput.Listener {
                override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) = Unit
                override fun onOffloadDataRequest() = Unit
                override fun onOffloadPresentationEnded() = Unit
                override fun onUnderrun() = Unit
                override fun onReleased() {
                    released++
                }
            },
        )
        output.release()
        output.release()
        assertEquals(listOf<UsbOutputStream>(stream), streams.closed)
        assertEquals(1, released)
    }

    @Test
    fun `a stream that opens after release is closed at once`() {
        val stream = FakeStream()
        val streams = FakeStreams(answerAtOnce = false, stream = stream)
        val output = output(streams)
        output.release()
        assertEquals(emptyList<UsbOutputStream>(), streams.closed)
        streams.answer()
        assertEquals(listOf<UsbOutputStream>(stream), streams.closed)
    }

    @Test
    fun `no write gets through after release`() {
        val stream = FakeStream()
        val output = output(FakeStreams(stream = stream))
        output.release()
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(pcm(1), 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_RELEASED, thrown.errorCode)
        assertEquals(0, stream.received.size())
    }

    @Test
    fun `the position counts only media frames`() {
        val stream = FakeStream().apply {
            processed = 48_000
            submitted = 48_000
        }
        val output = output(FakeStreams(stream = stream))
        assertEquals(1_000_000, output.positionUs)
    }

    @Test
    fun `the position starts at zero and survives an unopened stream`() {
        assertEquals(0, output(FakeStreams(answerAtOnce = false)).positionUs)
    }

    @Test
    fun `time only fills in while it runs`() {
        val stream = FakeStream().apply { submitted = 48_000 }
        var now = 0L
        val output = output(FakeStreams(stream = stream), nanoTime = { now })
        output.positionUs
        now = 100_000_000
        assertEquals("Not playing yet", 0, output.positionUs)
        output.play()
        output.write(pcm(prefillFrames), 1, 0)
        output.positionUs
        now = 200_000_000
        assertEquals(100_000, output.positionUs)
    }

    @Test
    fun `a duck factor is ignored and leaves the audio alone`() {
        val stream = FakeStream()
        val ignored = mutableListOf<Float>()
        val output = output(FakeStreams(stream = stream), onVolumeIgnored = { ignored += it })
        output.setVolume(1f)
        output.setVolume(0.2f)
        val data = pcm(4)
        val expected = data.array().copyOf()
        output.write(data, 1, 0)
        assertEquals(listOf(0.2f), ignored)
        assertArrayEquals(expected, stream.received.toByteArray())
    }

    @Test
    fun `it says what the plan says`() {
        val output = output(FakeStreams())
        assertEquals(48_000, output.sampleRate)
        assertEquals(24_000, output.bufferSizeInFrames)
        assertFalse(output.isStalled)
        assertFalse(output.isOffloadedPlayback)
    }
}
