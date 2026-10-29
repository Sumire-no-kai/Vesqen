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
import io.github.sumirenokai.vesqen.library.AudioTrack
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testPrefix = "playback-state-test-${UUID.randomUUID()}"
    private val usedPreferences = mutableSetOf<String>()
    private val isolatedContext = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            context.getSharedPreferences("$testPrefix-$name".also(usedPreferences::add), mode)
    }

    /** The queue's file, as the store names it. */
    private val preferences = isolatedContext.getSharedPreferences("playback-state", Context.MODE_PRIVATE)

    @After
    fun removeTestPreferences() {
        usedPreferences.forEach(context::deleteSharedPreferences)
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

    @Test
    fun aProgressCheckpointLeavesTheQueueFileUntouched() {
        // SharedPreferences rewrites a whole file on any change, so a cursor kept next to a long
        // queue rewrote the queue every five seconds (#165).
        val store = PlaybackStateStore(isolatedContext)
        store.save(
            PersistedPlaybackState(
                queueTrackIds = (1L..5_000L).toList(),
                currentTrackId = 1,
                currentQueueIndex = 0,
                positionMs = 0,
                shuffleEnabled = false,
                repeatMode = PlaybackRepeatMode.OFF,
                queueEnded = false,
            ),
        )
        val queueFileChanges = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> queueFileChanges += key }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        try {
            for (bucket in 1..3) {
                store.saveProgress(
                    currentTrackId = 2,
                    currentQueueIndex = 1,
                    positionMs = bucket * 5_000L,
                    shuffleEnabled = false,
                    repeatMode = PlaybackRepeatMode.OFF,
                    queueEnded = false,
                    countedOccurrenceId = "a",
                )
            }
        } finally {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }

        assertEquals(emptyList<String?>(), queueFileChanges)
        val loaded = PlaybackStateStore(isolatedContext).load()!!
        assertEquals(5_000, loaded.queueTrackIds.size)
        assertEquals(2L, loaded.currentTrackId)
        assertEquals(1, loaded.currentQueueIndex)
        assertEquals(15_000, loaded.positionMs)
        assertEquals("a", loaded.countedOccurrenceId)
        assertEquals(CountedPlayback(2, "a"), PlaybackStateStore(isolatedContext).loadCountedPlayback())
    }

    @Test
    fun aCursorFromBeforeAnOlderReleaseWroteTheQueueFileIsIgnored() {
        val store = PlaybackStateStore(isolatedContext)
        store.save(
            PersistedPlaybackState(
                queueTrackIds = listOf(1, 2, 3),
                currentTrackId = 1,
                currentQueueIndex = 0,
                positionMs = 0,
                shuffleEnabled = false,
                repeatMode = PlaybackRepeatMode.OFF,
                queueEnded = false,
            ),
        )
        store.saveProgress(
            currentTrackId = 3,
            currentQueueIndex = 2,
            positionMs = 40_000,
            shuffleEnabled = false,
            repeatMode = PlaybackRepeatMode.OFF,
            queueEnded = false,
            countedOccurrenceId = "a",
        )
        // A release before 1.1.0 (after a downgrade) keeps its cursor in the queue's file only.
        preferences.edit()
            .putLong("current_track_id", 2)
            .putInt("current_queue_index", 1)
            .putLong("position_ms", 7_000)
            .commit()

        val loaded = PlaybackStateStore(isolatedContext).load()!!
        assertEquals(listOf(1L, 2L, 3L), loaded.queueTrackIds)
        assertEquals(2L, loaded.currentTrackId)
        assertEquals(1, loaded.currentQueueIndex)
        assertEquals(7_000, loaded.positionMs)
        assertNull(loaded.countedOccurrenceId)
        // A queue saved later replaces the cursor in both files.
        store.save(loaded.copy(queueTrackIds = listOf(3, 2), currentTrackId = 3, currentQueueIndex = 0, positionMs = 0))
        assertEquals(0, PlaybackStateStore(isolatedContext).load()!!.positionMs)
        store.clear()
        assertNull(PlaybackStateStore(isolatedContext).load())
        assertNull(PlaybackStateStore(isolatedContext).loadCountedPlayback())
    }

    @Test
    fun aListenResumedByARestoreIsNotCountedAgain() {
        val store = PlaybackStateStore(isolatedContext)
        val fixtures = File(context.cacheDir, "keeper-listen-${UUID.randomUUID()}")
        val tracks = (1L..2L).map { id ->
            val file = File(fixtures, "$id.wav").also { writeSilentWav(it, durationMs = 3_000) }
            AudioTrack(id = id, contentUri = Uri.fromFile(file).toString(), title = "$id", artist = "", album = "", durationMs = 3_000)
        }
        val counted = mutableListOf<Long>()
        var player: ExoPlayer? = null
        var keeper: PlaybackStateKeeper? = null
        fun startService() {
            player = ExoPlayer.Builder(context).build()
            keeper = PlaybackStateKeeper(player!!, store, Handler(Looper.getMainLooper()), onPlaybackStarted = { counted += it })
                .also(PlaybackStateKeeper::start)
        }
        // The service ends (Android ending a paused app's process, say); the keeper saves on the way out.
        fun endService() {
            keeper?.stop()
            player?.release()
        }
        fun awaitCounted(expected: List<Long>) {
            val deadline = SystemClock.uptimeMillis() + 10_000
            var seen: List<Long> = emptyList()
            while (seen != expected) {
                check(SystemClock.uptimeMillis() < deadline) { "Counted $seen, expected $expected" }
                Thread.sleep(50)
                main { seen = counted.toList() }
            }
        }
        try {
            main {
                startService()
                player!!.setMediaItems(playbackMediaItems(tracks, startIndex = 0))
                player!!.prepare()
                player!!.play()
            }
            awaitCounted(listOf(1))
            main {
                player!!.pause()
                endService()
            }

            val restored = store.load()!!.restoreAgainst(tracks)!!
            assertEquals(0, restored.startIndex)
            assertNotNull(restored.startOccurrenceId)
            val items = playbackMediaItems(restored.tracks, restored.startIndex, restored.startOccurrenceId)
            assertEquals(restored.startOccurrenceId, items[0].mediaMetadata.extras?.getString(PLAYBACK_OCCURRENCE_EXTRA))
            assertTrue(items[1].mediaMetadata.extras?.getString(PLAYBACK_OCCURRENCE_EXTRA) != restored.startOccurrenceId)
            var playing = false
            main {
                startService()
                player!!.setMediaItems(items, restored.startIndex, restored.positionMs)
                player!!.prepare()
                player!!.play()
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!playing) {
                check(SystemClock.uptimeMillis() < deadline) { "The restored queue never played" }
                Thread.sleep(50)
                main { playing = player!!.isPlaying }
            }
            main { player!!.seekToNextMediaItem() }
            // Resuming track 1 was not a listen; the next track is.
            awaitCounted(listOf(1, 2))
        } finally {
            main(::endService)
            fixtures.deleteRecursively()
        }
    }

    @Test
    fun aCountedListenDoesNotPassToTheTrackAnOlderReleaseMadeCurrent() {
        val store = PlaybackStateStore(isolatedContext)
        store.save(
            PersistedPlaybackState(
                queueTrackIds = listOf(1, 2, 3),
                currentTrackId = 1,
                currentQueueIndex = 0,
                positionMs = 20_000,
                shuffleEnabled = false,
                repeatMode = PlaybackRepeatMode.OFF,
                queueEnded = false,
                countedOccurrenceId = "a",
            ),
        )
        // After a downgrade, a release before 1.1.0 moves to track 2 and leaves the keys it does not know.
        preferences.edit()
            .putLong("current_track_id", 2)
            .putInt("current_queue_index", 1)
            .putLong("position_ms", 0)
            .commit()

        val loaded = PlaybackStateStore(isolatedContext).load()!!
        assertEquals(2L, loaded.currentTrackId)
        assertNull(loaded.countedOccurrenceId)
        assertNull(PlaybackStateStore(isolatedContext).loadCountedPlayback())
        val tracks = (1L..3L).map { id ->
            AudioTrack(id = id, contentUri = "content://track/$id", title = "$id", artist = "", album = "", durationMs = 60_000)
        }
        assertNull(loaded.restoreAgainst(tracks)!!.startOccurrenceId)
    }

    @Test
    fun aTrackARestoreCouldNotPlayComesBackUntilTheQueueIsEdited() {
        // A card not mounted yet or a folder grant lost makes saved tracks unreadable for a while (#165).
        val store = PlaybackStateStore(isolatedContext)
        val tracks = (1L..4L).map { id ->
            AudioTrack(id = id, contentUri = "content://track/$id", title = "$id", artist = "", album = "", durationMs = 60_000)
        }
        fun playerState(queue: List<Long>, index: Int, positionMs: Long) = PersistedPlaybackState(
            queueTrackIds = queue,
            currentTrackId = queue[index],
            currentQueueIndex = index,
            positionMs = positionMs,
            shuffleEnabled = false,
            repeatMode = PlaybackRepeatMode.OFF,
            queueEnded = false,
        )
        store.save(playerState(listOf(1, 2, 3, 2, 4), index = 2, positionMs = 30_000))

        val first = store.load()!!.restoreAgainst(tracks.filter { it.id != 2L })!!
        assertEquals(listOf(1L, 3L, 4L), first.tracks.map(AudioTrack::id))
        store.holdBack(first.heldBackTrackIds)
        // The state keeper saves the player's queue, then checkpoints.
        store.save(playerState(listOf(1, 3, 4), index = 1, positionMs = 30_000))
        store.saveProgress(
            currentTrackId = 4,
            currentQueueIndex = 2,
            positionMs = 5_000,
            shuffleEnabled = false,
            repeatMode = PlaybackRepeatMode.OFF,
            queueEnded = false,
        )

        val second = PlaybackStateStore(isolatedContext).load()!!
        assertEquals(listOf(1L, 2L, 3L, 2L, 4L), second.queueTrackIds)
        // Releases before 1.1.0 read only the queue's file; its cursor is the saved queue's too.
        assertEquals(2, preferences.getInt("current_queue_index", -1))
        val restored = second.restoreAgainst(tracks)!!
        assertEquals(listOf(1L, 2L, 3L, 2L, 4L), restored.tracks.map(AudioTrack::id))
        assertEquals(4, restored.startIndex)
        assertEquals(5_000, restored.positionMs)

        // An edit saves the player's queue as it is; the held-back tracks go with it.
        store.save(playerState(listOf(1, 4), index = 1, positionMs = 0))
        assertEquals(listOf(1L, 4L), PlaybackStateStore(isolatedContext).load()!!.queueTrackIds)
        store.save(playerState(listOf(1, 3, 4), index = 2, positionMs = 0))
        assertEquals(listOf(1L, 3L, 4L), PlaybackStateStore(isolatedContext).load()!!.queueTrackIds)
    }

    @Test
    fun aShuffledPlayOrderIsSavedWithTheQueueAndAnOlderReleasesLeftoverIsIgnored() {
        val store = PlaybackStateStore(isolatedContext)
        val shuffled = PersistedPlaybackState(
            queueTrackIds = listOf(1, 2, 3),
            currentTrackId = 2,
            currentQueueIndex = 1,
            positionMs = 10_000,
            shuffleEnabled = true,
            repeatMode = PlaybackRepeatMode.OFF,
            queueEnded = false,
            playOrder = listOf(2, 1, 0),
        )
        store.save(shuffled)
        store.saveProgress(
            currentTrackId = 1,
            currentQueueIndex = 0,
            positionMs = 3_000,
            shuffleEnabled = true,
            repeatMode = PlaybackRepeatMode.OFF,
            queueEnded = false,
        )
        assertEquals(listOf(2, 1, 0), PlaybackStateStore(isolatedContext).load()!!.playOrder)
        // Without shuffle the list is the order, and none is kept.
        store.save(shuffled.copy(shuffleEnabled = false, playOrder = null))
        assertNull(PlaybackStateStore(isolatedContext).load()!!.playOrder)
        assertEquals(false, preferences.contains("play_order"))

        // After a downgrade, a release before 1.1.0 rewrites the queue's file and keeps the order.
        store.save(shuffled)
        preferences.edit()
            .putLong("current_track_id", 3)
            .putInt("current_queue_index", 2)
            .putLong("position_ms", 0)
            .commit()
        val loaded = PlaybackStateStore(isolatedContext).load()!!
        assertEquals(3L, loaded.currentTrackId)
        assertNull(loaded.playOrder)
        store.save(shuffled)
        preferences.edit().putString("queue_track_ids", "1,2,3,4").commit()
        assertNull(PlaybackStateStore(isolatedContext).load()!!.playOrder)
    }

    @Test
    fun theKeeperSavesTheShuffledOrderWhenOnlyTheOrderChanges() {
        val store = PlaybackStateStore(isolatedContext)
        val items = (1..4).map { MediaItem.Builder().setMediaId("$it").setUri("content://fixture/$it").build() }
        lateinit var player: ExoPlayer
        lateinit var keeper: PlaybackStateKeeper
        // The keeper hears a player's events in a later main-thread message.
        fun savedOrder(): List<Int>? {
            main {}
            return store.load()?.playOrder
        }
        try {
            main {
                player = ExoPlayer.Builder(context).build().apply { setShuffleOrder(PlayOrder(IntArray(0))) }
                keeper = PlaybackStateKeeper(player, store, Handler(Looper.getMainLooper())).also(PlaybackStateKeeper::start)
                player.setMediaItems(items, 1, 0)
            }
            assertNull(savedOrder())
            var order = emptyList<Int>()
            main {
                player.shuffleModeEnabled = true
                order = player.shuffleOrder.playOrder().toList()
            }
            assertEquals(order, savedOrder())
            // A shuffled move changes only the order (#158).
            main { player.setShuffleOrder(PlayOrder(intArrayOf(3, 1, 0, 2))) }
            assertEquals(listOf(3, 1, 0, 2), savedOrder())
            main { player.shuffleModeEnabled = false }
            assertNull(savedOrder())
        } finally {
            main {
                keeper.stop()
                player.release()
            }
        }
    }

    @Test
    fun aHeldBackTrackKeepsItsPlaceInTheSavedPlayOrder() {
        val store = PlaybackStateStore(isolatedContext)
        val tracks = (1L..4L).map { id ->
            AudioTrack(id = id, contentUri = "content://track/$id", title = "$id", artist = "", album = "", durationMs = 60_000)
        }
        fun playerState(queue: List<Long>, index: Int, order: List<Int>?) = PersistedPlaybackState(
            queueTrackIds = queue,
            currentTrackId = queue[index],
            currentQueueIndex = index,
            positionMs = 0,
            shuffleEnabled = order != null,
            repeatMode = PlaybackRepeatMode.OFF,
            queueEnded = false,
            playOrder = order,
        )
        store.save(playerState(listOf(1, 2, 3, 2, 4), index = 2, order = listOf(4, 1, 0, 3, 2)))
        val first = store.load()!!.restoreAgainst(tracks.filter { it.id != 2L })!!
        assertEquals(listOf(1L, 3L, 4L), first.tracks.map(AudioTrack::id))
        assertEquals(listOf(2, 0, 1), first.playOrder)
        store.holdBack(first.heldBackTrackIds)
        // While the player holds that restore, a shuffled move puts track 1 last.
        store.save(playerState(listOf(1, 3, 4), index = 1, order = listOf(2, 1, 0)))

        val second = PlaybackStateStore(isolatedContext).load()!!
        assertEquals(listOf(1L, 2L, 3L, 2L, 4L), second.queueTrackIds)
        // Both copies of track 2 keep their places; the others are in the player's order.
        assertEquals(listOf(4, 1, 2, 3, 0), second.playOrder)
        assertEquals(listOf(4, 1, 2, 3, 0), second.restoreAgainst(tracks)!!.playOrder)

        // A restore that was not shuffled has no order to keep: once shuffle is on, a held-back track
        // plays right after its list predecessor.
        store.clear()
        store.save(playerState(listOf(1, 2, 3, 2, 4), index = 2, order = null))
        store.holdBack(store.load()!!.restoreAgainst(tracks.filter { it.id != 2L })!!.heldBackTrackIds)
        store.save(playerState(listOf(1, 3, 4), index = 1, order = listOf(1, 2, 0)))
        assertEquals(listOf(2, 3, 4, 0, 1), PlaybackStateStore(isolatedContext).load()!!.playOrder)
    }

    @Test
    fun aClearedQueueLeavesOnlyItsModes() {
        val store = PlaybackStateStore(isolatedContext)
        store.save(
            PersistedPlaybackState(
                queueTrackIds = listOf(1, 2),
                currentTrackId = 2,
                positionMs = 1_000,
                shuffleEnabled = true,
                repeatMode = PlaybackRepeatMode.ALL,
                currentQueueIndex = 1,
                playOrder = listOf(1, 0),
            ),
        )
        assertNull(store.loadKeptPlayModes())
        // The latest modes are the progress file's.
        store.saveProgress(currentTrackId = 2, positionMs = 6_000, shuffleEnabled = true, repeatMode = PlaybackRepeatMode.ONE, currentQueueIndex = 1, queueEnded = false)
        store.clear()

        // #165 X2: the modes stay for the next queue. Releases before 1.1.0 read a saved queue from
        // queue_track_ids only, so after a downgrade they find none, as after a clear before.
        assertEquals(setOf("shuffle_enabled", "repeat_mode"), preferences.all.keys)
        assertEquals(emptyMap<String, Any?>(), isolatedContext.getSharedPreferences("playback-progress", Context.MODE_PRIVATE).all)
        val fresh = PlaybackStateStore(isolatedContext)
        assertNull(fresh.load())
        assertFalse(fresh.hasSavedQueue())
        assertNull(fresh.loadCountedPlayback())
        assertEquals(PlaybackOrderSettings(true, PlaybackRepeatMode.ONE), fresh.loadKeptPlayModes())

        // A catalog rebuild's clear keeps them too; the keeper's clear gives the player's own.
        fresh.clear(durable = true)
        assertEquals(PlaybackOrderSettings(true, PlaybackRepeatMode.ONE), PlaybackStateStore(isolatedContext).loadKeptPlayModes())
        fresh.clear(PlaybackOrderSettings(false, PlaybackRepeatMode.ALL))
        assertEquals(PlaybackOrderSettings(false, PlaybackRepeatMode.ALL), PlaybackStateStore(isolatedContext).loadKeptPlayModes())
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
