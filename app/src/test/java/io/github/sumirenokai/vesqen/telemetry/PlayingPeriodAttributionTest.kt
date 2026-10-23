package io.github.sumirenokai.vesqen.telemetry

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayingPeriodAttributionTest {
    private val playing = MediaSource.MediaPeriodId("playing", 1)
    private val next = MediaSource.MediaPeriodId("next", 2)

    @Test
    fun `a sink callback stamped with the next reading period goes to the playing period`() {
        // The renderer has read the playing item to its end, so Media3 stamps an underrun or sink
        // error with the next item's period while the AudioTrack still plays this one's tail.
        assertEquals(playing, eventTime(reading = next, playing = playing).playingPeriodId())
    }

    @Test
    fun `the reading period stands in only when no playing period is reported`() {
        assertEquals(playing, eventTime(reading = playing, playing = null).playingPeriodId())
        assertNull(eventTime(reading = null, playing = null).playingPeriodId())
    }

    private fun eventTime(reading: MediaSource.MediaPeriodId?, playing: MediaSource.MediaPeriodId?) =
        AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, reading, 0, Timeline.EMPTY, 0, playing, 0, 0)
}
