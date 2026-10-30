package io.github.sumirenokai.vesqen.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcParserTest {
    @Test
    fun oneLineWithSeveralTimesAppearsAtEachTimeInOrder() {
        val lyrics = synced(
            """
            [00:12.00][01:02.00]Lights along the river
            [00:20.00]A quiet street
            """,
        )

        assertEquals(
            listOf(
                LyricLine(12_000, "Lights along the river"),
                LyricLine(20_000, "A quiet street"),
                LyricLine(62_000, "Lights along the river"),
            ),
            lyrics.lines,
        )
    }

    @Test
    fun fractionsAreDecimalAndBothSeparatorsAreRead() {
        val lyrics = synced(
            """
            [00:01.5]a
            [00:02.50]b
            [00:03.500]c
            [00:04.05]d
            [00:05:25]e
            [1:06]f
            [100:00.00]g
            """,
        )

        assertEquals(
            listOf(1_500L, 2_500L, 3_500L, 4_050L, 5_250L, 66_000L, 6_000_000L),
            lyrics.lines.map(LyricLine::timeMs),
        )
    }

    @Test
    fun offsetMovesEveryLineEarlierAndTheLastValidOneWins() {
        val lyrics = synced(
            """
            [offset:+900]
            [00:01.00]first
            [00:05.00]second
            [offset:500]
            [offset:fast]
            """,
        )

        assertEquals(listOf(500L, 4_500L), lyrics.lines.map(LyricLine::timeMs))
    }

    @Test
    fun negativeOffsetMovesLinesLaterAndPositiveOffsetStopsAtZero() {
        assertEquals(
            listOf(1_250L),
            synced("[offset:-250]\n[00:01.00]late").lines.map(LyricLine::timeMs),
        )
        val clamped = synced("[offset:3000]\n[00:01.00]one\n[00:02.00]two\n[00:04.00]three")

        assertEquals(listOf(0L, 0L, 1_000L), clamped.lines.map(LyricLine::timeMs))
        assertEquals(1, clamped.lineIndexAt(0))
    }

    @Test
    fun linesSharingATimeBecomeTranslationsInFileOrder() {
        val lyrics = synced(
            """
            [00:01.00]夕暮れの駅
            [00:01.0]yuugure no eki
            [00:01.000]Station at dusk
            [00:01.00]夕暮れの駅
            [00:01.00]
            [00:01.00]Station at dusk
            [00:04.00]
            [00:04.00]A quiet rain
            """,
        )

        assertEquals(
            listOf(
                LyricLine(1_000, "夕暮れの駅", listOf("yuugure no eki", "Station at dusk")),
                LyricLine(4_000, "A quiet rain"),
            ),
            lyrics.lines,
        )
    }

    @Test
    fun translationsGroupBeforeTheOffsetIsApplied() {
        val lyrics = synced("[00:01.00]原文\n[00:01.00]translation\n[offset:2000]")

        assertEquals(listOf(LyricLine(0, "原文", listOf("translation"))), lyrics.lines)
    }

    @Test
    fun enhancedLrcKeepsLineTimesOnly() {
        val lyrics = synced(
            """
            [00:10.00]<00:10.00>Walk <00:10.40>along <00:10.90>the shore
            [00:14.00]<00:14.00> Slowly <00:14.50> home <00:15.00>
            [00:18.00]晚[00:18.30]风[00:18.60]起
            """,
        )

        assertEquals(listOf("Walk along the shore", "Slowly home", "晚风起"), lyrics.lines.map(LyricLine::text))
    }

    @Test
    fun badLinesAreSkippedWhenTheFileHasTimes() {
        val lyrics = synced(
            """
            [ar:Sample Artist]
            [ti:Sample Title]
            [length:03:45]
            [00:75.00]seventy-five seconds
            [00:01.00][00:99.00]second tag impossible
            [0a:12.00]letters in the time
            [00:02.00
            untimed text
            [Chorus]
            ［００：０３．００］full-width digits
            [00:04.00]kept
            """,
        )

        assertEquals(listOf(LyricLine(4_000, "kept")), lyrics.lines)
    }

    @Test
    fun textWithoutTimesIsPlainAndHidesInformationTags() {
        val lyrics = parseLyrics(
            "\n\n[ti:Sample]\nFirst verse line\n[Chorus]\n\n\n\nSecond verse line\n[00:61.00]not a time\n\n",
        )

        assertEquals(
            Lyrics.Plain(listOf("First verse line", "[Chorus]", "", "Second verse line")),
            lyrics,
        )
    }

    @Test
    fun pausesStayBetweenLinesButNotBeforeTheFirstOrTwiceInARow() {
        val lyrics = synced(
            """
            [00:00.00]
            [00:01.00]one
            [00:05.00]
            [00:06.00]
            [00:08.00]two
            [00:12.00]
            """,
        )

        assertEquals(
            listOf(
                LyricLine(1_000, "one"),
                LyricLine(5_000, ""),
                LyricLine(8_000, "two"),
                LyricLine(12_000, ""),
            ),
            lyrics.lines,
        )
    }

    @Test
    fun byteOrderMarksLineEndingsAndIdeographicSpacesAreCleaned() {
        val text = "\uFEFF[00:01.00]\u3000first\u3000\r\n\uFEFF[00:02.00]second\r[00:03.00]third\n\u0000\u0000"

        assertEquals(listOf("first", "second", "third"), synced(text).lines.map(LyricLine::text))
    }

    @Test
    fun onlyStandardIdTagsAreHiddenSoSectionHeadingsStay() {
        val lyrics = parseLyrics("[Ar:Sample]\n[#:a comment]\n[Intro: Pharrell]\nFirst line\n[Hook: Rihanna]\nSecond line\n[tool:Editor]")

        assertEquals(Lyrics.Plain(listOf("[Intro: Pharrell]", "First line", "[Hook: Rihanna]", "Second line")), lyrics)
    }

    @Test
    fun nothingToShowGivesNull() {
        assertNull(parseLyrics(""))
        assertNull(parseLyrics(" \n\u3000\n"))
        assertNull(parseLyrics("[ar:Someone]\n[ti:Something]\n[offset:100]"))
        assertNull(parseLyrics("[00:01.00]\n[00:02.00]\nuntimed"))
    }

    @Test
    fun longLinesAndLongFilesAreCapped() {
        val longLine = "a".repeat(MAX_LYRIC_LINE_CHARS - 1) + "🎵" + "b".repeat(10)
        val capped = synced("[00:01.00]$longLine").lines.single().text

        assertEquals("a".repeat(MAX_LYRIC_LINE_CHARS - 1), capped)

        val manyLines = (0 until MAX_LYRIC_LINES + 50).joinToString("\n") { index ->
            "[${index / 600}:${(index / 10) % 60}.${index % 10}]line $index"
        }
        assertEquals(MAX_LYRIC_LINES, synced(manyLines).lines.size)
    }

    @Test
    fun lineIndexAtFindsTheLastLineThatHasStarted() {
        val lyrics = synced("[00:01.00]one\n[00:03.00]two\n[00:05.00]three")

        assertEquals(-1, lyrics.lineIndexAt(999))
        assertEquals(0, lyrics.lineIndexAt(1_000))
        assertEquals(0, lyrics.lineIndexAt(2_999))
        assertEquals(1, lyrics.lineIndexAt(3_000))
        assertEquals(2, lyrics.lineIndexAt(60_000))
        assertEquals(-1, Lyrics.Synced(emptyList()).lineIndexAt(0))
    }

    private fun synced(text: String): Lyrics.Synced =
        parseLyrics(text.trimIndent()) as Lyrics.Synced
}
