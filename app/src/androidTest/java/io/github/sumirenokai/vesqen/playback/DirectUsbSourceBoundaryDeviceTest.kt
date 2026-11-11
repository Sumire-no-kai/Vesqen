package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceFacts
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import io.github.sumirenokai.vesqen.usb.session.UsbOutputHandle
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #282 with a real Media3 queue: two FLAC tracks of equal or different depths decode to the same float
 * format, so the strict sink keeps one own output across the natural change. Every byte that
 * reaches the USB handle must be the source sample at the top of a 24-bit subslot.
 *
 * The source facts follow the input format the renderer configures the sink for, which is what the
 * service has to provide in M7-4. No DAC is involved: the handle records what it is given.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DirectUsbSourceBoundaryDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "usb-source-boundary")

    @After
    fun deleteFixtures() {
        directory.deleteRecursively()
    }

    @Test
    fun aTwentyFourBitTrackAfterASixteenBitOne() = naturalChange(firstBits = 16, secondBits = 24)

    @Test
    fun aSixteenBitTrackAfterATwentyFourBitOne() = naturalChange(firstBits = 24, secondBits = 16)

    @Test
    fun twoSixteenBitTracksKeepSeparateDeliveryEvidence() = naturalChange(firstBits = 16, secondBits = 16)

    @Test
    fun aFortyEightKilohertzTrackThenAFortyFourKilohertzTrack() =
        naturalChange(16, 24, secondRate = 44_100)

    @Test
    fun aFortyFourKilohertzTrackThenAFortyEightKilohertzTrack() =
        naturalChange(24, 16, firstRate = 44_100)

    private fun naturalChange(firstBits: Int, secondBits: Int, firstRate: Int = 48_000, secondRate: Int = 48_000) {
        val first = PcmFixture(PcmContainer.FLAC, firstRate, firstBits, DURATION_MS, seed = 1)
        val second = PcmFixture(PcmContainer.FLAC, secondRate, secondBits, DURATION_MS, seed = 2)
        val files = listOf(first.write(directory), second.write(directory))
        val handles = mutableListOf<RecordingHandle>()
        val failures = mutableListOf<DirectUsbFailure>()
        var outputsOpened = 0
        var period: Any? = null
        val activePeriods = linkedSetOf<Any?>()
        val events = java.util.Collections.synchronizedList(mutableListOf<Pair<Any?, Boolean>>())
        var source: TelemetrySourceFacts? = null
        val provider = DirectUsbAudioOutputProvider(
            PLAN,
            openOutput = {
                outputsOpened++
                RecordingHandle().also(handles::add)
            },
            planForSource = { format -> PLAN.copy(sampleRateHz = format.sampleRateHz) },
            source = { source },
            onFailure = { failures += it },
            listenerFactory = {
                val outputPeriod = period
                object : DirectUsbAudioOutput.PlaybackListener {
                    override fun onActiveChanged(active: Boolean) {
                        if (active) activePeriods += outputPeriod
                        events += outputPeriod to active
                    }
                    override fun onFailed(failure: DirectUsbFailure) { failures += failure }
                }
            },
        )
        val sink = StrictPcmAudioSink(provider) { period = it.mediaPeriodId?.periodUid }
        val renderers = RenderersFactory { handler: Handler, _, audioListener, _, _ ->
            arrayOf(
                object : MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, sink) {
                    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
                        source = TelemetrySourceFacts(
                            codecMime = format.sampleMimeType,
                            sampleRateHz = format.sampleRate,
                            bitDepth = bitsOf(format.pcmEncoding),
                            channelCount = format.channelCount,
                        )
                        super.onOutputFormatChanged(format, mediaFormat)
                    }
                },
            )
        }
        val thread = HandlerThread("usb-source-boundary").apply { start() }
        val ended = CountDownLatch(1)
        val error = AtomicReference<PlaybackException?>()
        val player = onThread(thread) {
            ExoPlayer.Builder(context, renderers).setLooper(thread.looper).build().apply {
                addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) ended.countDown()
                        }

                        override fun onPlayerError(playbackError: PlaybackException) {
                            error.set(playbackError)
                            ended.countDown()
                        }
                    },
                )
                setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) })
                prepare()
                play()
            }
        }
        try {
            assertTrue("Playback did not end", ended.await(TIMEOUT_S, TimeUnit.SECONDS))
            assertEquals("Own-output failures", emptyList<DirectUsbFailure.Reason>(), failures.map { it.reason })
            assertNull("Player error, caused by ${error.get()?.cause}", error.get())
            assertEquals("The output changes only with the wire configuration", if (firstRate == secondRate) 1 else 2, outputsOpened)
            assertEquals("Delivery for both playlist periods", 2, activePeriods.filterNotNull().size)
            // A1 (#314): the first item's tail is still in the ring when the second one begins. The
            // second is reported before the first retires, so playback never looks idle in between.
            val playing = events.toList().runningFold(0) { count, (_, active) -> count + if (active) 1 else -1 }.drop(1)
            if (firstRate == secondRate) {
                assertTrue("A gap between the two items: $events", playing.dropLast(1).all { it > 0 })
            }
            assertArrayEquals(wire(first) + wire(second), handles.fold(ByteArray(0)) { bytes, handle -> bytes + handle.received.toByteArray() })
        } finally {
            onThread(thread) { player.release() }
            thread.quitSafely()
        }
    }

    private fun bitsOf(pcmEncoding: Int) = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        else -> null
    }

    /** The fixture's samples as a 24-bit wire carries them: at the top of three little-endian bytes. */
    private fun wire(fixture: PcmFixture): ByteArray {
        val bytes = ByteArray(fixture.samples.size * 3)
        fixture.samples.forEachIndexed { index, sample ->
            val value = sample shl (24 - fixture.bitDepth)
            for (byte in 0 until 3) bytes[index * 3 + byte] = (value shr (8 * byte)).toByte()
        }
        return bytes
    }

    private fun <T> onThread(thread: HandlerThread, block: () -> T): T {
        val result = AtomicReference<T>()
        val done = CountDownLatch(1)
        Handler(thread.looper).post {
            result.set(block())
            done.countDown()
        }
        assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS))
        return result.get()
    }

    /**
     * Takes everything at once, so the player runs as fast as it decodes, and reports it delivered
     * [DELIVERY_LAG_FRAMES] behind, the way a ring of queued audio would, until the end is marked.
     */
    private class RecordingHandle : UsbOutputHandle {
        val received = ByteArrayOutputStream()
        private var frames = 0L
        private var ended = false
        override val epoch = 0L
        override val failure: DirectUsbFailure? = null

        @Synchronized
        override fun write(buffer: ByteBuffer, offset: Int, size: Int): Int {
            val bytes = ByteArray(size)
            buffer.duplicate().apply { position(offset) }.get(bytes)
            received.write(bytes)
            frames += size / PLAN.frameBytes
            return size
        }

        override fun setRunning(running: Boolean) = Unit

        @Synchronized
        override fun markEndOfStream() {
            ended = true
        }

        @Synchronized
        override fun readStats(): UsbStreamStats {
            val delivered = if (ended) frames else maxOf(0, frames - DELIVERY_LAG_FRAMES)
            return UsbStreamStats(
                mediaFramesWritten = frames, mediaFramesSubmitted = frames, mediaFramesProcessed = delivered,
                mediaFramesDelivered = delivered, mediaFramesLost = 0, silenceFramesInserted = 0, usbFramesDelivered = delivered,
                underruns = 0, urbsCompleted = 0, packetsFailed = 0, failure = UsbStreamStats.Failure.NONE, failureErrno = 0,
                threadNice = 0, threadSchedulerPolicy = 0, epoch = 0, requestedEpoch = 0,
            )
        }

        override fun release() = Unit
    }

    private companion object {
        const val DURATION_MS = 2_000
        /** A quarter second at 48 kHz, half of PLAN's ring. */
        const val DELIVERY_LAG_FRAMES = 12_000L
        const val TIMEOUT_S = 30L

        /** A 24-bit stereo wire at 48 kHz, so both source depths fit without a new configuration. */
        val PLAN = StreamingPlan(
            interfaceNumber = 2, alternateSetting = 2, endpointAddress = 3, endpointSetsSampleRate = true, sampleRateHz = 48_000,
            channels = 2, subslotBytes = 3, bitResolution = 24, serviceIntervalUs = 1000, maxPacketBytes = 288,
            packetsPerUrb = 32, urbCount = 3, ringFrames = 24_000,
        )
    }
}
