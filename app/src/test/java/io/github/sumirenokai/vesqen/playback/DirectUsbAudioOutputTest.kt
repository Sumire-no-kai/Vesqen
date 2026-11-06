package io.github.sumirenokai.vesqen.playback

import androidx.media3.exoplayer.audio.AudioOutput
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.pcm.UsbPcmEncoding
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure.Reason
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

    /** One output's handle as the session gives it; whether its epoch is acknowledged is up to the test. */
    private class FakeHandle(private val acceptAtMost: () -> Int = { Int.MAX_VALUE }) : UsbOutputHandle {
        override val epoch = 0L
        val received = ByteArrayOutputStream()
        val runningCalls = mutableListOf<Boolean>()
        var endMarks = 0
        var ended = false
        var acknowledged = true
        var processed = 0L
        var submitted = 0L
        var streamFailure = UsbStreamStats.Failure.NONE
        var releases = 0
        override var failure: DirectUsbFailure? = null

        override fun write(buffer: ByteBuffer, offset: Int, size: Int): Int {
            if (ended) return -1
            if (!acknowledged) return 0
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

        override fun readStats() = if (!acknowledged) {
            null
        } else {
            UsbStreamStats(
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
                failure = streamFailure,
                failureErrno = 0,
                threadNice = 0,
                threadSchedulerPolicy = 0,
                epoch = epoch,
                requestedEpoch = epoch,
            )
        }

        override fun release() {
            releases++
        }
    }

    private fun pcm(frames: Int) = ByteBuffer.wrap(ByteArray(frames * 4) { it.toByte() })

    private fun output(
        handle: UsbOutputHandle?,
        encoding: UsbPcmEncoding = UsbPcmEncoding.INT16,
        onFailure: (DirectUsbFailure) -> Unit = {},
        onVolumeIgnored: (Float) -> Unit = {},
        nanoTime: () -> Long = { 0 },
    ) = DirectUsbAudioOutput(plan, encoding, handle, onFailure, onVolumeIgnored, nanoTime)

    @Test
    fun `nothing reaches the stream until the last output's URBs are back`() {
        val handle = FakeHandle().apply { acknowledged = false }
        val output = output(handle)
        val data = pcm(16)
        assertFalse(output.write(data, 1, 0))
        assertEquals(0, handle.received.size())
        handle.acknowledged = true
        assertTrue(output.write(data, 1, 0))
        assertArrayEquals(pcm(16).array(), handle.received.toByteArray())
    }

    @Test
    fun `everything arrives unchanged and in order however little the ring takes`() {
        val handle = FakeHandle(acceptAtMost = { 12 })
        val output = output(handle)
        val data = pcm(10_000)
        val expected = data.array().copyOf()
        var calls = 0
        while (!output.write(data, 1, 0)) {
            assertEquals("Only whole frames", 0, handle.received.size() % 4)
            calls++
        }
        assertTrue(calls > 1)
        assertArrayEquals(expected, handle.received.toByteArray())
    }

    @Test
    fun `a full ring keeps the rest for the next call`() {
        var room = 8
        val handle = FakeHandle(acceptAtMost = { room })
        val output = output(handle)
        val data = pcm(16)
        assertFalse(output.write(data, 1, 0))
        room = Int.MAX_VALUE
        assertTrue("An empty buffer still drains what was converted", output.write(data, 1, 0))
        assertEquals(64, handle.received.size())
    }

    @Test
    fun `playing waits for a little audio in the ring`() {
        val handle = FakeHandle()
        val output = output(handle)
        output.play()
        output.write(pcm(prefillFrames - 1), 1, 0)
        assertEquals(emptyList<Boolean>(), handle.runningCalls)
        output.write(pcm(1), 1, 0)
        assertEquals(listOf(true), handle.runningCalls)
    }

    @Test
    fun `a track too short to fill it still plays once it ends`() {
        val handle = FakeHandle()
        val output = output(handle)
        output.play()
        output.write(pcm(10), 1, 0)
        assertEquals(emptyList<Boolean>(), handle.runningCalls)
        output.stop()
        assertEquals(listOf(true), handle.runningCalls)
        assertEquals(1, handle.endMarks)
    }

    @Test
    fun `written while paused, it does not start until asked`() {
        val handle = FakeHandle()
        val output = output(handle)
        output.write(pcm(prefillFrames), 1, 0)
        assertEquals(emptyList<Boolean>(), handle.runningCalls)
        output.play()
        assertEquals(listOf(true), handle.runningCalls)
    }

    @Test
    fun `pause stops it at once and play starts it again`() {
        val handle = FakeHandle()
        val output = output(handle)
        output.play()
        output.write(pcm(prefillFrames), 1, 0)
        output.pause()
        output.play()
        assertEquals(listOf(true, false, true), handle.runningCalls)
    }

    @Test
    fun `the end is marked once`() {
        val handle = FakeHandle()
        val output = output(handle)
        output.stop()
        output.stop()
        output.positionUs
        assertEquals(1, handle.endMarks)
    }

    @Test
    fun `audio that is not exact stops the stream instead of being changed`() {
        val handle = FakeHandle()
        val output = output(handle, UsbPcmEncoding.FLOAT)
        val floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f / 65536f).putFloat(0f)
        floats.flip()
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(floats, 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_INEXACT_PCM, thrown.errorCode)
        assertFalse(thrown.isRecoverable)
        assertEquals(0, handle.received.size())
    }

    @Test
    fun `audio that stops being exact partway through stops the stream too`() {
        val handle = FakeHandle()
        val output = output(handle, UsbPcmEncoding.FLOAT)
        val floats = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.5f).putFloat(0.5f).putFloat(1f / 65536f).putFloat(0f)
        floats.flip()
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(floats, 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_INEXACT_PCM, thrown.errorCode)
        assertEquals("The frames before it never reach the ring", 0, handle.received.size())
    }

    @Test
    fun `less than a frame cannot wait forever`() {
        val output = output(FakeHandle())
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(ByteBuffer.wrap(ByteArray(3)), 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_INEXACT_PCM, thrown.errorCode)
    }

    @Test
    fun `no write gets through after release`() {
        val handle = FakeHandle()
        val output = output(handle)
        output.release()
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(pcm(1), 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_RELEASED, thrown.errorCode)
        assertEquals(0, handle.received.size())
    }

    @Test
    fun `the position counts only media frames`() {
        val handle = FakeHandle().apply {
            processed = 48_000
            submitted = 48_000
        }
        val output = output(handle)
        assertEquals(1_000_000, output.positionUs)
    }

    @Test
    fun `time only fills in while it runs`() {
        val handle = FakeHandle().apply { submitted = 48_000 }
        var now = 0L
        val output = output(handle, nanoTime = { now })
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
        val handle = FakeHandle()
        val ignored = mutableListOf<Float>()
        val output = output(handle, onVolumeIgnored = { ignored += it })
        output.setVolume(1f)
        output.setVolume(0.2f)
        val data = pcm(4)
        val expected = data.array().copyOf()
        output.write(data, 1, 0)
        assertEquals(listOf(0.2f), ignored)
        assertArrayEquals(expected, handle.received.toByteArray())
    }

    @Test
    fun `it says what the plan says`() {
        val output = output(FakeHandle())
        assertEquals(48_000, output.sampleRate)
        assertEquals(24_000, output.bufferSizeInFrames)
        assertFalse(output.isStalled)
        assertFalse(output.isOffloadedPlayback)
    }
    @Test
    fun `an end requested before the acknowledgement is still delivered`() {
        val handle = FakeHandle().apply { acknowledged = false }
        val output = output(handle)
        output.stop()
        output.positionUs
        assertEquals(1, handle.endMarks)
    }

    @Test
    fun `a failed stream stops the writes and is reported once`() {
        val handle = FakeHandle().apply { streamFailure = UsbStreamStats.Failure.DEVICE_GONE }
        val failures = mutableListOf<DirectUsbFailure>()
        val output = output(handle, onFailure = { failures += it })
        repeat(2) {
            val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(pcm(1), 1, 0) }
            assertEquals(DirectUsbAudioOutput.ERROR_STREAM_FAILED, thrown.errorCode)
        }
        output.positionUs
        assertEquals(listOf(Reason.DEVICE_DISCONNECTED), failures.map { it.reason })
    }

    @Test
    fun `a failure while only waiting for the end is reported from the position`() {
        val handle = FakeHandle()
        val failures = mutableListOf<DirectUsbFailure>()
        val output = output(handle, onFailure = { failures += it })
        output.stop()
        handle.streamFailure = UsbStreamStats.Failure.REAP
        output.positionUs
        assertEquals(listOf(Reason.USB_TRANSFER_FAILED), failures.map { it.reason })
    }

    @Test
    fun `a session that failed stops the writes and says why`() {
        val failure = DirectUsbFailure(Reason.USB_TRANSFER_FAILED, "the last output's URBs were not back within 1000 ms")
        val handle = FakeHandle().apply { this.failure = failure }
        val failures = mutableListOf<DirectUsbFailure>()
        val output = output(handle, onFailure = { failures += it })
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(pcm(1), 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_STREAM_FAILED, thrown.errorCode)
        assertEquals(listOf(failure), failures)
    }

    @Test
    fun `an output refused partway because the session failed says why rather than ended`() {
        val handle = FakeHandle()
        val failures = mutableListOf<DirectUsbFailure>()
        val output = output(handle, onFailure = { failures += it })
        output.write(pcm(4), 1, 0)
        handle.ended = true
        handle.failure = DirectUsbFailure(Reason.DEVICE_DISCONNECTED, "the device was detached")
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(pcm(4), 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_STREAM_FAILED, thrown.errorCode)
        assertEquals(listOf(Reason.DEVICE_DISCONNECTED), failures.map { it.reason })
    }

    @Test
    fun `an output the session could not give stops the writes`() {
        val output = output(handle = null)
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output.write(pcm(1), 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_NO_STREAM, thrown.errorCode)
        assertEquals(0, output.positionUs)
    }

    @Test
    fun `an output the stream has ended stops the writes`() {
        val handle = FakeHandle().apply { ended = true }
        val thrown = assertThrows(AudioOutput.WriteException::class.java) { output(handle).write(pcm(1), 1, 0) }
        assertEquals(DirectUsbAudioOutput.ERROR_STREAM_ENDED, thrown.errorCode)
    }

    @Test
    fun `release ends the output once and tells listeners once`() {
        val handle = FakeHandle()
        val output = output(handle)
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
        assertEquals(1, handle.releases)
        assertEquals(1, released)
    }

    @Test
    fun `the position is zero until the stream reaches this output`() {
        val handle = FakeHandle().apply {
            acknowledged = false
            processed = 48_000
            submitted = 48_000
        }
        val output = output(handle)
        assertEquals(0, output.positionUs)
        handle.acknowledged = true
        assertEquals(1_000_000, output.positionUs)
    }

    @Test
    fun `the provider asks the session for a new output each time`() {
        val handles = mutableListOf<UsbOutputHandle>()
        val provider = DirectUsbAudioOutputProvider(plan, openOutput = { FakeHandle().also(handles::add) })
        val config = androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(androidx.media3.common.C.ENCODING_PCM_16BIT)
            .setSampleRate(48_000)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
            .setBufferSize(96_000)
            .build()
        provider.getAudioOutput(config).release()
        provider.getAudioOutput(config)
        assertEquals(2, handles.size)
        assertEquals(1, (handles[0] as FakeHandle).releases)
        assertEquals(0, (handles[1] as FakeHandle).releases)
    }
}
