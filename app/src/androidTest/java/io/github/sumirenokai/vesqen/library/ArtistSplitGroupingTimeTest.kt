package io.github.sumirenokai.vesqen.library

import android.os.SystemClock
import android.util.Log
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD "2026-10-24 多位艺术家拆分": with ten thousand songs the Artists list takes about as long to
 * build as before splitting, with the real ICU name order. Times go to logcat (tag ArtistSplitTiming).
 * The bound is the PRD's "same order of magnitude", loose enough for a busy emulator.
 */
class ArtistSplitGroupingTimeTest {
    @Test
    fun tenThousandSongsGroupInTheSameOrderOfMagnitude() {
        val tracks = library(10_000)
        val off = ArtistSplitSettings(enabled = false)
        val on = ArtistSplitSettings()
        val cached = ArtistNames(on)

        val whole = medianMillis { group(tracks, ArtistNames(off)) }
        // Opening Artists, or a changed setting: every tag is split.
        val firstSplit = medianMillis { group(tracks, ArtistNames(on)) }
        // Typing a search or a new play count: the Library keeps one ArtistNames per setting.
        val cachedSplit = medianMillis { group(tracks, cached) }
        val artists = group(tracks, cached).size
        val message = "10000 songs, $artists artists: whole tag ${whole}ms, first split ${firstSplit}ms, cached split ${cachedSplit}ms"
        Log.i("ArtistSplitTiming", message)

        assertTrue(message, firstSplit <= whole * 10 + 10)
        assertTrue(message, cachedSplit <= whole * 10 + 10)
    }

    private fun group(tracks: List<AudioTrack>, artists: ArtistNames): List<LibraryCollection> {
        val names = LibraryNameOrder()
        return sortLibraryCollections(
            buildLibraryCollections(LibraryBrowseMode.ARTISTS, tracks, emptyList(), names, artists),
            LibrarySortOrder.TITLE,
            names,
        )
    }

    private fun medianMillis(block: () -> Unit): Long {
        repeat(2) { block() }
        return List(7) {
            val start = SystemClock.elapsedRealtimeNanos()
            block()
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
        }.sorted()[3]
    }

    /** About 1,200 artists over 10,000 songs; a quarter of the songs credit two of them. */
    private fun library(count: Int): List<AudioTrack> {
        val first = listOf("Adam", "Mori", "Nari", "Otis", "Maren", "Lena", "Kai", "Ruth", "Iris", "Theo", "陈", "王", "周", "林", "美咲", "蓮")
        val second = listOf("Gubman", "Vale", "Holt", "Calandra", "Reyes", "Sato", "Ito", "晓", "然", "一", "Brooks", "Hayes", "Moreau", "Quinn", "Park", "Lindqvist", "Okafor", "Silva", "Novak", "Kim", "宇", "静", "Ng", "Ahn", "Ruiz")
        val people = first.flatMap { a -> second.map { b -> if (a.first().code > 0x2000) "$a$b" else "$a $b" } } +
            List(800) { "塞壬唱片-$it" }
        val joints = listOf("/", " feat. ", "、", ";")
        return List(count) { index ->
            val lead = people[(index * 7) % people.size]
            val artist = when (index % 4) {
                0 -> lead + joints[(index / 4) % joints.size] + people[(index * 13 + 5) % people.size]
                else -> lead
            }
            AudioTrack(
                id = index.toLong(),
                contentUri = "content://fixture/$index",
                title = "Song $index",
                artist = artist,
                album = "Album ${index / 10}",
                durationMs = 1_000,
                albumArtist = if (index % 2 == 0) lead else "",
                trackNumber = index % 10 + 1,
                folderName = "Music/${index / 10}",
            )
        }
    }
}
