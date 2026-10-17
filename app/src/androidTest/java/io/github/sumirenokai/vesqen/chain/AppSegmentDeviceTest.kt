package io.github.sumirenokai.vesqen.chain

import android.content.ComponentName
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackController
import io.github.sumirenokai.vesqen.playback.PlaybackService
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricSelection
import io.github.sumirenokai.vesqen.telemetry.TelemetryObservation
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.telemetry.speakerFixture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #72 device acceptance on whatever route is current (phone speaker or Bluetooth): a lossless
 * fixture is reported unchanged, and a session controller changing the speed or the app volume
 * withdraws that verdict until the value is restored. Run explicitly by method on a phone.
 */
class AppSegmentDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = isolatedPlaybackTestApplication()
    private val telemetry = app.telemetryRuntime
    private val playback = AtomicReference(PlaybackSnapshot())
    private lateinit var controller: PlaybackController

    @Test
    fun speedAndAppVolumeWithdrawTheUnchangedVerdict() = runBlocking {
        val track = speakerFixture(app, 48_000, 24)
        var session: MediaController? = null
        try {
            start(listOf(track))
            awaitAssessment("lossless fixture") { it.status == AppSegmentStatus.UNCHANGED }
            val connected = connectSession().also { session = it }

            main { connected.setPlaybackSpeed(1.25f) }
            awaitAssessment("speed 1.25") { it.status != AppSegmentStatus.UNCHANGED }
            awaitAssessment("speed reported") { it.check(AppSegmentCondition.SPEED) == AppSegmentStatus.MODIFIED }
            main { connected.setPlaybackSpeed(1f) }
            awaitAssessment("speed restored") { it.status == AppSegmentStatus.UNCHANGED }

            main { connected.volume = 0.5f }
            awaitAssessment("app volume 50%") { it.status != AppSegmentStatus.UNCHANGED }
            awaitAssessment("volume reported") { it.check(AppSegmentCondition.PLAYER_VOLUME) == AppSegmentStatus.MODIFIED }
            main { connected.volume = 1f }
            awaitAssessment("volume restored") { it.status == AppSegmentStatus.UNCHANGED }
        } finally {
            session?.let { main { it.release() } }
            if (::controller.isInitialized) main { controller.clearQueue(); controller.release() }
        }
    }

    private fun AppSegmentAssessment.check(condition: AppSegmentCondition) =
        checks.firstOrNull { it.condition == condition }?.status

    /** The verdict Chain would show, from the same metrics and the same assessment. */
    private suspend fun awaitAssessment(phase: String, predicate: (AppSegmentAssessment) -> Boolean) {
        val observation = TelemetryObservation(
            refreshInterval = TelemetryRefreshInterval.QUARTER_SECOND,
            selection = TelemetryMetricSelection.Explicit(AppSegmentMetricIds.toSet()),
        )
        var last: AppSegmentAssessment? = null
        try {
            withTimeout(VERDICT_TIMEOUT_MS) {
                telemetry.observe(observation).first { snapshot ->
                    assessAppSegment(snapshot, SystemClock.elapsedRealtime(), MAX_SNAPSHOT_AGE_MS)
                        .also { last = it }
                        .let(predicate)
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$phase: last verdict ${last?.status} ${last?.checks}", timeout)
        }
    }

    private suspend fun connectSession(): MediaController {
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main {
            future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    private suspend fun start(tracks: List<AudioTrack>) {
        main { controller = PlaybackController(app) { playback.set(it) } }
        awaitPlayback { it.isControllerReady }
        main { controller.playQueue(tracks, 0) }
        awaitPlayback { it.trackId == tracks.first().id && it.isPlaying }
        assertEquals(tracks.first().id, playback.get().trackId)
    }

    private suspend fun awaitPlayback(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
        while (true) {
            main { controller.refreshPosition() }
            if (predicate(playback.get())) break
            delay(50)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private companion object {
        // A change settles within about 2 s (PRD F6.5.1); allow for the sampler's first window.
        const val VERDICT_TIMEOUT_MS = 8_000L
        const val MAX_SNAPSHOT_AGE_MS = 5_000L
    }
}
