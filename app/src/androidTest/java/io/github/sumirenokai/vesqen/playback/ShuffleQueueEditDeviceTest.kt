package io.github.sumirenokai.vesqen.playback

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
 * plays last, a move changes only the play order, and the queue lists that order.
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
    fun requestsWithoutTheirKeysAreRefused() {
        assertNull(PlayOrderSessionContract.readInsert(Bundle().apply { putBundle("item", MediaItem.Builder().setMediaId("1").build().toBundle()) }))
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
    }
}
