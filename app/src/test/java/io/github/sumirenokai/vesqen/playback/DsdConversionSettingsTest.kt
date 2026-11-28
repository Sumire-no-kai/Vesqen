package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.DSD_CODEC_LABEL
import io.github.sumirenokai.vesqen.telemetry.TelemetryMediaItemExtras
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
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

/** #249: the Audio output page's DSD settings, as stored and as playback picks them up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class DsdConversionSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private var player: ExoPlayer? = null

    @After
    fun releasePlayer() {
        player?.release()
    }

    @Test
    fun `the gain is off at first, turns on at 3 dB, stays on the 0_5 dB grid and comes back when turned on again`() {
        val settings = DsdConversionSettings(context)
        assertEquals(DsdConversionValues(), settings.values.value)
        assertEquals(DsdConversion(), settings.conversion())
        settings.setGainEnabled(true)
        assertEquals(DsdConversion(gainDb = 3f), settings.conversion())
        settings.setGainDb(4.3f)
        assertEquals(4.5f, settings.values.value.gainDb)
        settings.setGainDb(9f)
        assertEquals(6f, settings.values.value.gainDb)
        settings.setGainDb(0f)
        assertEquals(0.5f, settings.values.value.gainDb)
        settings.setGainEnabled(false)
        assertEquals(0f, settings.conversion().gainDb)
        settings.setGainEnabled(true)
        assertEquals(0.5f, settings.conversion().gainDb)
        settings.setRateMode(DsdOutputRateMode.MULTIPLE)
        assertEquals(settings.values.value, DsdConversionSettings(context).values.value)
        assertEquals(DsdConversion(DsdOutputRateMode.MULTIPLE, 0.5f), DsdConversionSettings(context).conversion())
    }

    @Test
    fun `stored values that cannot be read are the defaults`() {
        context.getSharedPreferences("dsd_conversion", Context.MODE_PRIVATE).edit()
            .putFloat("gain_db", Float.NaN)
            .putString("rate_mode", "MULTIPLE")
            .commit()
        assertEquals(DsdConversionValues(), DsdConversionSettings(context).values.value)
    }

    /**
     * Media3 opens the next track before it plays, so the track next in line already holds the old
     * settings when they change. It is opened again; the playing track is not, and the queue keeps
     * its entries in their order.
     */
    @Test
    fun `a change reaches the DSD track next in line even when Media3 has opened it`() {
        val opened = CopyOnWriteArrayList<DsdConversion>()
        // Read on Media3's loading thread.
        val current = AtomicReference(DsdConversion())
        val created = ExoPlayer.Builder(context, VesqenAudioRenderersFactory(context, AudioTrackAudioOutputProvider.Builder(context).build()))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, VesqenExtractorsFactory { current.get().also(opened::add) }))
            .build()
        player = created
        created.setMediaItems(listOf(dsdItem("1"), dsdItem("2")))
        created.prepare()
        created.play()
        runUntil { opened.size == 2 }
        assertEquals("the second track opened while the first plays", 0, created.currentMediaItemIndex)

        current.set(DsdConversion(DsdOutputRateMode.MULTIPLE))
        created.reopenUpcomingDsd(generation = 1)
        runUntil { opened.size == 3 }
        assertEquals(DsdOutputRateMode.MULTIPLE, opened.last().rateMode)
        assertEquals(0, created.currentMediaItemIndex)
        assertEquals(listOf("1", "2"), List(created.mediaItemCount) { created.getMediaItemAt(it).mediaId })

        // The same change asked again opens nothing again.
        created.reopenUpcomingDsd(generation = 1)
        runUntil { created.currentMediaItemIndex == 1 && created.audioFormat?.sampleRate == 176_400 }
        assertEquals("only the next track opened again", 3, opened.size)
    }

    /**
     * #356 review: on repeat, a track opens again after earlier changes while its queue item still
     * carries the key of the change that once re-created it. A later change back to those settings
     * must still re-create it. Paused, with seeks between the tracks: Media3 reuses a track it has
     * opened, so each opens exactly when counted here.
     */
    @Test
    fun `a change reaches the next track even when its item kept the key of an earlier change`() {
        val opened = CopyOnWriteArrayList<DsdConversion>()
        val current = AtomicReference(DsdConversion())
        val created = ExoPlayer.Builder(context, VesqenAudioRenderersFactory(context, AudioTrackAudioOutputProvider.Builder(context).build()))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, VesqenExtractorsFactory { current.get().also(opened::add) }))
            .build()
        player = created
        created.setMediaItems(listOf(dsdItem("1"), dsdItem("2")))
        created.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        created.prepare()
        val multiple = DsdConversion(DsdOutputRateMode.MULTIPLE)
        // A is current and B open next. Changing the rate re-creates B, which keeps that change's key.
        runUntil("A and B open") { opened.size == 2 }
        current.set(multiple)
        created.reopenUpcomingDsd(generation = 1)
        runUntil("B open again") { opened.size == 3 }
        // B is current and A opens for the next pass; changing back re-creates A.
        created.seekTo(1, 0)
        runUntil("A open after B") { opened.size == 4 }
        current.set(DsdConversion())
        created.reopenUpcomingDsd(generation = 2)
        runUntil("A open again") { opened.size == 5 }
        // A is current again and B opens with the defaults, its item still keyed for the earlier change.
        created.seekTo(0, 0)
        runUntil("B open after A") { opened.size == 6 }
        assertEquals(DsdConversion(), opened.last())
        // Changing to the settings of that old key must still re-create B.
        current.set(multiple)
        created.reopenUpcomingDsd(generation = 3)
        created.seekTo(1, 0)
        // B's own tracks, once it is the current item and ready; the renderer's format can still be A's.
        runUntil("B ready") {
            created.currentMediaItemIndex == 1 && created.playbackState == ExoPlayer.STATE_READY && !created.currentTracks.isEmpty
        }
        assertEquals(176_400, created.currentTracks.groups.single().getTrackFormat(0).sampleRate)
    }

    @Test
    fun `only a DSD track next in line is opened again, and never the one playing`() {
        val created = ExoPlayer.Builder(context, VesqenAudioRenderersFactory(context, AudioTrackAudioOutputProvider.Builder(context).build()))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, VesqenExtractorsFactory()))
            .build()
        player = created
        val flac = MediaItem.Builder().setUri(Uri.fromFile(dsdFile())).setMediaId("2")
            .setMediaMetadata(MediaMetadata.Builder().setExtras(TelemetryMediaItemExtras.from(track("FLAC"))).build()).build()
        created.setMediaItems(listOf(dsdItem("1"), flac))
        created.reopenUpcomingDsd(generation = 1)
        assertEquals(null, created.getMediaItemAt(1).localConfiguration?.customCacheKey)
        // Repeat one: the next track is the playing one.
        created.setMediaItems(listOf(dsdItem("1"), dsdItem("2")))
        created.repeatMode = ExoPlayer.REPEAT_MODE_ONE
        created.reopenUpcomingDsd(generation = 2)
        assertEquals(null, created.getMediaItemAt(0).localConfiguration?.customCacheKey)
        created.repeatMode = ExoPlayer.REPEAT_MODE_OFF
        created.reopenUpcomingDsd(generation = 3)
        assertTrue(created.getMediaItemAt(1).localConfiguration?.customCacheKey != null)
    }

    @Test
    fun `a rate change reaches the next DSD song when Media3 buffered it past a PCM song`() {
        val opened = CopyOnWriteArrayList<DsdConversion>()
        val current = AtomicReference(DsdConversion())
        val created = ExoPlayer.Builder(context, VesqenAudioRenderersFactory(context, AudioTrackAudioOutputProvider.Builder(context).build()))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, VesqenExtractorsFactory { current.get().also(opened::add) }))
            .build()
        player = created
        created.setMediaItems(listOf(dsdItem("1"), wavItem("2"), dsdItem("3")))
        created.prepare()
        runUntil("DSD after PCM already buffered") { opened.size >= 2 }
        assertEquals(0, created.currentMediaItemIndex)
        current.set(DsdConversion(DsdOutputRateMode.MULTIPLE))
        created.reopenUpcomingDsd(generation = 1)
        created.play()
        runUntil("automatic transition to third item") {
            created.currentMediaItemIndex == 2 && created.playbackState == ExoPlayer.STATE_READY && !created.currentTracks.isEmpty
        }
        assertEquals("new output rate", 176_400, created.currentTracks.groups.single().getTrackFormat(0).sampleRate)
    }

    @Test
    fun `updating the first upcoming DSD refreshes later buffered tracks and preserves shuffle order`() {
        val opened = CopyOnWriteArrayList<DsdConversion>()
        val current = AtomicReference(DsdConversion())
        val created = ExoPlayer.Builder(context, VesqenAudioRenderersFactory(context, AudioTrackAudioOutputProvider.Builder(context).build()))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, VesqenExtractorsFactory { current.get().also(opened::add) }))
            .build()
        player = created
        val order = listOf(0, 3, 1, 2)
        created.setShuffleOrder(PlayOrder(IntArray(0), nextSetOrder = order.toIntArray()))
        created.shuffleModeEnabled = true
        created.setMediaItems(listOf(dsdItem("1"), dsdItem("2"), dsdItem("3"), wavItem("4")))
        created.prepare()
        runUntil("all DSD streams opened") { opened.size >= 3 }
        assertEquals(0, created.currentMediaItemIndex)

        current.set(DsdConversion(DsdOutputRateMode.MULTIPLE))
        created.reopenUpcomingDsd(generation = 1)

        assertEquals(order, created.playOrder())
        assertEquals(0, created.currentMediaItemIndex)
        assertEquals(88_200, created.currentTracks.groups.single().getTrackFormat(0).sampleRate)
        assertEquals(null, created.getMediaItemAt(0).localConfiguration?.customCacheKey)
        assertEquals("vesqen-dsd:1", created.getMediaItemAt(1).localConfiguration?.customCacheKey)
        assertEquals(null, created.getMediaItemAt(2).localConfiguration?.customCacheKey)
        assertEquals(null, created.getMediaItemAt(3).localConfiguration?.customCacheKey)
        // Short tracks can start and end between two polls of the main looper (#406).
        val ratesByIndex = mutableMapOf<Int, Int>()
        created.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val rate = tracks.groups.singleOrNull()?.getTrackFormat(0)?.sampleRate ?: return
                ratesByIndex.putIfAbsent(created.currentMediaItemIndex, rate)
            }
        })
        created.play()
        runUntil("selected formats for both upcoming DSD items") { ratesByIndex.keys.containsAll(listOf(1, 2)) }
        assertEquals(176_400, ratesByIndex[1])
        assertEquals(176_400, ratesByIndex[2])
    }

    private fun wavItem(id: String): MediaItem {
        val wave = File(folder.root, "track-$id.wav").apply { writeBytes(PcmFiles.wavBytes(IntArray(44_100), 44_100, 16, 2)) }
        return MediaItem.Builder().setUri(Uri.fromFile(wave)).setMediaId(id).build()
    }

    private fun dsdItem(id: String, seconds: Int = 0) = MediaItem.Builder()
        .setUri(Uri.fromFile(dsdFile(seconds)))
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setExtras(TelemetryMediaItemExtras.from(track(DSD_CODEC_LABEL))).build())
        .build()

    private var fileNumber = 0

    /** [seconds] of DSD128, or a quarter of a second for 0; the two rate modes give different output rates. */
    private fun dsdFile(seconds: Int = 0): File {
        val bytes = if (seconds == 0) DsdExtractorTest.DSD64 * 2 / 4 / 8 else DsdExtractorTest.DSD64 * 2 / 8 * seconds
        val file = DsdExtractorTest.dsf(left = DsdExtractorTest.idle(bytes), right = DsdExtractorTest.idle(bytes), rate = DsdExtractorTest.DSD64 * 2)
        return File(folder.root, "track-${fileNumber++}.dsf").apply { writeBytes(file) }
    }

    private fun track(codec: String) = AudioTrack(
        id = 1,
        contentUri = "content://media/1",
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 250,
        codec = codec,
    )

    /** Lets the playback thread and the main looper run, advancing the clock a little. */
    private fun settle() {
        val done = CountDownLatch(1)
        Handler(player!!.playbackLooper).post { done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
    }

    /** Bounded by real time: files are read on Media3's own loader thread. */
    private fun runUntil(what: String = "the expected state", seconds: Long = 20, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            settle()
            Thread.sleep(2)
        }
        val state = player?.let { "item ${it.currentMediaItemIndex} at ${it.currentPosition} ms, state ${it.playbackState}, playing ${it.isPlaying}" }
        fail("the player did not reach $what within $seconds s ($state)")
    }
}
