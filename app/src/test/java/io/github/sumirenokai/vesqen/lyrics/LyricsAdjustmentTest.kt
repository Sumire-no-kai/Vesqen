package io.github.sumirenokai.vesqen.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LyricsAdjustmentTest {
    @Test
    fun advancingCorrectsTheSingleTimelineUsedForFollowingAndSeeking() {
        val lyrics = synced("[00:02.00]one\n[00:04.00]two")
        val corrected = lyrics.withUserAdvance(1_000)

        assertEquals(listOf(1_000L, 3_000L), corrected.lines.map(LyricLine::timeMs))
        assertEquals(-1, corrected.lineIndexAt(999))
        assertEquals(0, corrected.lineIndexAt(corrected.lines.first().timeMs))
        assertEquals(1, corrected.lineIndexAt(corrected.lines.last().timeMs))
        assertEquals(listOf(2_000L, 4_000L), lyrics.lines.map(LyricLine::timeMs))
    }

    @Test
    fun userDelayCanRecoverFileTimesThatTheEmbeddedOffsetHadClamped() {
        val lyrics = synced("[offset:3000]\n[00:01.00]one\n[00:02.00]two\n[00:04.00]three")
        assertEquals(listOf(0L, 0L, 1_000L), lyrics.lines.map(LyricLine::timeMs))

        assertEquals(listOf(500L, 1_500L, 3_500L), lyrics.withUserAdvance(-2_500).lines.map(LyricLine::timeMs))
        assertEquals(lyrics, lyrics.withUserAdvance(1_000).withUserAdvance(0))
        assertEquals(lyrics.withUserAdvance(500), lyrics.withUserAdvance(-500).withUserAdvance(500))
    }

    @Test
    fun translationsAndRepeatedTimesMoveTogetherWithoutRegroupingClampedLines() {
        val lyrics = synced("[00:01.00][00:03.00]original\n[00:01.00]translation\n[00:02.00]other")
        val corrected = lyrics.withUserAdvance(3_000)

        assertEquals(listOf(0L, 0L, 0L), corrected.lines.map(LyricLine::timeMs))
        assertEquals(listOf("original", "other", "original"), corrected.lines.map(LyricLine::text))
        assertEquals(listOf("translation"), corrected.lines.first().translations)
        assertEquals(2, corrected.lineIndexAt(0))
    }

    @Test
    fun extremeUserValuesSaturateWithoutReversingTheTimeline() {
        val lyrics = synced("[offset:3000]\n[00:01.00]one\n[00:04.00]two")

        assertEquals(listOf(0L, 0L), lyrics.withUserAdvance(Long.MAX_VALUE).lines.map(LyricLine::timeMs))
        assertEquals(listOf(Long.MAX_VALUE - 1_999, Long.MAX_VALUE), lyrics.withUserAdvance(Long.MIN_VALUE).lines.map(LyricLine::timeMs))
    }

    @Test
    fun revisionDependsOnSourceTimingAndTextButNotTheUserCalibration() {
        val lyrics = synced("[00:01.00]one")
        val revision = lyrics.contentRevision(LyricsSource.Imported)

        assertEquals(64, revision.length)
        assertEquals(revision, lyrics.withUserAdvance(500).contentRevision(LyricsSource.Imported))
        assertNotEquals(revision, lyrics.contentRevision(LyricsSource.Embedded))
        assertNotEquals(revision, synced("[00:01.00]two").contentRevision(LyricsSource.Imported))
        assertNotEquals(revision, synced("[offset:500]\n[00:01.00]one").contentRevision(LyricsSource.Imported))
        assertNotEquals(revision, synced("[00:01.00]one\n[00:01.00]translation").contentRevision(LyricsSource.Imported))
    }

    private fun synced(text: String) = parseLyrics(text) as Lyrics.Synced
}
