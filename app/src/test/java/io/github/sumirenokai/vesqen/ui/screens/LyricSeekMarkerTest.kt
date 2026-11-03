package io.github.sumirenokai.vesqen.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricSeekMarkerTest {
    @Test
    fun theMarkerTargetsTheTallRowItIsOnNotAShortRowWithACloserMiddle() {
        val rows = listOf(LyricRowBounds(lineIndex = 4, offset = 0, size = 200), LyricRowBounds(lineIndex = 5, offset = 200, size = 40))

        // The short row's middle (220) is closer to 190 than the tall row's (100).
        assertEquals(4, lineIndexAtMarker(190, rows))
        assertEquals(5, lineIndexAtMarker(200, rows))
    }

    @Test
    fun aMarkerInAGapTakesTheNearestRow() {
        val rows = listOf(LyricRowBounds(1, 0, 100), LyricRowBounds(3, 148, 100))

        assertEquals(1, lineIndexAtMarker(110, rows))
        assertEquals(3, lineIndexAtMarker(140, rows))
    }

    @Test
    fun noRowsMeansNoTarget() {
        assertNull(lineIndexAtMarker(100, emptyList()))
    }
}
