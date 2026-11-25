package io.github.sumirenokai.vesqen.ui.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainMetricSearchTest {
    @Test
    fun `every word must appear in the label or the section name, ignoring case`() {
        assertTrue(chainMetricMatches("采样率", "AudioTrack 采样率", "播放输出"))
        assertTrue(chainMetricMatches("audiotrack 采样率", "AudioTrack 采样率", "播放输出"))
        assertTrue(chainMetricMatches("usb", "线路采样率", "USB"))
        assertTrue(chainMetricMatches("  wire   RATE ", "Wire sample rate", "USB"))
        assertFalse(chainMetricMatches("audiotrack 位深", "AudioTrack 采样率", "播放输出"))
    }

    @Test
    fun `a blank query matches nothing`() {
        assertFalse(chainMetricMatches("", "Decoder", "Decoder"))
        assertFalse(chainMetricMatches("   ", "Decoder", "Decoder"))
    }

    @Test
    fun `match ranges cover each occurrence and merge overlaps`() {
        assertEquals(listOf(11..13), chainMetricMatchRanges("采样率", "AudioTrack 采样率"))
        assertEquals(listOf(0..9), chainMetricMatchRanges("audio track", "AudioTrack 采样率"))
        assertEquals(listOf(0..2, 7..9), chainMetricMatchRanges("usb", "USB to USB"))
        assertEquals(emptyList<IntRange>(), chainMetricMatchRanges("usb", "线路采样率"))
    }
}
