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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
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
import io.github.sumirenokai.vesqen.library.LibraryScanProgress
import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import io.github.sumirenokai.vesqen.playback.PlaybackOrderMode
import io.github.sumirenokai.vesqen.playback.PlaybackQueueItem
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class VesqenAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun navigation_exposes_library_now_and_settings_with_chain_as_a_secondary_action() {
        render(grantedState())

        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        composeRule.onNodeWithTag("vesqen.now.empty").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.now_empty_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.browse_library)).assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.now.orientation-toggle").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.nav.now").assertIsSelected()
        composeRule.onNodeWithTag("vesqen.nav.library").performClick()
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        composeRule.onNodeWithText(context.getString(R.string.chain_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.browse_library)).performClick()
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
    }

    @Test
    fun denied_device_permission_keeps_SAF_folder_import_embedded_in_library() {
        var addFolderCalls = 0
        render(
            state = VesqenUiState(
                library = LibraryUiState(musicAccess = MusicAccess.DENIED),
            ),
            onAddLibraryFolder = { addFolderCalls++ },
        )

        composeRule.onNodeWithTag("vesqen.permission.request").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.library.add-folder").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.library.add-folder").performClick()

        composeRule.runOnIdle { assertEquals(1, addFolderCalls) }
    }

    @Test
    fun compact_library_search_and_notices_preserve_touch_targets_on_narrow_window() {
        var appSettingsCalls = 0
        var notificationSettingsCalls = 0
        render(
            state = VesqenUiState(
                library = LibraryUiState(
                    musicAccess = MusicAccess.DENIED,
                    notificationsAllowed = false,
                    tracks = sampleTracks,
                ),
                playback = PlaybackSnapshot(trackId = sampleTracks.first().id),
            ),
            onOpenAppSettings = { appSettingsCalls++ },
            onOpenNotificationSettings = { notificationSettingsCalls++ },
            containerWidth = 320.dp,
            containerHeight = 480.dp,
        )

        composeRule.onNodeWithTag("vesqen.library.music-access-notice")
            .assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("vesqen.library.notifications-notice")
            .assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("vesqen.library.search").assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("vesqen.permission.request").assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("vesqen.library.notifications.settings").assertHeightIsEqualTo(48.dp)

        val musicAccessBounds = composeRule.onNodeWithTag("vesqen.library.music-access-notice")
            .fetchSemanticsNode()
            .boundsInRoot
        val notificationsBounds = composeRule.onNodeWithTag("vesqen.library.notifications-notice")
            .fetchSemanticsNode()
            .boundsInRoot
        val searchBounds = composeRule.onNodeWithTag("vesqen.library.search")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Compact library controls must retain vertical separation",
            musicAccessBounds.bottom <= notificationsBounds.top &&
                notificationsBounds.bottom <= searchBounds.top,
        )

        composeRule.onNodeWithTag("vesqen.permission.request").performClick()
        composeRule.onNodeWithTag("vesqen.library.notifications.settings").performClick()
        composeRule.runOnIdle {
            assertEquals(1, appSettingsCalls)
            assertEquals(1, notificationSettingsCalls)
        }
    }

    @Test
    fun empty_library_does_not_reserve_search_space() {
        render(grantedState())

        composeRule.onAllNodesWithTag("vesqen.library.search").assertCountEquals(0)
    }

    @Test
    fun compact_library_controls_can_expand_without_overlap_at_large_font() {
        render(
            state = VesqenUiState(
                library = LibraryUiState(
                    musicAccess = MusicAccess.DENIED,
                    notificationsAllowed = false,
                    tracks = sampleTracks,
                ),
                playback = PlaybackSnapshot(trackId = sampleTracks.first().id),
            ),
            containerWidth = 320.dp,
            containerHeight = 720.dp,
            fontScale = 2f,
        )

        val musicAccessBounds = composeRule.onNodeWithTag("vesqen.library.music-access-notice")
            .fetchSemanticsNode()
            .boundsInRoot
        val notificationsBounds = composeRule.onNodeWithTag("vesqen.library.notifications-notice")
            .fetchSemanticsNode()
            .boundsInRoot
        val searchBounds = composeRule.onNodeWithTag("vesqen.library.search")
            .fetchSemanticsNode()
            .boundsInRoot
        val minimumTouchTargetPx = with(composeRule.density) { 48.dp.toPx() }

        assertTrue(
            "The device-music action must retain a 48dp target at large font",
            composeRule.onNodeWithTag("vesqen.permission.request")
                .fetchSemanticsNode()
                .boundsInRoot
                .height >= minimumTouchTargetPx,
        )
        assertTrue(
            "Large-font library controls must stay in reading order without overlap",
            musicAccessBounds.bottom <= notificationsBounds.top &&
                notificationsBounds.bottom <= searchBounds.top,
        )
    }

    @Test
    fun paused_library_scan_keeps_cached_rows_visible_and_offers_resume() {
        var resumeCalls = 0
        val folder = LibrarySource(
            id = "tree:test",
            kind = LibrarySourceKind.FOLDER,
            displayName = "Test music",
            scanState = LibraryScanState.PAUSED,
            trackCount = 1,
        )
        render(
            state = VesqenUiState(
                library = LibraryUiState(
                    musicAccess = MusicAccess.GRANTED,
                    tracks = sampleTracks.take(1),
                    sources = listOf(folder),
                    scanProgress = LibraryScanProgress(
                        sourceId = folder.id,
                        sourceName = folder.displayName,
                        scannedTrackCount = 42,
                        isPaused = true,
                    ),
                ),
            ),
            onResumeLibraryScan = { resumeCalls++ },
        )

        composeRule.onNodeWithTag("vesqen.library.track.1").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.library.resume-scan").performClick()

        composeRule.runOnIdle { assertEquals(1, resumeCalls) }
    }

    @Test
    fun removing_the_last_folder_closes_the_source_manager() {
        var removedSourceId: String? = null
        val folder = LibrarySource(
            id = "tree:test",
            kind = LibrarySourceKind.FOLDER,
            displayName = "Test music",
        )
        render(
            state = VesqenUiState(
                library = LibraryUiState(
                    musicAccess = MusicAccess.GRANTED,
                    sources = listOf(folder),
                ),
            ),
            onRemoveLibraryFolder = { removedSourceId = it },
        )

        composeRule.onNodeWithTag("vesqen.library.sources.manage").performClick()
        composeRule.onNodeWithTag("vesqen.library.source-manager").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.library.source.tree:test.remove").performClick()

        composeRule.runOnIdle { assertEquals(folder.id, removedSourceId) }
        composeRule.onNodeWithTag("vesqen.library.source-manager").assertDoesNotExist()
    }

    @Test
    fun settings_opens_a_real_about_surface_with_the_build_version() {
        render(grantedState(), versionName = "0.1.0", versionCode = 1)

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithText(context.getString(R.string.settings_version, "0.1.0"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.settings.about").performClick()

        composeRule.onNodeWithTag("vesqen.about").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_version_value, "0.1.0", 1))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_license_value)).assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.nav.settings").assertCountEquals(0)

        composeRule.onNodeWithTag("vesqen.about.back").performClick()
        composeRule.onNodeWithTag("vesqen.settings").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.nav.settings").assertIsSelected()
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
    fun track_details_keeps_its_header_stable_and_does_not_end_in_an_oversized_blank_region() {
        render(grantedState(tracks = sampleTracks), containerHeight = 640.dp)

        composeRule.onNodeWithTag("vesqen.library.track.1.more").performClick()
        val headerBefore = composeRule.onNodeWithTag("vesqen.track-details.header")
            .fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithTag("vesqen.track-details.add-to-queue").performScrollTo()
        composeRule.waitForIdle()

        val headerAfter = composeRule.onNodeWithTag("vesqen.track-details.header")
            .fetchSemanticsNode().boundsInRoot
        val viewportBottom = composeRule.onNodeWithTag("vesqen.track-details.content")
            .fetchSemanticsNode().boundsInRoot.bottom
        val finalActionBottom = composeRule.onNodeWithTag("vesqen.track-details.add-to-queue")
            .fetchSemanticsNode().boundsInRoot.bottom
        val maximumBottomGap = with(composeRule.density) { 16.dp.toPx() }

        assertEquals("The details title must stay fixed while metadata scrolls", headerBefore, headerAfter)
        assertTrue(
            "The final details action must end near the sheet bottom; " +
                "gap=${viewportBottom - finalActionBottom}px",
            viewportBottom - finalActionBottom <= maximumBottomGap,
        )
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
        composeRule.onNodeWithTag("vesqen.now.playback-order").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.orientation-toggle").assertIsDisplayed()
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
        var playbackOrderCalls = 0
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
            onCyclePlaybackOrder = { playbackOrderCalls++ },
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.previous").performClick()
        composeRule.onNodeWithTag("vesqen.now.play-pause").performClick()
        composeRule.onNodeWithTag("vesqen.now.next").performClick()
        composeRule.onNodeWithTag("vesqen.now.playback-order").performClick()
        composeRule.onNodeWithTag("vesqen.now.back").performClick()

        composeRule.runOnIdle {
            assertEquals(1, previousCalls)
            assertEquals(1, playPauseCalls)
            assertEquals(1, nextCalls)
            assertEquals(1, playbackOrderCalls)
        }
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
    }

    @Test
    fun one_playback_order_control_cycles_all_modes_with_its_state_exposed() {
        val playbackOrder = androidx.compose.runtime.mutableStateOf(PlaybackOrderMode.SEQUENTIAL)
        composeRule.setContent {
            VesqenTheme {
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
                            shuffleEnabled = playbackOrder.value in setOf(
                                PlaybackOrderMode.SHUFFLE,
                                PlaybackOrderMode.SHUFFLE_REPEAT_ALL,
                                PlaybackOrderMode.SHUFFLE_REPEAT_ONE,
                            ),
                            repeatMode = when (playbackOrder.value) {
                                PlaybackOrderMode.REPEAT_ALL,
                                PlaybackOrderMode.SHUFFLE_REPEAT_ALL -> PlaybackRepeatMode.ALL
                                PlaybackOrderMode.REPEAT_ONE,
                                PlaybackOrderMode.SHUFFLE_REPEAT_ONE -> PlaybackRepeatMode.ONE
                                else -> PlaybackRepeatMode.OFF
                            },
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
                    onCyclePlaybackOrder = {
                        playbackOrder.value = when (playbackOrder.value) {
                            PlaybackOrderMode.SEQUENTIAL -> PlaybackOrderMode.SHUFFLE
                            PlaybackOrderMode.SHUFFLE -> PlaybackOrderMode.REPEAT_ALL
                            PlaybackOrderMode.REPEAT_ALL -> PlaybackOrderMode.REPEAT_ONE
                            PlaybackOrderMode.REPEAT_ONE -> PlaybackOrderMode.SEQUENTIAL
                            PlaybackOrderMode.SHUFFLE_REPEAT_ALL,
                            PlaybackOrderMode.SHUFFLE_REPEAT_ONE -> PlaybackOrderMode.SEQUENTIAL
                        }
                    },
                    onRefreshConnectedOutputs = {},
                    motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                )
            }
        }

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onAllNodesWithTag("vesqen.now.playback-order").assertCountEquals(1)
        composeRule.onAllNodesWithTag("vesqen.now.shuffle").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.now.repeat").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.now.playback-order-feedback").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.playback_order_sequential),
            ),
        )
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(context.getString(R.string.playback_order)),
            ),
        )
        composeRule.runOnIdle {
            playbackOrder.value = PlaybackOrderMode.SHUFFLE_REPEAT_ALL
        }
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.playback_order_shuffle_repeat_all),
            ),
        )
        composeRule.onAllNodesWithTag("vesqen.now.playback-order-feedback").assertCountEquals(0)
        composeRule.runOnIdle {
            playbackOrder.value = PlaybackOrderMode.SEQUENTIAL
        }

        composeRule.onNodeWithTag("vesqen.now.playback-order").performClick()
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.playback_order_shuffle),
            ),
        )
        composeRule.waitUntil(timeoutMillis = 1_000) {
            composeRule.onAllNodesWithTag("vesqen.now.playback-order-feedback")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("vesqen.now.playback-order-feedback").assertIsDisplayed()
        val feedbackBounds = composeRule.onNodeWithTag("vesqen.now.playback-order-feedback")
            .fetchSemanticsNode()
            .boundsInRoot
        val orderControlBounds = composeRule.onNodeWithTag("vesqen.now.playback-order")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Playback-order feedback must float above, not cover, the footer control",
            feedbackBounds.bottom <= orderControlBounds.top,
        )
        composeRule.onNodeWithText(
            context.getString(
                R.string.playback_order_changed,
                context.getString(R.string.playback_order_shuffle),
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.playback-order").performClick()
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.playback_order_repeat_all),
            ),
        )
        composeRule.onNodeWithTag("vesqen.now.playback-order").performClick()
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.playback_order_repeat_one),
            ),
        )
        composeRule.onNodeWithTag("vesqen.now.playback-order").performClick()
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.playback_order_sequential),
            ),
        )
        composeRule.runOnIdle {
            playbackOrder.value = PlaybackOrderMode.SHUFFLE_REPEAT_ALL
        }
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.playback_order_shuffle_repeat_all),
            ),
        )
        composeRule.onNodeWithTag("vesqen.now.playback-order").performClick()
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.playback_order_sequential),
            ),
        )
        composeRule.runOnIdle {
            playbackOrder.value = PlaybackOrderMode.SHUFFLE_REPEAT_ONE
        }
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.playback_order_shuffle_repeat_one),
            ),
        )
        composeRule.onNodeWithTag("vesqen.now.playback-order").performClick()
        composeRule.onNodeWithTag("vesqen.now.playback-order").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.playback_order_sequential),
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
                    onCyclePlaybackOrder = {},
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
    fun mini_player_floats_above_compact_navigation_without_overlap() {
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
            containerWidth = 360.dp,
            containerHeight = 720.dp,
        )

        val miniPlayerBounds = composeRule.onNodeWithTag("vesqen.mini-player")
            .fetchSemanticsNode()
            .boundsInRoot
        val compactNavigationBounds = composeRule.onNodeWithTag("vesqen.navigation.compact")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "The floating mini-player must leave the compact navigation unobscured",
            miniPlayerBounds.bottom <= compactNavigationBounds.top,
        )
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
        composeRule.onNodeWithTag("vesqen.now.playback-order").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
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
        assertFooterActionsDoNotOverlap(
            "vesqen.now.playback-order",
            "vesqen.now.session-toggle",
            "vesqen.now.info",
        )
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
        composeRule.onNodeWithTag("vesqen.now.playback-order").assertIsDisplayed()
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
        composeRule.onNodeWithTag("vesqen.now.playback-order").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        assertArtworkStageIsUsable()
        assertFocusedNowControlsAreFullyVisible()
    }

    @Test
    fun focused_player_keeps_primary_controls_inside_a_short_landscape_window() {
        assumeTrue(
            "A 640dp landscape fixture requires a host viewport at least 640dp wide",
            composeRule.activity.resources.configuration.screenWidthDp >= 640,
        )
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
        composeRule.onNodeWithTag("vesqen.now.landscape-player").assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.destination_now)).assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.now.back").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.title").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.progress").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.playback-order").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        assertArtworkStageIsUsable()
        assertFocusedNowControlsAreFullyVisible()
        assertLandscapeIdentityStartsBelowTheCommandBand()
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
        composeRule.onNodeWithTag("vesqen.now.playback-order").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").assertIsDisplayed()
        assertArtworkClearsTransportDock()
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

    @Test
    fun library_album_browse_plays_the_visible_collection_as_its_queue() {
        var queuedTrackIds = emptyList<Long>()
        render(
            state = grantedState(tracks = sampleTracks),
            onPlayQueue = { tracks, _ -> queuedTrackIds = tracks.map(AudioTrack::id) },
        )

        composeRule.onNodeWithTag("vesqen.library.mode.albums").performClick()
        composeRule.onNodeWithText("Quiet Rooms").performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.play_all)).performClick()

        assertEquals(listOf(1L, 2L), queuedTrackIds)
    }

    @Test
    fun playlist_creation_stays_embedded_in_library() {
        var createdName = ""
        render(
            state = grantedState(tracks = sampleTracks).copy(
                library = LibraryUiState(
                    musicAccess = MusicAccess.GRANTED,
                    tracks = sampleTracks,
                    playlists = listOf(LibraryPlaylist(1, "Existing", emptyList(), 1, 1)),
                ),
            ),
            onCreatePlaylist = { createdName = it },
        )

        composeRule.onNodeWithTag("vesqen.library.mode.playlists").performClick()
        composeRule.onNodeWithTag("vesqen.library.playlist.create").performClick()
        composeRule.onNodeWithText(context.getString(R.string.playlist_name)).performTextInput("Night")
        composeRule.onNodeWithText(context.getString(R.string.create)).performClick()

        assertEquals("Night", createdName)
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
    }

    @Test
    fun playback_session_exposes_an_editable_queue_sheet() {
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = "Dawn Signal",
                    artist = "Mori",
                    durationMs = 245_000,
                    queueIndex = 0,
                    queueSize = 2,
                    queue = listOf(
                        PlaybackQueueItem(1, "Dawn Signal", "Mori", true),
                        PlaybackQueueItem(2, "Long Light", "Mori", false),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.session-toggle").performClick()
        composeRule.onNodeWithTag("vesqen.now.info.session").performClick()

        composeRule.onNodeWithTag("vesqen.queue.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.queue.item.0").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.queue.item.1").assertIsDisplayed()
    }

    private fun assertFocusedNowControlsAreFullyVisible() {
        val footerTags = mutableListOf(
            "vesqen.now.playback-order",
            "vesqen.now.session-toggle",
            "vesqen.now.info",
        )
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
                *footerTags.toTypedArray(),
            ),
        )
        assertFooterActionsDoNotOverlap(*footerTags.toTypedArray())

        val title = composeRule.onNodeWithTag("vesqen.now.title").fetchSemanticsNode()
        val primaryTransport = composeRule.onNodeWithTag("vesqen.now.play-pause").fetchSemanticsNode()
        assertTrue(
            "Now title must remain a single transport-row height",
            title.size.height <= primaryTransport.size.height,
        )
    }

    private fun assertArtworkClearsTransportDock() {
        val artworkBounds = composeRule
            .onNodeWithTag("vesqen.now.artwork-stage")
            .fetchSemanticsNode()
            .boundsInRoot
        val dockBounds = composeRule
            .onNodeWithTag("vesqen.now.transport-dock")
            .fetchSemanticsNode()
            .boundsInRoot
        val minimumClearancePx = with(composeRule.density) { 12.dp.toPx() }
        val epsilon = 1f

        assertTrue(
            "The transport dock must not cover the artwork frame",
            artworkBounds.bottom <= dockBounds.top - minimumClearancePx + epsilon,
        )
    }

    private fun assertLandscapeIdentityStartsBelowTheCommandBand() {
        val controlsBounds = composeRule
            .onNodeWithTag("vesqen.now.landscape-controls")
            .fetchSemanticsNode()
            .boundsInRoot
        val titleBounds = composeRule
            .onNodeWithTag("vesqen.now.title")
            .fetchSemanticsNode()
            .boundsInRoot
        val minimumTopBeatPx = with(composeRule.density) { 24.dp.toPx() }

        assertTrue(
            "Landscape track identity must clear the top command band",
            titleBounds.top >= controlsBounds.top + minimumTopBeatPx,
        )
    }

    private fun assertArtworkStageIsUsable() {
        val artworkNode = composeRule
            .onNodeWithTag("vesqen.now.artwork-stage")
            .fetchSemanticsNode()
        val visibleBounds = artworkNode.boundsInRoot
        val minimumArtworkPx = with(composeRule.density) { 48.dp.toPx() }
        val epsilon = 1f

        assertTrue(
            "The artwork stage must not be vertically clipped",
            abs(visibleBounds.height - artworkNode.size.height) <= epsilon,
        )
        assertTrue(
            "The artwork stage must remain a usable square",
            visibleBounds.width >= minimumArtworkPx &&
                visibleBounds.height >= minimumArtworkPx &&
                abs(visibleBounds.width - visibleBounds.height) <= epsilon,
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
            "vesqen.now.playback-order",
            "vesqen.now.session-toggle",
            "vesqen.now.info",
        )

        tags.forEach { tag ->
            val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            val visibleBounds = node.boundsInRoot
            assertTrue(
                "$tag must not be vertically clipped",
                abs(visibleBounds.height - node.size.height) <= epsilon,
            )
            if (tag != "vesqen.now.title") {
                assertTrue(
                    "$tag must not be horizontally clipped",
                    abs(visibleBounds.width - node.size.width) <= epsilon,
                )
            }
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
                    "$tag must preserve the 48dp minimum touch target; " +
                        "actual=${touchBounds.width}x${touchBounds.height}px, " +
                        "minimum=${minimumTouchTargetPx}px",
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
            "vesqen.now.playback-order",
            "vesqen.now.session-toggle",
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
        onCyclePlaybackOrder: () -> Unit = {},
        onRequestMusicAccess: () -> Unit = {},
        onOpenAppSettings: () -> Unit = {},
        onOpenNotificationSettings: () -> Unit = {},
        onAddLibraryFolder: () -> Unit = {},
        onPlayQueue: (List<AudioTrack>, Int) -> Unit = { _, _ -> },
        onCreatePlaylist: (String) -> Unit = {},
        onRemoveLibraryFolder: (String) -> Unit = {},
        onResumeLibraryScan: () -> Unit = {},
        containerWidth: Dp? = null,
        containerHeight: Dp = 720.dp,
        fontScale: Float? = null,
        darkTheme: Boolean = true,
        motionPolicy: VesqenMotionPolicy = VesqenMotionPolicy(reduceMotion = true),
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
    ) {
        composeRule.setContent {
            VesqenTheme(darkTheme = darkTheme) {
                val app: @Composable () -> Unit = {
                    VesqenAppContent(
                        state = state,
                        onRequestMusicAccess = onRequestMusicAccess,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onRescan = {},
                        onTrackSelected = {},
                        onPlayQueue = onPlayQueue,
                        onCreatePlaylist = onCreatePlaylist,
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onSeek = {},
                        onCyclePlaybackOrder = onCyclePlaybackOrder,
                        onRefreshConnectedOutputs = {},
                        onAddLibraryFolder = onAddLibraryFolder,
                        onRemoveLibraryFolder = onRemoveLibraryFolder,
                        onResumeLibraryScan = onResumeLibraryScan,
                        motionPolicy = motionPolicy,
                        versionName = versionName,
                        versionCode = versionCode,
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
