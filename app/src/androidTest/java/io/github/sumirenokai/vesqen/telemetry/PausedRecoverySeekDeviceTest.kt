package io.github.sumirenokai.vesqen.telemetry

import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.playback.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Restoring a paused queue creates no audio output, so the player stays unprepared, and Media3 drops
 * a plain seek on an item it cannot yet call seekable. Only a fresh instrumentation process tests that:
 * a service that has already played has a prepared player.
 */
class PausedRecoverySeekDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()

    @Test
    fun aRestoredPausedQueueSeeksAndPlaysFromThere() = runBlocking {
        val fixture = speakerFixture(app, 48_000, 24)
        assertTrue(app.getSharedPreferences("usb_output_preferences", 0).edit()
            .putString("mode", UsbOutputMode.SYSTEM.name).commit())
        PlaybackStateStore(app).save(PersistedPlaybackState(
            listOf(fixture.id, fixture.id), fixture.id, 12_345, true, PlaybackRepeatMode.ALL, 1,
        ))
        val snapshots = AtomicReference(PlaybackSnapshot())
        var controller: PlaybackController? = null
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        // Nothing refreshes the position here: a paused screen has only the player's own report to go by.
        suspend fun await(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
            while (!predicate(snapshots.get())) delay(25)
        }
        try {
            main { controller = PlaybackController(app) { snapshots.set(it) } }
            await { it.isControllerReady }
            main { controller!!.syncLibrary(listOf(fixture)) }
            await { it.trackId == fixture.id && it.positionMs == 12_345L }

            main { controller!!.seekTo(15_000) }
            await { it.positionMs == 15_000L }
            assertFalse(snapshots.get().isPlaying)
            assertFalse(snapshots.get().showsPauseAction)
            assertEquals(fixture.durationMs, snapshots.get().durationMs)

            main { controller!!.togglePlayback() }
            await { it.isPlaying }
            main { controller!!.refreshPosition() }
            assertTrue("Played from ${snapshots.get().positionMs}", snapshots.get().positionMs >= 15_000L)
        } finally {
            main { controller?.clearQueue(); controller?.release() }
        }
    }
}
