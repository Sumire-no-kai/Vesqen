package io.github.sumirenokai.vesqen

import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecorder
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecordingState
import io.github.sumirenokai.vesqen.library.LibraryScanProgress
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import io.github.sumirenokai.vesqen.playback.PlaybackOrderMode
import io.github.sumirenokai.vesqen.playback.PlaybackQueueItem
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.PlaybackRepeatMode
import io.github.sumirenokai.vesqen.playback.AudioFormatSummary
import io.github.sumirenokai.vesqen.playback.UsbHardwareIdentity
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.telemetry.FakePlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.PlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventKind
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventSeverity
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricSelection
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import io.github.sumirenokai.vesqen.telemetry.TelemetryWindow
import io.github.sumirenokai.vesqen.telemetry.UsbAudioInterfaceReading
import io.github.sumirenokai.vesqen.telemetry.UsbHostDeviceReading
import io.github.sumirenokai.vesqen.telemetry.UsbInventoryReading
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.VesqenAppContent
import io.github.sumirenokai.vesqen.ui.VesqenUiState
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardPreferences
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardPreferencesRepository
import io.github.sumirenokai.vesqen.ui.chain.ChainMetricViewMode
import io.github.sumirenokai.vesqen.ui.chain.ChainUnitDisplayMode
import io.github.sumirenokai.vesqen.ui.chain.InMemoryChainDashboardPreferencesRepository
import io.github.sumirenokai.vesqen.ui.chain.DiagnosticExportFeedback
import io.github.sumirenokai.vesqen.ui.chain.formatSeconds
import io.github.sumirenokai.vesqen.ui.chain.formatTelemetryReading
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import io.github.sumirenokai.vesqen.verification.OutputVerificationRegistryState
import io.github.sumirenokai.vesqen.verification.OutputVerificationMatch
import io.github.sumirenokai.vesqen.verification.OutputVerificationRecord
import io.github.sumirenokai.vesqen.verification.OutputVerificationResult
import io.github.sumirenokai.vesqen.verification.VerificationPcmFormat
import kotlin.math.abs
import kotlin.math.min
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VesqenAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fixtureDensity: Density

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
        composeRule.onAllNodesWithTag("vesqen.chain.diagnostics").assertCountEquals(0)
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
        composeRule.onNodeWithTag("vesqen.library.menu").performClick()
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
        val minimumTouchTargetPx = with(fixtureDensity) { 48.dp.toPx() }

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
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.about"))
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
        val detailsContentBounds = composeRule.onNodeWithTag("vesqen.track-details.content")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The details title must be rendered inside the sheet content viewport",
            composeRule.onAllNodesWithText("Dawn Signal").fetchSemanticsNodes().any { node ->
                node.boundsInRoot.top >= detailsContentBounds.top &&
                    node.boundsInRoot.bottom <= detailsContentBounds.bottom
            },
        )
        composeRule.onNodeWithText("Quiet Rooms").assertIsDisplayed()
        composeRule.onNodeWithText("4:05").assertIsDisplayed()
        composeRule.onAllNodesWithText("96 kHz").assertCountEquals(0)
    }

    @Test
    fun track_details_keeps_its_header_stable_and_does_not_end_in_an_oversized_blank_region() {
        val detailedTrack = sampleTracks.first().copy(
            albumArtist = "Mori",
            trackNumber = 3,
            discNumber = 1,
            year = 2026,
            genre = "Ambient",
            fileName = "dawn-signal.flac",
            folderName = "Music/Quiet Rooms",
            fileSizeBytes = 42_000_000,
            mimeType = "audio/flac",
            codec = "FLAC",
            channelCount = 2,
            bitDepth = 24,
            sampleRateHz = 96_000,
            bitrate = 4_608_000,
            playCount = 5,
        )
        render(grantedState(tracks = listOf(detailedTrack)), containerHeight = 640.dp)

        composeRule.onNodeWithTag("vesqen.library.track.1.more").performClick()
        val headerBefore = composeRule.onNodeWithTag("vesqen.track-details.header")
            .fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithTag("vesqen.track-details.add-to-queue").performScrollTo()
        composeRule.onNodeWithTag("vesqen.track-details.content").performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        val headerAfter = composeRule.onNodeWithTag("vesqen.track-details.header")
            .fetchSemanticsNode().boundsInRoot
        val viewportBottom = composeRule.onNodeWithTag("vesqen.track-details.content")
            .fetchSemanticsNode().boundsInRoot.bottom
        val finalActionBottom = composeRule.onNodeWithTag("vesqen.track-details.add-to-queue")
            .fetchSemanticsNode().boundsInRoot.bottom
        val maximumBottomGap = with(fixtureDensity) { 16.dp.toPx() }

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
        chainNode("vesqen.chain.summary", "vesqen.chain.summary-list").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chain_system_mixed_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText("BIT-PERFECT ACTIVE").assertCountEquals(0)
        composeRule.onAllNodesWithText("BIT-PERFECT VERIFIED").assertCountEquals(0)
    }

    @Test
    fun buffering_playback_keeps_pause_action_in_mini_player_and_now() {
        val active = activePlaybackState()
        render(active.copy(playback = active.playback.copy(isPlaying = false, showsPauseAction = true)))

        composeRule.onNodeWithTag("vesqen.mini-player.play-pause")
            .assertContentDescriptionEquals(context.getString(R.string.pause))
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.play-pause")
            .assertContentDescriptionEquals(context.getString(R.string.pause))
    }

    @Test
    fun focused_player_position_refresh_stops_in_background_and_resumes_on_return() {
        val refreshes = AtomicInteger()
        val active = activePlaybackState()
        render(
            active.copy(playback = active.playback.copy(isPlaying = true)),
            onRefreshPlaybackPosition = { refreshes.incrementAndGet() },
        )
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.waitUntil(5_000) { refreshes.get() > 0 }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        try {
            val stoppedCount = refreshes.get()
            // Observe more than two production ticker intervals while the activity is stopped.
            SystemClock.sleep(1_200)
            assertEquals(stoppedCount, refreshes.get())
        } finally {
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        val resumedCount = refreshes.get()
        composeRule.waitUntil(5_000) { refreshes.get() > resumedCount }
    }

    @Test
    fun chain_observation_runs_only_while_visible_and_advanced_uses_saved_selection() {
        val telemetry = FakePlaybackTelemetry(chainTelemetrySnapshot())
        render(
            state = activePlaybackState(),
            playbackTelemetry = telemetry,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        composeRule.waitUntil(5_000) { telemetry.activeObservationCount == 1 }
        assertEquals(summaryMetricIds, (telemetry.observationHistory.last().selection as TelemetryMetricSelection.Explicit).metricIds)

        openAdvancedChain()
        composeRule.waitUntil(5_000) {
            telemetry.observationHistory.lastOrNull()?.selection is TelemetryMetricSelection.Explicit
        }
        val advancedSelection = telemetry.observationHistory.last().selection as TelemetryMetricSelection.Explicit
        assertEquals(summaryMetricIds, advancedSelection.metricIds)

        composeRule.onNodeWithTag("vesqen.chain.show-summary").performClick()
        composeRule.onNodeWithTag("vesqen.chain.back").performClick()
        composeRule.waitUntil(5_000) { telemetry.activeObservationCount == 0 }
        composeRule.onNodeWithTag("vesqen.settings").assertIsDisplayed()
    }

    @Test
    fun chain_advanced_observes_summary_defaults_and_refreshes_wide_path_for_a_repeated_track() {
        val telemetry = FakePlaybackTelemetry(chainTelemetrySnapshot(codecLabel = "FLAC"))
        val selectedMetricId = TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT
        val preferences = InMemoryChainDashboardPreferencesRepository(
            ChainDashboardPreferences(selectedMetricIds = listOf(selectedMetricId)),
        )
        render(
            state = activePlaybackState(),
            playbackTelemetry = telemetry,
            chainPreferencesRepository = preferences,
            containerWidth = 840.dp,
            containerHeight = 720.dp,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()
        composeRule.waitUntil(5_000) {
            (telemetry.observationHistory.lastOrNull()?.selection as? TelemetryMetricSelection.Explicit)
                ?.metricIds == summaryMetricIds + selectedMetricId
        }
        val advancedSelection = telemetry.observationHistory.last().selection as TelemetryMetricSelection.Explicit
        assertEquals(summaryMetricIds + selectedMetricId, advancedSelection.metricIds)
        composeRule.onNodeWithText("FLAC", substring = true).assertIsDisplayed()

        telemetry.publish(
            chainTelemetrySnapshot(
                codecLabel = "ALAC",
                playbackSessionId = "instrumentation-session-repeat",
            ),
        )
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("ALAC", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("ALAC", substring = true).assertIsDisplayed()
    }

    @Test
    fun chain_advanced_shows_recent_route_and_error_events_with_session_scope() {
        val elapsedMs = SystemClock.elapsedRealtime()
        val telemetry = FakePlaybackTelemetry(
            chainTelemetrySnapshot(
                recentEvents = listOf(
                    TelemetryEvent(
                        sequence = 7,
                        kind = TelemetryEventKind.ROUTE_CHANGED,
                        severity = TelemetryEventSeverity.INFO,
                        occurredAtEpochMs = System.currentTimeMillis(),
                        occurredAtElapsedRealtimeMs = elapsedMs,
                        code = "route.devices_changed",
                        playbackSessionId = "instrumentation-session",
                        relatedMetricIds = setOf(TelemetryMetricCatalog.ROUTE_CONNECTED_TYPES),
                    ),
                    TelemetryEvent(
                        sequence = 8,
                        kind = TelemetryEventKind.ERROR,
                        severity = TelemetryEventSeverity.ERROR,
                        occurredAtEpochMs = System.currentTimeMillis(),
                        occurredAtElapsedRealtimeMs = elapsedMs,
                        code = "error.player.code_1001",
                        playbackSessionId = "earlier-session",
                        relatedMetricIds = setOf(TelemetryMetricCatalog.PLAYBACK_LAST_ERROR_CODE),
                    ),
                ),
            ),
        )
        render(
            state = activePlaybackState(),
            playbackTelemetry = telemetry,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()
        chainNode("vesqen.chain.recent-events").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.chain.event.7").assertIsDisplayed()
        composeRule.onNodeWithText("route.devices_changed").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chain_event_scope_current), substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.chain.event.8").assertIsDisplayed()
        composeRule.onNodeWithText("error.player.code_1001").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chain_event_scope_earlier), substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun chain_unit_control_persists_raw_units_for_values_and_chart_descriptions() {
        val epochMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
        val source = TelemetryDataSource(TelemetrySourceId("test.telemetry"))
        val throughputReading = TelemetryReading.Decimal(1_536_000.0, TelemetryUnit.BITS_PER_SECOND)
        val telemetry = FakePlaybackTelemetry(
            chainTelemetrySnapshot().let { snapshot ->
                snapshot.copy(
                    metrics = snapshot.metrics + TelemetryMetric(
                        id = TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT,
                        section = TelemetrySection.PROCESS,
                        evidence = TelemetryEvidence.Measured(
                            reading = throughputReading,
                            source = source,
                            observedAtEpochMs = epochMs,
                            observedAtElapsedRealtimeMs = elapsedMs,
                        ),
                    ),
                )
            },
        )
        val preferences = InMemoryChainDashboardPreferencesRepository(
            ChainDashboardPreferences(
                selectedMetricIds = listOf(
                    TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
                    TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT,
                ),
                viewMode = ChainMetricViewMode.CHART,
            ),
        )
        render(
            state = activePlaybackState(),
            playbackTelemetry = telemetry,
            chainPreferencesRepository = preferences,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()
        chainNode("vesqen.chain.control.settings").performScrollTo().performClick()
        composeRule.onNodeWithTag("vesqen.chain.control.units").performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.chain_unit_display_raw)).performClick()

        composeRule.runOnIdle {
            assertEquals(ChainUnitDisplayMode.RAW, preferences.load().unitDisplayMode)
        }
        val expectedSampleRate = formatTelemetryReading(
            context,
            TelemetryReading.Integer(96_000, TelemetryUnit.HERTZ),
            ChainUnitDisplayMode.RAW,
        )
        val expectedThroughput = formatTelemetryReading(
            context,
            throughputReading,
            ChainUnitDisplayMode.RAW,
        )
        chainNode("vesqen.chain.metric-value.source.sample_rate", useUnmergedTree = true)
            .performScrollTo()
            .assertTextEquals(expectedSampleRate)
        chainNode("vesqen.chain.chart.process.data_source_read_throughput", useUnmergedTree = true)
            .performScrollTo()
            .assert(
                SemanticsMatcher("chart description uses the selected raw units") { node ->
                    node.config[SemanticsProperties.ContentDescription].any { expectedThroughput in it }
                },
            )
    }

    @Test
    fun navigation_animates_player_vertically_and_contextual_chain_horizontally_in_both_directions() {
        render(state = activePlaybackState(), motionPolicy = VesqenMotionPolicy(reduceMotion = false))
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.mainClock.advanceTimeBy(96)
        val enteringPlayer = composeRule.onNodeWithTag("vesqen.now.player-page").fetchSemanticsNode().positionInRoot
        composeRule.mainClock.advanceTimeBy(300)
        val playerRest = composeRule.onNodeWithTag("vesqen.now.player-page").fetchSemanticsNode().positionInRoot
        assertTrue("Player must visibly rise into place", enteringPlayer.y > playerRest.y + 12f)

        composeRule.onNodeWithTag("vesqen.now.open-chain").performClick()
        composeRule.mainClock.advanceTimeBy(96)
        val enteringChain = composeRule.onNodeWithTag("vesqen.chain").fetchSemanticsNode().positionInRoot
        composeRule.mainClock.advanceTimeBy(300)
        val chainRest = composeRule.onNodeWithTag("vesqen.chain").fetchSemanticsNode().positionInRoot
        assertTrue("Contextual Chain must enter from the side", enteringChain.x > chainRest.x + 12f)

        composeRule.onNodeWithTag("vesqen.chain.back").performClick()
        composeRule.mainClock.advanceTimeBy(96)
        val leavingChain = composeRule.onNodeWithTag("vesqen.chain").fetchSemanticsNode().positionInRoot
        assertTrue("Chain return must reverse the detail path: resting=$chainRest, leaving=$leavingChain",
            leavingChain.x > chainRest.x + 12f)
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onAllNodesWithTag("vesqen.chain").assertCountEquals(0)

        composeRule.onNodeWithTag("vesqen.now.back").performClick()
        composeRule.mainClock.advanceTimeBy(96)
        val leavingPlayer = composeRule.onNodeWithTag("vesqen.now.player-page").fetchSemanticsNode().positionInRoot
        assertTrue("Player must visibly retreat downward", leavingPlayer.y > playerRest.y + 12f)
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onAllNodesWithTag("vesqen.now.player-page").assertCountEquals(0)
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").assertIsDisplayed()
    }

    @Test
    fun reduced_motion_changes_destinations_without_spatial_movement() {
        render(state = activePlaybackState(), motionPolicy = VesqenMotionPolicy(reduceMotion = true))
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.mainClock.advanceTimeBy(32)
        val during = composeRule.onNodeWithTag("vesqen.now.player-page").fetchSemanticsNode().positionInRoot
        composeRule.mainClock.advanceTimeBy(120)
        val after = composeRule.onNodeWithTag("vesqen.now.player-page").fetchSemanticsNode().positionInRoot
        assertEquals(after.x, during.x, 1f)
        assertEquals(after.y, during.y, 1f)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun chain_decoder_identifier_uses_full_width_at_320dp() = assertDecoderIdentifierLayout(1f, 1)

    @Test
    fun chain_decoder_identifier_remains_complete_at_320dp_with_double_text() = assertDecoderIdentifierLayout(2f, 2)

    @Test
    fun chain_decoder_identifier_remains_complete_at_320dp_with_130_percent_text() =
        assertDecoderIdentifierLayout(1.3f, 2)

    @Test
    fun chain_decoder_identifier_remains_complete_at_320dp_with_150_percent_text() =
        assertDecoderIdentifierLayout(1.5f, 2)

    @Test
    fun chain_core_stacks_and_keeps_aged_evidence_on_one_line_at_130_percent_text() {
        val epochMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime() - 120_000
        val source = TelemetryDataSource(TelemetrySourceId("test.telemetry"))
        val snapshot = chainTelemetrySnapshot().copy(
            metrics = listOf(
                TelemetryMetric(
                    id = TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
                    section = TelemetrySection.SOURCE,
                    evidence = TelemetryEvidence.Estimated(
                        reading = TelemetryReading.Integer(96_000, TelemetryUnit.HERTZ),
                        source = source,
                        observedAtEpochMs = epochMs,
                        observedAtElapsedRealtimeMs = elapsedMs,
                        methodId = "test.estimate",
                    ),
                ),
                TelemetryMetric(
                    id = TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE,
                    section = TelemetrySection.PLAYBACK,
                    evidence = TelemetryEvidence.Measured(
                        reading = TelemetryReading.Integer(96_000, TelemetryUnit.HERTZ),
                        source = source,
                        observedAtEpochMs = epochMs,
                        observedAtElapsedRealtimeMs = elapsedMs,
                    ),
                ),
            ),
        )
        render(
            state = activePlaybackState(),
            playbackTelemetry = FakePlaybackTelemetry(snapshot),
            containerWidth = 360.dp,
            containerHeight = 720.dp,
            fontScale = 1.3f,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()

        val sourceBounds = composeRule.onNodeWithTag("vesqen.chain.core.source.sample_rate")
            .fetchSemanticsNode().boundsInRoot
        val playbackBounds = composeRule.onNodeWithTag("vesqen.chain.core.playback.audio_track_sample_rate")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Large text must give paired core facts the same full-width column",
            sourceBounds.left,
            playbackBounds.left,
            1f,
        )
        assertTrue("Playback facts must follow source facts vertically", playbackBounds.top > sourceBounds.top)

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(
            "vesqen.chain.core-evidence.playback.audio_track_sample_rate",
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount)
        assertTrue(
            "Core evidence annotation must not be clipped: " +
                "size=${layout.size}, overflowWidth=${layout.didOverflowWidth}, " +
                "overflowHeight=${layout.didOverflowHeight}, source=$sourceBounds, playback=$playbackBounds",
            !layout.hasVisualOverflow,
        )
    }

    private fun assertDecoderIdentifierLayout(fontScale: Float, maximumLines: Int) {
        val name = "c2.android.flac.decoder"
        val snapshot = chainTelemetrySnapshot().let { snapshot ->
            snapshot.copy(metrics = snapshot.metrics + TelemetryMetric(
                id = TelemetryMetricCatalog.DECODER_NAME,
                section = TelemetrySection.DECODER,
                evidence = TelemetryEvidence.Measured(
                    reading = TelemetryReading.Text(name),
                    source = TelemetryDataSource(TelemetrySourceId("media3.decoder")),
                    observedAtEpochMs = snapshot.capturedAtEpochMs,
                    observedAtElapsedRealtimeMs = snapshot.capturedAtElapsedRealtimeMs,
                ),
            ))
        }
        render(
            state = activePlaybackState(), playbackTelemetry = FakePlaybackTelemetry(snapshot),
            containerWidth = 320.dp, containerHeight = 640.dp, fontScale = fontScale,
        )
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()
        val value = chainNode("vesqen.chain.metric-value.decoder.name", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().assertTextEquals(name)
        val results = mutableListOf<TextLayoutResult>()
        value.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Decoder identifier must wrap using the full card width", results.single().lineCount <= maximumLines)
        assertTrue("Decoder identifier must not be clipped", !results.single().hasVisualOverflow)
        val card = composeRule.onNodeWithTag("vesqen.chain.metric.decoder.name").fetchSemanticsNode()
        assertTrue("Identifier must not occupy the narrow numeric column",
            value.fetchSemanticsNode().size.width > card.size.width * .8f)
    }

    @Test
    fun chain_chart_exposes_segment_confidence_source_and_window() {
        val metricId = TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT
        val initial = chainTelemetrySnapshot().let { snapshot ->
            snapshot.copy(metrics = snapshot.metrics + TelemetryMetric(
                id = metricId,
                section = TelemetrySection.PROCESS,
                evidence = TelemetryEvidence.Measured(
                    reading = TelemetryReading.Decimal(48_000.0, TelemetryUnit.BITS_PER_SECOND),
                    source = TelemetryDataSource(TelemetrySourceId("media3.data_source")),
                    observedAtEpochMs = snapshot.capturedAtEpochMs,
                    observedAtElapsedRealtimeMs = snapshot.capturedAtElapsedRealtimeMs,
                ),
            ))
        }
        val telemetry = FakePlaybackTelemetry(initial)
        val preferences = InMemoryChainDashboardPreferencesRepository(
            ChainDashboardPreferences(
                selectedMetricIds = listOf(metricId),
                viewMode = ChainMetricViewMode.CHART,
            ),
        )
        render(
            state = activePlaybackState(),
            playbackTelemetry = telemetry,
            chainPreferencesRepository = preferences,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()

        val nextEpochMs = initial.capturedAtEpochMs + 5_000
        val nextElapsedMs = initial.capturedAtElapsedRealtimeMs + 5_000
        val derivedThroughput = TelemetryMetric(
            id = metricId,
            section = TelemetrySection.PROCESS,
            evidence = TelemetryEvidence.Derived(
                reading = TelemetryReading.Decimal(96_000.0, TelemetryUnit.BITS_PER_SECOND),
                source = TelemetryDataSource(TelemetrySourceId("media3.data_source")),
                observedAtEpochMs = nextEpochMs,
                observedAtElapsedRealtimeMs = nextElapsedMs,
                window = TelemetryWindow(
                    startedAtEpochMs = nextEpochMs - 5_000,
                    endedAtEpochMs = nextEpochMs,
                    startedAtElapsedRealtimeMs = nextElapsedMs - 5_000,
                    endedAtElapsedRealtimeMs = nextElapsedMs,
                ),
                calculationId = "process.read_throughput.window",
                inputMetricIds = setOf(metricId),
                operands = mapOf("io.bytes" to 60_000.0, "window.seconds" to 5.0),
            ),
        )
        telemetry.publish(
            initial.copy(
                capturedAtEpochMs = nextEpochMs,
                capturedAtElapsedRealtimeMs = nextElapsedMs,
                metrics = initial.metrics.map { metric ->
                    if (metric.id == metricId) {
                        derivedThroughput
                    } else {
                        metric
                    }
                },
            ),
        )

        val evidence = chainNode(
            "vesqen.chain.chart-evidence.process.data_source_read_throughput",
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
        fun evidenceText(node: androidx.compose.ui.semantics.SemanticsNode): String =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text } +
                node.children.joinToString(" ") { evidenceText(it) }
        val evidenceText = evidenceText(evidence.fetchSemanticsNode())
        assertTrue(evidenceText.contains(context.getString(R.string.chain_confidence_measured)))
        assertTrue(evidenceText.contains(context.getString(R.string.chain_confidence_derived)))
        assertTrue(evidenceText.contains(context.getString(R.string.chain_source_media3_data_source)))
        assertTrue(
            evidenceText.contains(
                context.getString(
                    R.string.chain_window_value,
                    formatSeconds(context, 5.0),
                ),
            ),
        )
    }

    @Test
    fun chain_usb_details_expose_device_and_audio_interface_descriptors() {
        val epochMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
        val inventory = TelemetryMetric(
            id = TelemetryMetricCatalog.USB_DEVICE_INVENTORY,
            section = TelemetrySection.USB,
            evidence = TelemetryEvidence.Measured(
                reading = TelemetryReading.UsbInventory(
                    UsbInventoryReading(
                        hostDevices = listOf(
                            UsbHostDeviceReading(
                                snapshotKey = "usb-1",
                                manufacturerName = "Acme",
                                productName = "Reference DAC",
                                vendorId = 0x1234,
                                productId = 0xabcd,
                                permissionGranted = true,
                                audioInterfaces = listOf(
                                    UsbAudioInterfaceReading(
                                        interfaceClass = 0x01,
                                        interfaceSubclass = 0x02,
                                        interfaceProtocol = 0x20,
                                    ),
                                ),
                            ),
                        ),
                        audioOutputEndpoints = emptyList(),
                    ),
                ),
                source = TelemetryDataSource(TelemetrySourceId("android.usb_public_api")),
                observedAtEpochMs = epochMs,
                observedAtElapsedRealtimeMs = elapsedMs,
            ),
        )
        val telemetry = FakePlaybackTelemetry(
            chainTelemetrySnapshot().let { snapshot ->
                snapshot.copy(metrics = snapshot.metrics + inventory)
            },
        )
        val preferences = InMemoryChainDashboardPreferencesRepository(
            ChainDashboardPreferences(
                selectedMetricIds = listOf(TelemetryMetricCatalog.USB_DEVICE_INVENTORY),
                viewMode = ChainMetricViewMode.DETAILED,
            ),
        )
        render(
            state = activePlaybackState(),
            playbackTelemetry = telemetry,
            chainPreferencesRepository = preferences,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()
        chainNode(
            "vesqen.chain.usb.host.0.identity",
            useUnmergedTree = true,
        ).performScrollTo().assertTextEquals(
            context.getString(
                R.string.chain_usb_host_identity,
                "0x1234",
                "0xABCD",
                context.getString(R.string.chain_usb_permission_granted),
            ),
        )
        composeRule.onNodeWithTag(
            "vesqen.chain.usb.host.0.interface.0",
            useUnmergedTree = true,
        ).assertTextEquals(
            context.getString(
                R.string.chain_usb_audio_interface,
                1,
                "0x01",
                "0x02",
                "0x20",
            ),
        )
    }

    @Test
    fun diagnostic_recording_survives_leaving_chain_until_explicit_stop_and_clear() {
        val telemetry = FakePlaybackTelemetry(chainTelemetrySnapshot())
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = DiagnosticRecorder(telemetry, ownerScope)
        var exportRequests = 0
        try {
            render(
                state = activePlaybackState(),
                playbackTelemetry = telemetry,
                diagnosticRecorder = recorder,
                onRequestDiagnosticExport = { exportRequests++ },
            )

            composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
            composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
            openAdvancedChain()
            chainNode("vesqen.chain.diagnostics.start").performClick()
            composeRule.waitUntil(5_000) { recorder.state.value is DiagnosticRecordingState.Active }

            composeRule.onNodeWithTag("vesqen.chain.show-summary").performClick()
            composeRule.onNodeWithTag("vesqen.chain.back").performClick()
            composeRule.waitUntil(5_000) { telemetry.activeObservationCount == 1 }
            assertTrue(recorder.state.value is DiagnosticRecordingState.Active)

            composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
            openAdvancedChain()
            chainNode("vesqen.chain.diagnostics.stop").performClick()
            composeRule.waitUntil(5_000) { recorder.state.value is DiagnosticRecordingState.Stopped }

            composeRule.onNodeWithTag("vesqen.chain.diagnostics.export").performClick()
            composeRule.runOnIdle {
                assertEquals(1, exportRequests)
                assertTrue(recorder.state.value is DiagnosticRecordingState.Stopped)
            }
            composeRule.onNodeWithTag("vesqen.chain.diagnostics.clear").performClick()
            composeRule.runOnIdle {
                assertTrue(recorder.state.value is DiagnosticRecordingState.Stopped)
            }
            composeRule.onNodeWithTag("vesqen.chain.diagnostics.clear-confirm")
                .assertIsDisplayed()
                .performClick()
            composeRule.runOnIdle {
                assertEquals(DiagnosticRecordingState.Idle, recorder.state.value)
            }
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun retained_diagnostic_remains_actionable_from_chain_empty_state_after_playback_stops() {
        val telemetry = FakePlaybackTelemetry(chainTelemetrySnapshot())
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = DiagnosticRecorder(telemetry, ownerScope)
        val currentState = mutableStateOf(activePlaybackState())
        var exportRequests = 0
        try {
            render(
                state = currentState.value,
                stateProvider = { currentState.value },
                playbackTelemetry = telemetry,
                diagnosticRecorder = recorder,
                onRequestDiagnosticExport = { exportRequests++ },
            )

            composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
            composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
            openAdvancedChain()
            chainNode("vesqen.chain.diagnostics.start").performClick()
            composeRule.waitUntil(5_000) { recorder.state.value is DiagnosticRecordingState.Active }

            composeRule.runOnIdle {
                currentState.value = grantedState(tracks = sampleTracks)
            }
            composeRule.onNodeWithText(context.getString(R.string.chain_empty_title)).assertIsDisplayed()
            composeRule.onNodeWithTag("vesqen.chain.empty-retained-diagnostic").assertIsDisplayed()
            chainNode("vesqen.chain.diagnostics.stop", "vesqen.chain.empty-retained-diagnostic").assertIsDisplayed()

            val absentSinceElapsedMs = SystemClock.elapsedRealtime() + 10
            telemetry.publish(
                TelemetrySnapshot.empty(
                    capturedAtEpochMs = System.currentTimeMillis() + 10,
                    capturedAtElapsedRealtimeMs = absentSinceElapsedMs,
                ),
            )
            composeRule.waitUntil(5_000) {
                (recorder.state.value as? DiagnosticRecordingState.Active)
                    ?.progress
                    ?.snapshotCount == 1
            }
            telemetry.publish(
                TelemetrySnapshot.empty(
                    capturedAtEpochMs = System.currentTimeMillis() + 2_510,
                    capturedAtElapsedRealtimeMs = absentSinceElapsedMs + 2_500,
                ),
            )
            composeRule.waitUntil(5_000) { recorder.state.value is DiagnosticRecordingState.Stopped }

            chainNode("vesqen.chain.diagnostics.export", "vesqen.chain.empty-retained-diagnostic").performClick()
            composeRule.runOnIdle { assertEquals(1, exportRequests) }
            composeRule.onNodeWithTag("vesqen.chain.diagnostics.clear").performClick()
            composeRule.runOnIdle {
                assertTrue(recorder.state.value is DiagnosticRecordingState.Stopped)
            }
            composeRule.onNodeWithTag("vesqen.chain.diagnostics.clear-confirm")
                .assertIsDisplayed()
                .performClick()
            composeRule.runOnIdle {
                assertEquals(DiagnosticRecordingState.Idle, recorder.state.value)
            }
            composeRule.onAllNodesWithTag("vesqen.chain.diagnostics").assertCountEquals(0)
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun chain_source_title_is_available_from_settings_and_player() {
        val active = activePlaybackState()
        render(active, playbackTelemetry = FakePlaybackTelemetry(chainTelemetrySnapshot()))
        val expected = context.getString(R.string.chain_current_source, active.playback.title)
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        composeRule.onNodeWithTag("vesqen.chain.current-source").assertTextEquals(expected)
        openAdvancedChain()
        composeRule.onNodeWithTag("vesqen.chain.current-source").assertTextEquals(expected)
        composeRule.onNodeWithTag("vesqen.chain.back").performClick()
        composeRule.onNodeWithTag("vesqen.chain.back").performClick()
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.open-chain").performClick()
        openAdvancedChain()
        composeRule.onNodeWithTag("vesqen.chain.current-source").assertTextEquals(expected)
    }

    @Test
    fun chain_advanced_keeps_header_actions_separate_at_320_by_480_with_large_text() {
        render(
            state = activePlaybackState(),
            playbackTelemetry = FakePlaybackTelemetry(chainTelemetrySnapshot()),
            containerWidth = 320.dp,
            containerHeight = 480.dp,
            fontScale = 2f,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()
        val viewControl = chainNode("vesqen.chain.control.view")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.chain_view_detailed),
                ),
            )
        composeRule.onNodeWithText(context.getString(R.string.chain_view_mode)).assertIsDisplayed()

        val title = composeRule.onNodeWithTag("vesqen.chain.title").fetchSemanticsNode().boundsInRoot
        val summary = composeRule.onNodeWithTag("vesqen.chain.show-summary").fetchSemanticsNode().boundsInRoot
        assertTrue("Chain title must not overlap its summary action", title.right <= summary.left)

        composeRule.onNodeWithTag("vesqen.chain.control.settings").performScrollTo().performClick()
        val controls = composeRule.onNodeWithTag("vesqen.chain.dashboard-controls")
            .fetchSemanticsNode().boundsInRoot
        val viewNode = viewControl.fetchSemanticsNode()
        val view = viewNode.boundsInRoot
        val refreshNode = composeRule.onNodeWithTag("vesqen.chain.control.refresh").fetchSemanticsNode()
        val unitsNode = composeRule.onNodeWithTag("vesqen.chain.control.units").fetchSemanticsNode()
        assertTrue("320dp controls must stay inside the available width", view.left >= controls.left)
        assertTrue("320dp controls must stay inside the available width", view.right <= controls.right)
        assertTrue("Large text selectors must use the available width", abs(viewNode.size.width - controls.width) <= 1f)
        assertTrue("Large text selectors must stack so their labels remain readable",
            viewNode.positionInRoot.y + viewNode.size.height <= refreshNode.positionInRoot.y)
        // English at 2x can put the lower controls below the viewport. Clipped empty
        // bounds are not layout positions; compare the actual geometry, then scroll to them.
        assertTrue("320dp controls must stack instead of overlap",
            viewNode.positionInRoot.y + viewNode.size.height <= unitsNode.positionInRoot.y)
        chainNode("vesqen.chain.control.units").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun chain_advanced_uses_two_metric_columns_at_600dp() {
        render(
            state = activePlaybackState(),
            playbackTelemetry = FakePlaybackTelemetry(chainTelemetrySnapshot()),
            containerWidth = 600.dp,
            containerHeight = 720.dp,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()

        chainNode("vesqen.chain.metric.source.container")
        val container = composeRule.onNodeWithTag("vesqen.chain.metric.source.container")
            .fetchSemanticsNode().boundsInRoot
        val codecLabel = composeRule.onNodeWithTag("vesqen.chain.metric.source.codec_label")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("600dp metric cards must share a row", abs(container.top - codecLabel.top) <= 1f)
        assertTrue(
            "600dp metric cards must not overlap",
            container.right <= codecLabel.left || codecLabel.right <= container.left,
        )
        composeRule.onAllNodesWithTag("vesqen.chain.summary").assertCountEquals(0)
    }

    @Test
    fun chain_advanced_keeps_fixed_summary_left_of_metrics_at_840dp() {
        render(
            state = activePlaybackState(),
            playbackTelemetry = FakePlaybackTelemetry(chainTelemetrySnapshot()),
            containerWidth = 840.dp,
            containerHeight = 720.dp,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()

        val summary = composeRule.onNodeWithTag("vesqen.chain.summary").fetchSemanticsNode().boundsInRoot
        val metrics = composeRule.onNodeWithTag("vesqen.chain.metrics").fetchSemanticsNode().boundsInRoot
        assertTrue("840dp summary must remain left of the advanced dashboard", summary.right <= metrics.left)
    }

    @Test
    fun player_view_switch_labels_are_not_ellipsized_with_150_percent_text() {
        render(
            state = activePlaybackState(), containerWidth = 360.dp,
            containerHeight = 720.dp, fontScale = 1.5f,
        )
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        for (label in listOf(R.string.show_playback_session, R.string.show_album_artwork)) {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(context.getString(label), useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue("View switch action must fit on one line", layout.lineCount == 1)
            // The String Text semantics adapter rebuilds a paragraph at maxWidth while keeping
            // the original, narrower layoutSize. Compare glyph advance, not paragraph width.
            val textWidth = layout.getLineRight(0) - layout.getLineLeft(0)
            assertTrue("Visible action must be complete: text=${layout.layoutInput.text.text}, " +
                "size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                "textWidth=$textWidth, heightOverflow=${layout.didOverflowHeight}, " +
                "ellipsized=${layout.isLineEllipsized(0)}",
                textWidth <= layout.size.width + 1f &&
                    !layout.didOverflowHeight && !layout.isLineEllipsized(0))
            composeRule.onNodeWithTag("vesqen.now.session-toggle").performClick()
        }
    }

    @Test
    fun settings_exposes_signed_verification_registry_import_without_claiming_verified() {
        var importCalls = 0
        render(
            state = grantedState(),
            verificationRegistryState = OutputVerificationRegistryState.Ready(
                records = emptyList(),
                applicableInstallRecordCount = 0,
            ),
            onImportVerificationRegistry = { importCalls++ },
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.verification-registry"))
        composeRule.onNodeWithTag("vesqen.settings.verification-registry").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithText(context.getString(R.string.bit_perfect_verified)).assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(1, importCalls) }
    }

    @Test
    fun exact_verification_match_is_visually_distinct_and_traceable_in_chain() {
        val verification = fakeOutputVerification()
        val outputStatus = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.ACTIVE,
            deviceName = "Reference DAC",
            hardwareIdentity = UsbHardwareIdentity(0x1234, 0x5678, "2.10"),
            sourceFormat = AudioFormatSummary(96_000, 2, "pcm 24-bit"),
            sinkFormat = AudioFormatSummary(96_000, 2, "pcm 24-bit"),
            decisionCode = "strict_usb.active",
            generation = 1,
        )
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    trackId = sampleTracks.first().id,
                    title = sampleTracks.first().title,
                    usbOutputStatus = outputStatus,
                    outputVerification = verification,
                ),
            ),
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.verification-registry"))
        composeRule.onNodeWithText(
            context.getString(R.string.settings_verification_active, verification.record.recordId),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        composeRule.onNodeWithTag("vesqen.chain.summary-list")
            .performScrollToNode(hasTestTag("vesqen.chain.summary"))
        composeRule.onNodeWithText(context.getString(R.string.bit_perfect_verified)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chain_verified_title)).assertIsDisplayed()
        composeRule.onNodeWithText(verification.record.recordId, substring = true).assertIsDisplayed()
    }

    @Test
    fun playback_progress_label_is_not_ellipsized_with_150_percent_text() {
        render(
            state = activePlaybackState(),
            containerWidth = 360.dp,
            containerHeight = 720.dp,
            fontScale = 1.5f,
        )
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        composeRule.onNodeWithTag("vesqen.now.session-toggle").performClick()

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(context.getString(R.string.playback_progress), useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }

        val layout = layouts.single()
        assertTrue("Playback progress must remain complete at 150% text", layout.lineCount == 1)
        assertTrue("Playback progress must not be ellipsized", !layout.isLineEllipsized(0))
        assertTrue("Playback progress must not overflow vertically", !layout.didOverflowHeight)
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
        composeRule.onNodeWithTag("vesqen.now.open-chain").assertIsDisplayed()
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
            "vesqen.now.open-chain",
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
                    playlists = emptyList(),
                ),
            ),
            onCreatePlaylist = { createdName = it },
        )

        composeRule.onNodeWithTag("vesqen.library.mode.playlists").performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.no_playlists_body)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.no_local_music_body)).assertCountEquals(0)
        composeRule.onNodeWithText(context.getString(R.string.create_playlist)).performClick()
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
        val minimumClearancePx = with(fixtureDensity) { 12.dp.toPx() }
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
        val minimumTopBeatPx = with(fixtureDensity) { 24.dp.toPx() }

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
        val minimumArtworkPx = with(fixtureDensity) { 48.dp.toPx() }
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
        val minimumTouchTargetPx = with(fixtureDensity) { 48.dp.toPx() }
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

    private val summaryMetricIds = TelemetryMetricCatalog.defaultIds + setOf(
        TelemetryMetricCatalog.PLAYBACK_AUDIO_TRACK_ENCODING,
        TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME,
        TelemetryMetricCatalog.PLAYBACK_CURRENT_MEDIA_READ_BITRATE,
        TelemetryMetricCatalog.PLAYBACK_ESTIMATED_TOTAL_BUFFERED_DURATION,
    )

    private fun openAdvancedChain() {
        composeRule.onNodeWithTag("vesqen.chain.summary-list")
            .performScrollToNode(hasTestTag("vesqen.chain.open-advanced"))
        composeRule.onNodeWithTag("vesqen.chain.open-advanced").performClick()
    }

    private fun chainNode(
        tag: String,
        listTag: String = "vesqen.chain.metrics",
        useUnmergedTree: Boolean = false,
    ): SemanticsNodeInteraction {
        composeRule.onNodeWithTag(listTag, useUnmergedTree).performScrollToNode(hasTestTag(tag))
        return composeRule.onNodeWithTag(tag, useUnmergedTree)
    }

    private fun render(
        state: VesqenUiState,
        stateProvider: (() -> VesqenUiState)? = null,
        onPrevious: () -> Unit = {},
        onPlayPause: () -> Unit = {},
        onRefreshPlaybackPosition: () -> Unit = {},
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
        playbackTelemetry: PlaybackTelemetry? = null,
        chainPreferencesRepository: ChainDashboardPreferencesRepository =
            InMemoryChainDashboardPreferencesRepository(),
        diagnosticRecorder: DiagnosticRecorder? = null,
        diagnosticExportFeedback: DiagnosticExportFeedback = DiagnosticExportFeedback.NONE,
        onRequestDiagnosticExport: () -> Unit = {},
        verificationRegistryState: OutputVerificationRegistryState = OutputVerificationRegistryState.Empty,
        onImportVerificationRegistry: () -> Unit = {},
    ) {
        composeRule.setContent {
            VesqenTheme(darkTheme = darkTheme) {
                val app: @Composable () -> Unit = {
                    fixtureDensity = LocalDensity.current
                    VesqenAppContent(
                        state = stateProvider?.invoke() ?: state,
                        onRequestMusicAccess = onRequestMusicAccess,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onRescan = {},
                        onTrackSelected = {},
                        onPlayQueue = onPlayQueue,
                        onCreatePlaylist = onCreatePlaylist,
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onRefreshPlaybackPosition = onRefreshPlaybackPosition,
                        onNext = onNext,
                        onSeek = {},
                        onCyclePlaybackOrder = onCyclePlaybackOrder,
                        onAddLibraryFolder = onAddLibraryFolder,
                        onRemoveLibraryFolder = onRemoveLibraryFolder,
                        onResumeLibraryScan = onResumeLibraryScan,
                        motionPolicy = motionPolicy,
                        versionName = versionName,
                        versionCode = versionCode,
                        playbackTelemetry = playbackTelemetry,
                        chainPreferencesRepository = chainPreferencesRepository,
                        diagnosticRecorder = diagnosticRecorder,
                        diagnosticExportFeedback = diagnosticExportFeedback,
                        onRequestDiagnosticExport = onRequestDiagnosticExport,
                        verificationRegistryState = verificationRegistryState,
                        onImportVerificationRegistry = onImportVerificationRegistry,
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
                            containerWidth?.let { width ->
                                val widthDp = width.value.toInt()
                                val heightDp = containerHeight.value.toInt()
                                screenWidthDp = widthDp
                                screenHeightDp = heightDp
                                smallestScreenWidthDp = min(widthDp, heightDp)
                                orientation = if (widthDp > heightDp) {
                                    Configuration.ORIENTATION_LANDSCAPE
                                } else {
                                    Configuration.ORIENTATION_PORTRAIT
                                }
                            }
                        }
                    }
                    val windowSize = LocalWindowInfo.current.containerSize
                    val sizedDensity = remember(density, fontScale, containerWidth, containerHeight, windowSize) {
                        // Fit the requested dp viewport on the host; width(840.dp) alone is
                        // clamped to a 360dp phone and never exercises the wide layout.
                        val fittedDensity = if (containerWidth == null) density.density else minOf(
                            density.density,
                            windowSize.width / containerWidth.value,
                            windowSize.height / containerHeight.value,
                        )
                        Density(fittedDensity, fontScale ?: density.fontScale)
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

    private fun fakeOutputVerification() = OutputVerificationMatch(
        OutputVerificationRecord(
            recordId = "m4.reference_96k24",
            result = OutputVerificationResult.VERIFIED,
            verifiedAtEpochMs = 1_788_800_000_000,
            appVersionName = "0.4.0-beta.1",
            appVersionCode = 9,
            baseApkSha256 = "a".repeat(64),
            deviceManufacturer = "Example",
            deviceModel = "Reference Phone",
            androidApiLevel = 35,
            buildFingerprintSha256 = "b".repeat(64),
            dacVendorId = 0x1234,
            dacProductId = 0x5678,
            dacName = "Reference DAC",
            dacDescriptorVersion = "2.10",
            sourceFormat = VerificationPcmFormat(96_000, 2, "pcm 24-bit"),
            sinkFormat = VerificationPcmFormat(96_000, 2, "pcm 24-bit"),
            testVectorSha256 = "c".repeat(64),
            methodId = "digital_capture.sample_compare",
            signalPoint = "usb_digital_pcm",
            evidenceReference = "private-evidence/m4-reference-96k24",
        ),
    )

    private fun activePlaybackState(): VesqenUiState = grantedState(
        tracks = sampleTracks,
        playback = PlaybackSnapshot(
            isControllerReady = true,
            isPlaying = true,
            trackId = sampleTracks.first().id,
            title = sampleTracks.first().title,
            artist = sampleTracks.first().artist,
            album = sampleTracks.first().album,
            durationMs = sampleTracks.first().durationMs,
            positionMs = 30_000,
            hasNext = true,
        ),
    )

    private fun chainTelemetrySnapshot(
        codecLabel: String = "FLAC",
        playbackSessionId: String = "instrumentation-session",
        recentEvents: List<TelemetryEvent> = emptyList(),
    ): TelemetrySnapshot {
        val epochMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
        val source = TelemetryDataSource(TelemetrySourceId("test.telemetry"))
        return TelemetrySnapshot(
            capturedAtEpochMs = epochMs,
            capturedAtElapsedRealtimeMs = elapsedMs,
            playbackSessionId = playbackSessionId,
            recentEvents = recentEvents,
            metrics = listOf(
                TelemetryMetric(
                    id = TelemetryMetricCatalog.SOURCE_CODEC_LABEL,
                    section = TelemetrySection.SOURCE,
                    evidence = TelemetryEvidence.Measured(
                        reading = TelemetryReading.Text(codecLabel),
                        source = source,
                        observedAtEpochMs = epochMs,
                        observedAtElapsedRealtimeMs = elapsedMs,
                    ),
                ),
                TelemetryMetric(
                    id = TelemetryMetricCatalog.SOURCE_SAMPLE_RATE,
                    section = TelemetrySection.SOURCE,
                    evidence = TelemetryEvidence.Measured(
                        reading = TelemetryReading.Integer(96_000, TelemetryUnit.HERTZ),
                        source = source,
                        observedAtEpochMs = epochMs,
                        observedAtElapsedRealtimeMs = elapsedMs,
                    ),
                ),
            ),
        )
    }

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
