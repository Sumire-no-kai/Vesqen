package io.github.sumirenokai.vesqen.playback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #158 through the real service and session: with shuffle on, Play next plays next, Add to queue
 * plays last, a move changes only the play order, and the queue lists that order. #170 F8–F10: an
 * insert is one queue change that the controller shows at once, in the play order.
 */
class ShuffleQueueEditDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()
    private val playback = AtomicReference(PlaybackSnapshot())
    private lateinit var controller: PlaybackController

    @Test
    fun queueEditsLandInThePlayOrderWhileShuffled() = runBlocking {
        val queued = (1L..6L).map(::silentTrack)
        val (next, last) = listOf(silentTrack(7), silentTrack(8))
        try {
            main { controller = PlaybackController(app) { playback.set(it) } }
            awaitPlayback { it.isControllerReady }
            main { controller.playQueue(queued, 0) }
            awaitPlayback { it.trackId == queued[0].id && it.queueSize == 6 }
            main { controller.cyclePlaybackOrderMode() }
            // Shuffle starts at the current track, so all the others still play. The service sets
            // that order after the mode change reaches the controller.
            awaitPlayback {
                it.playbackOrderMode == PlaybackOrderMode.SHUFFLE && it.queueIndex == 0 &&
                    it.queue.firstOrNull()?.trackId == queued[0].id
            }
            val shuffled = playback.get().queue.map(PlaybackQueueItem::trackId)

            main { controller.playNext(next) }
            awaitPlayback { it.queueSize == 7 && it.queue.getOrNull(it.queueIndex + 1)?.trackId == next.id }
            assertEquals(shuffled, playback.get().queue.map(PlaybackQueueItem::trackId) - next.id)

            main { controller.addToQueue(last) }
            awaitPlayback { it.queueSize == 8 && it.queue.last().trackId == last.id }

            main { controller.skipToNext() }
            awaitPlayback { it.trackId == next.id }

            // The queue's last row moves up one: only the play order changes.
            val order = playback.get().queue
            val moved = order.last()
            val above = order[order.lastIndex - 1]
            main { controller.moveQueueItem(moved.listIndex, above.listIndex) }
            val expected = order.dropLast(2).map(PlaybackQueueItem::trackId) + listOf(moved.trackId, above.trackId)
            awaitPlayback { snapshot -> snapshot.queue.map(PlaybackQueueItem::trackId) == expected }

            // What the queue lists after the current track is what Next plays, to the end.
            val upcoming = expected.drop(playback.get().queueIndex + 1)
            val played = upcoming.map { expectedId ->
                main { controller.skipToNext() }
                awaitPlayback { it.trackId == expectedId || !it.hasNext }
                playback.get().trackId
            }
            assertEquals(upcoming, played)

            // Shuffle off (the cycle's next step): the list kept its order, with Play next after the
            // track that was current and Add to queue at the end.
            main { controller.cyclePlaybackOrderMode() }
            awaitPlayback { !it.shuffleEnabled }
            assertEquals(
                listOf(queued[0].id, next.id) + queued.drop(1).map(AudioTrack::id) + last.id,
                playback.get().queue.map(PlaybackQueueItem::trackId),
            )
        } finally {
            releasePlayback()
        }
    }

    @Test
    fun aMovedQueuedFileLeavesTheCurrentTrackPlaying() = runBlocking {
        val queued = (1L..4L).map(::silentTrack)
        try {
            main { controller = PlaybackController(app) { playback.set(it) } }
            awaitPlayback { it.isControllerReady }
            main { controller.playQueue(queued, 1) }
            awaitPlayback { it.trackId == queued[1].id && it.positionMs >= 1_000 }
            val position = playback.get().positionMs

            // A library refresh after a rename keeps the catalog ID with a new URI (#164), next to a
            // tag edit of the current track. The URI change used to replace the whole queue, which
            // ended the current track and started the first one.
            val renamed = File(app.cacheDir, "$FIXTURE_DIR/renamed.wav").also { writeSilentWav(it, durationMs = 20_000) }
            val library = listOf(queued[0], queued[1].copy(title = "Retitled"), queued[2], queued[3].copy(contentUri = renamed.toURI().toString()))
            main { controller.syncLibrary(library) }
            // Long enough for the session's own timeline to reach the controller.
            awaitPlayback { it.trackId != queued[1].id || (it.title == "Retitled" && it.positionMs >= position + 1_000) }

            val after = playback.get()
            assertEquals(queued[1].id, after.trackId)
            assertTrue("${after.positionMs} < $position", after.positionMs >= position)
            assertEquals(queued.map(AudioTrack::id), after.queue.map(PlaybackQueueItem::trackId))
        } finally {
            releasePlayback()
        }
    }

    @Test
    fun aLibraryRefreshRightAfterPlayNextSeesTheNewQueue() = runBlocking {
        val queued = (1L..4L).map(::silentTrack)
        val next = silentTrack(5)
        try {
            main { controller = PlaybackController(app) { playback.set(it) } }
            awaitPlayback { it.isControllerReady }
            main { controller.playQueue(queued, 0) }
            awaitPlayback { it.trackId == queued[0].id && it.queueSize == 4 && it.positionMs >= 500 }
            val position = playback.get().positionMs

            // #170 F8: the refresh comes before the session's queue does. It retitles the track that
            // followed the current one, which Play next's track now precedes.
            main {
                controller.playNext(next)
                controller.syncLibrary(listOf(queued[0], queued[1].copy(title = "Retitled"), queued[2], queued[3]))
            }
            val expected = listOf(queued[0].id, next.id, queued[1].id, queued[2].id, queued[3].id)
            // Long enough for the session's own timeline to reach the controller.
            awaitPlayback { snapshot -> snapshot.queue.map(PlaybackQueueItem::trackId) == expected && snapshot.positionMs >= position + 1_000 }

            assertEquals(listOf("Track 1", "Track 5", "Retitled", "Track 3", "Track 4"), playback.get().queue.map(PlaybackQueueItem::title))
        } finally {
            releasePlayback()
        }
    }

    @Test
    fun aShuffledEditIsListedInThePlayOrderBeforeTheSessionsQueueArrives() = runBlocking {
        val queued = (1L..6L).map(::silentTrack)
        val (next, last) = listOf(silentTrack(7), silentTrack(8))
        try {
            main { controller = PlaybackController(app) { playback.set(it) } }
            awaitPlayback { it.isControllerReady }
            // Shuffled from the third track, which plays first, so the play order is not the list order.
            main { controller.playQueue(queued, 2) }
            awaitPlayback { it.trackId == queued[2].id && it.queueSize == 6 }
            main { controller.cyclePlaybackOrderMode() }
            awaitPlayback { it.shuffleEnabled && it.queue.firstOrNull()?.trackId == queued[2].id }
            var order = playback.get().queue.map(PlaybackQueueItem::trackId)

            // #170 F10: Media3 shows each of these edits at once in list order; the queue lists the play
            // order the edit leads to, which the session's queue then confirms.
            val removed = order[3]
            order = order - removed
            assertEquals(order, shownAfter { controller.removeQueueItem(listIndexOf(removed)) })
            assertSettledQueue(order)

            val retitled = order[1]
            val library = queued.map { if (it.id == retitled) it.copy(title = "Retitled") else it }
            assertEquals(order, shownAfter { controller.syncLibrary(library) })
            assertSettledQueue(order)
            assertEquals("Retitled", playback.get().queue[1].title)

            order = order.take(1) + next.id + order.drop(1)
            assertEquals(order, shownAfter { controller.playNext(next) })
            order = order + last.id
            assertEquals(order, shownAfter { controller.addToQueue(last) })
            assertSettledQueue(order)
        } finally {
            releasePlayback()
        }
    }

    @Test
    fun turningShuffleOnListsTheCurrentTrackFirstAtOnce() = runBlocking {
        val queued = (1L..4L).map(::silentTrack)
        try {
            main { controller = PlaybackController(app) { playback.set(it) } }
            awaitPlayback { it.isControllerReady }
            // The queue's shuffled order starts at its start track, the first one.
            main { controller.playQueue(queued, 0) }
            awaitPlayback { it.trackId == queued[0].id }
            main { controller.skipToNext() }
            awaitPlayback { it.trackId == queued[1].id }

            // #170 F10: before the service's order arrives, the queue already starts at the current track.
            var shown = PlaybackSnapshot()
            main {
                controller.cyclePlaybackOrderMode()
                shown = playback.get()
            }
            assertTrue(shown.shuffleEnabled)
            assertEquals(queued[1].id, shown.queue.first().trackId)
            assertEquals(0, shown.queueIndex)
            assertSettledQueue(shown.queue.map(PlaybackQueueItem::trackId))
        } finally {
            releasePlayback()
        }
    }

    @Test
    fun aShuffledInsertChangesTheTimelineOnce() {
        val reasons = mutableListOf<List<Int>>()
        var before = emptyList<String>()
        var afterNext = emptyList<String>()
        var afterLast = emptyList<String>()
        main {
            val player = ExoPlayer.Builder(app).build().apply { setShuffleOrder(PlayOrder(IntArray(0))) }
            try {
                player.setMediaItems((1..6).map { entry("$it", "o$it") }, 2, 0)
                player.shuffleModeEnabled = true
                val changes = mutableListOf<Int>()
                player.addListener(object : Player.Listener {
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        changes += reason
                    }
                })
                before = player.entriesInPlayOrder()
                // #170 F9: each insert used to change the timeline twice, and so rebuild the session's queue twice.
                player.insertInPlayOrder(entry("7", "next"), PlayOrderPlacement.NEXT)
                reasons += changes.toList().also { changes.clear() }
                afterNext = player.entriesInPlayOrder()
                player.insertInPlayOrder(entry("8", "last"), PlayOrderPlacement.LAST)
                reasons += changes.toList()
                afterLast = player.entriesInPlayOrder()
            } finally {
                player.release()
            }
        }
        assertEquals(List(2) { listOf(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }, reasons)
        assertEquals("o3", before.first())
        assertEquals(listOf("o3", "next") + before.drop(1), afterNext)
        assertEquals(afterNext + "last", afterLast)
    }

    @Test
    fun playNextFollowsTheServicesOwnCurrentTrack() {
        var entries = emptyList<String>()
        var request = MediaItem.RequestMetadata.EMPTY
        main {
            val player = ExoPlayer.Builder(app).build().apply { setShuffleOrder(PlayOrder(IntArray(0))) }
            try {
                player.setMediaItems((1..4).map { entry("$it", "o$it") }, 2, 0)
                val session = PlayOrderSessionPlayer(player)
                // The controller asked while the first track was current; the service has moved on to
                // the third one. An insert without a placement keeps its list index.
                session.addMediaItems(1, listOf(entry("5", "next").withPlayOrderPlacement(PlayOrderPlacement.NEXT)))
                session.addMediaItems(0, listOf(entry("6", "plain")))
                entries = List(player.mediaItemCount) { player.getMediaItemAt(it).queueEntryId() }
                request = player.getMediaItemAt(4).requestMetadata
            } finally {
                player.release()
            }
        }
        assertEquals(listOf("plain", "o1", "o2", "o3", "next", "o4"), entries)
        assertEquals(MediaItem.RequestMetadata.EMPTY, request)
    }

    @Test
    fun aShuffledQueueRestoredAfterTheProcessEndsKeepsItsPlayOrder() = runBlocking {
        val queued = (1L..6L).map(::silentTrack)
        try {
            main { controller = PlaybackController(app) { playback.set(it) } }
            awaitPlayback { it.isControllerReady }
            main { controller.playQueue(queued, 0) }
            awaitPlayback { it.trackId == queued[0].id && it.queueSize == 6 }
            main { controller.cyclePlaybackOrderMode() }
            awaitPlayback { it.shuffleEnabled && it.queue.firstOrNull()?.trackId == queued[0].id }
            val second = playback.get().queue[1].trackId
            main { controller.skipToNext() }
            awaitPlayback { it.trackId == second }
            // A shuffled move changes only the order (#158), which the queue's file saves too.
            val before = playback.get().queue
            main { controller.moveQueueItem(before.last().listIndex, before[before.lastIndex - 1].listIndex) }
            val order = before.dropLast(2).map(PlaybackQueueItem::trackId) + listOf(before.last().trackId, before[before.lastIndex - 1].trackId)
            awaitPlayback { snapshot -> snapshot.queue.map(PlaybackQueueItem::trackId) == order }
            main { controller.togglePlayback() }
            withTimeout(15_000) { while (savedTracksInPlayOrder() != order) delay(50) }

            // Android ends the paused app: its files stay as they are, and the next process starts with
            // an empty player. Here the service and this controller live on (releasing the controller
            // could end a service an earlier test stopped), so the player is emptied, which clears the
            // saved queue, and the files are put back.
            val files = savedPlaybackFiles()
            main { controller.clearQueue() }
            withTimeout(15_000) { while (PlaybackStateStore(app).load() != null) delay(50) }
            restorePlaybackFiles(files)

            // The queue is restored once the Library is read (#170), as in the next process: the same
            // order and current track, listed at once.
            assertEquals(order, shownAfter { controller.syncLibrary(queued) })
            assertEquals(second, playback.get().trackId)
            main { controller.togglePlayback() }
            assertSettledQueue(order)
            // What the queue lists after the current track is what Next plays.
            val upcoming = order.drop(playback.get().queueIndex + 1)
            assertEquals(order.drop(2), upcoming)
            val played = upcoming.map { expectedId ->
                main { controller.skipToNext() }
                awaitPlayback { it.trackId == expectedId || !it.hasNext }
                playback.get().trackId
            }
            assertEquals(upcoming, played)
        } finally {
            releasePlayback()
        }
    }

    @Test
    fun aRestoredQueueIsSetInItsSavedPlayOrder() {
        val reasons = mutableListOf<Int>()
        var restoredReasons = emptyList<Int>()
        var order = emptyList<Int>()
        var current = C.INDEX_UNSET
        var requests = emptyList<MediaItem.RequestMetadata>()
        var unnamed = emptyList<Int>()
        main {
            val player = ExoPlayer.Builder(app).build().apply { setShuffleOrder(PlayOrder(IntArray(0))) }
            try {
                val session = PlayOrderSessionPlayer(player)
                session.shuffleModeEnabled = true
                player.addListener(object : Player.Listener {
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        reasons += reason
                    }
                })
                // As the app's controller sends them through the session.
                val items = (1..5).map { entry("$it", "o$it") }.withPlayOrder(listOf(3, 0, 4, 1, 2))
                    .map { MediaItem.fromBundle(it.toBundleIncludeLocalConfiguration()) }
                session.setMediaItems(items, 1, 0)
                restoredReasons = reasons.toList()
                order = player.shuffleOrder.playOrder().toList()
                current = player.currentMediaItemIndex
                requests = List(player.mediaItemCount) { player.getMediaItemAt(it).requestMetadata }
                // Places that do not name one item each are no order: a new queue plays its start first.
                session.setMediaItems(items.take(4), 1, 0)
                unnamed = player.shuffleOrder.playOrder().toList()
            } finally {
                player.release()
            }
        }
        assertEquals(listOf(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED), restoredReasons)
        assertEquals(listOf(3, 0, 4, 1, 2), order)
        assertEquals(1, current)
        assertEquals(List(5) { MediaItem.RequestMetadata.EMPTY }, requests)
        assertEquals(1, unnamed.first())
    }

    @Test
    fun requestsWithoutTheirKeysAreRefused() {
        // The session reads an insert's placement from the item the controller sent.
        val sent = MediaItem.fromBundle(entry("1", "a").withPlayOrderPlacement(PlayOrderPlacement.LAST).toBundle())
        assertEquals(PlayOrderPlacement.LAST, sent.playOrderPlacement())
        assertNull(entry("1", "a").playOrderPlacement())
        val move = PlayOrderSessionContract.moveArgs(PlayOrderSessionContract.Move(0, "a", 1, "b", after = true))
        assertEquals(PlayOrderSessionContract.Move(0, "a", 1, "b", after = true), PlayOrderSessionContract.readMove(move))
        assertNull(PlayOrderSessionContract.readMove(Bundle(move).apply { remove("after") }))
    }

    private fun releasePlayback() {
        main {
            if (playback.get().showsPauseAction) controller.togglePlayback()
            controller.clearQueue()
            controller.release()
            // Later tests expect a service that has never played.
            app.stopService(Intent(app, PlaybackService::class.java))
        }
        File(app.cacheDir, FIXTURE_DIR).deleteRecursively()
    }

    @Test
    fun aMoveAgainstAChangedQueueIsRefused() {
        var before = emptyList<Int>()
        var after = emptyList<Int>()
        var stale = true
        var current = false
        main {
            val player = ExoPlayer.Builder(app).build()
            try {
                // The same track twice: only the entry ID tells the copies apart.
                player.setMediaItems(listOf(entry("1", "a"), entry("2", "b"), entry("1", "c")))
                player.shuffleModeEnabled = true
                before = player.shuffleOrder.playOrder().toList()
                stale = player.moveInPlayOrder(PlayOrderSessionContract.Move(0, "c", 1, "b", after = true))
                after = player.shuffleOrder.playOrder().toList()
                current = player.moveInPlayOrder(PlayOrderSessionContract.Move(0, "a", 1, "b", after = true))
            } finally {
                player.release()
            }
        }
        assertEquals(false, stale)
        assertEquals(before, after)
        assertEquals(true, current)
    }

    /** The saved queue's tracks in its saved play order. */
    private fun savedTracksInPlayOrder(): List<Long>? =
        PlaybackStateStore(app).load()?.let { saved -> saved.playOrder?.map(saved.queueTrackIds::get) }

    private fun savedPlaybackFiles(): Map<String, Map<String, *>> =
        PLAYBACK_FILES.associateWith { app.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }

    private fun restorePlaybackFiles(files: Map<String, Map<String, *>>) = files.forEach { (name, values) ->
        val editor = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> error("Unexpected $key: $value")
            }
        }
        check(editor.commit())
    }

    private fun ExoPlayer.entriesInPlayOrder(): List<String> = shuffleOrder.playOrder().map { getMediaItemAt(it).queueEntryId() }

    /** The queue the controller lists right after [edit], before the session's own queue can arrive. */
    private fun shownAfter(edit: () -> Unit): List<Long> {
        var shown = emptyList<Long>()
        main {
            edit()
            shown = playback.get().queue.map(PlaybackQueueItem::trackId)
        }
        return shown
    }

    private fun listIndexOf(trackId: Long): Int = playback.get().queue.first { it.trackId == trackId }.listIndex

    /** The queue still lists [order] once the session's own queue has had time to arrive. */
    private suspend fun assertSettledQueue(order: List<Long>) {
        val position = playback.get().positionMs
        awaitPlayback { it.positionMs >= position + 500 }
        assertEquals(order, playback.get().queue.map(PlaybackQueueItem::trackId))
    }

    private fun entry(trackId: String, occurrence: String) = MediaItem.Builder()
        .setMediaId(trackId)
        .setUri("content://fixture/$occurrence")
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putString(PLAYBACK_OCCURRENCE_EXTRA, occurrence) }).build())
        .build()

    private suspend fun awaitPlayback(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
        while (true) {
            main { controller.refreshPosition() }
            if (predicate(playback.get())) break
            delay(50)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    /** Twenty seconds of silence, private to the deviceTest app. */
    private fun silentTrack(id: Long): AudioTrack {
        val file = File(File(app.cacheDir, FIXTURE_DIR), "VESQEN_QA_PRIVATE_$id.wav").also { writeSilentWav(it, durationMs = 20_000) }
        return AudioTrack(-1_000 - id, file.toURI().toString(), "Track $id", "", "", 20_000,
            fileName = file.name, fileSizeBytes = file.length(), mimeType = "audio/wav", codec = "PCM",
            sampleRateHz = 8_000, bitDepth = 16, channelCount = 1)
    }

    private companion object {
        const val FIXTURE_DIR = "shuffle-queue-edits"
        val PLAYBACK_FILES = listOf("playback-state", "playback-progress")
    }
}
