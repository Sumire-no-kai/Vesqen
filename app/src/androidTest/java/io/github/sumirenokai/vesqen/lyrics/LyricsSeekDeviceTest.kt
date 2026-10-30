package io.github.sumirenokai.vesqen.lyrics

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackService
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.writeSilentWav
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.VesqenAppContent
import io.github.sumirenokai.vesqen.ui.VesqenViewModel
import io.github.sumirenokai.vesqen.ui.screens.NowLyricsState
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test

/**
 * #169 PRD 完成条件: tapping a lyric line moves the real player to that line's time. The tap goes
 * through the app's own wiring, Now's actions to the view model's seek, with playback paused first
 * so that only the seek can reach the line's time.
 */
class LyricsSeekDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun tappingALineMovesThePlayerToItsTime() = runBlocking {
        val app = isolatedPlaybackTestApplication()
        val file = File(File(app.cacheDir, FIXTURE_DIR), "VESQEN_QA_PRIVATE_lyrics.wav").also { writeSilentWav(it, durationMs = 20_000) }
        val track = AudioTrack(-2_001, file.toURI().toString(), "Lyrics seek", "", "", 20_000,
            fileName = file.name, fileSizeBytes = file.length(), mimeType = "audio/wav", codec = "PCM",
            sampleRateHz = 8_000, bitDepth = 16, channelCount = 1)
        val lyrics = Lyrics.Synced(listOf(LyricLine(1_000, "One"), LyricLine(6_000, "Two"), LyricLine(14_000, "Three")))
        val viewModels = ViewModelStore()
        lateinit var viewModel: VesqenViewModel
        main {
            viewModel = ViewModelProvider(viewModels, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[VesqenViewModel::class.java]
        }
        try {
            main { viewModel.playQueue(listOf(track), 0) }
            awaitPlayback(viewModel, 15_000) { it.trackId == track.id && it.isPlaying }
            main { viewModel.togglePlayback() }
            awaitPlayback(viewModel, 5_000) { !it.isPlaying }
            compose.setContent {
                VesqenTheme {
                    val state = viewModel.uiState
                    VesqenAppContent(
                        state = state.copy(
                            library = state.library.copy(musicAccess = MusicAccess.GRANTED, isLoading = false, tracks = listOf(track)),
                        ),
                        onRequestMusicAccess = {},
                        onOpenAppSettings = {},
                        onOpenNotificationSettings = {},
                        onRescan = {},
                        onTrackSelected = {},
                        onPrevious = viewModel::skipToPrevious,
                        onPlayPause = viewModel::togglePlayback,
                        onNext = viewModel::skipToNext,
                        onSeek = viewModel::seekTo,
                        onCyclePlaybackOrder = viewModel::cyclePlaybackOrderMode,
                        motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                        lyrics = NowLyricsState(shown = true),
                        loadLyrics = { TrackLyrics.Found(LyricsSource.Embedded, lyrics) },
                    )
                }
            }
            compose.onNodeWithTag("vesqen.mini-player.open-now").performClick()
            compose.onNodeWithTag("vesqen.now.lyrics.line.2").performClick()

            // Paused near the start, the player reaches 14 s only through the seek, at once.
            awaitPlayback(viewModel, 2_000) { !it.isPlaying && it.positionMs >= 14_000 }
        } finally {
            main {
                viewModel.clearQueue()
                // Releases the view model's playback controller.
                viewModels.clear()
                // Later tests expect a service that has never played.
                app.stopService(Intent(app, PlaybackService::class.java))
            }
            File(app.cacheDir, FIXTURE_DIR).deleteRecursively()
        }
    }

    private suspend fun awaitPlayback(
        viewModel: VesqenViewModel,
        timeoutMs: Long,
        predicate: (PlaybackSnapshot) -> Boolean,
    ) = withTimeout(timeoutMs) {
        while (true) {
            var snapshot = PlaybackSnapshot()
            main {
                viewModel.refreshPlaybackPosition()
                snapshot = viewModel.uiState.playback
            }
            if (predicate(snapshot)) break
            delay(50)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private companion object {
        const val FIXTURE_DIR = "lyrics-seek"
    }
}
