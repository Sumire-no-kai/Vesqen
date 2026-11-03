package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.media.MediaCrypto
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

internal enum class PcmGuard {
    /** What Media3 does today; these runs pin the fade-in, so an upgrade that changes it fails. */
    NONE,

    /** M7 D9: strict mode bypasses `DefaultAudioSink`. */
    STRICT_SINK,
}

/**
 * What Media3 actually hands to the audio output, compared byte for byte with the source PCM.
 * No DAC is involved: the output keeps the data instead of playing it (M7 D9).
 */
@RunWith(Parameterized::class)
@androidx.annotation.OptIn(UnstableApi::class)
internal class Media3PcmCaptureDeviceTest(private val guard: PcmGuard) {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "pcm-capture")

    @After
    fun deleteFixtures() {
        directory.deleteRecursively()
    }

    @Test
    fun wholeFile() = forEachFixture { fixture ->
        capture { session ->
            session.play(fixture.write(directory))
            session.awaitEnded()
            val output = session.outputs.single()
            if (!output.lostPrecision(fixture, "whole")) {
                val comparison = output.compareWith(fixture.samples, fixture)
                report("whole", fixture, comparison)
                assertStartAsExpected(fixture, comparison, "whole ${fixture.name}")
                assertWholeSource(comparison, "whole ${fixture.name}")
                session.assertEndedAt(fixture.durationUs, "whole ${fixture.name}")
            }
        }
    }

    @Test
    fun afterSeek() = forEachFixture { fixture ->
        capture { session ->
            session.play(fixture.write(directory))
            session.awaitCapturedFrames(fixture.sampleRateHz / 4L)
            session.seekTo(SEEK_POSITION_MS)
            session.awaitEnded()
            assertTrue("Seek should open a new output", session.outputs.size >= 2)
            val output = session.outputs.last()
            if (!output.lostPrecision(fixture, "seek")) {
                val comparison = output.compareWith(fixture.samples, fixture)
                report("seek", fixture, comparison)
                assertStartAsExpected(fixture, comparison, "seek ${fixture.name}")
                assertEquals("Lost frames at the end of ${fixture.name}", 0, comparison.missingTailFrames)
                val target = fixture.sampleRateHz * SEEK_POSITION_MS / 1_000
                assertTrue(
                    "Seek landed at frame ${comparison.referenceStartFrame}, target $target",
                    comparison.referenceStartFrame in target - fixture.sampleRateHz / 2..target + fixture.sampleRateHz / 10,
                )
            }
        }
    }

    @Test
    fun afterPrepare() = forEachFixture { fixture ->
        capture { session ->
            session.play(fixture.write(directory))
            session.awaitEnded()
            session.prepareFromStartAndPlay()
            session.awaitEnded()
            assertEquals("Prepare should open one more output", 2, session.outputs.size)
            val output = session.outputs.last()
            if (!output.lostPrecision(fixture, "prepare")) {
                val comparison = output.compareWith(fixture.samples, fixture)
                report("prepare", fixture, comparison)
                assertStartAsExpected(fixture, comparison, "prepare ${fixture.name}")
                assertWholeSource(comparison, "prepare ${fixture.name}")
            }
        }
    }

    @Test
    fun sameFormatTracks() = forEachFixture { first ->
        val second = PcmFixture(first.container, first.sampleRateHz, first.bitDepth, DURATION_MS, seed = 2)
        capture { session ->
            session.play(first.write(directory), second.write(directory))
            session.awaitEnded()
            val output = session.outputs.single()
            if (!output.lostPrecision(first, "same-format")) {
                val comparison = output.compareWith(first.samples + second.samples, first)
                report("same-format", first, comparison)
                assertStartAsExpected(first, comparison, "same-format ${first.name}")
                assertWholeSource(comparison, "same-format ${first.name}")
            }
        }
    }

    /**
     * Both FLAC tracks decode to float at the same rate, so Media3 sees one PCM format although
     * the sources differ. M7 has to decide about the wire format itself: see the architecture.
     */
    @Test
    fun mixedDepthTracks() {
        val first = PcmFixture(PcmContainer.FLAC, 48_000, 16, DURATION_MS, seed = 1)
        val second = PcmFixture(PcmContainer.FLAC, 48_000, 24, DURATION_MS, seed = 2)
        capture { session ->
            session.play(first.write(directory), second.write(directory))
            session.awaitEnded()
            val output = session.outputs.first()
            if (!output.lostPrecision(second, "mixed-depth")) {
                assertEquals("Float tracks at one rate should reuse the output", 1, session.outputs.size)
                val reference = IntArray(first.samples.size) { first.samples[it] shl 8 } + second.samples
                val comparison = output.compareWith(reference, second)
                report("mixed-depth", second, comparison)
                assertStartAsExpected(second, comparison, "mixed-depth")
                assertWholeSource(comparison, "mixed-depth")
            }
        }
    }

    @Test
    fun formatChanges() {
        for (container in PcmContainer.entries) {
            val tracks = listOf(
                PcmFixture(container, 44_100, 16, DURATION_MS, seed = 1),
                PcmFixture(container, 96_000, 24, DURATION_MS, seed = 2),
                PcmFixture(container, 48_000, 24, DURATION_MS, seed = 3),
                PcmFixture(container, 44_100, 16, DURATION_MS, seed = 4),
            )
            capture { session ->
                session.play(*tracks.map { it.write(directory) }.toTypedArray())
                session.awaitEnded()
                // Neighbouring tracks differ in sample rate, so this holds whatever the decoder delivers.
                assertEquals("Every format change should open one output", tracks.size, session.outputs.size)
                tracks.zip(session.outputs).filterNot { (fixture, output) ->
                    output.lostPrecision(fixture, "format-change")
                }.forEach { (fixture, output) ->
                    val comparison = output.compareWith(fixture.samples, fixture)
                    report("format-change", fixture, comparison)
                    assertStartAsExpected(fixture, comparison, "format-change ${fixture.name}")
                    assertWholeSource(comparison, "format-change ${fixture.name}")
                }
            }
        }
    }

    /** Shorter than the fade: the baseline fades all of it, and EOS must still close. */
    @Test
    fun trackShorterThanTheFade() {
        for (container in PcmContainer.entries) {
            val fixture = PcmFixture(container, 48_000, 16, SHORT_DURATION_MS, seed = 1)
            capture { session ->
                session.play(fixture.write(directory))
                session.awaitEnded()
                val output = session.outputs.single()
                val comparison = compareCapturedPcm(
                    output.capturedPcm(), fixture.samples, fixture.bitDepth, probeFrame = 0,
                    knownReferenceStart = 0,
                )
                report("short", fixture, comparison)
                assertEquals("Lost frames in ${fixture.name}", 0, comparison.missingTailFrames)
                assertEquals("Extra frames after ${fixture.name}", 0, comparison.trailingFrames)
                if (guard == PcmGuard.NONE) {
                    assertEquals("The fade should cover the whole track", fixture.frameCount, comparison.mismatchedFrames)
                } else {
                    assertEquals("${fixture.name} differs from the source", 0, comparison.mismatchedFrames)
                }
                session.assertEndedAt(fixture.durationUs, "short ${fixture.name}")
            }
        }
    }

    /** The player's position must describe the source. */
    @Test
    fun progressFollowsTheSource() {
        val fixture = PcmFixture(PcmContainer.WAV, 48_000, 16, DURATION_MS, seed = 1)
        capture(progressOptions(fixture)) { session ->
            session.play(fixture.write(directory))
            session.awaitOutputPosition(PROGRESS_SAMPLE_US)
            session.pauseAndSettle()
            session.assertProgress(startUs = 0, session.outputs.single().positionUs, "start")
        }
    }

    @Test
    fun progressFollowsTheSourceAfterSeek() {
        for (container in PcmContainer.entries) {
            val fixture = PcmFixture(container, 48_000, 16, DURATION_MS, seed = 1)
            capture(progressOptions(fixture)) { session ->
                session.play(fixture.write(directory))
                session.awaitCapturedFrames(fixture.sampleRateHz / 4L)
                val before = session.outputs.size
                session.seekTo(SEEK_POSITION_MS)
                session.awaitNewOutput(before)
                session.awaitOutputPosition(PROGRESS_SAMPLE_US)
                session.pauseAndSettle()
                val output = session.outputs.last()
                val comparison = output.compareWith(fixture.samples, fixture)
                val startUs = Util.sampleCountToDurationUs(comparison.referenceStartFrame.toLong(), fixture.sampleRateHz)
                session.assertProgress(startUs, output.positionUs, "seek ${fixture.name}")
            }
        }
    }

    /** A full output accepts part of a buffer. */
    @Test
    fun partialWrites() {
        for (container in PcmContainer.entries) {
            val fixture = PcmFixture(container, 48_000, 16, DURATION_MS / 2, seed = 1)
            capture(CaptureOptions(maxFramesPerWrite = 64)) { session ->
                session.play(fixture.write(directory))
                session.awaitEnded()
                val output = session.outputs.single()
                val comparison = output.compareWith(fixture.samples, fixture)
                report("partial", fixture, comparison)
                assertStartAsExpected(fixture, comparison, "partial ${fixture.name}")
                assertWholeSource(comparison, "partial ${fixture.name}")
                session.assertEndedAt(fixture.durationUs, "partial ${fixture.name}")
            }
        }
    }

    /**
     * `DefaultAudioSink` answers a stalled output by rebuilding it, which fades real samples.
     * The strict sink stays out of this path; a real stall has to fail closed instead (R2-01).
     */
    @Test
    fun stalledOutput() {
        val fixture = PcmFixture(PcmContainer.WAV, 48_000, 16, DURATION_MS, seed = 1)
        capture(CaptureOptions(stallAfterFrames = fixture.sampleRateHz / 4L)) { session ->
            session.play(fixture.write(directory))
            session.awaitEnded()
            if (guard == PcmGuard.STRICT_SINK) {
                val comparison = session.outputs.single().compareWith(fixture.samples, fixture)
                report("stall", fixture, comparison)
                assertStartAsExpected(fixture, comparison, "stall")
                assertWholeSource(comparison, "stall")
            } else {
                assertEquals("The stall should rebuild the output", 2, session.outputs.size)
                val comparison = compareCapturedPcm(
                    session.outputs[1].capturedPcm(), fixture.samples, fixture.bitDepth,
                    probeFrame = fadeFrames(fixture.sampleRateHz).toInt() * 2,
                )
                report("stall", fixture, comparison)
                assertFadeBaseline(comparison, fadeFrames(fixture.sampleRateHz).toInt(), "stall")
            }
        }
    }

    /** Strict output does not trim, so it must not accept a source that needs trimming. */
    @Test
    fun rejectsEncoderDelay() {
        assumeTrue("Applies to strict output only", guard == PcmGuard.STRICT_SINK)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(PcmFixture.CHANNELS)
            .setSampleRate(48_000)
            .setEncoderDelay(100)
            .build()
        val provider = CapturingAudioOutputProvider(context, CaptureOptions(answersFormats = true))
        val rejected = runCatching { StrictPcmAudioSink(provider).configure(AudioSink.AudioSinkConfig.Builder(format).build()) }
        assertTrue("Strict output must reject what it does not trim", rejected.exceptionOrNull() is AudioSink.ConfigurationException)
    }

    /** The output decides what a volume means: the official path fails closed, D11 ignores it. */
    @Test
    fun volumeReachesTheOutput() {
        assumeTrue("Applies to strict output only", guard == PcmGuard.STRICT_SINK)
        val provider = CapturingAudioOutputProvider(context, CaptureOptions(answersFormats = true))
        val sink = StrictPcmAudioSink(provider)
        sink.setVolume(0.5f)
        sink.configure(strictSinkConfig())
        assertTrue(sink.handleBuffer(silence(), 0, 1))
        assertEquals("Set before the output existed", 0.5f, provider.outputs.single().lastVolume)
        sink.setVolume(0.2f)
        assertEquals("Set on the running output", 0.2f, provider.outputs.single().lastVolume)
    }

    /** R2-05: the official path has to keep failing closed once it uses the strict sink. */
    @Test
    fun aNonNeutralVolumeStillClosesTheOfficialGate() {
        assumeTrue("Applies to strict output only", guard == PcmGuard.STRICT_SINK)
        val capturing = CapturingAudioOutputProvider(context, CaptureOptions(answersFormats = true))
        val violations = mutableListOf<Float>()
        var gate: StrictGatedAudioOutput? = null
        val sink = StrictPcmAudioSink(
            object : ForwardingAudioOutputProvider(capturing) {
                override fun getAudioOutput(config: AudioOutputProvider.OutputConfig) =
                    StrictGatedAudioOutput(super.getAudioOutput(config), onNonNeutralVolume = violations::add)
                        .also { gate = it }
            },
        )
        sink.configure(strictSinkConfig())
        sink.play()
        val first = silence()
        assertFalse("The gate starts closed", sink.handleBuffer(first, 0, 1))
        assertTrue(checkNotNull(gate).activate())
        assertTrue(sink.handleBuffer(first, 0, 1))

        sink.setVolume(0.2f)

        assertEquals(listOf(0.2f), violations)
        assertFalse("The gate should close at once", sink.handleBuffer(silence(), 0, 1))
    }

    /** All the data has arrived, but ending now would drop what the output has not played. */
    @Test
    fun endWaitsForTheOutputToDrain() {
        val fixture = PcmFixture(PcmContainer.WAV, 48_000, 16, DURATION_MS, seed = 1)
        val heldFrames = fixture.sampleRateHz / 10
        capture(CaptureOptions(holdClockAtFrames = (fixture.frameCount - heldFrames).toLong())) { session ->
            session.play(fixture.write(directory))
            session.awaitCapturedFrames(fixture.frameCount.toLong())
            session.assertNotEndedFor(DRAIN_WAIT_MS)
            session.outputs.single().releaseClock()
            session.awaitEnded()
            session.assertEndedAt(fixture.durationUs, "drain")
        }
    }

    /** The baseline has to notice an upgrade that changes the fade, whichever way it changes. */
    @Test
    fun aDifferentFadeIsCaught() {
        assumeTrue("Needs no playback, so one mode is enough", guard == PcmGuard.STRICT_SINK)
        val fade = fadeFrames(48_000).toInt()
        assertFadeBaseline(fadeResult(first = 0, last = fade - 1, mismatched = fade), fade, "the current fade")
        listOf(
            "15 ms" to fadeResult(first = 0, last = fade * 3 / 4 - 1, mismatched = fade * 3 / 4),
            "25 ms" to fadeResult(first = 0, last = fade * 5 / 4 - 1, mismatched = fade * 5 / 4),
            "first frame only" to fadeResult(first = 0, last = 0, mismatched = 1),
            "no fade" to fadeResult(first = -1, last = -1, mismatched = 0),
            "changes after the fade" to fadeResult(first = 0, last = fade * 2, mismatched = fade + 1),
            "gaps inside the fade" to fadeResult(first = 0, last = fade - 1, mismatched = fade / 2),
        ).forEach { (name, result) ->
            assertThrows(name, AssertionError::class.java) { assertFadeBaseline(result, fade, name) }
        }
    }

    /** Ending early would also leave the position short of the source. */
    @Test
    fun anEndPositionOffTheSourceIsCaught() {
        assumeTrue("Needs no playback, so one mode is enough", guard == PcmGuard.STRICT_SINK)
        val durationUs = 2_000_000L
        assertEndPosition(durationUs, durationUs, "the end of the source")
        listOf(0L, 1_000_000L, -1L, durationUs - 10_000, durationUs + 10_000).forEach { reachedUs ->
            assertThrows("$reachedUs", AssertionError::class.java) {
                assertEndPosition(reachedUs, durationUs, "$reachedUs")
            }
        }
    }

    /** A capture is aligned by content, so losing the head of the source shows only here. */
    @Test
    fun aCaptureMissingItsFirstFrameIsCaught() {
        assumeTrue("Needs no playback, so one mode is enough", guard == PcmGuard.STRICT_SINK)
        val fixture = PcmFixture(PcmContainer.WAV, 48_000, 16, DURATION_MS / 2, seed = 1)
        val bytes = ByteBuffer.allocate((fixture.samples.size - PcmFixture.CHANNELS) * 2).order(ByteOrder.LITTLE_ENDIAN)
        fixture.samples.drop(PcmFixture.CHANNELS).forEach { bytes.putShort(it.toShort()) }
        val capture = CapturedPcm(C.ENCODING_PCM_16BIT, PcmFixture.CHANNELS, bytes.array())
        val comparison = compareCapturedPcm(capture, fixture.samples, fixture.bitDepth, probeFrame = 256)
        assertEquals("Content alone cannot see the loss", 0, comparison.mismatchedFrames)
        assertEquals(1, comparison.referenceStartFrame)
        assertThrows(AssertionError::class.java) { assertWholeSource(comparison, "missing first frame") }
    }

    /** For captures of a whole source: nothing lost at either end, nothing added after it. */
    private fun assertWholeSource(result: PcmComparison, label: String) {
        assertEquals("$label: does not start at the first source frame", 0, result.referenceStartFrame)
        assertEquals("$label: lost frames at the end", 0, result.missingTailFrames)
        assertEquals("$label: extra frames after the end", 0, result.trailingFrames)
    }

    private fun assertStartAsExpected(fixture: PcmFixture, result: PcmComparison, label: String) {
        val fadeFrames = fadeFrames(fixture.sampleRateHz).toInt()
        when (guard) {
            PcmGuard.NONE -> {
                assertEquals("$label: extra frames before the source", 0, result.leadingFrames)
                assertFadeBaseline(result, fadeFrames, label)
            }
            PcmGuard.STRICT_SINK -> {
                assertEquals("$label: ${result.mismatchedFrames} frames differ from the source", 0, result.mismatchedFrames)
                assertEquals("$label: samples off the source lattice", 0, result.notExactSamples)
                assertEquals("$label: frames before the source", 0, result.leadingFrames)
            }
        }
    }

    /**
     * The clock holds still at the sample point. Running at 4x, it moves 4 ms for every real
     * millisecond between the player reading its position and the output pausing.
     */
    private fun progressOptions(fixture: PcmFixture) = CaptureOptions(
        holdClockAtFrames = Util.durationUsToSampleCount(PROGRESS_SAMPLE_US, fixture.sampleRateHz),
    )

    private fun strictSinkConfig() = AudioSink.AudioSinkConfig.Builder(
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(PcmFixture.CHANNELS)
            .setSampleRate(48_000)
            .build(),
    ).build()

    private fun silence(): ByteBuffer = ByteBuffer.allocate(4 * PcmFixture.CHANNELS).order(ByteOrder.nativeOrder())

    private val PcmFixture.durationUs get() = Util.sampleCountToDurationUs(frameCount.toLong(), sampleRateHz)

    private fun CapturingAudioOutput.compareWith(reference: IntArray, fixture: PcmFixture) = compareCapturedPcm(
        capturedPcm(), reference, fixture.bitDepth,
        probeFrame = fadeFrames(fixture.sampleRateHz).toInt() * 2,
    )

    private fun CapturingAudioOutput.lostPrecision(fixture: PcmFixture, case: String): Boolean {
        val lost = capturedPcm().lostPrecision(fixture.bitDepth)
        if (lost) Log.i(TAG, "guard=$guard case=$case fixture=${fixture.name} decoder-output=int16 precision-lost")
        return lost
    }

    private fun report(case: String, fixture: PcmFixture, result: PcmComparison) {
        Log.i(TAG, "guard=$guard case=$case fixture=${fixture.name} $result")
    }

    private fun forEachFixture(block: (PcmFixture) -> Unit) {
        for (container in PcmContainer.entries) {
            block(PcmFixture(container, 44_100, 16, DURATION_MS, seed = 1))
            block(PcmFixture(container, 48_000, 24, DURATION_MS, seed = 1))
            block(PcmFixture(container, 96_000, 24, DURATION_MS, seed = 1))
        }
    }

    private fun capture(options: CaptureOptions = CaptureOptions(), block: (CaptureSession) -> Unit) {
        val provider = CapturingAudioOutputProvider(
            context,
            options.copy(answersFormats = guard == PcmGuard.STRICT_SINK),
        )
        val probe = if (guard == PcmGuard.NONE) null else PositionProbeAudioSink(StrictPcmAudioSink(provider))
        val factory: RenderersFactory = if (probe == null) {
            VesqenAudioRenderersFactory(context, provider)
        } else {
            GuardedRenderersFactory(context, probe)
        }
        CaptureSession(context, factory, provider, probe).use(block)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun guards() = PcmGuard.entries.toList()

        private const val TAG = "Media3PcmCapture"
        private const val DURATION_MS = 2_000
        private const val SHORT_DURATION_MS = 10
        private const val SEEK_POSITION_MS = 1_000
        private const val PROGRESS_SAMPLE_US = 300_000L
        private const val DRAIN_WAIT_MS = 500L
    }
}

