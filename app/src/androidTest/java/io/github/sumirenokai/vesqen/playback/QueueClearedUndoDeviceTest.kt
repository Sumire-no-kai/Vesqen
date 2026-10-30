package io.github.sumirenokai.vesqen.playback

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.telemetry.isolatedPlaybackTestApplication
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.VesqenAppContent
import io.github.sumirenokai.vesqen.ui.VesqenViewModel
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test

/**
 * #191 D2: Clear queue in a landscape Now leaves no track, so Now turns portrait and the activity is
 * recreated. The Undo offer is the view model's, so the recreated activity offers it again, and Undo
 * puts the queue back through the real player. A queue started meanwhile ends the offer.
 */
class QueueClearedUndoDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun undoIsStillOfferedAfterTheActivityIsRecreated() = runBlocking {
        val app = isolatedPlaybackTestApplication()
        val file = File(File(app.cacheDir, FIXTURE_DIR), "VESQEN_QA_PRIVATE_undo.wav").also { writeSilentWav(it, durationMs = 20_000) }
        val track = AudioTrack(-2_002, file.toURI().toString(), "Undo after recreation", "", "", 20_000,
            fileName = file.name, fileSizeBytes = file.length(), mimeType = "audio/wav", codec = "PCM",
            sampleRateHz = 8_000, bitDepth = 16, channelCount = 1)
        val viewModels = ViewModelStore()
        lateinit var viewModel: VesqenViewModel
        main {
            viewModel = ViewModelProvider(viewModels, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[VesqenViewModel::class.java]
        }
        val content: @Composable () -> Unit = {
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
                    onClearQueue = viewModel::clearQueue,
                    onUndoClearQueue = viewModel::undoClearQueue,
                    onQueueClearedOfferEnded = viewModel::queueClearedOfferEnded,
                    onPrevious = viewModel::skipToPrevious,
                    onPlayPause = viewModel::togglePlayback,
                    onNext = viewModel::skipToNext,
                    onSeek = viewModel::seekTo,
                    onCyclePlaybackOrder = viewModel::cyclePlaybackOrderMode,
                    motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                )
            }
        }
        try {
            main { viewModel.playQueue(listOf(track), 0) }
            awaitPlayback(viewModel) { it.trackId == track.id && it.isPlaying }
            compose.setContent(content)
            compose.onNodeWithTag("vesqen.mini-player.open-now").performClick()
            compose.onNodeWithTag("vesqen.now.queue").performClick()
            compose.onNodeWithText(app.getString(R.string.clear_queue)).performClick()
            awaitPlayback(viewModel) { !it.hasActiveTrack }
            compose.onNodeWithTag("vesqen.queue-cleared").assertIsDisplayed()

            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { activity -> activity.setContent(content = content) }

            compose.onNodeWithTag("vesqen.queue-cleared").assertIsDisplayed()
            compose.onNodeWithTag("vesqen.queue-cleared.undo").performClick()
            awaitPlayback(viewModel) { it.trackId == track.id && !it.isPlaying }
            main { check(viewModel.uiState.queueClearedOffer == null) { "Undo leaves the offer behind" } }

            // A queue that starts while Undo is offered takes the cleared one's place.
            main { viewModel.clearQueue() }
            main { check(viewModel.uiState.queueClearedOffer != null) { "Clear queue made no offer" } }
            main { viewModel.playQueue(listOf(track), 0) }
            awaitPlayback(viewModel) { it.trackId == track.id }
            main { check(viewModel.uiState.queueClearedOffer == null) { "A new queue leaves the offer behind" } }
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

    private suspend fun awaitPlayback(viewModel: VesqenViewModel, predicate: (PlaybackSnapshot) -> Boolean) =
        withTimeout(15_000) {
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
        const val FIXTURE_DIR = "queue-cleared-undo"
    }
}
