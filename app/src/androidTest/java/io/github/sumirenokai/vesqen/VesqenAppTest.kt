package io.github.sumirenokai.vesqen

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.VesqenAppContent
import io.github.sumirenokai.vesqen.ui.VesqenUiState
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VesqenAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun navigation_exposes_library_now_and_chain_with_empty_state_actions() {
        render(grantedState())

        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        composeRule.onNodeWithText(context.getString(R.string.now_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.browse_library)).performClick()
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()

        composeRule.onNodeWithTag("vesqen.nav.chain").performClick()
        composeRule.onNodeWithText(context.getString(R.string.chain_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.browse_library)).performClick()
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
    }

    @Test
    fun library_row_opens_real_track_details_without_exposing_advanced_audio_fields() {
        render(grantedState(tracks = sampleTracks))

        composeRule.onNodeWithTag("vesqen.library.track.1.more").performClick()

        composeRule.onNodeWithText(context.getString(R.string.track_details)).assertIsDisplayed()
        composeRule.onNodeWithText("Dawn Signal").assertIsDisplayed()
        composeRule.onNodeWithText("Quiet Rooms").assertIsDisplayed()
        composeRule.onNodeWithText("4:05").assertIsDisplayed()
        composeRule.onAllNodesWithText("96 kHz").assertCountEquals(0)
    }

    @Test
    fun active_playback_exposes_mini_player_then_now_then_honest_chain() {
        val activeState = grantedState(
            tracks = sampleTracks,
            playback = PlaybackSnapshot(
                isControllerReady = true,
                isPlaying = true,
                trackId = 1,
                title = "Dawn Signal",
                artist = "Mori",
                album = "Quiet Rooms",
                durationMs = 245_000,
                positionMs = 30_000,
                hasNext = true,
            ),
        )
        render(activeState)

        composeRule.onNodeWithTag("vesqen.mini-player").assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.system_mixed)).assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.progress").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.shuffle").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.repeat").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").assertIsDisplayed()
        val outputDescription = "${context.getString(R.string.output_status_description, context.getString(R.string.system_mixed))}. " +
            context.getString(R.string.open_playback_chain)
        composeRule.onNodeWithContentDescription(outputDescription).assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.open-chain").performClick()

        composeRule.onNodeWithTag("vesqen.chain").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chain_system_mixed_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText("BIT-PERFECT ACTIVE").assertCountEquals(0)
        composeRule.onAllNodesWithText("BIT-PERFECT VERIFIED").assertCountEquals(0)
    }

    @Test
    fun full_player_uses_track_information_sheet_and_horizontal_session_page() {
        render(
            grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "Dawn Signal",
                    artist = "Mori",
                    durationMs = 245_000,
                    positionMs = 30_000,
                    queueIndex = 0,
                    queueSize = 2,
                ),
            ),
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.info").performClick()
        composeRule.onNodeWithTag("vesqen.track-details").assertIsDisplayed()

        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.onNodeWithTag("vesqen.now.info-pager").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("vesqen.now.info.session").assertIsDisplayed()
    }

    @Test
    fun now_back_returns_to_library_and_extended_controls_call_real_callbacks() {
        var shuffleCalls = 0
        var repeatCalls = 0
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "Dawn Signal",
                    artist = "Mori",
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            onToggleShuffle = { shuffleCalls++ },
            onCycleRepeatMode = { repeatCalls++ },
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.shuffle").performClick()
        composeRule.onNodeWithTag("vesqen.now.repeat").performClick()
        composeRule.onNodeWithTag("vesqen.now.back").performClick()

        composeRule.runOnIdle {
            assertEquals(1, shuffleCalls)
            assertEquals(1, repeatCalls)
        }
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
    }

    @Test
    fun android_back_returns_from_now_and_contextual_chain_without_exiting_the_app() {
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "Dawn Signal",
                    artist = "Mori",
                    durationMs = 245_000,
                ),
            ),
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.info").performClick()
        composeRule.onNodeWithTag("vesqen.track-details").assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.track-details").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.now.back").assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.open-chain").performClick()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.now.back").assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
    }

    @Test
    fun mini_player_controls_call_their_own_callbacks() {
        var previousCalls = 0
        var toggleCalls = 0
        var nextCalls = 0
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "Dawn Signal",
                    artist = "Mori",
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            onPrevious = { previousCalls++ },
            onPlayPause = { toggleCalls++ },
            onNext = { nextCalls++ },
        )

        composeRule.onNodeWithTag("vesqen.mini-player.previous").performClick()
        composeRule.onNodeWithTag("vesqen.mini-player.play-pause").performClick()
        composeRule.onNodeWithTag("vesqen.mini-player.next").performClick()

        composeRule.runOnIdle {
            assertEquals(1, previousCalls)
            assertEquals(1, toggleCalls)
            assertEquals(1, nextCalls)
        }
    }

    @Test
    fun mini_player_stays_a_single_compact_row_at_320dp() {
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "A deliberately long local track title",
                    artist = "A deliberately long local artist name",
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            containerWidth = 320.dp,
        )

        composeRule.onNodeWithTag("vesqen.mini-player").assertHeightIsEqualTo(72.dp)
        composeRule.onAllNodesWithText(context.getString(R.string.system_mixed)).assertCountEquals(0)
    }

    @Test
    fun now_player_keeps_a_long_title_single_line_and_all_controls_visible_at_480dp_with_large_text() {
        val longTitle = "A deliberately long local track title that must not create a second player row"
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = longTitle,
                    artist = "Mori",
                    durationMs = 245_000,
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            containerWidth = 320.dp,
            containerHeight = 480.dp,
            fontScale = 2f,
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.player-page").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.title").assertIsDisplayed()
        composeRule.onNodeWithText(longTitle).assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.progress").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.shuffle").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.repeat").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info-pager").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("vesqen.now.info.chain").assertIsDisplayed()
    }

    private fun render(
        state: VesqenUiState,
        onPrevious: () -> Unit = {},
        onPlayPause: () -> Unit = {},
        onNext: () -> Unit = {},
        onToggleShuffle: () -> Unit = {},
        onCycleRepeatMode: () -> Unit = {},
        containerWidth: Dp? = null,
        containerHeight: Dp = 720.dp,
        fontScale: Float? = null,
    ) {
        composeRule.setContent {
            VesqenTheme(darkTheme = true) {
                val app: @Composable () -> Unit = {
                    VesqenAppContent(
                        state = state,
                        onRequestMusicAccess = {},
                        onOpenAppSettings = {},
                        onOpenNotificationSettings = {},
                        onRescan = {},
                        onTrackSelected = {},
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onSeek = {},
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeatMode = onCycleRepeatMode,
                        onRefreshConnectedOutputs = {},
                        motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                    )
                }
                val renderWithinSize: @Composable () -> Unit = {
                    if (containerWidth == null) {
                        app()
                    } else {
                        Box(
                            modifier = Modifier
                                .width(containerWidth)
                                .height(containerHeight),
                        ) {
                            app()
                        }
                    }
                }
                if (fontScale == null) {
                    renderWithinSize()
                } else {
                    val configuration = LocalConfiguration.current
                    val density = LocalDensity.current
                    val scaledConfiguration = remember(configuration, fontScale) {
                        Configuration(configuration).apply { this.fontScale = fontScale }
                    }
                    val scaledDensity = remember(density, fontScale) {
                        Density(density.density, fontScale)
                    }
                    CompositionLocalProvider(
                        LocalConfiguration provides scaledConfiguration,
                        LocalDensity provides scaledDensity,
                    ) {
                        renderWithinSize()
                    }
                }
            }
        }
    }

    private fun grantedState(
        tracks: List<AudioTrack> = emptyList(),
        playback: PlaybackSnapshot = PlaybackSnapshot(),
    ): VesqenUiState = VesqenUiState(
        library = LibraryUiState(
            musicAccess = MusicAccess.GRANTED,
            tracks = tracks,
        ),
        playback = playback,
    )

    private companion object {
        val sampleTracks = listOf(
            AudioTrack(
                id = 1,
                contentUri = "content://media/external/audio/media/1",
                title = "Dawn Signal",
                artist = "Mori",
                album = "Quiet Rooms",
                durationMs = 245_000,
            ),
            AudioTrack(
                id = 2,
                contentUri = "content://media/external/audio/media/2",
                title = "Long Light",
                artist = "Mori",
                album = "Quiet Rooms",
                durationMs = 189_000,
            ),
        )
    }
}
