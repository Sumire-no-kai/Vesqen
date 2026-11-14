package io.github.sumirenokai.vesqen.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.control.UsbControlTransfer
import io.github.sumirenokai.vesqen.usb.descriptor.UacDescriptorParser
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacParseResult
import io.github.sumirenokai.vesqen.usb.engine.UsbStream
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlanResult
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlanner
import io.github.sumirenokai.vesqen.usb.plan.UsbBusSpeed
import io.github.sumirenokai.vesqen.usb.session.DirectUsbSession
import io.github.sumirenokai.vesqen.usb.session.TakeoverMarker
import io.github.sumirenokai.vesqen.usb.session.TakeoverMarkerStore
import io.github.sumirenokai.vesqen.usb.session.UsbDeviceIdentity
import io.github.sumirenokai.vesqen.usb.session.UsbLink
import io.github.sumirenokai.vesqen.usb.session.UsbStreamFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #284 step 4: the whole own path from the strict sink through [DirectUsbAudioOutput] and a real
 * [DirectUsbSession] to a stream that keeps epochs as the native one does. Every frame carries its own
 * number, so what reaches the wire shows each output's first frame, its order, and any repeat or gap
 * across a seek, a track that keeps the output, and a track that needs another wire format.
 *
 * The native core already checks the same boundaries at the URB level (core_test.cpp, #278); this
 * covers what the Kotlin layers hand to it. The stream delivers what it accepts at once, so a frame
 * on the wire here is one the layers above wrote under that epoch.
 */
@RunWith(RobolectricTestRunner::class)
class DirectUsbFrameOrderTest {
    private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "usb-control") }
    private val streams = CopyOnWriteArrayList<NumberedStream>()

    /** For each stream created, whether every one before it had been destroyed by then. */
    private val earlierStreamsGone = CopyOnWriteArrayList<Boolean>()
    private val function = jblLikeDevice()
    private val plan16 = plan(16)
    private val plan24 = plan(24)
    private var source = flac(16)
    private val session = DirectUsbSession(
        device = UsbDeviceIdentity(deviceId = 7, vendorId = 0x1234, productId = 0x5678),
        link = FakeLink(),
        function = function,
        markers = MemoryMarkers(),
        control = control,
        streams = UsbStreamFactory { _, plan ->
            earlierStreamsGone += streams.all { it.destroyed }
            UsbStreamFactory.Creation.Created(NumberedStream(plan.frameBytes).also(streams::add))
        },
        timing = DirectUsbSession.Timing(monitorPeriodMs = 10, retireTimeoutMs = 5_000, destroyTimeoutMs = 1, destroyRetryMs = 10),
    )
    private val sink by lazy {
        val provider = DirectUsbAudioOutputProvider(
            plan16,
            openOutput = session::openOutput,
            openPlannedOutput = session::openOutput,
            // As DirectUsbRoute does: keep the wire format while it carries the track, else plan for its depth.
            planForSource = { wanted ->
                val active = (session.state as? DirectUsbSession.State.Streaming)?.plan ?: plan16
                if (active.carries(wanted)) active else plan(wanted.bitDepth)
            },
            source = { source },
        )
        StrictPcmAudioSink(provider).apply { play() }
    }

    @After
    fun releaseSession() {
        val released = CountDownLatch(1)
        session.release { released.countDown() }
        assertTrue(released.await(2, TimeUnit.SECONDS))
        control.shutdownNow()
    }

    /** D14. The output between the two seeks is handed out, so it takes an epoch of its own. */
    @Test
    fun `a second seek while the first output's urbs are still out starts at its own first frame`() {
        val first = playThenSeekWhileRetiring()
        // The session hands the seek's output out on the control thread; let it.
        control.submit {}.get(2, TimeUnit.SECONDS)
        sink.flush()
        val target = numbered(900_001, 3_000, bits = 16)
        assertFalse(sink.handleBuffer(target, 18_750_000, 1))
        first.acknowledge()
        feed(target, 18_750_000)

        assertEquals("Only the streams the session needed", 1, streams.size)
        assertEquals(listOf(0L, 1L, 2L), first.epochs())
        assertEquals("Not the 300 frames the first output still held", (1..4_700).toList(), first.frames(epoch = 0, bits = 16))
        assertEquals("The output between the two seeks wrote nothing", emptyList<Int>(), first.frames(epoch = 1, bits = 16))
        assertEquals((900_001..903_000).toList(), first.frames(epoch = 2, bits = 16))
    }

    /** As above, but the second seek comes before the control thread could hand out the first one's output. */
    @Test
    fun `a second seek before the first seek's output is handed out starts at its own first frame`() {
        val busy = CountDownLatch(1)
        val first = playThenSeekWhileRetiring { control.execute { busy.await(5, TimeUnit.SECONDS) } }
        sink.flush()
        val target = numbered(900_001, 3_000, bits = 16)
        assertFalse(sink.handleBuffer(target, 18_750_000, 1))
        busy.countDown()
        first.acknowledge()
        feed(target, 18_750_000)

        assertEquals("The output that was never handed out took no epoch", listOf(0L, 1L), first.epochs())
        assertEquals((1..4_700).toList(), first.frames(epoch = 0, bits = 16))
        assertEquals((900_001..903_000).toList(), first.frames(epoch = 1, bits = 16))
    }

    /**
     * Plays frames 1 to 4,700, of which the first output still holds 300 more when a seek releases it,
     * since the stream takes 700 frames at a time. The output's URBs stay out, so the seek's output
     * can write nothing yet. [beforeSeek] runs just before that seek.
     */
    private fun playThenSeekWhileRetiring(beforeSeek: () -> Unit = {}): NumberedStream {
        takeOver()
        configure(C.ENCODING_PCM_16BIT)
        feed(numbered(1, 4_000, bits = 16), firstUs = 0)
        val first = streams.single()
        first.holdRetirements = true
        assertFalse(sink.handleBuffer(numbered(4_001, 1_000, bits = 16), usOf(4_000), 1))
        beforeSeek()
        sink.flush()
        assertFalse(sink.handleBuffer(numbered(500_001, 1_000, bits = 16), 10_000_000, 1))
        return first
    }

    /** An output is handed out on the control thread, which may be busy, for example with the volume. */
    @Test
    fun `writes made before the session hands out the output are not taken`() {
        takeOver()
        configure(C.ENCODING_PCM_16BIT)
        val busy = CountDownLatch(1)
        control.execute { busy.await(5, TimeUnit.SECONDS) }
        val first = numbered(1, 1_000, bits = 16)
        repeat(3) { assertFalse(sink.handleBuffer(first, 0, 1)) }
        busy.countDown()
        feed(first, firstUs = 0)
        feed(numbered(1_001, 2_000, bits = 16), firstUs = usOf(1_000))

        assertEquals((1..3_000).toList(), streams.single().frames(epoch = 0, bits = 16))
    }

    @Test
    fun `a next track in the same format follows the last one without a gap or a repeat`() {
        takeOver()
        configure(C.ENCODING_PCM_16BIT)
        feed(numbered(1, 3_333, bits = 16), firstUs = 0)
        source = flac(16)
        configure(C.ENCODING_PCM_16BIT)
        feed(numbered(1_000_001, 2_500, bits = 16), firstUs = usOf(3_333))

        val stream = streams.single()
        assertEquals("The output and its epoch are kept", listOf(0L), stream.epochs())
        assertEquals((1..3_333).toList() + (1_000_001..1_002_500).toList(), stream.frames(epoch = 0, bits = 16))
    }

    @Test
    fun `a next track that needs a deeper format waits for the last tail and starts a new stream at its first frame`() {
        takeOver()
        configure(C.ENCODING_PCM_16BIT)
        feed(numbered(1, 3_333, bits = 16), firstUs = 0)
        val old = streams.single()
        // The last 667 frames are still in the ring when the next track is configured.
        old.holdDelivery = true
        feed(numbered(3_334, 667, bits = 16), firstUs = usOf(3_333))
        source = flac(24)
        configure(C.ENCODING_PCM_24BIT)
        val next = numbered(2_000_001, 2_500, bits = 24)
        repeat(3) { assertFalse(sink.handleBuffer(next, usOf(4_000), 1)) }
        assertEquals("Nothing is rebuilt while the last tail plays", 1, streams.size)
        old.deliver()
        feed(next, firstUs = usOf(4_000))

        assertEquals("The session rebuilt its stream for the 24-bit setting", 2, streams.size)
        val new = streams.last()
        assertEquals("The old stream was gone before the new one was created", listOf(true, true), earlierStreamsGone.toList())
        assertEquals("The last track's tail went out in full", (1..4_000).toList(), old.frames(epoch = 0, bits = 16))
        assertEquals(listOf(0L), new.epochs())
        assertEquals((2_000_001..2_002_500).toList(), new.frames(epoch = 0, bits = 24))
        assertEquals(plan24, (session.state as DirectUsbSession.State.Streaming).plan)
    }

    @Test
    fun `a seek after a format change still starts at the target frame`() {
        takeOver()
        configure(C.ENCODING_PCM_16BIT)
        feed(numbered(1, 2_000, bits = 16), firstUs = 0)
        source = flac(24)
        configure(C.ENCODING_PCM_24BIT)
        feed(numbered(2_000_001, 1_500, bits = 24), firstUs = usOf(2_000))
        sink.flush()
        feed(numbered(2_100_001, 1_200, bits = 24), firstUs = 5_000_000)

        val new = streams.last()
        assertEquals(listOf(0L, 1L), new.epochs())
        assertEquals((2_000_001..2_001_500).toList(), new.frames(epoch = 0, bits = 24))
        assertEquals((2_100_001..2_101_200).toList(), new.frames(epoch = 1, bits = 24))
    }

    private fun takeOver() {
        val done = CountDownLatch(1)
        var result: DirectUsbSession.Takeover? = null
        session.takeOver(plan16, startVolume = -15_400) {
            result = it
            done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue("$result", result is DirectUsbSession.Takeover.Ready)
    }

    private fun configure(encoding: Int) {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding)
            .setSampleRate(RATE)
            .setChannelCount(2)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
    }

    /** Hands [buffer] over as Media3 does: the same buffer again until it is fully taken. */
    private fun feed(buffer: ByteBuffer, firstUs: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!sink.handleBuffer(buffer, firstUs, 1)) {
            check(System.nanoTime() < deadline) { "The sink never took the buffer" }
            Thread.sleep(1)
        }
    }

    private fun usOf(frames: Int) = frames * 1_000_000L / RATE

    private fun flac(bits: Int) = TelemetrySourceFacts(codecMime = "audio/flac", sampleRateHz = RATE, bitDepth = bits, channelCount = 2)

    private fun plan(bits: Int): StreamingPlan =
        (StreamingPlanner.plan(function, UsbBusSpeed.FULL, RATE, bits) as StreamingPlanResult.Planned).plan

    /**
     * Keeps epochs as the native stream does: an epoch that is not the newest is refused, and the newest
     * waits until the last one's URBs are back. Its ring takes [CHUNK_FRAMES] at a time and sends what it
     * holds at once unless [holdDelivery] is set. A retirement drops what the ring still holds, and
     * nothing goes out once the stream is destroyed. [frames] reads only what went out.
     */
    private class NumberedStream(private val frameBytes: Int) : UsbStream {
        @Volatile var holdRetirements = false
        @Volatile var holdDelivery = false
        @Volatile var destroyed = false
        private var requested = 0L
        private var acknowledged = 0L
        private val accepted = linkedMapOf(0L to ByteArrayOutputStream())
        private val sentBytes = mutableMapOf<Long, Int>()

        @Synchronized fun acknowledge() {
            holdRetirements = false
            settle()
        }

        @Synchronized fun deliver() {
            holdDelivery = false
            settle()
        }

        @Synchronized fun epochs(): List<Long> = accepted.keys.toList()

        /** The numbers of the frames epoch [epoch] sent, in order. */
        @Synchronized fun frames(epoch: Long, bits: Int): List<Int> {
            val bytes = accepted[epoch]?.toByteArray()?.copyOf(sentBytes[epoch] ?: 0) ?: return emptyList()
            val sampleBytes = bits / 8
            return (0 until bytes.size / (2 * sampleBytes)).map { frame ->
                val left = sample(bytes, frame * 2 * sampleBytes, sampleBytes)
                val right = sample(bytes, frame * 2 * sampleBytes + sampleBytes, sampleBytes)
                left or (right shl bits)
            }
        }

        override fun start() = true

        @Synchronized override fun write(epoch: Long, buffer: ByteBuffer, offset: Int, size: Int): Int {
            settle()
            if (destroyed || epoch != requested) return -1
            if (acknowledged != requested) return 0
            // The engine refuses a partial frame, and its ring takes what fits.
            if (size % frameBytes != 0) return -1
            val taken = minOf(size, CHUNK_FRAMES * frameBytes)
            val bytes = ByteArray(taken)
            buffer.duplicate().apply { position(offset) }.get(bytes)
            accepted.getValue(epoch).write(bytes)
            settle()
            return taken
        }

        override fun setRunning(epoch: Long, running: Boolean) = Unit

        override fun markEndOfStream(epoch: Long) = Unit

        @Synchronized override fun retireOutput(): Long {
            settle()
            requested++
            accepted[requested] = ByteArrayOutputStream()
            return requested
        }

        @Synchronized override fun readStats(): UsbStreamStats {
            settle()
            val written = if (acknowledged == requested) accepted.getValue(acknowledged).size().toLong() / frameBytes else 0
            val sent = if (acknowledged == requested) (sentBytes[acknowledged] ?: 0).toLong() / frameBytes else 0
            return UsbStreamStats(
                mediaFramesWritten = written, mediaFramesSubmitted = sent, mediaFramesProcessed = sent,
                mediaFramesDelivered = sent, mediaFramesLost = 0, silenceFramesInserted = 0, usbFramesDelivered = sent,
                underruns = 0, urbsCompleted = 0, packetsFailed = 0, failure = UsbStreamStats.Failure.NONE,
                failureErrno = 0, threadNice = 0, threadSchedulerPolicy = 0, epoch = acknowledged, requestedEpoch = requested,
            )
        }

        @Synchronized override fun destroy(timeoutMs: Int): Boolean {
            destroyed = true
            return true
        }

        /** Only the newest epoch sends, and only once it is acknowledged. */
        private fun settle() {
            if (!holdRetirements) acknowledged = requested
            if (!holdDelivery && !destroyed && acknowledged == requested) sentBytes[requested] = accepted.getValue(requested).size()
        }

        private fun sample(bytes: ByteArray, at: Int, size: Int): Int =
            (0 until size).fold(0) { value, i -> value or ((bytes[at + i].toInt() and 0xFF) shl (8 * i)) }
    }

    /** A UAC1 device that answers volume, mute and the endpoint's sampling frequency. */
    private class FakeLink : UsbLink {
        private var volume = -2_560
        private var muted = false
        private var rate = 44_100

        override val fileDescriptor = 3

        override val control = UsbControlTransfer { requestType, request, value, _, buffer, length, _ ->
            val selector = value shr 8
            when (requestType) {
                0xA1 -> {
                    val answer = when {
                        selector == 0x02 && request == 0x82 -> -15_400
                        selector == 0x02 && request == 0x83 -> 0
                        selector == 0x02 && request == 0x84 -> 154
                        selector == 0x02 && request == 0x81 -> volume
                        selector == 0x01 && request == 0x81 -> if (muted) 1 else 0
                        else -> return@UsbControlTransfer -1
                    }
                    buffer[0] = answer.toByte()
                    if (length > 1) buffer[1] = (answer shr 8).toByte()
                    length
                }
                0x21 -> {
                    if (selector == 0x01) muted = buffer[0].toInt() != 0
                    if (selector == 0x02) volume = (buffer[0].toInt() and 0xFF) or (buffer[1].toInt() shl 8)
                    length
                }
                0xA2 -> {
                    buffer[0] = rate.toByte()
                    buffer[1] = (rate shr 8).toByte()
                    buffer[2] = (rate shr 16).toByte()
                    3
                }
                0x22 -> {
                    rate = (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8) or ((buffer[2].toInt() and 0xFF) shl 16)
                    3
                }
                else -> -1
            }
        }

        override fun claim(interfaceNumber: Int) = true

        override fun release(interfaceNumber: Int) = true

        override fun selectAlternate(interfaceNumber: Int, alternateSetting: Int) = true

        override fun close() = Unit
    }

    private class MemoryMarkers : TakeoverMarkerStore() {
        private var stored: TakeoverMarker? = null

        override fun write(marker: TakeoverMarker): Boolean {
            stored = marker
            return true
        }

        override fun read() = stored

        override fun hasPendingRecovery() = stored != null

        override fun clear(): Boolean {
            stored = null
            return true
        }
    }

    private companion object {
        const val RATE = 48_000
        const val CHUNK_FRAMES = 700

        /**
         * Stereo frames whose two samples together hold the frame's number: the low bits on the left,
         * the high bits on the right. Every value lies on the source's lattice, so it reaches the wire
         * unchanged.
         */
        fun numbered(first: Int, frames: Int, bits: Int): ByteBuffer {
            val sampleBytes = bits / 8
            val mask = (1 shl bits) - 1
            val buffer = ByteBuffer.allocateDirect(frames * 2 * sampleBytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { i ->
                val number = first + i
                for (sample in intArrayOf(number and mask, (number ushr bits) and mask)) {
                    repeat(sampleBytes) { b -> buffer.put((sample shr (8 * b)).toByte()) }
                }
            }
            buffer.flip()
            return buffer
        }

        /**
         * A UAC1 device laid out like the JBL Flip 7 (docs/M7_DEVICE_ACCEPTANCE.md): USB streaming terminal,
         * feature unit with master mute and volume, speaker; 48 kHz at 16 and 24 bits, adaptive, with a
         * sampling frequency control on the endpoint.
         */
        fun jblLikeDevice(): UacFunction {
            fun descriptor(type: Int, vararg body: Int): ByteArray =
                (intArrayOf(body.size + 2, type) + body).map(Int::toByte).toByteArray()
            fun le16(value: Int) = intArrayOf(value and 0xFF, value shr 8 and 0xFF)
            fun le24(value: Int) = le16(value) + (value shr 16 and 0xFF)
            fun audioInterface(number: Int, alternate: Int, subclass: Int, endpoints: Int) =
                descriptor(0x04, number, alternate, endpoints, 0x01, subclass, 0x00, 0)
            fun classInterface(subtype: Int, vararg body: Int) = descriptor(0x24, subtype, *body)
            fun setting(alternate: Int, subslot: Int, bits: Int) =
                audioInterface(1, alternate, 0x02, endpoints = 1) +
                    classInterface(0x01, 1, 0, *le16(1)) + // AS General: terminal 1, PCM
                    classInterface(0x02, 1, 2, subslot, bits, 1, *le24(RATE)) + // Type I: stereo, one rate
                    descriptor(0x05, 0x01, 0x09, *le16(48 * 2 * subslot), 1, 0, 0) + // OUT 1: isochronous, adaptive
                    descriptor(0x25, 0x01, 0b1, 0, *le16(0)) // Sampling frequency control

            val units = classInterface(0x02, 1, *le16(0x0101), 0, 2, *le16(0b11), 0, 0) + // Input Terminal 1: USB streaming
                classInterface(0x06, 2, 1, 1, 0b11, 0, 0, 0) + // Feature Unit 2: master mute and volume
                classInterface(0x03, 3, *le16(0x0301), 0, 2, 0) // Output Terminal 3: speaker
            val header = classInterface(0x01, *le16(0x0100), *le16(9 + units.size), 1, 1)
            val raw = descriptor(0x01, *le16(0x0110), 0, 0, 0, 64, *le16(0x1234), *le16(0x5678), *le16(0x0100), 1, 2, 3, 1) +
                descriptor(0x02, *le16(0), 2, 1, 0, 0x80, 50) +
                audioInterface(0, 0, 0x01, endpoints = 0) + header + units +
                audioInterface(1, 0, 0x02, endpoints = 0) + setting(1, subslot = 2, bits = 16) + setting(2, subslot = 3, bits = 24)
            return (UacDescriptorParser.parse(raw) as UacParseResult.Parsed).topology.functions.single()
        }
    }
}
