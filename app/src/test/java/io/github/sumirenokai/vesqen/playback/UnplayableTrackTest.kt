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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** #344: a track no decoder can play fails visibly and counts no listen, instead of running silently. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class UnplayableTrackTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val counted = mutableListOf<Long>()
    private val errors = mutableListOf<PlaybackException>()
    private var everPlaying = false
    private var player: ExoPlayer? = null

    @After
    fun releasePlayer() {
        player?.release()
    }

    @Test
    fun `only an encoded track without a decoder is reported as exceeding capabilities`() {
        val missingDecoder = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        assertEquals(RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES), reportMissingDecoder(missingDecoder, MimeTypes.AUDIO_FLAC))
        assertEquals(RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES), reportMissingDecoder(missingDecoder, MimeTypes.AUDIO_ALAC))
        // For raw PCM the answer means the sink refuses the encoding; no decoder is involved.
        assertEquals(missingDecoder, reportMissingDecoder(missingDecoder, MimeTypes.AUDIO_RAW))
        val untouched = listOf(
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE),
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM),
            RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES),
            RendererCapabilities.create(
                C.FORMAT_HANDLED,
                RendererCapabilities.ADAPTIVE_SEAMLESS,
                RendererCapabilities.TUNNELING_NOT_SUPPORTED,
                RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
                RendererCapabilities.DECODER_SUPPORT_PRIMARY,
            ),
        )
        for (capabilities in untouched) assertEquals(capabilities, reportMissingDecoder(capabilities, MimeTypes.AUDIO_FLAC))
    }

    @Test
    fun `Vesqen's audio renderer reports a missing decoder as exceeding capabilities`() {
        val renderer = withoutDecoders().createRenderers(
            Handler(Looper.getMainLooper()), object : VideoRendererEventListener {}, object : AudioRendererEventListener {}, {}, {},
        ).filterIsInstance<MediaCodecAudioRenderer>().single()
        fun support(format: Format) = RendererCapabilities.getFormatSupport(renderer.supportsFormat(format))
        val flac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setChannelCount(2).setSampleRate(44_100).build()
        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, support(flac))
        val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2).setSampleRate(44_100).build()
        assertEquals(C.FORMAT_HANDLED, support(pcm))
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, support(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()))
    }

    @Test
    fun `a track without a decoder stops with a decoder error and counts no listen`() {
        play(flacFile(), withoutDecoders())
        runUntil { player!!.playerError != null || player!!.playbackState == Player.STATE_ENDED }

        assertEquals(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, player!!.playerError?.errorCode)
        assertEquals(PlaybackProblem.DECODER_FAILURE, player!!.playerError!!.toPlaybackProblem())
        assertEquals(Player.STATE_IDLE, player!!.playbackState)
        assertFalse("the track never played", everPlaying)
        assertEquals(emptyList<Long>(), counted)
    }

    @Test
    fun `repeat one on a track without a decoder fails once instead of looping`() {
        play(flacFile(), withoutDecoders()) { it.repeatMode = Player.REPEAT_MODE_ONE }
        runUntil { player!!.playerError != null }
        repeat(20) { settle() }

        assertEquals(listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED), errors.map { it.errorCode })
        assertEquals(emptyList<Long>(), counted)
    }

    /** Stands in for the silent paths the renderer cannot catch, such as a track the sink refuses. */
    @Test
    fun `an item whose audio track is not selected counts no listen`() {
        val factory = VesqenAudioRenderersFactory(context, AudioTrackAudioOutputProvider.Builder(context).build())
        play(wavFile(), factory) { player ->
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

    /** The app's factory with a platform that offers no decoder at all. */
    private fun withoutDecoders() = VesqenAudioRenderersFactory(context, AudioTrackAudioOutputProvider.Builder(context).build())
        .setMediaCodecSelector { _, _, _ -> emptyList() }

    private fun play(file: File, factory: androidx.media3.exoplayer.RenderersFactory, configure: (ExoPlayer) -> Unit = {}) {
        val created = ExoPlayer.Builder(context, factory)
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
        })
        PlaybackStateKeeper(
            player = created,
            stateStore = PlaybackStateStore(context),
            handler = Handler(Looper.getMainLooper()),
            onPlaybackStarted = { counted += it },
        ).start()
        configure(created)
        created.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId("1").build())
        created.prepare()
        created.play()
    }

    private fun flacFile() = File(folder.root, "unplayable.flac").apply {
        writeBytes(PcmFiles.flacBytes(IntArray(FRAMES * 2) { it % 64 }, 44_100, 16, 2))
    }

    private fun wavFile() = File(folder.root, "silent.wav").apply {
        writeBytes(PcmFiles.wavBytes(IntArray(FRAMES * 2), 44_100, 16, 2))
    }

    /** Lets the playback thread and the main looper run, advancing the clock a little. */
    private fun settle() {
        val done = CountDownLatch(1)
        Handler(player!!.playbackLooper).post { done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
    }

    private fun runUntil(condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            settle()
        }
        fail("the player did not reach the expected state")
    }

    private companion object {
        /** 200 ms at 44.1 kHz. */
        const val FRAMES = 8_820
    }
}
