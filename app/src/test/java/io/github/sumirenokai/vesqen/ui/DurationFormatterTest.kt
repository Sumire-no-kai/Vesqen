package io.github.sumirenokai.vesqen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatterTest {
    @Test
    fun `formats duration as minutes and seconds`() {
        assertEquals("2:05", formatDuration(125_900))
    }
}
