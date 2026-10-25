package io.github.sumirenokai.vesqen.playback

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
            awaitPlayback { it.playbackOrderMode == PlaybackOrderMode.SHUFFLE }
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
            main { controller.moveQueueItem(moved.queueIndex, above.queueIndex) }
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
            main {
                if (playback.get().showsPauseAction) controller.togglePlayback()
                controller.clearQueue()
                controller.release()
                // Later tests expect a service that has never played.
                app.stopService(Intent(app, PlaybackService::class.java))
            }
            File(app.cacheDir, FIXTURE_DIR).deleteRecursively()
        }
    }

    private suspend fun awaitPlayback(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
        while (true) {
            main { controller.refreshPosition() }
            if (predicate(playback.get())) break
            delay(50)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    /** Twenty seconds of 8 kHz mono silence; private to the deviceTest app, never in MediaStore. */
    private fun silentTrack(id: Long): AudioTrack {
        val rate = 8_000
        val dataSize = rate * 2 * 20
        val file = File(File(app.cacheDir, FIXTURE_DIR).apply { mkdirs() }, "VESQEN_QA_PRIVATE_$id.wav")
        file.outputStream().buffered().use { output ->
            output.write(ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(rate)
                putInt(rate * 2); putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
            }.array())
            output.write(ByteArray(dataSize))
        }
        return AudioTrack(-1_000 - id, file.toURI().toString(), "Track $id", "", "", 20_000,
            fileName = file.name, fileSizeBytes = file.length(), mimeType = "audio/wav", codec = "PCM",
            sampleRateHz = rate, bitDepth = 16, channelCount = 1)
    }

    private companion object {
        const val FIXTURE_DIR = "shuffle-queue-edits"
    }
}
