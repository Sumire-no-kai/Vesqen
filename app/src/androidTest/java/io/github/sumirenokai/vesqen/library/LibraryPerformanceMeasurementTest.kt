package io.github.sumirenokai.vesqen.library

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.ui.screens.filterTracks
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The numbers behind #223 and #224, not a pass or fail: run with `-e measure true`, then read
 * `library-performance-compute.txt` and `library-performance-sqlite.txt` from the app's files, or
 * the `LibraryPerformance` lines in logcat when the target is not debuggable. The test build is
 * debuggable, so compare within a run and do not read those times as Release times. Synthetic data
 * leaves out tag reading on purpose.
 */
class LibraryPerformanceMeasurementTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val report = StringBuilder()

    @Before
    fun onlyWhenAsked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measure") == "true")
        report.appendLine("${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")
    }

    private fun write(name: String) {
        File(context.filesDir, name).writeText(report.toString())
        report.lineSequence().forEach { Log.i("LibraryPerformance", it) }
    }

    private fun syntheticTracks(count: Int): List<AudioTrack> = List(count) { index ->
        val album = index / 12
        val artist = album % (count / 60).coerceAtLeast(1)
        AudioTrack(
            id = index.toLong() + 1,
            contentUri = "content://fixture/$index",
            title = if (index % 3 == 0) "夜の坂道 $index 雨" else "Evening Light Number $index",
            artist = if (artist % 4 == 0) "坂本 $artist" else "Artist Name $artist",
            album = if (album % 4 == 0) "窓辺のアルバム $album" else "Album Title $album",
            durationMs = 200_000,
            dateModifiedSeconds = index.toLong(),
            albumArtist = "Artist Name $artist",
            genre = "Genre ${index % 40}",
            fileName = "Track $index.flac",
            folderName = "Music/Album $album",
            codec = "FLAC",
            playCount = index % 50,
            lastPlayedAtMs = (index % 1000).toLong(),
        )
    }

    /** The median of the runs after the first two, in milliseconds. */
    private fun medianMs(action: () -> Unit): Double {
        val times = List(7) {
            val start = System.nanoTime()
            action()
            (System.nanoTime() - start) / 1_000_000.0
        }.drop(2).sorted()
        return times[times.size / 2]
    }

    private fun line(label: String, ms: Double) {
        report.appendLine("%-44s %9.1f ms".format(label, ms))
    }

    @Test
    fun searchSortAndGrouping() {
        listOf(10_000, 50_000).forEach { size ->
            val tracks = syntheticTracks(size)
            report.appendLine("\n$size tracks")
            listOf("e", "Evening", "夜の", "no such text").forEach { query ->
                line("search \"$query\"", medianMs { filterTracks(tracks, query) })
            }
            LibrarySortOrder.entries.filter { it != LibrarySortOrder.TITLE }.forEach { order ->
                line("sort songs by $order", medianMs { sortLibraryTracks(tracks, order) })
            }
            listOf(LibraryBrowseMode.ALBUMS, LibraryBrowseMode.ARTISTS, LibraryBrowseMode.FOLDERS, LibraryBrowseMode.GENRES).forEach { mode ->
                val artists = ArtistNames(ArtistSplitSettings())
                line("group into $mode, fresh name order", medianMs { buildLibraryCollections(mode, tracks, emptyList(), LibraryNameOrder(), artists) })
                val names = LibraryNameOrder()
                val collections = buildLibraryCollections(mode, tracks, emptyList(), names, artists)
                LibrarySortOrder.entries.forEach { order ->
                    line("  sort $mode by $order", medianMs { sortLibraryCollections(collections, order, names) })
                }
            }
            write("library-performance-compute.txt")
        }
    }

    private class Latencies {
        private val times = ArrayList<Double>()

        @Synchronized fun add(ms: Double) {
            times += ms
        }

        @Synchronized fun summary(): String {
            if (times.isEmpty()) return "no samples"
            val sorted = times.sorted()
            fun at(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()]
            return "n=%d p50=%.1f p95=%.1f p99=%.1f max=%.1f ms".format(sorted.size, at(.5), at(.95), at(.99), sorted.last())
        }
    }

    private fun candidate(index: Int, revision: Int) = LibraryTrackCandidate(
        remoteId = "doc-$index",
        contentUri = "content://fixture/$index",
        title = "Evening Light Number $index",
        artist = "Artist Name ${index % 500}",
        album = "Album Title ${index / 12}",
        durationMs = 200_000,
        dateModifiedSeconds = revision.toLong(),
        sizeBytes = 1_000L + revision,
        fileName = "Track $index.flac",
        folderName = "Music/Album ${index / 12}",
        fingerprint = "revision-$revision",
    )

    private fun scenario(wal: Boolean, size: Int) {
        val name = "library-performance-${System.nanoTime()}.db"
        val store = LibraryCatalogStore(context, name)
        store.setWriteAheadLoggingEnabled(wal)
        try {
            val tree = "content://fixture.tree/tree/music"
            store.upsertFolderSource(tree, "Music")
            val sourceId = store.readSources().first { it.id != LibrarySourceId.DEVICE }.id
            var start = System.nanoTime()
            val first = store.beginSourceScan(sourceId)
            repeat(size) { store.upsertTrack(first, candidate(it, 1)) }
            store.completeSourceScan(first)
            report.appendLine("first import of $size rows: ${(System.nanoTime() - start) / 1_000_000} ms")
            val ids = store.readTracks(listOf(sourceId)).map(AudioTrack::id)

            val phases = mapOf("idle" to Latencies(), "row writes" to Latencies(), "scan completion" to Latencies())
            var phase = "idle"
            val stop = AtomicBoolean(false)
            val foreground = thread(name = "foreground-edits") {
                var at = 0
                while (!stop.get()) {
                    val id = ids[at++ % ids.size]
                    val latencies = phases.getValue(phase)
                    var began = System.nanoTime()
                    store.recordPlayback(id, System.currentTimeMillis())
                    latencies.add((System.nanoTime() - began) / 1_000_000.0)
                    began = System.nanoTime()
                    store.setFavorite(id, at % 2 == 0)
                    latencies.add((System.nanoTime() - began) / 1_000_000.0)
                    began = System.nanoTime()
                    store.readPlaylists()
                    latencies.add((System.nanoTime() - began) / 1_000_000.0)
                    Thread.sleep(20)
                }
            }
            Thread.sleep(3_000)
            phase = "row writes"
            start = System.nanoTime()
            val second = store.beginSourceScan(sourceId)
            repeat(size) { store.upsertTrack(second, candidate(it, 2)) }
            report.appendLine("rescan writing every row: ${(System.nanoTime() - start) / 1_000_000} ms")
            phase = "scan completion"
            start = System.nanoTime()
            store.completeSourceScan(second)
            report.appendLine("scan completion: ${(System.nanoTime() - start) / 1_000_000} ms")
            Thread.sleep(200)
            stop.set(true)
            foreground.join()
            phases.forEach { (label, latencies) -> report.appendLine("foreground edits during $label: ${latencies.summary()}") }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun foregroundEditsDuringAScan() {
        listOf(false, true).forEach { wal ->
            report.appendLine("\n${if (wal) "WAL" else "Current journal"}, 10000 rows")
            scenario(wal, 10_000)
            write("library-performance-sqlite.txt")
        }
    }
}