private const val TIMEOUT_SECONDS = 60L
private const val FADE_US = 20_000L
private const val PROGRESS_TOLERANCE_US = 3_000L

private fun assertFadeBaseline(result: PcmComparison, fadeFrames: Int, label: String) {
    assertEquals("$label: the first frame should be faded", 0, result.firstMismatchFrame)
    assertEquals("$label: the fade should end at frame ${fadeFrames - 1}", fadeFrames - 1, result.lastMismatchFrame)
    assertEquals("$label: every frame of the fade should change", fadeFrames, result.mismatchedFrames)
}

private fun assertEndPosition(reachedUs: Long, durationUs: Long, label: String) {
    assertTrue(
        "$label: position reached $reachedUs us, the source lasts $durationUs us",
        abs(reachedUs - durationUs) <= PROGRESS_TOLERANCE_US,
    )
}

private fun fadeResult(first: Int, last: Int, mismatched: Int) = PcmComparison(
    capturedFrames = 96_000,
    notExactSamples = 0,
    leadingFrames = 0,
    referenceStartFrame = 0,
    mismatchedFrames = mismatched,
    firstMismatchFrame = first,
    lastMismatchFrame = last,
    missingTailFrames = 0,
    trailingFrames = 0,
)

/** Mirrors `DefaultAudioSink.maybeRampUpVolume()`. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun fadeFrames(sampleRateHz: Int): Long = Util.durationUsToSampleCount(FADE_US, sampleRateHz)

@androidx.annotation.OptIn(UnstableApi::class)
private class CaptureSession(
    context: Context,
    factory: RenderersFactory,
    private val provider: CapturingAudioOutputProvider,
    private val probe: PositionProbeAudioSink?,
) : AutoCloseable {
    private val thread = HandlerThread("pcm-capture-player").apply { start() }
    private val handler = Handler(thread.looper)
    private val failure = AtomicReference<PlaybackException?>()

    @Volatile
    private var ended = CountDownLatch(1)

    private val player: ExoPlayer = onPlayer {
        ExoPlayer.Builder(context, factory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, VesqenExtractorsFactory()))
            .setLooper(thread.looper)
            .build()
            .also { player ->
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) ended.countDown()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        failure.set(error)
                        ended.countDown()
                    }
                })
            }
    }

    val outputs get() = provider.outputs

    fun play(vararg files: File) {
        ended = CountDownLatch(1)
        onPlayer {
            player.setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) })
            player.prepare()
            player.play()
        }
    }

    fun seekTo(positionMs: Int) {
        ended = CountDownLatch(1)
        onPlayer { player.seekTo(positionMs.toLong()) }
    }

    fun prepareFromStartAndPlay() {
        ended = CountDownLatch(1)
        onPlayer {
            player.stop()
            player.seekTo(0, 0)
            player.prepare()
            player.play()
        }
    }

    fun pauseAndSettle() {
        val output = outputs.last()
        onPlayer { player.pause() }
        await("Output kept playing after pause") { !output.isPlaying }
        // The player publishes the position it read while pausing a moment later.
        Thread.sleep(SETTLE_MS)
    }

    fun awaitCapturedFrames(frames: Long) =
        await("Captured too little audio") { (outputs.lastOrNull()?.capturedFrames ?: 0) >= frames }

    fun awaitOutputPosition(positionUs: Long) =
        await("The output position did not advance") { (outputs.lastOrNull()?.positionUs ?: 0) >= positionUs }

    fun awaitNewOutput(previousCount: Int) =
        await("No new output after the seek") { outputs.size > previousCount }

    fun awaitEnded() {
        check(ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Playback did not end" }
        failure.get()?.let { throw AssertionError("Playback failed", it) }
    }

    fun assertProgress(startUs: Long, outputUs: Long, label: String) {
        val playerUs = onPlayer { player.currentPosition } * 1_000
        val expectedUs = startUs + outputUs
        Log.i("Media3PcmCapture", "case=progress $label playerUs=$playerUs expectedUs=$expectedUs")
        assertTrue(
            "$label: player at $playerUs us, expected $expectedUs us",
            abs(playerUs - expectedUs) <= PROGRESS_TOLERANCE_US,
        )
    }

    /** STATE_ENDED already means isEnded(); this checks the time axis it ended on. */
    fun assertEndedAt(durationUs: Long, label: String) {
        val probe = probe ?: return
        assertTrue("$label: the sink still had pending data at the end", probe.endedWithoutPendingData)
        assertEndPosition(probe.endPositionUs - probe.firstBufferUs, durationUs, label)
    }

    fun assertNotEndedFor(millis: Long) {
        assertFalse("Ended with audio still in the output", ended.await(millis, TimeUnit.MILLISECONDS))
    }

    override fun close() {
        onPlayer { player.release() }
        thread.quitSafely()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (!condition()) {
            failure.get()?.let { throw AssertionError("Playback failed", it) }
            check(System.nanoTime() < deadline) { message }
            Thread.sleep(2)
        }
    }

    private fun <T> onPlayer(block: () -> T): T {
        val task = FutureTask(block)
        handler.post(task)
        return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val SETTLE_MS = 300L
    }
}

