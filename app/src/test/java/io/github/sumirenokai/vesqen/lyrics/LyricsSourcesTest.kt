package io.github.sumirenokai.vesqen.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSourcesTest {
    private val imported = SourceLyrics.Readable(Lyrics.Plain(listOf("imported")))
    private val sameName = SourceLyrics.Readable(Lyrics.Plain(listOf("same-name file")))
    private val embedded = SourceLyrics.Readable(Lyrics.Plain(listOf("embedded")))

    @Test
    fun importedLyricsComeFirstAndLaterSourcesAreNotRead() {
        val read = mutableListOf<LyricsSource>()

        val picked = pickLyrics { source ->
            read += source
            sources(imported, sameName, embedded).getValue(source)
        }

        assertEquals(PickedLyrics.Found(LyricsSource.Imported, imported.lyrics), picked)
        assertEquals(listOf(LyricsSource.Imported), read)
    }

    @Test
    fun eachSourceIsUsedWhenTheOnesBeforeItAreAbsent() {
        assertEquals(
            PickedLyrics.Found(LyricsSource.SameNameFile, sameName.lyrics),
            pickLyrics(sources(SourceLyrics.Absent, sameName, embedded)::getValue),
        )
        assertEquals(
            PickedLyrics.Found(LyricsSource.Embedded, embedded.lyrics),
            pickLyrics(sources(SourceLyrics.Absent, SourceLyrics.Absent, embedded)::getValue),
        )
        assertEquals(
            PickedLyrics.Absent,
            pickLyrics(sources(SourceLyrics.Absent, SourceLyrics.Absent, SourceLyrics.Absent)::getValue),
        )
    }

    @Test
    fun anUnreadableSourceFallsThroughAndIsShownOnlyWhenNothingIsReadable() {
        assertEquals(
            PickedLyrics.Found(LyricsSource.Embedded, embedded.lyrics),
            pickLyrics(sources(SourceLyrics.Absent, SourceLyrics.Unreadable, embedded)::getValue),
        )
        assertEquals(
            PickedLyrics.Found(LyricsSource.SameNameFile, sameName.lyrics),
            pickLyrics(sources(SourceLyrics.Unreadable, sameName, SourceLyrics.Unreadable)::getValue),
        )
        assertEquals(
            PickedLyrics.Unreadable,
            pickLyrics(sources(SourceLyrics.Absent, SourceLyrics.Unreadable, SourceLyrics.Absent)::getValue),
        )
        assertEquals(
            PickedLyrics.Unreadable,
            pickLyrics(sources(SourceLyrics.Absent, SourceLyrics.Absent, SourceLyrics.Unreadable)::getValue),
        )
    }

    @Test
    fun aLyricsFileIsUnreadableOnlyWhenItCannotBeDecoded() {
        assertEquals(
            SourceLyrics.Readable(Lyrics.Synced(listOf(LyricLine(1_000, "line")))),
            readLyricsFile("[00:01.00]line".toByteArray()),
        )
        assertEquals(SourceLyrics.Absent, readLyricsFile("[ti:only a title]".toByteArray()))
        assertEquals(SourceLyrics.Absent, readLyricsFile(ByteArray(0)))
        assertEquals(SourceLyrics.Unreadable, readLyricsFile("你好".toByteArray(charset("GBK"))))
    }

    private fun sources(
        imported: SourceLyrics,
        sameName: SourceLyrics,
        embedded: SourceLyrics,
    ): Map<LyricsSource, SourceLyrics> = mapOf(
        LyricsSource.Imported to imported,
        LyricsSource.SameNameFile to sameName,
        LyricsSource.Embedded to embedded,
    )
}
