package io.github.sumirenokai.vesqen.library

import android.os.Build
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.screens.LibraryScreen
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The longest time the main thread stayed away from drawing during each Library action, for #223:
 * not a pass or fail, and it reads only public behavior, so the same file runs on the code before the
 * change. Run with `-e measure true` and read the `LibraryPerformance` lines in logcat. The page sits in
 * a bare activity without the app's bars, so on a device it draws under the status bar; only its timing
 * is of interest here.
 */
class LibraryMainThreadMeasurementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tracks = mutableStateOf(emptyList<AudioTrack>())
    private val visible = mutableStateOf(true)

    @Before
    fun onlyWhenAsked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measure") == "true")
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

    /** Runs [action], lets its lists arrive, and returns the longest gap between frame callbacks. */
    private fun longestGapMs(action: () -> Unit): Double {
        var longest = 0L
        var last = 0L
        var running = true
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = System.nanoTime()
                if (last != 0L) longest = maxOf(longest, now - last)
                last = now
                if (running) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        compose.runOnUiThread { Choreographer.getInstance().postFrameCallback(callback) }
        Thread.sleep(200)
        compose.runOnUiThread {
            longest = 0L
            last = 0L
        }
        action()
        // Work that finished off the main thread is composed when the test clock next advances.
        repeat(6) {
            Thread.sleep(250)
            compose.waitForIdle()
        }
        var result = 0L
        compose.runOnUiThread {
            running = false
            result = longest
        }
        return result / 1_000_000.0
    }

    private fun report(label: String, runs: List<Double>) {
        val sorted = runs.sorted()
        Log.i(
            "LibraryPerformance",
            "%-34s median %7.1f ms  max %7.1f ms".format(label, sorted[sorted.size / 2], sorted.last()),
        )
    }

    private fun measure(label: String, first: () -> Unit, second: () -> Unit) {
        val firstRuns = mutableListOf<Double>()
        val secondRuns = mutableListOf<Double>()
        repeat(5) {
            firstRuns += longestGapMs(first)
            secondRuns += longestGapMs(second)
        }
        report("$label: there", firstRuns)
        report("$label: back", secondRuns)
    }

    private fun mode(name: String) = compose.onNodeWithTag("vesqen.library.mode.$name").performClick()

    @Test
    fun longestMainThreadGapsPerAction() {
        val size = InstrumentationRegistry.getArguments().getString("tracks")?.toInt() ?: 10_000
        tracks.value = syntheticTracks(size)
        compose.setContent {
            VesqenTheme {
                if (visible.value) {
                    LibraryScreen(
                        state = LibraryUiState(musicAccess = MusicAccess.GRANTED, tracks = tracks.value),
                        playback = PlaybackSnapshot(),
                        onRequestMusicAccess = {},
                        onOpenAppSettings = {},
                        onOpenNotificationSettings = {},
                        onRescan = {},
                        onTrackSelected = {},
                    )
                }
            }
        }
        // The title order arrives by itself and is not what is measured.
        repeat(8) {
            Thread.sleep(500)
            compose.waitForIdle()
        }
        Log.i("LibraryPerformance", "${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, $size tracks")

        measure("Songs to Artists", { mode("artists") }, { mode("songs") })
        measure("Songs to Albums", { mode("albums") }, { mode("songs") })

        // A new play count reaches every list while the page is open, with no action by the user.
        fun played(id: Long) {
            tracks.value = tracks.value.map { if (it.id == id) it.copy(playCount = it.playCount + 1) else it }
        }
        measure("Play count, Songs", { played(1) }, { played(2) })
        mode("artists")
        measure("Play count, Artists", { played(1) }, { played(2) })
        mode("songs")

        measure(
            "Leave and return to the page",
            { compose.runOnUiThread { visible.value = false } },
            { compose.runOnUiThread { visible.value = true } },
        )

        compose.onNodeWithTag("vesqen.library.search-toggle").performClick()
        measure(
            "Search, one typed letter",
            { compose.onNode(hasSetTextAction()).performTextReplacement("夜") },
            { compose.onNode(hasSetTextAction()).performTextReplacement("") },
        )
    }
}