/** Records what the renderer saw from the sink under test. */
@androidx.annotation.OptIn(UnstableApi::class)
private class PositionProbeAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    /** Read when the sink reports its end: the renderer stops asking for the position after that. */
    @Volatile var endPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
        private set

    @Volatile var endedWithoutPendingData = false
        private set

    /** Renderer time carries ExoPlayer's stream offset, so positions are relative to this. */
    @Volatile var firstBufferUs = 0L
        private set

    private var sawFirstBuffer = false

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!sawFirstBuffer) {
            sawFirstBuffer = true
            firstBufferUs = presentationTimeUs
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun isEnded(): Boolean = super.isEnded().also {
        if (it) {
            endedWithoutPendingData = !hasPendingData()
            endPositionUs = getCurrentPositionUs(true)
        }
    }
}

/**
 * M7 D9, still a prototype: it moves to production code in M7-4.
 * PCM goes straight to the output with no processing, so there is nothing to undo.
 * Strict mode already requires neutral speed and skip-silence, and rejects what would need
 * trimming, so those parts of the `AudioSink` contract have nothing to do here. Volume goes to the
 * output, which decides: the official path fails closed, and the direct path ignores it (D11).
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class StrictPcmAudioSink(private val provider: AudioOutputProvider) : AudioSink {
    private var listener: AudioSink.Listener? = null
    private var audioAttributes = AudioAttributes.DEFAULT
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var volume = 1f
    private var playing = false
    private var configuration: Configuration? = null
    private var pendingConfiguration: Configuration? = null
    private var output: AudioOutput? = null
    private var startMediaTimeUs = 0L
    private var startNeedsInit = true
    private var startNeedsSync = false
    private var writtenBytes = 0L
    private var submittedBytes = 0L
    private var inputBuffer: ByteBuffer? = null
    private var handledEndOfStream = false

    private class Configuration(val formatConfig: AudioOutputProvider.FormatConfig, val outputConfig: AudioOutputProvider.OutputConfig) {
        val frameSize = Util.getPcmFrameSize(formatConfig.format.pcmEncoding, formatConfig.format.channelCount)
        val sampleRate = formatConfig.format.sampleRate

        fun durationUs(frames: Long) = Util.sampleCountToDurationUs(frames, sampleRate)
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int = when {
        format.sampleMimeType != MimeTypes.AUDIO_RAW -> AudioSink.SINK_FORMAT_UNSUPPORTED
        format.pcmEncoding in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT) ->
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else -> AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        if (format.encoderDelay > 0 || format.encoderPadding > 0) {
            throw AudioSink.ConfigurationException("Strict output does not trim samples", format)
        }
        val formatConfig = AudioOutputProvider.FormatConfig.Builder(format)
            .setAudioAttributes(audioAttributes)
            .setAudioSessionId(audioSessionId)
            .setEnableHighResolutionPcmOutput(true)
            .build()
        val next = Configuration(formatConfig, provider.getOutputConfig(formatConfig))
        if (output == null) configuration = next else pendingConfiguration = next
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        check(inputBuffer == null || inputBuffer === buffer)
        pendingConfiguration?.let { pending ->
            val current = output
            if (current != null && !current.canReuseAudioOutput(
                    checkNotNull(configuration).outputConfig, pending.formatConfig, pending.outputConfig,
                )
            ) {
                current.stop()
                if (hasPendingData()) return false
                flush()
            } else {
                configuration = pending
                pendingConfiguration = null
            }
        }
        val active = checkNotNull(configuration) { "handleBuffer before configure" }
        val currentOutput = output ?: provider.getAudioOutput(active.outputConfig).also {
            output = it
            it.setVolume(volume)
            if (playing) it.play()
        }
        if (startNeedsInit) {
            startMediaTimeUs = maxOf(0, presentationTimeUs)
            startNeedsInit = false
            startNeedsSync = false
        }
        if (inputBuffer == null) {
            if (!buffer.hasRemaining()) return true
            val expectedUs = startMediaTimeUs + active.durationUs(submittedBytes / active.frameSize)
            if (startNeedsSync || abs(expectedUs - presentationTimeUs) > MAX_TIMESTAMP_DRIFT_US) {
                startMediaTimeUs += presentationTimeUs - expectedUs
                startNeedsSync = false
                listener?.onPositionDiscontinuity()
            }
            submittedBytes += buffer.remaining()
            inputBuffer = buffer
        }
        val before = buffer.remaining()
        val fullyHandled = currentOutput.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        writtenBytes += before - buffer.remaining()
        if (fullyHandled) inputBuffer = null
        return fullyHandled
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val current = output ?: return AudioSink.CURRENT_POSITION_NOT_SET
        val active = checkNotNull(configuration)
        if (startNeedsInit) return AudioSink.CURRENT_POSITION_NOT_SET
        return startMediaTimeUs + minOf(current.positionUs, active.durationUs(writtenBytes / active.frameSize))
    }

    override fun playToEndOfStream() {
        if (!handledEndOfStream && output != null) {
            checkNotNull(output).stop()
            handledEndOfStream = true
        }
    }

    override fun isEnded() = output == null || (handledEndOfStream && !hasPendingData())

    override fun hasPendingData(): Boolean {
        val current = output ?: return false
        val active = checkNotNull(configuration)
        return writtenBytes / active.frameSize > Util.durationUsToSampleCount(current.positionUs, active.sampleRate)
    }

    override fun play() {
        playing = true
        output?.play()
    }

    override fun pause() {
        playing = false
        output?.pause()
    }

    override fun handleDiscontinuity() {
        startNeedsSync = true
    }

    override fun flush() {
        output?.release()
        output = null
        pendingConfiguration?.let { configuration = it }
        pendingConfiguration = null
        inputBuffer = null
        writtenBytes = 0
        submittedBytes = 0
        startNeedsInit = true
        startNeedsSync = false
        handledEndOfStream = false
    }

    override fun reset() {
        flush()
        playing = false
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit

    override fun getSkipSilenceEnabled() = false

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit

    override fun getAudioTrackBufferSizeUs(): Long {
        val current = output ?: return C.TIME_UNSET
        return checkNotNull(configuration).durationUs(current.bufferSizeInFrames)
    }

    override fun enableTunnelingV21() = throw UnsupportedOperationException("Strict output has no tunneling")

    override fun disableTunneling() = Unit

    /** Only the focus duck factor can differ from 1, since strict mode pins the user volume. */
    override fun setVolume(volume: Float) {
        this.volume = volume
        output?.setVolume(volume)
    }

    private companion object {
        const val MAX_TIMESTAMP_DRIFT_US = 200_000L
    }
}

/** The production factory with the strict sink; the renderer keeps #107's OMX protection. */
@androidx.annotation.OptIn(UnstableApi::class)
private class GuardedRenderersFactory(
    context: Context,
    private val sink: AudioSink,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = sink

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            object : MediaCodecAudioRenderer(
                context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, audioSink,
            ) {
                override fun getMediaCodecConfiguration(
                    codecInfo: MediaCodecInfo,
                    format: Format,
                    crypto: MediaCrypto?,
                    codecOperatingRate: Float,
                ): MediaCodecAdapter.Configuration =
                    super.getMediaCodecConfiguration(codecInfo, format, crypto, codecOperatingRate).also {
                        it.mediaFormat.keepIntegerPcmForOmx(codecInfo.name)
                    }
            },
        )
    }
}
