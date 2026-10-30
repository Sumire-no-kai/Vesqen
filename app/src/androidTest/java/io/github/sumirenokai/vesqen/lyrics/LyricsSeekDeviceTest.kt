package io.github.sumirenokai.vesqen.lyrics

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackController
import io.github.sumirenokai.vesqen.playback.PlaybackService
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.writeSilentWav
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.ui.screens.NowLyricsPane
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test

/** #169 PRD 完成条件: tapping a lyric line moves the real player to that line's time. */
class LyricsSeekDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val playback = AtomicReference(PlaybackSnapshot())
    private var shown by mutableStateOf(PlaybackSnapshot())
    private lateinit var controller: PlaybackController

    @Test
    fun tappingALineMovesThePlayerToItsTime() = runBlocking {
        val app = isolatedPlaybackTestApplication()
        val file = File(File(app.cacheDir, FIXTURE_DIR), "VESQEN_QA_PRIVATE_lyrics.wav").also { writeSilentWav(it, durationMs = 20_000) }
        val track = AudioTrack(-2_001, file.toURI().toString(), "Lyrics seek", "", "", 20_000,
            fileName = file.name, fileSizeBytes = file.length(), mimeType = "audio/wav", codec = "PCM",
            sampleRateHz = 8_000, bitDepth = 16, channelCount = 1)
        val lyrics = Lyrics.Synced(listOf(LyricLine(1_000, "One"), LyricLine(6_000, "Two"), LyricLine(14_000, "Three")))
        try {
            main {
                controller = PlaybackController(app) { snapshot ->
                    playback.set(snapshot)
                    shown = snapshot
                }
            }
            awaitPlayback { it.isControllerReady }
            main { controller.playQueue(listOf(track), 0) }
            awaitPlayback { it.trackId == track.id && it.isPlaying }
            compose.setContent {
                VesqenTheme {
                    NowLyricsPane(
                        lyrics = TrackLyrics.Found(LyricsSource.Embedded, lyrics),
                        positionMs = shown.positionMs,
                        durationMs = shown.durationMs,
                        isPlaying = shown.isPlaying,
                        canSeek = shown.isControllerReady,
                        motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                        onSeek = { controller.seekTo(it) },
                        onImportLyrics = null,
                    )
                }
            }

            compose.onNodeWithTag("vesqen.now.lyrics.line.2").performClick()

            awaitPlayback { it.positionMs >= 14_000 }
        } finally {
            main {
                if (playback.get().showsPauseAction) controller.togglePlayback()
                controller.clearQueue()
                controller.release()
                // Later tests expect a service that has never played.
                app.stopService(Intent(app, PlaybackService::class.java))
            }
            File(app.cacheDir, FIXTURE_DIR).deleteRecursively()
        }
    }

    private suspend fun awaitPlayback(predicate: (PlaybackSnapshot) -> Boolean) = withTimeout(15_000) {
        while (true) {
            main { controller.refreshPosition() }
            if (predicate(playback.get())) break
            delay(50)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private companion object {
        const val FIXTURE_DIR = "lyrics-seek"
    }
}
