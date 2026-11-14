package io.github.sumirenokai.vesqen.playback

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the ordinary service-owned queue; no separate folder player or persistence format. */
class FolderPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()
    private val playback = AtomicReference(PlaybackSnapshot())
    private lateinit var controller: PlaybackController

    @Test fun folderBoundaryPlaysAutomaticallyAndRestoresWithItsPosition() = runBlocking {
        val tracks = listOf(track(1, "A"), track(2, "A"), track(3, "B"), track(4, "B"))
        try {
            connect()
            main { controller.playQueue(tracks, 1) }
            await { it.trackId == tracks[1].id && it.isPlaying }
            main { controller.syncLibrary(tracks + track(5, "C")) }
            assertEquals(tracks.map(AudioTrack::id), playback.get().queue.map(PlaybackQueueItem::trackId))
            main { controller.seekTo(19_600) }
            await { it.trackId == tracks[2].id && it.isPlaying && it.positionMs >= 500 }
            main { controller.togglePlayback() }
            await { !it.showsPauseAction }
            withTimeout(15_000) {
                while (PlaybackStateStore(app).load()?.currentTrackId != tracks[2].id) delay(50)
            }
            val saved = PlaybackStateStore(app).load()!!
            assertEquals(tracks.map(AudioTrack::id), saved.queueTrackIds)
            assertTrue(saved.positionMs >= 500)

            // As in the next process, an empty player restores its persisted queue when the catalog arrives.
            main { controller.clearQueue() }
            withTimeout(15_000) { while (PlaybackStateStore(app).load() != null) delay(50) }
            PlaybackStateStore(app).save(saved)
            main { controller.syncLibrary(tracks) }
            await { it.trackId == tracks[2].id && it.queueSize == 4 }
            assertFalse(playback.get().showsPauseAction)
            assertEquals(saved.positionMs, playback.get().positionMs)
            assertTrue(playback.get().canSkipPreviousFolder)
            assertFalse(playback.get().canSkipNextFolder)
            main { controller.skipToPreviousFolder() }
            await { it.trackId == tracks[0].id && it.isPlaying }
        } finally {
            release()
        }
    }

    @Test fun folderCommandsUseTheLiveQueueAfterEditsAndHonorRepeat() = runBlocking {
        val tracks = listOf(track(1, "A"), track(2, "A"), track(3, "B"), track(4, "B"), track(5, "C"))
        try {
            connect()
            main { controller.playQueue(tracks, 1) }
            await { it.trackId == tracks[1].id && it.isPlaying }
            assertFalse(playback.get().canSkipPreviousFolder)
            assertTrue(playback.get().canSkipNextFolder)
            main { controller.skipToNextFolder() }
            await { it.trackId == tracks[2].id }
            main { controller.skipToPreviousFolder() }
            await { it.trackId == tracks[0].id }

            // The menu was opened with B next; deleting B must make the same command resolve C.
            main {
                controller.removeQueueItem(3)
                controller.removeQueueItem(2)
                controller.skipToNextFolder()
            }
            await { it.trackId == tracks[4].id && it.queueSize == 3 }
            assertFalse(playback.get().canSkipNextFolder)
            main { controller.cycleTo(PlaybackOrderMode.REPEAT_ONE) { playback.get().playbackOrderMode } }
            main { controller.skipToPreviousFolder() }
            await { it.trackId == tracks[0].id }
            main { controller.cycleTo(PlaybackOrderMode.REPEAT_ALL) { playback.get().playbackOrderMode } }
            main { controller.skipToPreviousFolder() }
            await { it.trackId == tracks[4].id }
            main { controller.skipToNextFolder() }
            await { it.trackId == tracks[0].id }
        } finally {
            release()
        }
    }

    @Test fun shuffledFolderCommandsFollowRealOrderIncludingDuplicateOccurrences() = runBlocking {
        val tracks = listOf(track(1, "A"), track(2, "A"), track(3, "B"), track(4, "B"), track(5, "C"))
        try {
            connect()
            main { controller.playQueue(tracks + tracks.first(), 0) }
            await { it.trackId == tracks[0].id && it.isPlaying }
            main { controller.cycleTo(PlaybackOrderMode.SHUFFLE) { playback.get().playbackOrderMode } }
            await { it.shuffleEnabled && it.queueSize == 6 }
            val rows = playback.get().queue
            val current = rows.indexOfFirst(PlaybackQueueItem::isCurrent)
            val next = rows.drop(current + 1).first { it.folderKey != rows[current].folderKey }
            main { controller.skipToNextFolder() }
            await { it.queueEntryId == next.entryId }
            assertEquals(next.trackId, playback.get().trackId)
            assertEquals(rows.map(PlaybackQueueItem::entryId), playback.get().queue.map(PlaybackQueueItem::entryId))
        } finally {
            release()
        }
    }

    private suspend fun connect() {
        main { controller = PlaybackController(app) { playback.set(it) } }
        await { it.isControllerReady }
        main {
            controller.clearQueue()
            controller.cycleTo(PlaybackOrderMode.SEQUENTIAL) { playback.get().playbackOrderMode }
        }
    }

    private fun track(id: Long, folder: String): AudioTrack {
        val file = File(File(app.cacheDir, "folder-playback-fixtures"), "VESQEN_QA_PRIVATE_$id.wav")
        writeSilentWav(file, 20_000)
        return AudioTrack(
            -3300 - id, file.toURI().toString(), "Track $id", "", "", 20_000,
            fileName = file.name, fileSizeBytes = file.length(), folderName = folder,
            mimeType = "audio/wav", codec = "PCM", sampleRateHz = 8000, bitDepth = 16, channelCount = 1,
        )
    }

    private suspend fun await(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
        while (true) {
            main { controller.refreshPosition() }
            if (predicate(playback.get())) break
            delay(50)
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun release() {
        if (::controller.isInitialized) main {
            if (playback.get().showsPauseAction) controller.togglePlayback()
            controller.cycleTo(PlaybackOrderMode.SEQUENTIAL) { playback.get().playbackOrderMode }
            controller.clearQueue()
            PlaybackStateStore(app).clear(PlaybackOrderSettings(false, PlaybackRepeatMode.OFF))
            controller.release()
            app.stopService(Intent(app, PlaybackService::class.java))
        }
        File(app.cacheDir, "folder-playback-fixtures").deleteRecursively()
    }
}
