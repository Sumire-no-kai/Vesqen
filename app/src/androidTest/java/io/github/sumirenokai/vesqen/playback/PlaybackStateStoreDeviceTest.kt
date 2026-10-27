package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStateStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferencesName = "playback-state-test-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val isolatedContext = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    @After
    fun removeTestPreferences() {
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun queueOccurrenceSurvivesNewStoreAndProgressOnlyCheckpoint() {
        val store = PlaybackStateStore(isolatedContext)
        store.save(
            PersistedPlaybackState(
                queueTrackIds = listOf(1, 2, 1),
                currentTrackId = 1,
                currentQueueIndex = 0,
                positionMs = 0,
                shuffleEnabled = false,
                repeatMode = PlaybackRepeatMode.OFF,
            ),
        )
        store.saveProgress(
            currentTrackId = 1,
            currentQueueIndex = 2,
            positionMs = 12_345,
            shuffleEnabled = true,
            repeatMode = PlaybackRepeatMode.ALL,
            queueEnded = true,
        )

        val loaded = PlaybackStateStore(isolatedContext).load()!!
        assertEquals(listOf(1L, 2L, 1L), loaded.queueTrackIds)
        assertEquals(2, loaded.currentQueueIndex)
        assertEquals(12_345, loaded.positionMs)
        assertEquals(true, loaded.shuffleEnabled)
        assertEquals(PlaybackRepeatMode.ALL, loaded.repeatMode)
        assertEquals(true, loaded.queueEnded)
    }

    @Test
    fun anUnknownEndStateStaysUnknownWhenSaved() {
        val store = PlaybackStateStore(isolatedContext)
        val state = PersistedPlaybackState(
            queueTrackIds = listOf(1, 2),
            currentTrackId = 2,
            currentQueueIndex = 1,
            positionMs = 59_500,
            shuffleEnabled = false,
            repeatMode = PlaybackRepeatMode.OFF,
            queueEnded = true,
        )
        store.save(state)
        store.save(state.copy(queueEnded = null))

        assertNull(PlaybackStateStore(isolatedContext).load()!!.queueEnded)
    }

    @Test
    fun theKeeperKeepsTheEndThroughAStopUntilTheQueueMovesAway() {
        val store = PlaybackStateStore(isolatedContext)
        val fixtures = File(context.cacheDir, "keeper-end-${UUID.randomUUID()}")
        val items = (1..2).map { id ->
            val file = File(fixtures, "$id.wav").also { writeSilentWav(it, durationMs = 300) }
            MediaItem.Builder().setMediaId("$id").setUri(Uri.fromFile(file)).build()
        }
        lateinit var player: ExoPlayer
        lateinit var keeper: PlaybackStateKeeper
        // The keeper hears a player's events in a later main-thread message.
        fun savedEnd(): Boolean? {
            main {}
            return store.load()?.queueEnded
        }
        try {
            main {
                player = ExoPlayer.Builder(context).build()
                keeper = PlaybackStateKeeper(player, store, Handler(Looper.getMainLooper()))
                keeper.start()
                player.setMediaItems(items)
                player.prepare()
                player.play()
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var state = Player.STATE_IDLE
            while (state != Player.STATE_ENDED) {
                check(SystemClock.uptimeMillis() < deadline) { "The queue never ended" }
                Thread.sleep(50)
                main { state = player.playbackState }
            }
            assertEquals(true, savedEnd())
            // A dismissed notification stops the player at the end of its last track (#170 P2).
            main { player.stop() }
            assertEquals(true, savedEnd())
            // Removing an earlier item, as a library refresh can, changes the timeline only.
            main { player.removeMediaItem(0) }
            assertEquals(true, savedEnd())
            main { player.seekTo(0, 0) }
            assertEquals(false, savedEnd())
        } finally {
            main {
                keeper.stop()
                player.release()
            }
            fixtures.deleteRecursively()
        }
    }

    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    @Test
    fun legacyPreferencesWithoutOccurrenceStillLoad() {
        preferences.edit()
            .putString("queue_track_ids", "1,2,1")
            .putLong("current_track_id", 1)
            .putLong("position_ms", 9_876)
            .commit()

        val loaded = PlaybackStateStore(isolatedContext).load()!!
        assertNull(loaded.currentQueueIndex)
        assertNull(loaded.queueEnded)
        assertEquals(listOf(1L, 2L, 1L), loaded.queueTrackIds)
        assertEquals(9_876, loaded.positionMs)
    }
}
