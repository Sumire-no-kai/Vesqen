package io.github.sumirenokai.vesqen

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.PlaybackRepeatMode
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.VesqenAppContent
import io.github.sumirenokai.vesqen.ui.VesqenUiState
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun full_player_keeps_its_shell_stable_while_an_explicit_session_toggle_replaces_only_the_stage() {
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
        composeRule.onNodeWithTag("vesqen.now.session-toggle").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.album_artwork),
            ),
        )
        val stableShellBounds = captureNowShellBounds()
        composeRule.onNodeWithTag("vesqen.now.focus-content").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("vesqen.now.info.session").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.now.session-toggle").performClick()
        composeRule.onNodeWithTag("vesqen.now.info.session").assertIsDisplayed()
        assertNowShellBoundsStable(stableShellBounds)
        composeRule.onNodeWithTag("vesqen.now.back").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.focus-content").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.HorizontalScrollAxisRange),
        )
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.now.info.session").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
    }

    @Test
    fun now_back_returns_to_library_and_extended_controls_call_real_callbacks() {
        var previousCalls = 0
        var playPauseCalls = 0
        var nextCalls = 0
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
            onPrevious = { previousCalls++ },
            onPlayPause = { playPauseCalls++ },
            onNext = { nextCalls++ },
            onToggleShuffle = { shuffleCalls++ },
            onCycleRepeatMode = { repeatCalls++ },
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.previous").performClick()
        composeRule.onNodeWithTag("vesqen.now.play-pause").performClick()
        composeRule.onNodeWithTag("vesqen.now.next").performClick()
        composeRule.onNodeWithTag("vesqen.now.shuffle").performClick()
        composeRule.onNodeWithTag("vesqen.now.repeat").performClick()
        composeRule.onNodeWithTag("vesqen.now.back").performClick()

        composeRule.runOnIdle {
            assertEquals(1, previousCalls)
            assertEquals(1, playPauseCalls)
            assertEquals(1, nextCalls)
            assertEquals(1, shuffleCalls)
            assertEquals(1, repeatCalls)
        }
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
    }

    @Test
    fun one_repeat_control_cycles_off_all_one_and_back_with_its_state_exposed() {
        composeRule.setContent {
            VesqenTheme {
                val repeatMode = remember { androidx.compose.runtime.mutableStateOf(PlaybackRepeatMode.OFF) }
                val track = sampleTracks.first()
                VesqenAppContent(
                    state = grantedState(
                        tracks = sampleTracks,
                        playback = PlaybackSnapshot(
                            isControllerReady = true,
                            trackId = track.id,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            durationMs = track.durationMs,
                            repeatMode = repeatMode.value,
                        ),
                    ),
                    onRequestMusicAccess = {},
                    onOpenAppSettings = {},
                    onOpenNotificationSettings = {},
                    onRescan = {},
                    onTrackSelected = {},
                    onPrevious = {},
                    onPlayPause = {},
                    onNext = {},
                    onSeek = {},
                    onToggleShuffle = {},
                    onCycleRepeatMode = {
                        repeatMode.value = when (repeatMode.value) {
                            PlaybackRepeatMode.OFF -> PlaybackRepeatMode.ALL
                            PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
                            PlaybackRepeatMode.ONE -> PlaybackRepeatMode.OFF
                        }
                    },
                    onRefreshConnectedOutputs = {},
                    motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                )
            }
        }

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onAllNodesWithTag("vesqen.now.repeat").assertCountEquals(1)
        composeRule.onNodeWithTag("vesqen.now.repeat").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.repeat_off),
            ),
        )

        composeRule.onNodeWithTag("vesqen.now.repeat").performClick()
        composeRule.onNodeWithTag("vesqen.now.repeat").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.repeat_all),
            ),
        )
        composeRule.onNodeWithTag("vesqen.now.repeat").performClick()
        composeRule.onNodeWithTag("vesqen.now.repeat").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.repeat_one),
            ),
        )
        composeRule.onNodeWithTag("vesqen.now.repeat").performClick()
        composeRule.onNodeWithTag("vesqen.now.repeat").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.repeat_off),
            ),
        )
    }

    @Test
    fun track_skip_keeps_the_focused_transport_stable_while_track_identity_changes() {
        composeRule.setContent {
            VesqenTheme {
                val trackIndex = remember { androidx.compose.runtime.mutableStateOf(0) }
                val track = sampleTracks[trackIndex.value]
                VesqenAppContent(
                    state = grantedState(
                        tracks = sampleTracks,
                        playback = PlaybackSnapshot(
                            isControllerReady = true,
                            trackId = track.id,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            durationMs = track.durationMs,
                            hasPrevious = trackIndex.value > 0,
                            hasNext = trackIndex.value < sampleTracks.lastIndex,
                            canSkipPrevious = trackIndex.value > 0,
                            canSkipNext = trackIndex.value < sampleTracks.lastIndex,
                        ),
                    ),
                    onRequestMusicAccess = {},
                    onOpenAppSettings = {},
                    onOpenNotificationSettings = {},
                    onRescan = {},
                    onTrackSelected = {},
                    onPrevious = { trackIndex.value = (trackIndex.value - 1).coerceAtLeast(0) },
                    onPlayPause = {},
                    onNext = { trackIndex.value = (trackIndex.value + 1).coerceAtMost(sampleTracks.lastIndex) },
                    onSeek = {},
                    onToggleShuffle = {},
                    onCycleRepeatMode = {},
                    onRefreshConnectedOutputs = {},
                    motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                )
            }
        }

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithText("Dawn Signal").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").performClick()
        composeRule.onNodeWithText("Long Light").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").performClick()
        composeRule.onNodeWithText("Dawn Signal").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
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
    fun transport_controls_stay_disabled_when_the_session_has_no_executable_neighbor_route() {
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
                    canSkipPrevious = false,
                    canSkipNext = false,
                ),
            ),
        )

        composeRule.onNodeWithTag("vesqen.mini-player.previous").assertIsNotEnabled()
        composeRule.onNodeWithTag("vesqen.mini-player.next").assertIsNotEnabled()
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsNotEnabled()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsNotEnabled()
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
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        assertFocusedNowControlsAreFullyVisible()
        composeRule.onNodeWithTag("vesqen.now.session-toggle").performClick()
        composeRule.onNodeWithTag("vesqen.now.info.session").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.playback_progress)).assertDoesNotExist()
        assertNodesAreFullyVisibleIn(
            containerTag = "vesqen.now.focus-content",
            tags = arrayOf("vesqen.now.info.session"),
        )
        composeRule.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.compact-chain").assertIsDisplayed()
        assertNodesAreFullyVisibleIn(
            containerTag = "vesqen.now.player-page",
            tags = arrayOf("vesqen.now.compact-chain"),
        )
        assertFooterActionsDoNotOverlap(
            "vesqen.now.shuffle",
            "vesqen.now.session-toggle",
            "vesqen.now.repeat",
            "vesqen.now.info",
        )
        composeRule.onNodeWithTag("vesqen.now.compact-chain").performClick()
        composeRule.onNodeWithTag("vesqen.chain").assertIsDisplayed()
    }

    @Test
    fun focused_player_keeps_all_primary_controls_visible_inside_a_light_host() {
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "Dawn Signal",
                    artist = "Mori",
                    album = "Quiet Rooms",
                    durationMs = 245_000,
                    positionMs = 30_000,
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            containerWidth = 360.dp,
            containerHeight = 533.dp,
            darkTheme = false,
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.focus-surface").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.back").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.title").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.progress").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.shuffle").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.repeat").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").assertIsDisplayed()
    }

    @Test
    fun focused_player_keeps_primary_controls_inside_360dp_with_large_text() {
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "A deliberately long local track title that must remain one line",
                    artist = "Mori",
                    durationMs = 245_000,
                    positionMs = 30_000,
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            containerWidth = 360.dp,
            containerHeight = 533.dp,
            fontScale = 2f,
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.back").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.title").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.progress").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.shuffle").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.repeat").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        assertFocusedNowControlsAreFullyVisible()
    }

    @Test
    fun focused_player_keeps_primary_controls_inside_a_short_landscape_window() {
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "Dawn Signal",
                    artist = "Mori",
                    durationMs = 245_000,
                    positionMs = 30_000,
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            containerWidth = 640.dp,
            containerHeight = 320.dp,
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.back").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.title").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.progress").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.shuffle").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.repeat").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        assertFocusedNowControlsAreFullyVisible()
    }

    @Test
    fun focused_player_hides_the_wide_navigation_rail_and_keeps_a_single_system_bar_surface() {
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "Dawn Signal",
                    artist = "Mori",
                    durationMs = 245_000,
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            containerWidth = 720.dp,
            containerHeight = 720.dp,
            darkTheme = false,
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.focus-surface").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.nav.library").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
    }

    @Test
    fun focused_player_keeps_primary_controls_inside_a_640dp_height_window() {
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "Dawn Signal",
                    artist = "Mori",
                    durationMs = 245_000,
                    positionMs = 30_000,
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            containerWidth = 360.dp,
            containerHeight = 640.dp,
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.progress").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.shuffle").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.repeat").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").assertIsDisplayed()
        assertFocusedNowControlsAreFullyVisible()
    }

    @Test
    fun focused_now_keeps_an_opaque_material_fallback_without_artwork_at_320dp_with_large_text() {
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "Dawn Signal",
                    artist = "Mori",
                    durationMs = 245_000,
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            containerWidth = 320.dp,
            containerHeight = 480.dp,
            fontScale = 2f,
            darkTheme = false,
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.backdrop").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.backdrop.opaque-fallback").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.album-artwork.fallback").assertCountEquals(1)
        composeRule.onAllNodesWithTag("vesqen.now.artwork-reflection").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        assertFocusedNowControlsAreFullyVisible()
    }

    @Test
    fun focused_now_never_invents_a_reflection_for_an_unreadable_artwork_uri() {
        val trackWithUnreadableArtwork = sampleTracks.first().copy(
            contentUri = "content://io.github.sumirenokai.vesqen.test/missing-audio",
            albumArtworkUri = "content://io.github.sumirenokai.vesqen.test/missing-artwork",
            artworkRevision = 1L,
        )
        render(
            state = grantedState(
                tracks = listOf(trackWithUnreadableArtwork),
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = trackWithUnreadableArtwork.id,
                    title = trackWithUnreadableArtwork.title,
                    artist = trackWithUnreadableArtwork.artist,
                    album = trackWithUnreadableArtwork.album,
                    durationMs = trackWithUnreadableArtwork.durationMs,
                ),
            ),
            motionPolicy = VesqenMotionPolicy(reduceMotion = false),
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.backdrop").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.backdrop.opaque-fallback").assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.now.artwork-reflection").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
    }

    private fun assertFocusedNowControlsAreFullyVisible() {
        assertNodesAreFullyVisibleIn(
            containerTag = "vesqen.now.focus-surface",
            tags = arrayOf("vesqen.now.back"),
        )
        assertNodesAreFullyVisibleIn(
            containerTag = "vesqen.now.player-page",
            tags = arrayOf(
                "vesqen.now.title",
                "vesqen.now.progress",
                "vesqen.now.previous",
                "vesqen.now.play-pause",
                "vesqen.now.next",
                "vesqen.now.shuffle",
                "vesqen.now.session-toggle",
                "vesqen.now.repeat",
                "vesqen.now.info",
            ),
        )
        assertFooterActionsDoNotOverlap(
            "vesqen.now.shuffle",
            "vesqen.now.session-toggle",
            "vesqen.now.repeat",
            "vesqen.now.info",
        )

        val title = composeRule.onNodeWithTag("vesqen.now.title").fetchSemanticsNode()
        val primaryTransport = composeRule.onNodeWithTag("vesqen.now.play-pause").fetchSemanticsNode()
        assertTrue(
            "Now title must remain a single transport-row height",
            title.size.height <= primaryTransport.size.height,
        )
    }

    private fun assertNodesAreFullyVisibleIn(
        containerTag: String,
        tags: Array<String>,
    ) {
        val containerBounds = composeRule.onNodeWithTag(containerTag).fetchSemanticsNode().boundsInRoot
        val minimumTouchTargetPx = with(composeRule.density) { 48.dp.toPx() }
        val epsilon = 1f
        val touchTargetTags = setOf(
            "vesqen.now.back",
            "vesqen.now.progress",
            "vesqen.now.previous",
            "vesqen.now.play-pause",
            "vesqen.now.next",
            "vesqen.now.shuffle",
            "vesqen.now.session-toggle",
            "vesqen.now.compact-chain",
            "vesqen.now.repeat",
            "vesqen.now.info",
        )

        tags.forEach { tag ->
            val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            val visibleBounds = node.boundsInRoot
            assertTrue(
                "$tag must not be vertically clipped",
                abs(visibleBounds.height - node.size.height) <= epsilon,
            )
            assertTrue(
                "$tag must not be horizontally clipped",
                abs(visibleBounds.width - node.size.width) <= epsilon,
            )
            assertTrue(
                "$tag must remain inside its Now container",
                visibleBounds.left >= containerBounds.left - epsilon &&
                    visibleBounds.top >= containerBounds.top - epsilon &&
                    visibleBounds.right <= containerBounds.right + epsilon &&
                    visibleBounds.bottom <= containerBounds.bottom + epsilon,
            )
            if (tag in touchTargetTags) {
                val touchBounds = node.touchBoundsInRoot
                assertTrue(
                    "$tag must preserve the 48dp minimum touch target",
                    touchBounds.width + epsilon >= minimumTouchTargetPx &&
                        touchBounds.height + epsilon >= minimumTouchTargetPx,
                )
            }
        }
    }

    private fun captureNowShellBounds(): Map<String, androidx.compose.ui.geometry.Rect> {
        val stableTags = arrayOf(
            "vesqen.now.back",
            "vesqen.now.title",
            "vesqen.now.transport-dock",
            "vesqen.now.progress",
            "vesqen.now.previous",
            "vesqen.now.play-pause",
            "vesqen.now.next",
            "vesqen.now.shuffle",
            "vesqen.now.session-toggle",
            "vesqen.now.repeat",
            "vesqen.now.info",
        )
        return stableTags.associateWith { tag ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        }
    }

    private fun assertNowShellBoundsStable(
        before: Map<String, androidx.compose.ui.geometry.Rect>,
    ) {
        before.forEach { (tag, expectedBounds) ->
            assertEquals(
                "$tag must not move when the focus stage changes",
                expectedBounds,
                composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot,
            )
        }
    }

    private fun assertFooterActionsDoNotOverlap(vararg tags: String) {
        val bounds = tags.associateWith { tag ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        }
        tags.forEachIndexed { index, firstTag ->
            tags.drop(index + 1).forEach { secondTag ->
                val first = bounds.getValue(firstTag)
                val second = bounds.getValue(secondTag)
                val separate = first.right <= second.left ||
                    second.right <= first.left ||
                    first.bottom <= second.top ||
                    second.bottom <= first.top
                assertTrue("$firstTag must not overlap $secondTag", separate)
            }
        }
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
        darkTheme: Boolean = true,
        motionPolicy: VesqenMotionPolicy = VesqenMotionPolicy(reduceMotion = true),
    ) {
        composeRule.setContent {
            VesqenTheme(darkTheme = darkTheme) {
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
                        motionPolicy = motionPolicy,
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
                if (fontScale == null && containerWidth == null) {
                    renderWithinSize()
                } else {
                    val configuration = LocalConfiguration.current
                    val density = LocalDensity.current
                    val sizedConfiguration = remember(
                        configuration,
                        containerWidth,
                        containerHeight,
                        fontScale,
                    ) {
                        Configuration(configuration).apply {
                            this.fontScale = fontScale ?: configuration.fontScale
                            containerWidth?.let { screenWidthDp = it.value.toInt() }
                            containerWidth?.let { screenHeightDp = containerHeight.value.toInt() }
                        }
                    }
                    val sizedDensity = remember(density, fontScale) {
                        Density(density.density, fontScale ?: density.fontScale)
                    }
                    CompositionLocalProvider(
                        LocalConfiguration provides sizedConfiguration,
                        LocalDensity provides sizedDensity,
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
