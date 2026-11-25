package io.github.sumirenokai.vesqen.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #344: a track Android cannot decode or output pauses playback, or is skipped when the listener
 * asks for that, instead of running silently and counting a listen. The song before it is not cut.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class UnplayableTrackTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val counted = mutableListOf<Long>()
    private val errors = mutableListOf<PlaybackException>()
    private val visited = mutableListOf<Int>()
    private val stopped = mutableListOf<Int>()
    private val reported = mutableListOf<String>()
    private val interruptions = PlaybackInterruptions()
    private var skip = false
    private var everPlaying = false
    private var player: ExoPlayer? = null
    private var outputs: RecordingOutputs? = null

    /** The service's session player: Next and Previous reach the guard through it. */
    private var session: PlayOrderSessionPlayer? = null

    @After
    fun releasePlayer() {
        player?.release()
    }

    @Test
    fun `Vesqen's audio renderer keeps Media3's answer for a missing decoder`() {
        val renderer = withoutDecoders().createRenderers(
            Handler(Looper.getMainLooper()), object : VideoRendererEventListener {}, object : AudioRendererEventListener {}, {}, {},
        ).filterIsInstance<MediaCodecAudioRenderer>().single()
        val flac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setChannelCount(2).setSampleRate(44_100).build()

        // Answered as anything Media3 selects, the read-ahead decoder failure would cut the song before (#344).
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, RendererCapabilities.getFormatSupport(renderer.supportsFormat(flac)))
    }

    @Test
    fun `unplayable audio is told apart from preparing and playable tracks`() {
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).build()
        fun tracks(support: Int, selected: Boolean, format: Format = audio) = Tracks(
            listOf(Tracks.Group(TrackGroup(format), false, intArrayOf(support), booleanArrayOf(selected))),
        )

        assertTrue(tracks(C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false).hasUnplayableAudio())
        assertFalse("still preparing", Tracks.EMPTY.hasUnplayableAudio())
        assertFalse(tracks(C.FORMAT_HANDLED, selected = true).hasUnplayableAudio())
        assertFalse("selected beyond its capabilities", tracks(C.FORMAT_EXCEEDS_CAPABILITIES, selected = true).hasUnplayableAudio())
        assertFalse("playable but turned off", tracks(C.FORMAT_HANDLED, selected = false).hasUnplayableAudio())
        val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        assertFalse("no audio at all", tracks(C.FORMAT_UNSUPPORTED_SUBTYPE, selected = false, format = video).hasUnplayableAudio())
    }

    @Test
    fun `a track without a decoder pauses before it plays and counts no listen`() {
        play(listOf(flacFile()))
        runUntil { interruptions.current.value != null }

        val interruption = interruptions.current.value!!
        assertEquals(PlaybackProblem.UNPLAYABLE_TRACK, interruption.kind)
        assertEquals("1", interruption.queueEntryId)
        assertNull("no playback error", player!!.playerError)
        assertFalse(player!!.playWhenReady)
        assertFalse("the track never played", everPlaying)
        assertEquals(emptyList<Long>(), counted)
        assertEquals(listOf("1"), reported)
        assertEquals(1, stopped.size)
    }

    @Test
    fun `the song before an unplayable track plays to its end`() {
        play(listOf(wavFile(), flacFile()))
        runUntil { interruptions.current.value != null }
        runUntil { outputs!!.calls.firstOrNull()?.contains("release") == true }

        assertEquals(1, player!!.currentMediaItemIndex)
        val first = outputs!!.calls.first().toList()
        // Drained with stop() before release(); a flush first would drop the frames not yet played.
        assertTrue("The first song's output was not drained: $first", "stop" in first)
        assertTrue("The first song's output was flushed or released before draining: $first",
            first.indexOf("stop") < first.indexOf("release") && "flush" !in first.subList(0, first.indexOf("stop")))
        assertFalse("the unplayable track was counted", 2L in counted)
    }

    @Test
    fun `Play again on a paused unplayable track moves on`() {
        play(listOf(flacFile(), wavFile()))
        runUntil { interruptions.current.value != null }

        player!!.play()
        runUntil { player!!.currentMediaItemIndex == 1 && everPlaying }

        assertEquals(listOf(0, 1), visited)
        assertEquals(1, stopped.size)
    }

    @Test
    fun `the first Play on an unplayable track reached while paused pauses there`() {
        play(listOf(wavFile(), flacFile(), wavFile()), playWhenReady = false)
        runUntil { player!!.playbackState == Player.STATE_READY }
        player!!.seekToNextMediaItem()
        runUntil { player!!.currentTracks.containsType(C.TRACK_TYPE_AUDIO) && player!!.playbackState == Player.STATE_READY }
        repeat(5) { settle() }
        assertNull("moving while paused explains nothing yet", interruptions.current.value)

        player!!.play()
        runUntil { interruptions.current.value != null }
        assertEquals(1, player!!.currentMediaItemIndex)
        assertFalse(player!!.playWhenReady)

        player!!.play()
        runUntil { player!!.currentMediaItemIndex == 2 && everPlaying }
    }

    @Test
    fun `repeat one on an unplayable track pauses once`() {
        play(listOf(flacFile())) { it.repeatMode = Player.REPEAT_MODE_ONE }
        runUntil { interruptions.current.value != null }
        repeat(20) { settle() }

        assertEquals(1, stopped.size)
        assertEquals(emptyList<PlaybackException>(), errors)
        assertEquals(emptyList<Long>(), counted)
    }

    @Test
    fun `auto-skip lets the song before finish, then plays the next playable track`() {
        skip = true
        play(listOf(wavFile(), flacFile(), wavFile()))
        runUntil { player!!.currentMediaItemIndex == 2 && player!!.isPlaying }

        assertNull(interruptions.current.value)
        assertEquals(emptyList<Int>(), stopped)
        assertEquals(listOf("2"), reported)
        val first = outputs!!.calls.first().toList()
        assertTrue("The first song's output was not drained: $first", "stop" in first)
    }

    @Test
    fun `auto-skip pauses on an unplayable last track`() {
        skip = true
        play(listOf(wavFile(), flacFile()))
        runUntil { interruptions.current.value != null }

        assertEquals(1, player!!.currentMediaItemIndex)
        assertEquals("2", interruptions.current.value!!.queueEntryId)
        assertFalse(player!!.playWhenReady)
    }

    @Test
    fun `auto-skip goes back when the listener goes back`() {
        skip = true
        play(listOf(wavFile(), flacFile(), wavFile()), startIndex = 2)
        runUntil { everPlaying }

        session!!.seekToPreviousMediaItem()
        runUntil { visited.size >= 3 }

        assertEquals(listOf(2, 1, 0), visited.take(3))
        assertNull(interruptions.current.value)
    }

    @Test
    fun `Next onto an unplayable track with auto-skip on moves past it`() {
        skip = true
        play(listOf(wavFile(), flacFile(), wavFile()))
        runUntil { everPlaying }

        session!!.seekToNextMediaItem()
        runUntil { visited.size >= 3 }

        assertEquals(listOf(0, 1, 2), visited.take(3))
        assertNull(interruptions.current.value)
    }

    /** A queue tap seeks by index, like a jump anywhere else in the queue (PR #345 review). */
    @Test
    fun `a track picked next to the current one pauses even with auto-skip on`() {
        skip = true
        play(listOf(wavFile(), flacFile(), wavFile()))
        runUntil { everPlaying }

        session!!.seekToDefaultPosition(1)
        runUntil { interruptions.current.value != null }

        assertEquals(1, player!!.currentMediaItemIndex)
        assertEquals("2", interruptions.current.value!!.queueEntryId)
        assertFalse(player!!.playWhenReady)
    }

    @Test
    fun `Play again moves past several unplayable tracks to one that plays`() {
        play(listOf(flacFile(), flacFile(), wavFile()))
        runUntil { interruptions.current.value != null }

        player!!.play()
        runUntil { player!!.currentMediaItemIndex == 2 && everPlaying }

        assertEquals(listOf(0, 1, 2), visited)
        assertEquals("only the first track paused", 1, stopped.size)
    }

    /** PR #345 review: only a Play while staying on the paused track moves on. */
    @Test
    fun `coming back to a paused unplayable track pauses there again`() {
        play(listOf(flacFile(), flacFile(), wavFile()))
        runUntil { interruptions.current.value != null }
        session!!.seekToDefaultPosition(1)
        runUntil { player!!.currentTracks.containsType(C.TRACK_TYPE_AUDIO) }
        session!!.seekToDefaultPosition(0)
        runUntil { player!!.currentTracks.containsType(C.TRACK_TYPE_AUDIO) && player!!.playbackState == Player.STATE_READY }

        player!!.play()
        runUntil { interruptions.current.value != null }
        repeat(5) { settle() }

        assertEquals(0, player!!.currentMediaItemIndex)
        assertFalse(player!!.playWhenReady)
        assertEquals(2, stopped.size)
    }

    /** PR #345 review: tapping the paused track in the queue chooses it again; it is not a second Play. */
    @Test
    fun `picking the paused unplayable track again pauses there again`() {
        play(listOf(flacFile(), wavFile()))
        runUntil { interruptions.current.value != null }
        val first = interruptions.current.value!!.id

        session!!.seekToDefaultPosition(0)
        player!!.play()
        runUntil { interruptions.current.value.let { it != null && it.id != first } }
        repeat(5) { settle() }

        assertEquals(0, player!!.currentMediaItemIndex)
        assertFalse(player!!.playWhenReady)
        assertEquals(listOf(0), visited)
    }

    @Test
    fun `a track the listener picked pauses even with auto-skip on`() {
        skip = true
        play(listOf(flacFile(), wavFile()))
        runUntil { interruptions.current.value != null }

        assertEquals(0, player!!.currentMediaItemIndex)
        assertFalse(player!!.playWhenReady)
    }

    @Test
    fun `auto-skip stops after one lap when nothing can play`() {
        skip = true
        play(listOf(flacFile(), flacFile())) { it.repeatMode = Player.REPEAT_MODE_ALL }
        runUntil { interruptions.current.value != null }
        val first = interruptions.current.value!!.id

        player!!.play()
        runUntil { interruptions.current.value.let { it != null && it.id != first } }
        repeat(10) { settle() }

        assertEquals(listOf(0, 1, 0), visited)
        assertFalse(player!!.playWhenReady)
    }

    @Test
    fun `auto-skip stops at the skip limit`() {
        skip = true
        val file = flacFile()
        play(List(PlaybackProblemGuard.SKIP_LIMIT + 2) { file })
        runUntil { interruptions.current.value != null }
        val first = interruptions.current.value!!.id

        player!!.play()
        runUntil(seconds = 30) { interruptions.current.value.let { it != null && it.id != first } }

        assertEquals(PlaybackProblemGuard.SKIP_LIMIT, player!!.currentMediaItemIndex)
        assertFalse(player!!.playWhenReady)
    }

    @Test
    fun `a playback error is what the app explains`() {
        play(listOf(File(folder.root, "missing.flac")))
        runUntil { player!!.playerError != null }

        val interruption = interruptions.current.value
        assertNotNull(interruption)
        assertEquals(PlaybackProblem.SOURCE_UNAVAILABLE, interruption!!.kind)
        assertEquals("1", interruption.queueEntryId)
    }

    /** The same entry plays after Retry: no transition clears it, playing does. */
    @Test
    fun `the explanation goes once the track plays after all`() {
        val late = File(folder.root, "late.wav")
        play(listOf(late))
        runUntil { player!!.playerError != null }
        assertNotNull(interruptions.current.value)

        late.writeBytes(PcmFiles.wavBytes(IntArray(FRAMES * 2), 44_100, 16, 2))
        player!!.prepare()
        player!!.play()
        runUntil { everPlaying }
        settle()

        assertNull(interruptions.current.value)
    }

    @Test
    fun `moving to another track clears the explanation`() {
        play(listOf(flacFile(), wavFile()))
        runUntil { interruptions.current.value != null }

        player!!.seekToNextMediaItem()
        settle()

        assertNull(interruptions.current.value)
    }

    @Test
    fun `an acknowledged interruption goes, a newer one stays`() {
        interruptions.interrupted(PlaybackProblem.UNPLAYABLE_TRACK, "1", "One")
        val old = interruptions.current.value!!.id
        interruptions.interrupted(PlaybackProblem.SOURCE_UNAVAILABLE, "2", "Two")

        interruptions.acknowledge(old)
        assertEquals("2", interruptions.current.value?.queueEntryId)
        interruptions.acknowledge(interruptions.current.value!!.id)
        assertNull(interruptions.current.value)
    }

    @Test
    fun `the auto-skip switch is off by default and keeps its setting`() {
        val settings = UnplayableTrackSettings(context)
        assertFalse(settings.skip.value)

        settings.setSkip(true)

        assertTrue(UnplayableTrackSettings(context).skip.value)
    }

    /** Stands in for the silent paths the guard does not stop, such as an audio track turned off. */
    @Test
    fun `an item whose audio track is not selected counts no listen`() {
        play(listOf(wavFile()), guard = false) { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        runUntil { everPlaying }
        settle()

        assertFalse(player!!.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))
        assertEquals(emptyList<Long>(), counted)
    }

    @Test
    fun `failures map to the existing problem text`() {
        fun problem(code: Int) = PlaybackException("test", null, code).toPlaybackProblem()
        assertEquals(PlaybackProblem.DECODER_FAILURE, problem(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(PlaybackProblem.UNSUPPORTED_FORMAT, problem(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(PlaybackProblem.SOURCE_UNAVAILABLE, problem(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
    }

    /** The app's factory with a platform that offers no decoder at all; WAV needs none. */
    private fun withoutDecoders() = VesqenAudioRenderersFactory(
        context,
        RecordingOutputs(AudioTrackAudioOutputProvider.Builder(context).build()).also { outputs = it },
    ).setMediaCodecSelector { _, _, _ -> emptyList() }

    /**
     * Plays [files] as one queue, the first with media ID 1, the next 2, and so on. The guard and the
     * keeper are installed in the service's order.
     */
    private fun play(
        files: List<File>,
        startIndex: Int = 0,
        playWhenReady: Boolean = true,
        guard: Boolean = true,
        configure: (ExoPlayer) -> Unit = {},
    ) {
        val created = ExoPlayer.Builder(context, withoutDecoders())
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, VesqenExtractorsFactory()))
            .build()
        player = created
        created.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) everPlaying = true
            }

            override fun onPlayerError(error: PlaybackException) {
                errors += error
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (visited.lastOrNull() != created.currentMediaItemIndex) visited += created.currentMediaItemIndex
            }
        })
        if (guard) {
            val problemGuard = PlaybackProblemGuard(
                player = created,
                skipUnplayable = { skip },
                interruptions = interruptions,
                onStopped = { stopped += created.currentMediaItemIndex },
                onUnplayable = { reported += it.mediaId },
            )
            created.addListener(problemGuard)
            session = PlayOrderSessionPlayer(created, navigating = problemGuard::navigating)
        }
        PlaybackStateKeeper(
            player = created,
            stateStore = PlaybackStateStore(context),
            handler = Handler(Looper.getMainLooper()),
            onPlaybackStarted = { counted += it },
        ).start()
        configure(created)
        created.setMediaItems(
            files.mapIndexed { index, file -> MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId("${index + 1}").build() },
            startIndex,
            C.TIME_UNSET,
        )
        created.prepare()
        created.playWhenReady = playWhenReady
    }

    private var fileNumber = 0

    private fun flacFile() = File(folder.root, "unplayable-${fileNumber++}.flac").apply {
        writeBytes(PcmFiles.flacBytes(IntArray(FRAMES * 2) { it % 64 }, 44_100, 16, 2))
    }

    private fun wavFile() = File(folder.root, "silent-${fileNumber++}.wav").apply {
        writeBytes(PcmFiles.wavBytes(IntArray(FRAMES * 2), 44_100, 16, 2))
    }

    /** Lets the playback thread and the main looper run, advancing the clock a little. */
    private fun settle() {
        val done = CountDownLatch(1)
        Handler(player!!.playbackLooper).post { done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
    }

    /** Bounded by real time: preparing reads the file on Media3's own loader thread, which settle() does not wait for. */
    private fun runUntil(seconds: Long = 10, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            settle()
            Thread.sleep(2)
        }
        fail("the player did not reach the expected state within $seconds s")
    }

    /** Records each output's stop, flush and release, in the order the sink calls them. */
    private class RecordingOutputs(delegate: AudioOutputProvider) : ForwardingAudioOutputProvider(delegate) {
        val calls = CopyOnWriteArrayList<MutableList<String>>()

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
            val record = Collections.synchronizedList(mutableListOf<String>())
            calls += record
            return object : ForwardingAudioOutput(super.getAudioOutput(config)) {
                override fun stop() {
                    record += "stop"
                    super.stop()
                }

                override fun flush() {
                    record += "flush"
                    super.flush()
                }

                override fun release() {
                    record += "release"
                    super.release()
                }
            }
        }
    }

    private companion object {
        /** 200 ms at 44.1 kHz. */
        const val FRAMES = 8_820
    }
}
