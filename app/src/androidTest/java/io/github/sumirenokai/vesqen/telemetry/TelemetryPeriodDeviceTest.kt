package io.github.sumirenokai.vesqen.telemetry

import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TelemetryPeriodDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val telemetry = AndroidPlaybackTelemetry(instrumentation.targetContext)
    private val current = MediaSource.MediaPeriodId("current", 1)
    private val next = MediaSource.MediaPeriodId("next", 2)

    @Test
    fun deferredCurrentPeriodPromotesOnlyMatchingInputFacts() = runBlocking {
        main {
            telemetry.onMediaItemTransition(event(null, null), MediaItem.fromUri("file:///test/current.flac"), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            // The renderer may report input before the playing period becomes available.
            telemetry.onAudioInputFormatChanged(event(current, null), format(96_000), null)
            telemetry.onAudioInputFormatChanged(event(next, null), format(48_000), null)
        }
        assertNull(inputRate())
        main { telemetry.onTimelineChanged(event(current, current), Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) }
        assertEquals(96_000L, inputRate())
        main { telemetry.onAudioInputFormatChanged(event(next, current), format(44_100), null) }
        assertEquals(96_000L, inputRate())
        main { telemetry.onMediaItemTransition(event(next, next), MediaItem.fromUri("file:///test/next.flac"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
        assertEquals(44_100L, inputRate())
        main { telemetry.onAudioInputFormatChanged(event(current, next), format(192_000), null) }
        assertEquals(44_100L, inputRate())
    }

    @Test
    fun rendererCallbackBindsExplicitCurrentPeriodWithoutAnotherTransition() = runBlocking {
        main {
            telemetry.onMediaItemTransition(event(null, null), MediaItem.fromUri("file:///test/current.flac"), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            telemetry.onAudioInputFormatChanged(event(current, current), format(96_000), null)
        }
        assertEquals(96_000L, inputRate())
    }

    @Test
    fun transitionWithoutCurrentPeriodDoesNotAdoptPrefetchPeriod() = runBlocking {
        main {
            telemetry.onMediaItemTransition(event(next, null), MediaItem.fromUri("file:///test/current.flac"), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            telemetry.onAudioInputFormatChanged(event(next, null), format(48_000), null)
        }
        assertNull(inputRate())
    }

    private suspend fun inputRate(): Long? = withTimeout(5_000) {
        val snapshot = telemetry.observe(TelemetryObservation(selection = TelemetryMetricSelection.Explicit(setOf(TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE)))).first()
        (snapshot.metric(TelemetryMetricCatalog.DECODER_INPUT_SAMPLE_RATE)?.evidence?.reading as? TelemetryReading.Integer)?.value
    }

    private fun format(rate: Int) = Format.Builder().setSampleMimeType("audio/flac").setSampleRate(rate).setChannelCount(2).build()
    private fun event(period: MediaSource.MediaPeriodId?, playing: MediaSource.MediaPeriodId?) = AnalyticsListener.EventTime(
        SystemClock.elapsedRealtime(), Timeline.EMPTY, 0, period, 0, Timeline.EMPTY, 0, playing, 0, 0,
    )
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
