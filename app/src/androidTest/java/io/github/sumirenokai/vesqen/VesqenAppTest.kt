package io.github.sumirenokai.vesqen

import android.text.format.Formatter
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import io.github.sumirenokai.vesqen.library.AudioTrack
import io.github.sumirenokai.vesqen.library.LibraryCatalogSnapshot
import io.github.sumirenokai.vesqen.chain.AppSegmentMetricIds
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecorder
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecordingState
import io.github.sumirenokai.vesqen.library.LibraryPlaylist
import io.github.sumirenokai.vesqen.library.LibraryRebuildNotice
import io.github.sumirenokai.vesqen.library.LibraryScanProgress
import io.github.sumirenokai.vesqen.library.LibraryScanState
import io.github.sumirenokai.vesqen.library.LibrarySource
import io.github.sumirenokai.vesqen.library.LibrarySourceKind
import io.github.sumirenokai.vesqen.playback.PlaybackOrderMode
import io.github.sumirenokai.vesqen.playback.PlaybackQueueItem
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.PlaybackRepeatMode
import io.github.sumirenokai.vesqen.playback.OutputDeclaration
import io.github.sumirenokai.vesqen.playback.AudioFormatSummary
import io.github.sumirenokai.vesqen.playback.OfficialMixerApiSupport
import io.github.sumirenokai.vesqen.playback.UsbHardwareIdentity
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputFailureOrigin
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.premium.ActivationCodes
import io.github.sumirenokai.vesqen.premium.ActivationResult
import io.github.sumirenokai.vesqen.premium.ActivationState
import io.github.sumirenokai.vesqen.premium.PremiumAccess
import io.github.sumirenokai.vesqen.premium.PremiumStore
import io.github.sumirenokai.vesqen.premium.TrialRecord
import io.github.sumirenokai.vesqen.reports.CrashPrompt
import io.github.sumirenokai.vesqen.support.SupportPrompt
import io.github.sumirenokai.vesqen.reports.DeviceReportArtifact
import io.github.sumirenokai.vesqen.reports.DeviceReportDelivery
import io.github.sumirenokai.vesqen.reports.DeviceReportFailure
import io.github.sumirenokai.vesqen.reports.DeviceReportOptions
import io.github.sumirenokai.vesqen.reports.DeviceReportSnapshot
import io.github.sumirenokai.vesqen.reports.DeviceReportState
import io.github.sumirenokai.vesqen.reports.DeviceReporter
import io.github.sumirenokai.vesqen.reports.FakeDeviceReporter
import io.github.sumirenokai.vesqen.service.ServiceState
import io.github.sumirenokai.vesqen.telemetry.FakePlaybackTelemetry
import io.github.sumirenokai.vesqen.updates.AppUpdater
import io.github.sumirenokai.vesqen.updates.FakeAppUpdater
import io.github.sumirenokai.vesqen.updates.UpdateFailure
import io.github.sumirenokai.vesqen.updates.UpdateInstallationSource
import io.github.sumirenokai.vesqen.updates.UpdateLanguage
import io.github.sumirenokai.vesqen.updates.UpdateRelease
import io.github.sumirenokai.vesqen.updates.UpdateSnapshot
import io.github.sumirenokai.vesqen.updates.UpdateState
import io.github.sumirenokai.vesqen.usage.FakeUsageStatistics
import io.github.sumirenokai.vesqen.usage.UsageConsent
import io.github.sumirenokai.vesqen.usage.UsageRegionPolicy
import io.github.sumirenokai.vesqen.usage.UsageSettingsStatus
import io.github.sumirenokai.vesqen.usage.UsageStatistics
import io.github.sumirenokai.vesqen.usage.UsageStatisticsSnapshot
import io.github.sumirenokai.vesqen.ui.screens.UsageIntroductionScreen
import io.github.sumirenokai.vesqen.telemetry.PlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.TelemetryDataSource
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventKind
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventSeverity
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricSelection
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetrySection
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetrySourceId
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import io.github.sumirenokai.vesqen.telemetry.TelemetryWindow
import io.github.sumirenokai.vesqen.telemetry.UsbAudioInterfaceReading
import io.github.sumirenokai.vesqen.telemetry.UsbHostDeviceReading
import io.github.sumirenokai.vesqen.telemetry.UsbInventoryReading
import io.github.sumirenokai.vesqen.ui.LibraryUiState
import io.github.sumirenokai.vesqen.ui.MusicAccess
import io.github.sumirenokai.vesqen.ui.VesqenAppContent
import io.github.sumirenokai.vesqen.ui.VesqenUiState
import io.github.sumirenokai.vesqen.ui.scanProgressAfterRead
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardPreferences
import io.github.sumirenokai.vesqen.ui.chain.ChainDashboardPreferencesRepository
import io.github.sumirenokai.vesqen.ui.chain.ChainMetricViewMode
import io.github.sumirenokai.vesqen.ui.chain.ChainUnitDisplayMode
import io.github.sumirenokai.vesqen.ui.chain.InMemoryChainDashboardPreferencesRepository
import io.github.sumirenokai.vesqen.ui.chain.DiagnosticExportFeedback
import io.github.sumirenokai.vesqen.ui.chain.formatSeconds
import io.github.sumirenokai.vesqen.ui.chain.formatTelemetryReading
import io.github.sumirenokai.vesqen.ui.components.OutputStatusChip
import io.github.sumirenokai.vesqen.ui.components.QueueSheet
import io.github.sumirenokai.vesqen.ui.components.QueueSheetHeader
import io.github.sumirenokai.vesqen.ui.navigation.NavigationRailWidth
import io.github.sumirenokai.vesqen.ui.screens.ChainScreen
import io.github.sumirenokai.vesqen.ui.screens.ChainSummaryMetricIds
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.screens.ChainNowPlaying
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import io.github.sumirenokai.vesqen.verification.OutputVerificationRegistryState
import io.github.sumirenokai.vesqen.verification.OutputVerificationMatch
import io.github.sumirenokai.vesqen.verification.OutputVerificationRecord
import io.github.sumirenokai.vesqen.verification.OutputVerificationResult
import io.github.sumirenokai.vesqen.verification.VerificationPcmFormat
import io.github.sumirenokai.vesqen.lyrics.LyricLine
import io.github.sumirenokai.vesqen.lyrics.Lyrics
import io.github.sumirenokai.vesqen.lyrics.LyricsSource
import io.github.sumirenokai.vesqen.lyrics.TrackLyrics
import io.github.sumirenokai.vesqen.ui.screens.NowLyricsState
import io.github.sumirenokai.vesqen.lyrics.LyricsImportResult
import io.github.sumirenokai.vesqen.ui.LyricsImportNotice
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import kotlin.math.abs
import kotlin.math.min
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun strict_output_failure_explains_reason_and_requires_explicit_fallback() {
        val failure = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.FAILED,
            failure = UsbOutputFailure.NO_USB_AUDIO_DEVICE,
            generation = 1,
            failureOrigin = UsbOutputFailureOrigin.USER_PLAYBACK,
        )
        var state by mutableStateOf(activePlaybackState().let {
            it.copy(playback = it.playback.copy(usbOutputStatus = failure))
        })
        val modes = mutableListOf<UsbOutputMode>()
        render(state, stateProvider = { state }, onSetUsbOutputMode = { modes += it })
        composeRule.onNodeWithTag("vesqen.output.failure-dialog").assertIsDisplayed()
        assertClickablesAtLeast48Dp("Strict USB failure dialog")
        composeRule.onNodeWithText(context.getString(
            R.string.player_strict_failure_body, context.getString(R.string.usb_failure_no_device),
        )).assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.output.keep-strict").performClick()
        composeRule.runOnIdle {
            assertTrue(modes.isEmpty())
            state = state.copy(playback = state.playback.copy(positionMs = 1000))
        }
        composeRule.onNodeWithTag("vesqen.output.failure-dialog").assertDoesNotExist()
        composeRule.runOnIdle {
            state = state.copy(playback = state.playback.copy(
                usbOutputStatus = failure.copy(generation = 2),
                isControllerReady = false,
                canSetUsbOutputMode = false,
            ))
        }
        composeRule.onNodeWithTag("vesqen.output.failure-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.output.use-system").assertIsNotEnabled()
        composeRule.runOnIdle {
            state = state.copy(
                playback = state.playback.copy(
                    isControllerReady = true,
                    canSetUsbOutputMode = true,
                ),
            )
        }
        composeRule.onNodeWithTag("vesqen.output.use-system").performClick()
        composeRule.runOnIdle { assertEquals(listOf(UsbOutputMode.SYSTEM), modes) }
        // A command request alone is not success; wait for the service snapshot to confirm it.
        composeRule.onNodeWithTag("vesqen.output.failure-dialog").assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(playback = state.playback.copy(usbOutputStatus = UsbOutputStatus()))
        }
        composeRule.onNodeWithTag("vesqen.output.failure-dialog").assertDoesNotExist()
    }

    @Test
    fun turning_strict_output_on_explains_pro_features_once() {
        val premium = PremiumAccess(MemoryPremiumStore(), enforced = false)
        val modes = mutableListOf<UsbOutputMode>()
        render(
            grantedState(playback = PlaybackSnapshot(isControllerReady = true)),
            onSetUsbOutputMode = modes::add,
            premiumAccess = premium,
        )
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.premium")
            .assertTextContains(context.getString(R.string.settings_premium_not_charging))
        composeRule.onNodeWithTag("vesqen.settings.output.strict-usb").performClick()
        composeRule.onNodeWithTag("vesqen.premium.strict-intro").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.premium_strict_intro_body_not_charging)).assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.premium.strict-intro.open").performClick()
        composeRule.onNodeWithTag("vesqen.premium.status")
            .assertTextEquals(context.getString(R.string.premium_status_not_charging))
        composeRule.runOnIdle {
            assertEquals(listOf(UsbOutputMode.STRICT_BIT_PERFECT), modes)
            assertTrue(premium.strictIntroductionShown.value)
        }
        composeRule.onNodeWithTag("vesqen.premium.back").performClick()
        composeRule.onNodeWithTag("vesqen.settings.output.strict-usb").performClick()
        composeRule.onNodeWithTag("vesqen.premium.strict-intro").assertDoesNotExist()
    }

    @Test
    fun settings_shows_pro_unlocked_after_a_code_is_activated_on_the_pro_page() {
        val codes = FakeActivationCodes(valid = "ABCDE-FGHJK")
        val premium = PremiumAccess(
            MemoryPremiumStore(TrialRecord(startedAtEpochMs = 0, latestSeenEpochMs = 0)),
            enforced = true,
            isUnlocked = { codes.state.value is ActivationState.Active },
        )
        render(grantedState(), premiumAccess = premium, activationCodes = codes)
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.premium")
            .assertTextContains(context.getString(R.string.settings_premium_trial_ended))
        composeRule.onNodeWithTag("vesqen.settings.premium").performClick()
        composeRule.onNodeWithTag("vesqen.premium").performScrollToNode(hasTestTag("vesqen.premium.activation.input"))
        composeRule.onNodeWithTag("vesqen.premium.activation.input").performTextInput("abcde fghjk")
        composeRule.onNodeWithTag("vesqen.premium.activation.activate").performClick()
        composeRule.onNodeWithTag("vesqen.premium.back").performClick()
        // #50: Settings stayed composed under the Pro page, so it must follow the activation itself.
        composeRule.onNodeWithTag("vesqen.settings.premium")
            .assertTextContains(context.getString(R.string.settings_premium_unlocked))
    }

    @Test
    fun an_activation_code_is_entered_on_the_pro_page_and_then_shown_for_copying() {
        val codes = FakeActivationCodes(valid = "ABCDE-FGHJK")
        render(grantedState(), premiumAccess = PremiumAccess(MemoryPremiumStore(), enforced = false), activationCodes = codes)
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.premium").performClick()
        composeRule.onNodeWithTag("vesqen.premium").performScrollToNode(hasTestTag("vesqen.premium.activation.input"))
        composeRule.onNodeWithTag("vesqen.premium.activation.input").performTextInput("wrong")
        composeRule.onNodeWithTag("vesqen.premium.activation.activate").performClick()
        composeRule.onNodeWithTag("vesqen.premium.activation.error")
            .assertTextEquals(context.getString(R.string.premium_activation_not_genuine))
        composeRule.onNodeWithTag("vesqen.premium.activation.input").performTextClearance()
        composeRule.onNodeWithTag("vesqen.premium.activation.input").performTextInput("abcde fghjk")
        composeRule.onNodeWithTag("vesqen.premium.activation.activate").performClick()
        composeRule.onNodeWithTag("vesqen.premium").performScrollToNode(hasTestTag("vesqen.premium.activation.code"))
        composeRule.onNodeWithTag("vesqen.premium.activation.code").assertTextEquals("ABCDE-FGHJK")
        composeRule.onAllNodesWithTag("vesqen.premium.activation.input").assertCountEquals(0)
    }

    @Test
    fun an_ended_trial_offers_pro_features_instead_of_keeping_strict_mode() {
        val ended = MemoryPremiumStore(TrialRecord(startedAtEpochMs = 0, latestSeenEpochMs = 0))
        val premium = PremiumAccess(ended, enforced = true)
        val failure = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.FAILED,
            failure = UsbOutputFailure.TRIAL_ENDED,
            generation = 1,
            failureOrigin = UsbOutputFailureOrigin.USER_PLAYBACK,
        )
        render(
            state = activePlaybackState().let { it.copy(playback = it.playback.copy(usbOutputStatus = failure)) },
            premiumAccess = premium,
        )
        composeRule.onNodeWithTag("vesqen.output.failure-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(
            R.string.player_trial_ended_body, context.getString(R.string.usb_failure_trial_ended),
        )).assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.output.keep-strict").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.output.open-premium").performClick()
        composeRule.onNodeWithTag("vesqen.output.failure-dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.premium.status")
            .assertTextEquals(context.getString(R.string.premium_status_trial_ended))
    }

    @Test
    fun strict_failures_without_a_user_action_stay_out_of_a_dialog_but_visible() {
        var state by mutableStateOf(activePlaybackState())
        render(state, stateProvider = { state })
        // #34: startup, queue restore and unknown origins never interrupt with a modal.
        listOf(UsbOutputFailureOrigin.SERVICE_START, UsbOutputFailureOrigin.QUEUE_RESTORE, null)
            .forEachIndexed { index, origin ->
                composeRule.runOnIdle {
                    state = state.copy(playback = state.playback.copy(usbOutputStatus = UsbOutputStatus(
                        mode = UsbOutputMode.STRICT_BIT_PERFECT,
                        phase = UsbOutputPhase.FAILED,
                        failure = UsbOutputFailure.NO_USB_AUDIO_DEVICE,
                        generation = index + 1L,
                        failureOrigin = origin,
                    )))
                }
                composeRule.onNodeWithTag("vesqen.output.failure-dialog").assertDoesNotExist()
            }
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithText(context.getString(
            R.string.settings_strict_usb_failed, context.getString(R.string.usb_failure_no_device),
        )).assertIsDisplayed()
    }

    @Test
    fun saved_strict_mode_turns_off_in_now_where_the_official_api_is_missing() {
        val modes = mutableListOf<UsbOutputMode>()
        val restoredOnAndroid13 = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.FAILED,
            failure = UsbOutputFailure.UNSUPPORTED_ANDROID_VERSION,
            generation = 1,
            officialMixerApiSupport = OfficialMixerApiSupport(
                androidRelease = "13",
                apiLevel = 33,
                mixerApiAvailable = false,
            ),
            failureOrigin = UsbOutputFailureOrigin.SERVICE_START,
        )
        render(
            state = activePlaybackState().let { state ->
                state.copy(playback = state.playback.copy(usbOutputStatus = restoredOnAndroid13))
            },
            onSetUsbOutputMode = modes::add,
        )
        composeRule.onNodeWithTag("vesqen.output.failure-dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        openLinerNotes()
        composeRule.onNodeWithTag("vesqen.now.output-mode").performScrollTo().performClick()
        // #62 item 4: the switch cannot turn strict on here, but a saved strict mode turns off.
        composeRule.onNodeWithTag("vesqen.now.strict-usb-switch").assertIsEnabled().assertIsOn().performClick()
        composeRule.onNodeWithTag("vesqen.output.unavailable-dialog").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf(UsbOutputMode.SYSTEM), modes) }
    }

    @Test
    fun player_output_switch_is_reachable_in_portrait() = assertPlayerOutputSwitch(360.dp, 720.dp, 1f)

    @Test
    fun player_output_switch_is_reachable_with_large_text() = assertPlayerOutputSwitch(320.dp, 533.dp, 2f)

    @Test
    fun player_output_switch_is_reachable_in_landscape() = assertPlayerOutputSwitch(720.dp, 360.dp, 1f)

    private fun assertPlayerOutputSwitch(width: Dp, height: Dp, fontScale: Float) {
        var state by mutableStateOf(activePlaybackState())
        val modes = mutableListOf<UsbOutputMode>()
        render(
            state, stateProvider = { state }, containerWidth = width, containerHeight = height,
            fontScale = fontScale,
            onSetUsbOutputMode = { mode ->
                modes += mode
                state = state.copy(playback = state.playback.copy(usbOutputStatus = UsbOutputStatus(
                    mode = mode,
                    phase = if (mode == UsbOutputMode.SYSTEM) UsbOutputPhase.SYSTEM else UsbOutputPhase.APPLYING,
                )))
            },
        )
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        // Output mode belongs to the liner notes: one tap opens them, then the action is reachable.
        openLinerNotes()
        composeRule.onNodeWithTag("vesqen.now.output-mode").performScrollTo()
        assertNodesAreFullyVisibleIn("vesqen.now.focus-surface", arrayOf("vesqen.now.output-mode", "vesqen.now.notes-toggle"))
        assertFooterActionsDoNotOverlap("vesqen.now.back", "vesqen.now.notes-toggle", "vesqen.now.favorite")
        composeRule.onNodeWithTag("vesqen.now.output-mode").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("vesqen.now.strict-usb-switch").assertIsOff().performClick()
        composeRule.runOnIdle { assertEquals(listOf(UsbOutputMode.STRICT_BIT_PERFECT), modes) }
        composeRule.onNodeWithTag("vesqen.now.output-mode").performScrollTo().performClick()
        composeRule.onNodeWithTag("vesqen.now.strict-usb-switch").assertIsOn().performClick()
        composeRule.runOnIdle { assertEquals(listOf(UsbOutputMode.STRICT_BIT_PERFECT, UsbOutputMode.SYSTEM), modes) }
    }

    @Test
    fun navigation_exposes_library_now_chain_and_settings_as_tabs() {
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

        // Chain is a tab: no back arrow, and Back returns to Library like the other tabs.
        composeRule.onNodeWithTag("vesqen.nav.chain").performClick()
        composeRule.onNodeWithTag("vesqen.nav.chain").assertIsSelected()
        composeRule.onNodeWithText(context.getString(R.string.chain_empty_title)).assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.chain.back").assertCountEquals(0)
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()

        // Opened in context from Settings, Chain keeps a back arrow that returns there.
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        composeRule.onNodeWithText(context.getString(R.string.chain_empty_title)).assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.chain.diagnostics").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.chain.back").performClick()
        composeRule.onNodeWithTag("vesqen.nav.settings").assertIsSelected()
        composeRule.onNodeWithTag("vesqen.nav.chain").performClick()
        composeRule.onNodeWithText(context.getString(R.string.browse_library)).performClick()
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
    }

    @Test
    fun tapping_the_chain_tab_over_settings_keeps_its_way_back() {
        // Re-selecting Chain opened from Settings once dropped its back arrow and rebuilt the page
        // (#143 Q4).
        render(grantedState())
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        composeRule.onNodeWithTag("vesqen.chain.back").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.nav.chain").assertIsSelected().performClick()
        composeRule.onNodeWithTag("vesqen.chain.back").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("vesqen.nav.settings").assertIsSelected()
    }

    @Test
    fun chain_opened_from_settings_keeps_its_way_back_after_now() {
        // #156 V25: after a visit to Now, Back reached Chain without its arrow, and Chain then
        // returned to Library instead of Settings.
        render(grantedState())
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        composeRule.onNodeWithTag("vesqen.nav.now").assertIsSelected()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.nav.chain").assertIsSelected()
        composeRule.onNodeWithTag("vesqen.chain.back").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("vesqen.nav.settings").assertIsSelected()
    }

    @Test
    fun empty_library_without_access_makes_allowing_access_the_main_action() {
        var accessRequests = 0
        var addFolderCalls = 0
        render(
            state = VesqenUiState(library = LibraryUiState(musicAccess = MusicAccess.NEEDS_PERMISSION)),
            onRequestMusicAccess = { accessRequests++ },
            onAddLibraryFolder = { addFolderCalls++ },
        )
        // One request on screen: the centred main button, not a second one in a notice above it.
        composeRule.onAllNodesWithTag("vesqen.library.music-access-notice").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.permission.request")
            .assertTextEquals(context.getString(R.string.device_music_access_compact))
            .performClick()
        composeRule.onNodeWithTag("vesqen.library.empty.add-folder").performClick()
        composeRule.runOnIdle {
            assertEquals(1, accessRequests)
            assertEquals(1, addFolderCalls)
        }
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
        composeRule.onNodeWithTag("vesqen.library.search-toggle").assertHeightIsEqualTo(48.dp).performClick()
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
    fun tracks_found_above_the_top_row_keep_the_list_at_the_top() {
        fun track(id: Long, title: String) = AudioTrack(
            id = id, contentUri = "content://media/external/audio/media/$id",
            title = title, artist = "Mori", album = "Quiet Rooms", durationMs = 200_000,
        )
        val later = (1L..30L).map { track(it, "M %02d".format(it)) }
        val earlier = (101L..130L).map { track(it, "A %02d".format(it - 100)) }
        var state by mutableStateOf(grantedState(tracks = later))
        render(state, stateProvider = { state })
        composeRule.onNodeWithTag("vesqen.library.track.1").assertIsDisplayed()
        // A scan finds tracks that sort above everything shown so far.
        composeRule.runOnIdle { state = grantedState(tracks = earlier + later) }
        composeRule.onNodeWithTag("vesqen.library.track.101").assertIsDisplayed()
    }

    @Test
    fun empty_library_does_not_reserve_search_space() {
        render(grantedState())

        composeRule.onAllNodesWithTag("vesqen.library.search-toggle").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.library.search").assertCountEquals(0)
    }

    @Test
    fun library_search_opens_from_the_title_bar_and_back_closes_it() {
        render(grantedState(tracks = sampleTracks))

        composeRule.onAllNodesWithTag("vesqen.library.search").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.library.search-toggle").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Dawn")
        composeRule.onNodeWithTag("vesqen.library.track.1").assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.library.track.2").assertCountEquals(0)

        // Back clears the query and folds the field away before it leaves the Library.
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onAllNodesWithTag("vesqen.library.search").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.library.track.2").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.library.title.root").assertIsDisplayed()
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

        composeRule.onNodeWithTag("vesqen.library.search-toggle").performClick()
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
    fun every_clickable_on_the_main_pages_is_at_least_48dp_tall() {
        // #162: TalkBack, accessibility scanners and Play's pre-launch report read the clickable
        // node itself, so Material's 40 dp buttons fall short even with a wider touch layer.
        render(state = activePlaybackState(), containerWidth = 320.dp)

        assertClickablesAtLeast48Dp("Library")
        composeRule.onNodeWithTag("vesqen.library.mode.albums").performScrollTo().performClick()
        assertClickablesAtLeast48Dp("Library albums")
        composeRule.onNodeWithTag("vesqen.nav.chain").performClick()
        assertClickablesAtLeast48Dp("Chain")
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        assertClickablesAtLeast48Dp("Settings")
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        openLinerNotes()
        assertClickablesAtLeast48Dp("Now")
        // Last: the queue is a sheet over Now.
        composeRule.onNodeWithTag("vesqen.now.queue").performClick()
        composeRule.onNodeWithTag("vesqen.queue.sheet").assertExists()
        assertClickablesAtLeast48Dp("Queue")
    }

    /** The node's own size, not its clipped bounds, so a row half scrolled off the screen still counts whole. */
    private fun assertClickablesAtLeast48Dp(page: String) {
        composeRule.waitForIdle()
        val minimumPx = with(fixtureDensity) { 48.dp.roundToPx() }
        val small = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .fetchSemanticsNodes()
            .filter { it.size.height < minimumPx }
            .map { node ->
                val name = node.config.getOrNull(SemanticsProperties.TestTag)
                    ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                "$name (${with(fixtureDensity) { node.size.height.toDp() }})"
            }
        assertTrue("$page: clickables under 48 dp: $small", small.isEmpty())
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
    fun a_catalog_read_during_a_scan_keeps_the_pause_button() {
        // #136 U1: a favorite, a playlist edit or a return to the app reads the catalog while the scan
        // runs. The read lists the device source as scanning, not paused, and the scan reports its
        // progress again only when the source ends.
        val device = LibrarySource(
            id = "media:external",
            kind = LibrarySourceKind.DEVICE,
            displayName = "Device music",
            scanState = LibraryScanState.SCANNING,
        )
        var state by mutableStateOf(
            VesqenUiState(
                library = LibraryUiState(
                    musicAccess = MusicAccess.GRANTED,
                    tracks = sampleTracks.take(1),
                    sources = listOf(device),
                    scanProgress = LibraryScanProgress(
                        sourceId = device.id,
                        sourceName = device.displayName,
                        scannedTrackCount = 0,
                    ),
                ),
            ),
        )
        render(state, stateProvider = { state })
        composeRule.onNodeWithTag("vesqen.library.pause-scan").assertIsDisplayed()

        composeRule.runOnIdle {
            val read = LibraryCatalogSnapshot(tracks = sampleTracks.take(2), sources = listOf(device))
            state = state.copy(
                library = state.library.copy(
                    tracks = read.tracks,
                    scanProgress = scanProgressAfterRead(state.library.scanProgress, read, scanRunning = true),
                ),
            )
        }
        composeRule.onNodeWithTag("vesqen.library.track.2").assertExists()
        composeRule.onNodeWithTag("vesqen.library.pause-scan").assertIsDisplayed()
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
        // Removing a folder forgets its favorites, play counts and playlist entries, so it asks first.
        composeRule.onNodeWithTag("vesqen.library.source.remove-confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.library.source.remove-confirm.cancel").performClick()
        composeRule.runOnIdle { assertNull(removedSourceId) }
        composeRule.onNodeWithTag("vesqen.library.source.tree:test.remove").performClick()
        composeRule.onNodeWithTag("vesqen.library.source.remove-confirm.confirm").performClick()

        composeRule.runOnIdle { assertEquals(folder.id, removedSourceId) }
        composeRule.onNodeWithTag("vesqen.library.source-manager").assertDoesNotExist()
    }

    @Test
    fun deleting_a_playlist_asks_first() {
        var deletedId: Long? = null
        render(
            state = VesqenUiState(
                library = LibraryUiState(
                    musicAccess = MusicAccess.GRANTED,
                    tracks = sampleTracks,
                    playlists = listOf(LibraryPlaylist(7, "Night", listOf(1L, 2L), 0, 0)),
                ),
            ),
            onDeletePlaylist = { deletedId = it },
        )

        composeRule.onNodeWithTag("vesqen.library.mode.playlists").performScrollTo().performClick()
        composeRule.onNodeWithText("Night").performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.edit_playlist)).performClick()
        composeRule.onNodeWithTag("vesqen.library.playlist.delete").performClick()
        composeRule.onNodeWithTag("vesqen.library.playlist.delete-confirm").assertIsDisplayed()
        assertClickablesAtLeast48Dp("Delete playlist dialog")
        composeRule.onNodeWithTag("vesqen.library.playlist.delete-confirm.cancel").performClick()
        composeRule.runOnIdle { assertNull(deletedId) }

        composeRule.onNodeWithTag("vesqen.library.playlist.delete").performClick()
        composeRule.onNodeWithTag("vesqen.library.playlist.delete-confirm.confirm").performClick()
        composeRule.runOnIdle { assertEquals(7L, deletedId) }
    }

    @Test
    fun catalog_mutation_failure_keeps_existing_library_rows_visible() {
        render(
            state = VesqenUiState(
                library = LibraryUiState(
                    musicAccess = MusicAccess.GRANTED,
                    tracks = sampleTracks,
                    catalogMutationFailed = true,
                ),
            ),
        )

        composeRule.onNodeWithTag("vesqen.library.mutation-failed").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.library_change_save_failed))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.library.track.1").assertIsDisplayed()
    }

    @Test
    fun settings_groups_keep_every_entry_without_slogans() {
        render(grantedState(), appUpdater = FakeAppUpdater())
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        listOf(
            "vesqen.settings.section.playback-output",
            "vesqen.settings.section.audio-proof",
            "vesqen.settings.section.updates",
            "vesqen.settings.section.application",
            "vesqen.settings.privacy-policy",
            "vesqen.settings.licenses",
            "vesqen.settings.section.advanced",
            "vesqen.settings.verification-registry",
            "vesqen.settings.footer",
        ).forEach { tag ->
            composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun settings_open_privacy_policy_and_full_license_texts() {
        render(grantedState())
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.privacy-policy"))
        composeRule.onNodeWithTag("vesqen.settings.privacy-policy").performClick()
        composeRule.onNodeWithTag("vesqen.privacy").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.privacy.back").performClick()
        composeRule.onNodeWithTag("vesqen.settings").assertIsDisplayed()

        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.licenses"))
        composeRule.onNodeWithTag("vesqen.settings.licenses").performClick()
        composeRule.onNodeWithTag("vesqen.licenses").assertIsDisplayed()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("vesqen.licenses.entry.0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("vesqen.licenses.entry.0").performClick()
        composeRule.onNodeWithTag("vesqen.licenses.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.licenses.text.0").assertExists()
        // System Back leaves the license text for the list first, then Settings.
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.licenses").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.licenses.back").performClick()
        composeRule.onNodeWithTag("vesqen.settings").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.nav.settings").assertIsSelected()
    }

    @Test
    fun settings_keeps_its_position_after_a_detail_page() {
        // Settings once lost its saved list state while a detail page was open, so Back always
        // returned to the top of the list.
        render(grantedState())
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.footer"))
        composeRule.onNodeWithTag("vesqen.settings.licenses").performClick()
        composeRule.onNodeWithTag("vesqen.licenses").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.licenses.back").performClick()
        composeRule.onNodeWithTag("vesqen.settings.footer").assertIsDisplayed()
    }

    @Test
    fun settings_update_card_follows_each_update_state() {
        val release = UpdateRelease(
            versionName = "1.0.0-beta.3",
            versionCode = 12,
            minimumAndroidApi = 26,
            apkUrls = listOf("https://github.com/Sumire-no-kai/Vesqen/releases/download/v1.0.0-beta.3/Vesqen.apk"),
            sha256 = "0".repeat(64),
            releaseNotes = mapOf(UpdateLanguage.ENGLISH to "Fixes", UpdateLanguage.SIMPLIFIED_CHINESE to "修复"),
        )
        val updater = FakeAppUpdater()
        render(grantedState(), appUpdater = updater)
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        val check = composeRule.onNodeWithTag("vesqen.settings.updates.check")
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.updates.check"))
        check.assert(hasText(context.getString(R.string.update_status_never))).performClick()
        check.assert(hasText(context.getString(R.string.update_status_checking))).assertHasNoClickAction()
        composeRule.runOnIdle { updater.emit(UpdateState.UpToDate) }
        check.assert(hasText(context.getString(R.string.update_status_up_to_date)))

        composeRule.runOnIdle { updater.emit(UpdateState.Available(release)) }
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.updates.release"))
        composeRule.onNodeWithTag("vesqen.settings.updates.version").assertTextEquals("1.0.0-beta.3")
        composeRule.onNodeWithTag("vesqen.settings.updates.skip").performClick()
        composeRule.onNodeWithText(context.getString(R.string.update_skipped)).assertExists()
        composeRule.onAllNodesWithTag("vesqen.settings.updates.skip").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.settings.updates.download").performClick()
        composeRule.onNodeWithTag("vesqen.settings.updates.progress").assertExists()
        composeRule.runOnIdle { updater.emit(UpdateState.Downloading(release, 5_000_000, 10_000_000)) }
        composeRule.onNodeWithText(context.getString(
            R.string.update_downloading,
            Formatter.formatShortFileSize(context, 5_000_000),
            Formatter.formatShortFileSize(context, 10_000_000),
        )).assertExists()

        composeRule.runOnIdle { updater.emit(UpdateState.ReadyToInstall(release, requiresInstallPermission = true)) }
        composeRule.onNodeWithText(context.getString(R.string.update_permission_needed)).assertExists()
        composeRule.onNodeWithTag("vesqen.settings.updates.install").performClick()
        // The runtime opens the system permission; nothing installs until the user returns.
        composeRule.runOnIdle { assertTrue(updater.snapshot.value.state is UpdateState.ReadyToInstall) }

        composeRule.runOnIdle { updater.emit(UpdateState.Failed(UpdateFailure.SIGNATURE_MISMATCH, release)) }
        composeRule.onNodeWithTag("vesqen.settings.updates.failure")
            .assertTextEquals(context.getString(R.string.update_failure_signature))
        composeRule.onNodeWithTag("vesqen.settings.updates.retry").assertExists()
        composeRule.runOnIdle {
            updater.emit(UpdateState.Failed(UpdateFailure.UNSUPPORTED_ANDROID_VERSION, release.copy(minimumAndroidApi = 99)))
        }
        composeRule.onNodeWithTag("vesqen.settings.updates.failure")
            .assertTextEquals(context.getString(R.string.update_requires_android, 99))
        composeRule.onAllNodesWithTag("vesqen.settings.updates.retry").assertCountEquals(0)
    }

    @Test
    fun settings_leaves_updates_to_google_play_when_it_installed_the_app() {
        val updater = FakeAppUpdater(UpdateSnapshot(
            state = UpdateState.ManagedExternally(UpdateInstallationSource.GOOGLE_PLAY, "com.android.vending"),
            automaticChecksEnabled = false,
            installationSource = UpdateInstallationSource.GOOGLE_PLAY,
            installerPackageName = "com.android.vending",
        ))
        render(grantedState(), appUpdater = updater)
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.updates.check"))
        composeRule.onNodeWithTag("vesqen.settings.updates.check")
            .assert(hasText(context.getString(R.string.update_managed, "Google Play")))
            .assertHasNoClickAction()
        composeRule.onAllNodesWithTag("vesqen.settings.updates.automatic").assertCountEquals(0)
    }

    @Test
    fun settings_turns_automatic_update_checks_off() {
        val updater = FakeAppUpdater()
        render(grantedState(), appUpdater = updater)
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.updates.automatic"))
        composeRule.onNodeWithTag("vesqen.settings.updates.automatic").assertIsOn().performClick()
        composeRule.runOnIdle { assertFalse(updater.snapshot.value.automaticChecksEnabled) }
        composeRule.onNodeWithTag("vesqen.settings.updates.automatic").assertIsOff()
    }

    @Test
    fun settings_show_statistics_only_with_a_server_and_turn_them_off() {
        val usage = FakeUsageStatistics(UsageStatisticsSnapshot(
            status = UsageSettingsStatus.READY,
            enabled = true,
            introductionRequired = false,
            endpointConfigured = false,
        ))
        render(grantedState(), usageStatistics = usage, deviceReporter = FakeDeviceReporter())
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.device-report"))
        // Without a server Vesqen never sends statistics, so it does not offer them.
        composeRule.onAllNodesWithTag("vesqen.settings.usage-statistics").assertCountEquals(0)
        composeRule.runOnIdle { usage.emit(usage.snapshot.value.copy(endpointConfigured = true)) }
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.usage-statistics"))
        composeRule.onNodeWithTag("vesqen.settings.usage-statistics")
            .assert(hasText(context.getString(R.string.settings_usage_on)))
            .performClick()
        composeRule.onNodeWithTag("vesqen.usage").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.usage.switch").assertIsOn().performClick()
        composeRule.runOnIdle { assertFalse(usage.snapshot.value.enabled) }
        composeRule.onNodeWithTag("vesqen.usage.switch").assertIsOff()
        composeRule.onNodeWithTag("vesqen.usage.back").performClick()
        composeRule.onNodeWithTag("vesqen.settings.usage-statistics")
            .assert(hasText(context.getString(R.string.settings_usage_off)))
    }

    @Test
    fun settings_show_a_paused_statistics_service_and_drop_a_retired_one() {
        val usage = FakeUsageStatistics(UsageStatisticsSnapshot(
            status = UsageSettingsStatus.READY,
            enabled = true,
            introductionRequired = false,
            endpointConfigured = true,
            service = ServiceState.PAUSED,
        ))
        render(grantedState(), usageStatistics = usage, deviceReporter = FakeDeviceReporter())
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.usage-statistics"))
        // #96: paused by the owner reads "Paused", not "On", and the page explains it.
        composeRule.onNodeWithTag("vesqen.settings.usage-statistics")
            .assert(hasText(context.getString(R.string.settings_usage_paused)))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.usage_paused)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.usage.back").performClick()
        composeRule.runOnIdle { usage.emit(usage.snapshot.value.copy(service = ServiceState.RETIRED)) }
        composeRule.onAllNodesWithTag("vesqen.settings.usage-statistics").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.device-report"))
        composeRule.onNodeWithTag("vesqen.settings.device-report").assertIsDisplayed()
    }

    @Test
    fun usage_introduction_where_statistics_start_on_keeps_the_switch_on_the_same_page() {
        val usage = FakeUsageStatistics(UsageStatisticsSnapshot(
            status = UsageSettingsStatus.READY,
            enabled = true,
            regionPolicy = UsageRegionPolicy.DEFAULT_ENABLED,
            introductionRequired = true,
            endpointConfigured = true,
        ))
        composeRule.setContent { VesqenTheme { UsageIntroductionScreen(usage) } }
        composeRule.onAllNodesWithTag("vesqen.usage-intro.accept").assertCountEquals(0)
        // The answer is pinned on the first screen; it never needs a scroll to reach.
        composeRule.onNodeWithTag("vesqen.usage-intro.switch").assertIsDisplayed().assertIsOn().performClick()
        composeRule.onNodeWithTag("vesqen.usage-intro.continue").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertFalse(usage.snapshot.value.introductionRequired)
            assertFalse(usage.snapshot.value.enabled)
        }
    }

    @Test
    fun usage_introduction_answer_fits_a_small_phone_without_scrolling() {
        val usage = FakeUsageStatistics(UsageStatisticsSnapshot(
            status = UsageSettingsStatus.READY,
            enabled = true,
            regionPolicy = UsageRegionPolicy.DEFAULT_ENABLED,
            introductionRequired = true,
            endpointConfigured = true,
        ))
        composeRule.setContent {
            VesqenTheme {
                Box(Modifier.width(360.dp).height(640.dp).testTag("frame")) { UsageIntroductionScreen(usage) }
            }
        }
        val frame = composeRule.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        listOf("vesqen.usage-intro.switch", "vesqen.usage-intro.continue").forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must sit fully inside the first screen", bounds.top >= frame.top && bounds.bottom <= frame.bottom)
        }
    }

    @Test
    fun usage_introduction_answer_stays_reachable_in_a_very_short_window() {
        val usage = FakeUsageStatistics(UsageStatisticsSnapshot(
            status = UsageSettingsStatus.READY,
            enabled = true,
            regionPolicy = UsageRegionPolicy.DEFAULT_ENABLED,
            introductionRequired = true,
            endpointConfigured = true,
        ))
        // Shorter than the answer itself, as in split screen: pinning would cut the button off.
        composeRule.setContent {
            VesqenTheme {
                Box(Modifier.width(360.dp).height(140.dp)) { UsageIntroductionScreen(usage) }
            }
        }
        composeRule.onNodeWithTag("vesqen.usage-intro.continue").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertFalse(usage.snapshot.value.introductionRequired) }
    }

    @Test
    fun usage_introduction_where_consent_comes_first_offers_two_answers_and_no_switch() {
        val usage = FakeUsageStatistics(UsageStatisticsSnapshot(
            status = UsageSettingsStatus.READY,
            enabled = false,
            regionPolicy = UsageRegionPolicy.EXPLICIT_CONSENT,
            introductionRequired = true,
            endpointConfigured = true,
        ))
        composeRule.setContent { VesqenTheme { UsageIntroductionScreen(usage) } }
        composeRule.onAllNodesWithTag("vesqen.usage-intro.switch").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.usage-intro.decline").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.usage-intro.accept").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertFalse(usage.snapshot.value.introductionRequired)
            assertTrue(usage.snapshot.value.enabled)
            assertEquals(UsageConsent.ACCEPTED, usage.snapshot.value.consent)
        }
    }

    @Test
    fun device_report_previews_the_exact_report_then_uploads_and_shows_the_receipt() {
        val reporter = FakeDeviceReporter()
        render(grantedState(), deviceReporter = reporter, reportUploadAvailable = true)
        openDeviceReport()
        // File names belong to failed-track formats, so they wait for that group.
        composeRule.onNodeWithTag("vesqen.report.option.file-names").assertIsNotEnabled()
        composeRule.onNodeWithTag("vesqen.report.option.formats").performClick()
        composeRule.onNodeWithTag("vesqen.report.option.file-names").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("vesqen.report.option.errors").performClick()
        composeRule.runOnIdle {
            assertEquals(
                DeviceReportOptions(recentErrors = true, failedTrackFormats = true, includeFileNames = true),
                reporter.snapshot.value.options,
            )
        }
        composeRule.onNodeWithTag("vesqen.report.option.formats").performClick()
        composeRule.runOnIdle { assertFalse(reporter.snapshot.value.options.includeFileNames) }

        composeRule.onNodeWithTag("vesqen.report").performScrollToNode(hasTestTag("vesqen.report.create"))
        composeRule.onNodeWithTag("vesqen.report.create").performClick()
        composeRule.onNodeWithTag("vesqen.report.progress").assertExists()
        val text = "{\"schemaVersion\":1,\"basic\":{\"model\":\"V2171A\"}}\n"
        val artifact = DeviceReportArtifact(text.toByteArray(Charsets.UTF_8))
        composeRule.runOnIdle { reporter.emit(DeviceReportState.Preview(artifact)) }
        composeRule.onNodeWithTag("vesqen.report.preview.0").assertTextEquals(text)
        // The send buttons sit below the preview, so no scrolling is needed (#35).
        composeRule.onNodeWithTag("vesqen.report.upload").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(DeviceReportState.Sending(artifact, DeviceReportDelivery.UPLOAD), reporter.snapshot.value.state)
        }
        val receipt = "00000000-0000-4000-8000-000000000000"
        composeRule.runOnIdle { reporter.emit(DeviceReportState.Sent(artifact, DeviceReportDelivery.UPLOAD, receipt)) }
        composeRule.onNodeWithTag("vesqen.report.receipt").assertIsDisplayed().assertTextEquals(context.getString(R.string.report_receipt, receipt))
        // System Back from a preview returns to the choices instead of leaving the page.
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(DeviceReportState.Editing, reporter.snapshot.value.state) }
        composeRule.onNodeWithTag("vesqen.report").assertIsDisplayed()
    }

    @Test
    fun back_during_an_upload_leaves_the_page_and_keeps_the_receipt() {
        val reporter = FakeDeviceReporter()
        render(grantedState(), deviceReporter = reporter, reportUploadAvailable = true)
        openDeviceReport()
        val artifact = DeviceReportArtifact("{\"schemaVersion\":1}\n".toByteArray(Charsets.UTF_8))
        val sending = DeviceReportState.Sending(artifact, DeviceReportDelivery.UPLOAD)
        composeRule.runOnIdle { reporter.emit(DeviceReportState.Preview(artifact)) }
        composeRule.onNodeWithTag("vesqen.report.upload").performClick()

        // The upload may still reach the server, so Back leaves without discarding it.
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onAllNodesWithTag("vesqen.report").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(sending, reporter.snapshot.value.state) }
        val receipt = "00000000-0000-4000-8000-000000000000"
        composeRule.runOnIdle { reporter.emit(DeviceReportState.Sent(artifact, DeviceReportDelivery.UPLOAD, receipt)) }
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.device-report"))
        composeRule.onNodeWithTag("vesqen.settings.device-report").performClick()
        composeRule.onNodeWithTag("vesqen.report.receipt").assertIsDisplayed()

        // The header arrow keeps a send in flight too.
        composeRule.runOnIdle { reporter.emit(sending) }
        composeRule.onNodeWithTag("vesqen.report").performScrollToNode(hasTestTag("vesqen.report.back"))
        composeRule.onNodeWithTag("vesqen.report.back").performClick()
        composeRule.onAllNodesWithTag("vesqen.report").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(sending, reporter.snapshot.value.state) }
    }

    @Test
    fun device_report_without_a_server_shares_or_emails_and_explains_failures() {
        val reporter = FakeDeviceReporter()
        render(grantedState(), deviceReporter = reporter, reportUploadAvailable = false)
        openDeviceReport()
        // As long as a real report with every section; the buttons must still show without scrolling.
        val artifact = DeviceReportArtifact(("{\"schemaVersion\":1,\"x\":\"" + "a".repeat(45_000) + "\"}\n").toByteArray(Charsets.UTF_8))
        composeRule.runOnIdle { reporter.emit(DeviceReportState.Preview(artifact)) }
        composeRule.onAllNodesWithTag("vesqen.report.upload").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.report.share").assertIsDisplayed().performClick()
        composeRule.runOnIdle { reporter.emit(DeviceReportState.Failed(DeviceReportFailure.NO_SHARE_APPLICATION, artifact)) }
        composeRule.onNodeWithTag("vesqen.report.failure").assertIsDisplayed().assertTextEquals(context.getString(R.string.report_failure_no_app))
        composeRule.runOnIdle { reporter.emit(DeviceReportState.Sent(artifact, DeviceReportDelivery.EMAIL)) }
        composeRule.onNodeWithTag("vesqen.report.shared").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.report").performScrollToNode(hasTestTag("vesqen.report.start-over"))
        composeRule.onNodeWithTag("vesqen.report.start-over").performClick()
        composeRule.runOnIdle { assertEquals(DeviceReportState.Editing, reporter.snapshot.value.state) }
    }

    @Test
    fun device_report_buttons_keep_whole_labels_side_by_side_at_130_percent_text() {
        assertDeviceReportButtonLabels(width = 360.dp, fontScale = 1.3f)
    }

    @Test
    fun device_report_buttons_stack_with_whole_labels_at_200_percent_text() {
        assertDeviceReportButtonLabels(width = 320.dp, fontScale = 2f)
    }

    /** #118: three equal buttons used to break "Upload" and "Email" mid-word at large text. */
    private fun assertDeviceReportButtonLabels(width: Dp, fontScale: Float) {
        val reporter = FakeDeviceReporter()
        render(grantedState(), deviceReporter = reporter, reportUploadAvailable = true,
            containerWidth = width, containerHeight = 720.dp, fontScale = fontScale)
        openDeviceReport()
        val artifact = DeviceReportArtifact("{\"schemaVersion\":1}\n".toByteArray(Charsets.UTF_8))
        composeRule.runOnIdle { reporter.emit(DeviceReportState.Preview(artifact)) }
        listOf(R.string.report_upload, R.string.report_share, R.string.report_email)
            .forEach { assertSingleCompleteLine(context.getString(it)) }
    }

    @Test
    fun after_a_crash_the_question_appears_once_and_review_opens_the_report_with_crash_details_selected() {
        val reporter = FakeDeviceReporter()
        val crash = FakeCrashPrompt(due = true)
        render(grantedState(), deviceReporter = reporter, crashPrompt = crash)

        composeRule.onNodeWithTag("vesqen.crash-prompt").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.crash_prompt_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.crash_prompt_body)).assertIsDisplayed()
        assertClickablesAtLeast48Dp("Crash prompt")
        composeRule.runOnIdle { assertEquals(1, crash.shown) }
        composeRule.onNodeWithTag("vesqen.crash-prompt.review")
            .assertTextEquals(context.getString(R.string.crash_prompt_review)).performClick()

        composeRule.onNodeWithTag("vesqen.crash-prompt").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.report").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(DeviceReportOptions(recentErrors = true, crashes = true), reporter.snapshot.value.options)
            // Nothing is created or sent until the user asks for it on this page.
            assertEquals(DeviceReportState.Editing, reporter.snapshot.value.state)
            assertEquals(0, crash.declined)
        }
        composeRule.onNodeWithTag("vesqen.report").performScrollToNode(hasTestTag("vesqen.report.option.crashes"))
        composeRule.onNodeWithTag("vesqen.report.option.crashes").assertIsOn()
        composeRule.onNodeWithTag("vesqen.report.option.errors").assertIsOn()
        composeRule.onNodeWithTag("vesqen.report.option.chain").assertIsOff()

        // Back returns to where the question was asked, and it is not asked again.
        composeRule.onNodeWithTag("vesqen.report").performScrollToNode(hasTestTag("vesqen.report.back"))
        composeRule.onNodeWithTag("vesqen.report.back").performClick()
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
        composeRule.onNodeWithTag("vesqen.crash-prompt").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, crash.shown) }
    }

    @Test
    fun no_thanks_closes_the_crash_question_and_sends_nothing() {
        val reporter = FakeDeviceReporter()
        val crash = FakeCrashPrompt(due = true)
        render(grantedState(), deviceReporter = reporter, crashPrompt = crash)

        composeRule.onNodeWithTag("vesqen.crash-prompt.decline")
            .assertTextEquals(context.getString(R.string.crash_prompt_decline)).performClick()

        composeRule.onNodeWithTag("vesqen.crash-prompt").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(1, crash.declined)
            assertEquals(DeviceReportSnapshot(), reporter.snapshot.value)
        }
    }

    @Test
    fun the_crash_question_waits_until_a_crash_is_found() {
        val crash = FakeCrashPrompt(due = false)
        render(grantedState(), deviceReporter = FakeDeviceReporter(), crashPrompt = crash)
        composeRule.onNodeWithTag("vesqen.crash-prompt").assertDoesNotExist()

        // The records are read on IO after start, so the answer can arrive after the first frame.
        composeRule.runOnIdle { crash.emit(true) }
        composeRule.onNodeWithTag("vesqen.crash-prompt").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.crash-prompt.decline").performClick()
        composeRule.runOnIdle { assertEquals(1, crash.shown) }
    }

    @Test
    fun without_device_reports_the_crash_question_is_not_asked() {
        // A build without device reports has nowhere to send one.
        val crash = FakeCrashPrompt(due = true)
        render(grantedState(), crashPrompt = crash)
        composeRule.onNodeWithTag("vesqen.crash-prompt").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, crash.shown) }
    }

    @Test
    fun the_support_question_says_what_support_pays_for_and_later_closes_it() {
        val support = FakeSupportPrompt(due = true)
        render(grantedState(), supportPrompt = support, supportUrl = UnopenableSupportUrl)

        composeRule.onNodeWithTag("vesqen.support-prompt").assertIsDisplayed()
        assertClickablesAtLeast48Dp("Support prompt")
        composeRule.onNodeWithText(context.getString(R.string.support_prompt_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.support_prompt_body)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, support.checks)
            // Counted as soon as it is on screen, so leaving the app does not ask again at once.
            assertEquals(1, support.shown)
        }
        composeRule.onNodeWithTag("vesqen.support-prompt.later")
            .assertTextEquals(context.getString(R.string.support_prompt_later)).performClick()

        composeRule.onNodeWithTag("vesqen.support-prompt").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(1, support.shown)
            assertEquals(0, support.finished)
        }
    }

    @Test
    fun dont_ask_again_ends_the_support_question() {
        val support = FakeSupportPrompt(due = true)
        render(grantedState(), supportPrompt = support, supportUrl = UnopenableSupportUrl)

        composeRule.onNodeWithTag("vesqen.support-prompt.never")
            .assertTextEquals(context.getString(R.string.support_prompt_never)).performClick()

        composeRule.onNodeWithTag("vesqen.support-prompt").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, support.finished) }
    }

    @Test
    fun support_without_a_browser_keeps_the_question_and_says_so() {
        val support = FakeSupportPrompt(due = true)
        render(grantedState(), supportPrompt = support, supportUrl = UnopenableSupportUrl)

        composeRule.onNodeWithTag("vesqen.support-prompt.support")
            .assertTextEquals(context.getString(R.string.support_prompt_support)).performClick()

        composeRule.onNodeWithTag("vesqen.support-prompt").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.support-prompt.no-browser")
            .assertTextEquals(context.getString(R.string.privacy_policy_no_browser)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, support.finished) }
    }

    @Test
    fun the_support_question_waits_until_the_crash_records_are_read() {
        val crash = FakeCrashPrompt(due = false, checked = false)
        val support = FakeSupportPrompt(due = true)
        render(grantedState(), deviceReporter = FakeDeviceReporter(), crashPrompt = crash,
            supportPrompt = support, supportUrl = UnopenableSupportUrl)
        composeRule.onNodeWithTag("vesqen.support-prompt").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, support.shown) }

        composeRule.runOnIdle { crash.emitChecked() }
        composeRule.onNodeWithTag("vesqen.support-prompt").assertIsDisplayed()
    }

    @Test
    fun an_open_with_the_crash_question_does_not_also_ask_for_support() {
        val crash = FakeCrashPrompt(due = true)
        val support = FakeSupportPrompt(due = true)
        render(grantedState(), deviceReporter = FakeDeviceReporter(), crashPrompt = crash,
            supportPrompt = support, supportUrl = UnopenableSupportUrl)

        composeRule.onNodeWithTag("vesqen.crash-prompt").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.support-prompt").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.crash-prompt.decline").performClick()

        composeRule.onNodeWithTag("vesqen.crash-prompt").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.support-prompt").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, support.shown) }
    }

    @Test
    fun after_a_catalog_rebuild_the_notice_comes_once_before_the_other_questions() {
        val rebuilt = FakeLibraryRebuildNotice(due = true)
        val crash = FakeCrashPrompt(due = true)
        val support = FakeSupportPrompt(due = true)
        render(grantedState(), deviceReporter = FakeDeviceReporter(), crashPrompt = crash,
            supportPrompt = support, supportUrl = UnopenableSupportUrl, rebuildNotice = rebuilt)

        composeRule.onNodeWithTag("vesqen.library.rebuilt-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.library_rebuilt_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.library_rebuilt_body)).assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.crash-prompt").assertDoesNotExist()
        assertClickablesAtLeast48Dp("Library rebuilt notice")
        composeRule.runOnIdle { assertEquals(1, rebuilt.shown) }
        composeRule.onNodeWithTag("vesqen.library.rebuilt-ok")
            .assertTextEquals(context.getString(R.string.library_rebuilt_ok)).performClick()

        // The crash question follows it; this open asks for nothing else.
        composeRule.onNodeWithTag("vesqen.library.rebuilt-dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("vesqen.crash-prompt").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.crash-prompt.decline").performClick()
        composeRule.onNodeWithTag("vesqen.support-prompt").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, rebuilt.shown)
            assertEquals(0, support.shown)
        }
    }

    @Test
    fun a_catalog_rebuilt_while_the_app_is_open_is_announced_at_once() {
        val rebuilt = FakeLibraryRebuildNotice(due = false)
        render(grantedState(), rebuildNotice = rebuilt)
        composeRule.onNodeWithTag("vesqen.library.rebuilt-dialog").assertDoesNotExist()

        composeRule.runOnIdle { rebuilt.emit(true) }
        composeRule.onNodeWithTag("vesqen.library.rebuilt-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.library.rebuilt-ok").performClick()
        composeRule.onNodeWithTag("vesqen.library.rebuilt-dialog").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, rebuilt.shown) }
    }

    @Test
    fun without_a_support_link_the_support_question_is_not_asked() {
        // The Play build has no link (Google Play's Payments policy).
        val support = FakeSupportPrompt(due = true)
        render(grantedState(), supportPrompt = support, supportUrl = null)
        composeRule.onNodeWithTag("vesqen.support-prompt").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, support.shown) }
    }

    @Test
    fun support_question_buttons_keep_whole_labels_at_200_percent_text() {
        render(grantedState(), supportPrompt = FakeSupportPrompt(due = true),
            supportUrl = UnopenableSupportUrl, fontScale = 2f)

        composeRule.onNodeWithTag("vesqen.support-prompt").assertIsDisplayed()
        listOf(R.string.support_prompt_support, R.string.support_prompt_later, R.string.support_prompt_never)
            .forEach { assertSingleCompleteLine(context.getString(it)) }
        assertFooterActionsDoNotOverlap(
            "vesqen.support-prompt.support",
            "vesqen.support-prompt.later",
            "vesqen.support-prompt.never",
        )
    }

    @Test
    fun the_current_track_row_says_whether_it_is_playing_or_paused() {
        val playing = grantedState(
            tracks = sampleTracks,
            playback = PlaybackSnapshot(isControllerReady = true, isPlaying = true, trackId = 1, title = "Dawn Signal"),
        )
        var state by mutableStateOf(playing)
        render(playing, stateProvider = { state })

        // #35: TalkBack skipped the play marker's description, so the row carries the state itself.
        composeRule.onNodeWithTag("vesqen.library.track.1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.playing)),
        )
        composeRule.onNodeWithTag("vesqen.library.track.2")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        composeRule.runOnIdle { state = playing.copy(playback = playing.playback.copy(isPlaying = false)) }
        composeRule.onNodeWithTag("vesqen.library.track.1").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.paused)),
        )
    }

    @Test
    fun the_sort_menu_marks_the_current_order_as_selected() {
        render(grantedState(tracks = sampleTracks))

        composeRule.onNodeWithTag("vesqen.library.sort").performClick()

        composeRule.onNode(hasText(context.getString(R.string.sort_title)) and hasClickAction(), useUnmergedTree = false)
            .assertIsSelected()
        composeRule.onNode(hasText(context.getString(R.string.sort_artist)) and hasClickAction(), useUnmergedTree = false)
            .assertIsNotSelected()
    }

    @Test
    fun folders_lead_with_their_own_name_and_show_the_path_after_the_count() {
        render(grantedState(tracks = sampleTracks.map { it.copy(folderName = "Music/Demo/Quiet Rooms/") }))

        composeRule.onNodeWithTag("vesqen.library.mode.folders").performScrollTo().performClick()

        val count = context.resources.getQuantityString(R.plurals.collection_track_count, sampleTracks.size, sampleTracks.size)
        composeRule.onNodeWithText("Quiet Rooms").assertIsDisplayed()
        composeRule.onNodeWithText("$count · Music/Demo").assertIsDisplayed()
    }

    @Test
    fun the_chain_labels_a_paused_track_as_paused() {
        var playback by mutableStateOf(PlaybackSnapshot(isControllerReady = true, isPlaying = true, title = "Dawn Signal"))
        composeRule.setContent { VesqenTheme { ChainNowPlaying(playback) } }

        composeRule.onNodeWithText(context.getString(R.string.chain_current_playing)).assertIsDisplayed()
        composeRule.runOnIdle { playback = playback.copy(isPlaying = false, showsPauseAction = false) }
        // #35: it said "Now playing" above a notice that said nothing was playing.
        composeRule.onNodeWithText(context.getString(R.string.paused)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.chain_current_playing)).assertCountEquals(0)
    }

    @Test
    fun track_details_buttons_keep_whole_labels_at_200_percent_text() {
        render(grantedState(tracks = sampleTracks), fontScale = 2f)

        composeRule.onNodeWithTag("vesqen.library.track.1.more").performClick()

        // #35: side by side, "Play this track" was cut to "Play…"; the buttons now stack instead.
        assertSingleCompleteLine(context.getString(R.string.play_this_track))
        assertSingleCompleteLine(context.getString(R.string.favorite))
    }

    private fun openDeviceReport() {
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings").performScrollToNode(hasTestTag("vesqen.settings.device-report"))
        composeRule.onNodeWithTag("vesqen.settings.device-report").performClick()
        composeRule.onNodeWithTag("vesqen.report").assertIsDisplayed()
    }

    @Test
    fun settings_opens_a_real_about_surface_with_the_build_version() {
        render(grantedState(), versionName = "0.1.0", versionCode = 1)

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.about"))
        composeRule.onNodeWithTag("vesqen.settings.about").assert(hasText("0.1.0")).assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.settings.about").performClick()

        composeRule.onNodeWithTag("vesqen.about").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_version_value, "0.1.0", 1))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_license_value)).assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.nav.settings").assertCountEquals(0)
        // #39: the page says plainly that nothing is backed up or moved to a new phone.
        composeRule.onNodeWithTag("vesqen.about").performScrollToNode(hasTestTag("vesqen.about.data"))
        composeRule.onNodeWithText(context.getString(R.string.about_data_body)).assertIsDisplayed()

        // On a 640 dp phone the header has scrolled away by now; it scrolls with the page.
        composeRule.onNodeWithTag("vesqen.about").performScrollToNode(hasTestTag("vesqen.about.back"))
        composeRule.onNodeWithTag("vesqen.about.back").performClick()
        composeRule.onNodeWithTag("vesqen.settings").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.nav.settings").assertIsSelected()
    }

    @Test
    fun only_the_github_build_offers_support_development_in_about() {
        render(grantedState())

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.about"))
        composeRule.onNodeWithTag("vesqen.settings.about").performClick()

        // Google Play's Payments policy does not allow a link to another payment method (2026-10-19).
        if (BuildConfig.FLAVOR == "github") {
            composeRule.onNodeWithTag("vesqen.about").performScrollToNode(hasTestTag("vesqen.about.support"))
            composeRule.onNodeWithTag("vesqen.about.support")
                .assert(hasText(context.getString(R.string.about_support)))
                .assert(hasText(context.getString(R.string.about_support_value)))
                .assert(hasClickAction())
                .assertIsDisplayed()
        } else {
            composeRule.onAllNodesWithTag("vesqen.about.support").assertCountEquals(0)
            composeRule.onAllNodesWithText(context.getString(R.string.about_support_value)).assertCountEquals(0)
        }
    }

    @Test
    fun about_opens_the_packaged_privacy_policy_and_back_unwinds_both_levels() {
        render(grantedState())

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.about"))
        composeRule.onNodeWithTag("vesqen.settings.about").performClick()
        composeRule.onNodeWithTag("vesqen.about")
            .performScrollToNode(hasTestTag("vesqen.about.privacy"))
        composeRule.onNodeWithTag("vesqen.about.privacy").performClick()

        composeRule.onNodeWithTag("vesqen.privacy").assertIsDisplayed()
        // The policy is read from the packaged assets off the main thread.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Vesqen", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onAllNodesWithText(context.getString(R.string.privacy_policy_unavailable))
            .assertCountEquals(0)

        composeRule.onNodeWithTag("vesqen.privacy.back").performClick()
        composeRule.onNodeWithTag("vesqen.about").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.about.back").performClick()
        composeRule.onNodeWithTag("vesqen.settings").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.nav.settings").assertIsSelected()
    }

    @Test
    fun settings_groups_output_proof_and_application_controls_in_reading_order() {
        render(grantedState())

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.section.playback-output").assertIsDisplayed()
        // The two radio rows, and only they, form the selectable group.
        composeRule.onNodeWithTag("vesqen.settings.output-modes")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        composeRule.onNodeWithTag("vesqen.settings.output.system").assertIsSelected()
        composeRule.onNodeWithTag("vesqen.settings.output.system").assertIsNotEnabled()
        composeRule.onNodeWithTag("vesqen.settings.output.strict-usb").assertIsNotEnabled()
        val systemOutput = composeRule.onNodeWithTag("vesqen.settings.output.system")
            .fetchSemanticsNode().boundsInRoot
        val strictUsb = composeRule.onNodeWithTag("vesqen.settings.output.strict-usb")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Strict USB must follow System output", strictUsb.top >= systemOutput.bottom)

        // Scroll to each row itself: a section heading at the screen edge leaves its rows below.
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.about"))
        composeRule.onNodeWithTag("vesqen.settings.about").assertIsDisplayed()
        // Output verification records are a maintainer tool, so they sit in Advanced after the app info.
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.verification-registry"))
        composeRule.onNodeWithTag("vesqen.settings.verification-registry").assertIsDisplayed()
        val about = composeRule.onNodeWithTag("vesqen.settings.about").fetchSemanticsNode().positionInRoot
        val registry = composeRule.onNodeWithTag("vesqen.settings.verification-registry").fetchSemanticsNode().positionInRoot
        assertTrue("Advanced must follow the application group", registry.y > about.y)
    }

    @Test
    fun output_status_chips_render_distinct_non_color_cues() {
        composeRule.setContent {
            VesqenTheme {
                Column {
                    OutputDeclaration.entries.forEach { declaration ->
                        OutputStatusChip(declaration = declaration)
                    }
                }
            }
        }

        // Each chip is one merged TalkBack stop, so its decorative cue icon exists only in the unmerged tree.
        listOf("route", "availability", "requested", "activity", "verified", "failure").forEach { cue ->
            composeRule.onNodeWithTag("vesqen.output-status.cue.$cue", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun settings_output_modes_enable_only_when_session_grants_the_command() {
        render(
            grantedState(
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    canSetUsbOutputMode = true,
                ),
            ),
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.output.system").assertIsEnabled()
        composeRule.onNodeWithTag("vesqen.settings.output.strict-usb").assertIsEnabled()
    }

    @Test
    fun unavailable_official_mixer_path_is_dimmed_explained_and_never_requested() {
        val modes = mutableListOf<UsbOutputMode>()
        val unsupportedStatus = UsbOutputStatus(
            officialMixerApiSupport = OfficialMixerApiSupport(
                androidRelease = "9",
                apiLevel = 28,
                mixerApiAvailable = false,
            ),
        )
        render(
            state = activePlaybackState().let { state ->
                state.copy(
                    playback = state.playback.copy(usbOutputStatus = unsupportedStatus),
                )
            },
            onSetUsbOutputMode = modes::add,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings.output.strict-usb").performClick()
        composeRule.onNodeWithTag("vesqen.output.unavailable-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.strict_usb_unavailable_body, "9", 28),
        ).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(modes.isEmpty()) }
        composeRule.onNodeWithTag("vesqen.output.unavailable-dismiss").performClick()

        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        openLinerNotes()
        composeRule.onNodeWithTag("vesqen.now.output-mode").performScrollTo().performClick()
        composeRule.onNodeWithTag("vesqen.now.strict-usb-switch").assertIsNotEnabled()
        composeRule.onNodeWithTag("vesqen.now.strict-usb-row").performClick()
        composeRule.onNodeWithTag("vesqen.output.unavailable-dialog").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(modes.isEmpty()) }
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
        // A connected controller: the disconnected notice below the queue actions has its own test.
        render(
            grantedState(tracks = listOf(detailedTrack), playback = PlaybackSnapshot(isControllerReady = true)),
            containerHeight = 640.dp,
        )

        composeRule.onNodeWithTag("vesqen.library.track.1.more").performClick()
        val headerBefore = composeRule.onNodeWithTag("vesqen.track-details.header")
            .fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithTag("vesqen.track-details.add-to-queue").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.track-details.end").performScrollTo()
        composeRule.onNodeWithTag("vesqen.track-details.content").performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        val headerAfter = composeRule.onNodeWithTag("vesqen.track-details.header")
            .fetchSemanticsNode().boundsInRoot
        val viewportBottom = composeRule.onNodeWithTag("vesqen.track-details.content")
            .fetchSemanticsNode().boundsInRoot.bottom
        // A layout position: the zero-height marker's clipped bounds would read as empty.
        val end = composeRule.onNodeWithTag("vesqen.track-details.end").fetchSemanticsNode()
        val contentEnd = end.positionInRoot.y + end.size.height
        // Only the content's own 16 dp bottom padding may follow it: the sheet wraps its content.
        val maximumBottomGap = with(fixtureDensity) { 24.dp.toPx() }

        assertEquals("The details title must stay fixed while metadata scrolls", headerBefore, headerAfter)
        assertTrue(
            "The details must end near the sheet bottom; gap=${viewportBottom - contentEnd}px",
            viewportBottom - contentEnd <= maximumBottomGap,
        )
    }

    @Test
    fun track_details_play_still_plays_after_its_play_count_changes() {
        // #136 R28: the sheet kept the track as it was when opened. Once a listen raised its play
        // count, Play looked that copy up in the album and found nothing, so the tap did nothing.
        var state by mutableStateOf(grantedState(tracks = sampleTracks))
        var queued = emptyList<Long>()
        var startIndex = -1
        render(
            state,
            stateProvider = { state },
            onPlayQueue = { tracks, index ->
                queued = tracks.map(AudioTrack::id)
                startIndex = index
            },
        )
        composeRule.onNodeWithTag("vesqen.library.mode.albums").performClick()
        composeRule.onNodeWithText("Quiet Rooms").performClick()
        composeRule.onNodeWithTag("vesqen.library.track.2.more").performClick()

        composeRule.runOnIdle {
            state = grantedState(tracks = sampleTracks.map { if (it.id == 2L) it.copy(playCount = 1) else it })
        }
        composeRule.onNodeWithText(context.getString(R.string.play_this_track)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(1L, 2L), queued)
            assertEquals(1, startIndex)
        }
    }

    @Test
    fun track_details_disables_queue_mutations_until_playback_controls_connect() {
        render(grantedState(tracks = sampleTracks))

        composeRule.onNodeWithTag("vesqen.library.track.1.more").performClick()
        composeRule.onNodeWithTag("vesqen.track-details.add-to-queue").performScrollTo()

        composeRule.onNodeWithText(context.getString(R.string.play_next)).assertIsNotEnabled()
        composeRule.onNodeWithTag("vesqen.track-details.add-to-queue").assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.playback_controls_connecting))
            .assertIsDisplayed()
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
        composeRule.onNodeWithTag("vesqen.now.notes-toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.lyrics-toggle").assertIsDisplayed()
        // #169: the orientation toggle moved into the "more" menu (owner decision 1).
        composeRule.onNodeWithTag("vesqen.now.more").performClick()
        composeRule.onNodeWithTag("vesqen.now.orientation-toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.more.info").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.onAllNodesWithTag("vesqen.now.orientation-toggle").assertCountEquals(0)
        openLinerNotes()
        composeRule.onNodeWithTag("vesqen.now.info").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.open-chain").performScrollTo()
        composeRule.onNode(
            hasContentDescription(context.getString(R.string.output_status_label)) and
                hasText(context.getString(R.string.system_mixed)),
        ).assertIsDisplayed()
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
    fun liner_notes_observe_telemetry_only_while_the_app_is_visible() {
        // Open notes stay composed in the background; their observer must not keep the sampler running.
        val telemetry = FakePlaybackTelemetry(chainTelemetrySnapshot())
        render(state = activePlaybackState(), playbackTelemetry = telemetry)
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.notes-toggle").performClick()
        composeRule.waitUntil(5_000) { telemetry.activeObservationCount == 1 }

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        try {
            composeRule.waitUntil(5_000) { telemetry.activeObservationCount == 0 }
        } finally {
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        composeRule.waitUntil(5_000) { telemetry.activeObservationCount == 1 }
    }

    @Test
    fun chain_observation_runs_only_while_visible_and_advanced_uses_saved_selection() {
        val telemetry = FakePlaybackTelemetry(chainTelemetrySnapshot())
        render(
            state = activePlaybackState(),
            playbackTelemetry = telemetry,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        composeRule.waitUntil(5_000) { telemetry.activeObservationCount == 1 }
        val summarySelection = (telemetry.observationHistory.last().selection as TelemetryMetricSelection.Explicit).metricIds
        assertEquals(summaryMetricIds, summarySelection)
        // The segment verdict (#72) is reachable only when every input it reads is requested.
        assertTrue(summarySelection.containsAll(AppSegmentMetricIds))

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
    fun chain_labels_kept_values_as_the_last_playback_until_playback_starts() {
        // Paused, stopped by strict output or restored from the queue: the telemetry session stays open
        // for the current item while its values come from the last time it played.
        val telemetry = FakePlaybackTelemetry(chainTelemetrySnapshot())
        val playing = activePlaybackState()
        val currentState = mutableStateOf(
            playing.copy(playback = playing.playback.copy(isPlaying = false, showsPauseAction = false)),
        )
        render(
            state = currentState.value,
            stateProvider = { currentState.value },
            playbackTelemetry = telemetry,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        chainNode("vesqen.chain.idle", "vesqen.chain.summary-list").assertIsDisplayed()
        // Metrics are missing here, but the unavailable count belongs to the partial notice only.
        composeRule.onNodeWithText(context.getString(R.string.chain_idle_title)).assertIsDisplayed()
        chainSummaryText(R.string.chain_last_path).assertIsDisplayed()
        // Kept values are not judged as the current path.
        composeRule.onNodeWithTag("vesqen.chain.segment.app")
            .assertTextContains(context.getString(R.string.chain_segment_waiting), substring = true)

        // No scrolling until the heading check, so the path stays composed.
        composeRule.runOnIdle { currentState.value = playing }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.chain_current_path))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onAllNodesWithText(context.getString(R.string.chain_last_path)).assertCountEquals(0)
        // Back at the top the notice slot is composed again, so its absence means something.
        composeRule.onNodeWithTag("vesqen.chain.summary-list").performScrollToIndex(0)
        composeRule.onAllNodesWithTag("vesqen.chain.idle").assertCountEquals(0)
    }

    @Test
    fun chain_advanced_observes_summary_defaults_and_the_path_refreshes_for_a_repeated_track() {
        val telemetry = FakePlaybackTelemetry(chainTelemetrySnapshot(codecLabel = "FLAC"))
        val selectedMetricId = TelemetryMetricCatalog.PROCESS_DATA_SOURCE_READ_THROUGHPUT
        val preferences = InMemoryChainDashboardPreferencesRepository(
            ChainDashboardPreferences(selectedMetricIds = listOf(selectedMetricId)),
        )
        render(
            state = activePlaybackState(),
            playbackTelemetry = telemetry,
            chainPreferencesRepository = preferences,
            // Chain is a tab, so the rail sits beside it. Chain keeps 840dp; the extra 1dp absorbs
            // the rail's rounding to whole pixels at the fitted test density.
            containerWidth = 840.dp + NavigationRailWidth + 1.dp,
            containerHeight = 720.dp,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()
        composeRule.waitUntil(5_000) {
            (telemetry.observationHistory.lastOrNull()?.selection as? TelemetryMetricSelection.Explicit)
                ?.metricIds == summaryMetricIds + selectedMetricId
        }
        val advancedSelection = telemetry.observationHistory.last().selection as TelemetryMetricSelection.Explicit
        assertEquals(summaryMetricIds + selectedMetricId, advancedSelection.metricIds)

        // The path lives in the summary; a new session for the same track must refresh it.
        composeRule.onNodeWithTag("vesqen.chain.show-summary").performClick()
        val codec = "vesqen.chain.core-value.${TelemetryMetricCatalog.SOURCE_CODEC_LABEL.value}"
        chainNode(codec, "vesqen.chain.summary-list", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("FLAC", substring = true)
        telemetry.publish(
            chainTelemetrySnapshot(
                codecLabel = "ALAC",
                playbackSessionId = "instrumentation-session-repeat",
            ),
        )
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithTag(codec, useUnmergedTree = true).assertTextContains("ALAC", substring = true)
            }.isSuccess
        }
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
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
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
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
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

        composeRule.onNodeWithTag("vesqen.now.notes-toggle").performClick()
        composeRule.mainClock.advanceTimeBy(800)
        // performScrollTo repeats until the chip is in view, and the semantic scroll is animated,
        // so it needs a running clock. Short phones put the chip below the fold.
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("vesqen.now.open-chain").performScrollTo()
        composeRule.mainClock.autoAdvance = false
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
    fun library_favorites_uses_a_reversible_hierarchy_transition() {
        val tracks = listOf(
            sampleTracks.first().copy(isFavorite = true, favoritePosition = 0L),
            sampleTracks.last(),
        )
        render(
            state = grantedState(tracks = tracks),
            motionPolicy = VesqenMotionPolicy(reduceMotion = false),
        )
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag("vesqen.library.favorites").performClick()
        composeRule.mainClock.advanceTimeBy(72)
        val enteringFavorites = composeRule.onNodeWithTag("vesqen.library.title.favorites")
            .fetchSemanticsNode().positionInRoot
        composeRule.mainClock.advanceTimeBy(240)
        val favoritesRest = composeRule.onNodeWithTag("vesqen.library.title.favorites")
            .fetchSemanticsNode().positionInRoot
        assertTrue(
            "Favorites must enter from the detail side",
            enteringFavorites.x > favoritesRest.x + 8f,
        )

        composeRule.onNodeWithContentDescription(context.getString(R.string.show_all_music)).performClick()
        composeRule.mainClock.advanceTimeBy(72)
        val leavingFavorites = composeRule.onNodeWithTag("vesqen.library.title.favorites")
            .fetchSemanticsNode().positionInRoot
        assertTrue(
            "Favorites must reverse toward the detail side",
            leavingFavorites.x > favoritesRest.x + 8f,
        )
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("vesqen.library.title.root").assertIsDisplayed()
    }

    @Test
    fun library_restores_scroll_position_after_settings_round_trip() {
        val tracks = (0L..149L).map { id ->
            AudioTrack(
                id = id,
                contentUri = "content://media/external/audio/media/$id",
                title = "Track ${id.toString().padStart(3, '0')}",
                artist = "Fixture",
                album = "Scroll state",
                durationMs = 1_000,
            )
        }
        render(state = grantedState(tracks = tracks))

        composeRule.onNodeWithTag("vesqen.library.tracks").performScrollToIndex(80)
        composeRule.onNodeWithText("Track 080").assertIsDisplayed()

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.nav.library").performClick()

        composeRule.onNodeWithText("Track 080").assertIsDisplayed()
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
    fun chain_path_keeps_station_facts_in_one_column_and_aged_evidence_on_one_line_at_130_percent_text() {
        // Evidence two minutes old needs two minutes of uptime; a freshly booted device waits for it.
        SystemClock.sleep((121_000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
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
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()

        composeRule.onNodeWithTag("vesqen.chain.summary-list")
            .performScrollToNode(hasTestTag("vesqen.chain.core.source.sample_rate"))
        // Compare layout positions, not viewport-clipped rectangles: large text scrolls the
        // second group below the fold, where boundsInRoot becomes an empty rectangle.
        val sourcePosition = composeRule.onNodeWithTag("vesqen.chain.core.source.sample_rate")
            .assertIsDisplayed().fetchSemanticsNode().positionInRoot
        val playbackPosition = composeRule.onNodeWithTag("vesqen.chain.core.playback.audio_track_sample_rate")
            .fetchSemanticsNode().positionInRoot
        assertEquals(
            "Station facts must share the path's column",
            sourcePosition.x,
            playbackPosition.x,
            1f,
        )
        assertTrue("AudioTrack facts must follow source facts down the path", playbackPosition.y > sourcePosition.y)

        composeRule.onNodeWithTag("vesqen.chain.summary-list")
            .performScrollToNode(hasTestTag("vesqen.chain.core.playback.audio_track_sample_rate"))
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(
            "vesqen.chain.core-evidence.playback.audio_track_sample_rate",
            useUnmergedTree = true,
        ).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount)
        assertTrue(
            "Station evidence annotation must not be clipped: " +
                "size=${layout.size}, overflowWidth=${layout.didOverflowWidth}, " +
                "overflowHeight=${layout.didOverflowHeight}, source=$sourcePosition, playback=$playbackPosition",
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
    fun a_compact_card_says_why_it_is_unavailable_and_opens_to_its_evidence() {
        val metricId = TelemetryMetricCatalog.ROUTE_BLUETOOTH_TRANSPORT_BITRATE
        val snapshot = chainTelemetrySnapshot().let { snapshot ->
            snapshot.copy(metrics = snapshot.metrics + TelemetryMetric(
                id = metricId,
                section = TelemetrySection.ROUTE,
                evidence = TelemetryEvidence.Unavailable(
                    reason = TelemetryUnavailableReason.NOT_EXPOSED_BY_PLATFORM,
                    observedAtEpochMs = snapshot.capturedAtEpochMs,
                    source = TelemetryDataSource(TelemetrySourceId("android.audio_route")),
                    observedAtElapsedRealtimeMs = snapshot.capturedAtElapsedRealtimeMs,
                ),
            ))
        }
        val preferences = InMemoryChainDashboardPreferencesRepository(
            ChainDashboardPreferences(
                selectedMetricIds = listOf(metricId),
                metricOrder = listOf(metricId),
                viewMode = ChainMetricViewMode.COMPACT,
            ),
        )
        render(
            state = activePlaybackState(),
            playbackTelemetry = FakePlaybackTelemetry(snapshot),
            chainPreferencesRepository = preferences,
        )
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()

        val card = "vesqen.chain.metric.${metricId.value}"
        val source = context.getString(R.string.chain_source_android_audio_route)
        val updated = context.getString(R.string.chain_metric_updated)
        fun state(resource: Int) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(resource))
        chainNode(card).assertTextContains(context.getString(R.string.chain_unavailable_not_exposed), substring = true)
            .assert(!hasText(source, substring = true))
            .assert(state(R.string.now_notes_collapsed))
            .performClick()
        composeRule.onNodeWithTag(card)
            .assertTextContains(source, substring = true)
            .assertTextContains(updated, substring = true)
            .assert(state(R.string.now_notes_expanded))
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
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
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
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
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
            composeRule.onNodeWithTag("vesqen.settings")
                .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
            composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
            openAdvancedChain()
            chainNode("vesqen.chain.diagnostics.start").performClick()
            composeRule.waitUntil(5_000) { recorder.state.value is DiagnosticRecordingState.Active }

            composeRule.onNodeWithTag("vesqen.chain.show-summary").performClick()
            composeRule.onNodeWithTag("vesqen.chain.back").performClick()
            composeRule.waitUntil(5_000) { telemetry.activeObservationCount == 1 }
            assertTrue(recorder.state.value is DiagnosticRecordingState.Active)

            composeRule.onNodeWithTag("vesqen.settings")
                .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
            composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
            openAdvancedChain()
            chainNode("vesqen.chain.diagnostics.stop").performClick()
            composeRule.waitUntil(5_000) { recorder.state.value is DiagnosticRecordingState.Stopped }

            // Stopping replaces the stop button with export and clear, which can fall below the fold
            // (Chinese text on a 360 dp phone does). A click on a node scrolled out of view misses it.
            chainNode("vesqen.chain.diagnostics.export").performClick()
            composeRule.runOnIdle {
                assertEquals(1, exportRequests)
                assertTrue(recorder.state.value is DiagnosticRecordingState.Stopped)
            }
            chainNode("vesqen.chain.diagnostics.clear").performClick()
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
            composeRule.onNodeWithTag("vesqen.settings")
                .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
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
    fun chain_omits_diagnostic_controls_when_build_does_not_supply_a_recorder() {
        composeRule.setContent {
            VesqenTheme(darkTheme = true) {
                ChainScreen(
                    snapshot = activePlaybackState().playback,
                    playbackTelemetry = FakePlaybackTelemetry(chainTelemetrySnapshot()),
                    preferencesRepository = InMemoryChainDashboardPreferencesRepository(),
                    diagnosticRecorder = null,
                    diagnosticExportFeedback = DiagnosticExportFeedback.NONE,
                    onRequestDiagnosticExport = {},
                    onClearDiagnosticExportFeedback = {},
                    onBack = {},
                    onBrowseLibrary = {},
                )
            }
        }
        openAdvancedChain()

        composeRule.onAllNodesWithTag("vesqen.chain.diagnostics").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.chain.diagnostics.start").assertCountEquals(0)
    }

    @Test
    fun chain_offers_the_explicit_system_fallback_next_to_a_strict_failure() {
        val failure = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.FAILED,
            failure = UsbOutputFailure.NO_USB_AUDIO_DEVICE,
            failureOrigin = UsbOutputFailureOrigin.USER_PLAYBACK,
            generation = 1,
        )
        val active = activePlaybackState()
        val modes = mutableListOf<UsbOutputMode>()
        render(
            active.copy(playback = active.playback.copy(usbOutputStatus = failure)),
            playbackTelemetry = FakePlaybackTelemetry(chainTelemetrySnapshot()),
            onSetUsbOutputMode = { modes += it },
        )
        // Keeping strict output in the dialog leaves the same choice next to the claim in Chain.
        composeRule.onNodeWithTag("vesqen.output.keep-strict").performClick()
        composeRule.onNodeWithTag("vesqen.nav.chain").performClick()
        chainNode("vesqen.chain.use-system", "vesqen.chain.summary-list").performClick()
        composeRule.runOnIdle { assertEquals(listOf(UsbOutputMode.SYSTEM), modes) }
    }

    @Test
    fun chain_source_title_is_available_from_settings_and_player() {
        val active = activePlaybackState()
        render(active, playbackTelemetry = FakePlaybackTelemetry(chainTelemetrySnapshot()))
        val expected = active.playback.title
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        composeRule.onNodeWithTag("vesqen.chain.current-source").assertTextEquals(expected)
        openAdvancedChain()
        composeRule.onNodeWithTag("vesqen.chain.current-source").assertTextEquals(expected)
        composeRule.onNodeWithTag("vesqen.chain.back").performClick()
        composeRule.onNodeWithTag("vesqen.chain.back").performClick()
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        openLinerNotes()
        composeRule.onNodeWithTag("vesqen.now.open-chain").performScrollTo().performClick()
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
        // At 2x text the opened controls can sit below the grid's composed items; bring them in first.
        val controls = chainNode("vesqen.chain.dashboard-controls").fetchSemanticsNode().boundsInRoot
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
            // Chain is a tab, so the rail sits beside it. Chain keeps 600dp; the extra 1dp absorbs
            // the rail's rounding to whole pixels at the fitted test density.
            containerWidth = 600.dp + NavigationRailWidth + 1.dp,
            containerHeight = 720.dp,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
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
    fun chain_numeric_readouts_do_not_reflow_at_360dp_in_raw_units() {
        assertChainNumericReadoutsStayStable(360.dp, ChainUnitDisplayMode.RAW)
    }

    @Test
    fun chain_numeric_readouts_do_not_reflow_at_600dp_in_raw_units() {
        assertChainNumericReadoutsStayStable(600.dp, ChainUnitDisplayMode.RAW)
    }

    @Test
    fun chain_numeric_readouts_do_not_reflow_at_600dp_in_auto_units() {
        assertChainNumericReadoutsStayStable(600.dp, ChainUnitDisplayMode.AUTO)
    }

    @Test
    fun chain_advanced_keeps_fixed_summary_left_of_metrics_at_840dp() {
        render(
            state = activePlaybackState(),
            playbackTelemetry = FakePlaybackTelemetry(chainTelemetrySnapshot()),
            // Chain is a tab, so the rail sits beside it. Chain keeps 840dp; the extra 1dp absorbs
            // the rail's rounding to whole pixels at the fitted test density.
            containerWidth = 840.dp + NavigationRailWidth + 1.dp,
            containerHeight = 720.dp,
        )

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()
        openAdvancedChain()

        val summary = composeRule.onNodeWithTag("vesqen.chain.summary").fetchSemanticsNode().boundsInRoot
        val metrics = composeRule.onNodeWithTag("vesqen.chain.metrics").fetchSemanticsNode().boundsInRoot
        assertTrue("840dp summary must remain left of the advanced dashboard", summary.right <= metrics.left)
    }

    @Test
    fun liner_notes_header_is_not_ellipsized_with_150_percent_text() {
        render(
            state = activePlaybackState(), containerWidth = 360.dp,
            containerHeight = 720.dp, fontScale = 1.5f,
        )
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        assertSingleCompleteLine(context.getString(R.string.now_liner_notes))
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
    fun liner_notes_step_labels_are_not_ellipsized_with_150_percent_text() {
        render(
            state = activePlaybackState(),
            containerWidth = 360.dp,
            containerHeight = 720.dp,
            fontScale = 1.5f,
        )
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        openLinerNotes()
        for (label in listOf(R.string.chain_core_source, R.string.chain_core_output, R.string.chain_core_route)) {
            composeRule.onNodeWithText(context.getString(label), useUnmergedTree = true).performScrollTo()
            assertSingleCompleteLine(context.getString(label))
        }
    }

    @Test
    fun liner_notes_name_the_output_as_the_chain_page_does() {
        val snapshot = chainTelemetrySnapshot()
        val source = TelemetryDataSource(TelemetrySourceId("test.telemetry"))
        fun measured(id: TelemetryMetricId, section: TelemetrySection, reading: TelemetryReading) = TelemetryMetric(
            id = id,
            section = section,
            evidence = TelemetryEvidence.Measured(
                reading = reading,
                source = source,
                observedAtEpochMs = snapshot.capturedAtEpochMs,
                observedAtElapsedRealtimeMs = snapshot.capturedAtElapsedRealtimeMs,
            ),
        )
        val telemetry = FakePlaybackTelemetry(
            snapshot.copy(
                metrics = snapshot.metrics + listOf(
                    measured(TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_TYPE, TelemetrySection.ROUTE, TelemetryReading.Text("phone_speaker")),
                    measured(TelemetryMetricCatalog.ROUTE_SELECTED_SYSTEM_NAME, TelemetrySection.ROUTE, TelemetryReading.Text("Phone")),
                    measured(TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT, TelemetrySection.PLAYBACK, TelemetryReading.Integer(0, TelemetryUnit.COUNT)),
                ),
            ),
        )
        render(state = activePlaybackState(), playbackTelemetry = telemetry)
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        openLinerNotes()

        // #35: the notes named this output "Phone", Android's route name, where Chain says "Speaker".
        composeRule.onNodeWithTag("vesqen.now.notes.route").performScrollTo()
            .assert(hasText(context.getString(R.string.chain_core_route), substring = true))
            .assert(hasText(context.getString(R.string.chain_pinned_output_speaker), substring = true))
            .assert(hasText("Phone", substring = true).not())
        composeRule.onNodeWithTag("vesqen.now.notes.audio-track")
            .assert(hasText(context.getString(R.string.chain_core_output), substring = true))
    }

    @Test
    fun liner_notes_shrink_the_cover_keep_transport_visible_and_back_closes_them() {
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
        assertLinerNotesState(R.string.now_notes_collapsed)
        val fullCover = composeRule.onNodeWithTag("vesqen.now.artwork-stage").fetchSemanticsNode().boundsInRoot
        // Only the explicit toggle opens the notes; a swipe on the cover is not a hidden gesture.
        composeRule.onNodeWithTag("vesqen.now.artwork-stage").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("vesqen.now.notes.source").assertDoesNotExist()

        composeRule.onNodeWithTag("vesqen.now.notes-toggle").performClick()
        assertLinerNotesState(R.string.now_notes_expanded)
        composeRule.onNodeWithTag("vesqen.now.notes.source").assertIsDisplayed()
        // Small windows give the notes the cover's room entirely; larger ones keep a smaller cover.
        val notesCovers = composeRule.onAllNodesWithTag("vesqen.now.artwork-stage").fetchSemanticsNodes()
        assertTrue(
            "The cover must shrink or step aside while the notes are open",
            notesCovers.all { it.boundsInRoot.height < fullCover.height },
        )
        composeRule.onNodeWithTag("vesqen.now.back").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )

        composeRule.onNodeWithTag("vesqen.now.info").performScrollTo().performClick()
        composeRule.onNodeWithTag("vesqen.track-details").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.now.notes.source").assertDoesNotExist()
        assertLinerNotesState(R.string.now_notes_collapsed)
        assertEquals(fullCover, composeRule.onNodeWithTag("vesqen.now.artwork-stage").fetchSemanticsNode().boundsInRoot)
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
    fun now_track_details_offer_no_play_action_that_would_replace_the_queue() {
        // The track already plays in Now; "Play this track" there started the whole library as a new queue.
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
        openLinerNotes()
        composeRule.onNodeWithTag("vesqen.now.info").performScrollTo().performClick()
        composeRule.onNodeWithTag("vesqen.track-details").assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.play_this_track)).assertCountEquals(0)
    }

    @Test
    fun leaving_now_returns_to_the_tab_it_was_opened_from() {
        // The bar and the mini player once kept a navigation copy from the first composition, so
        // Now returned to Library from every tab (#136 R8).
        render(state = activePlaybackState())

        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        composeRule.onNodeWithTag("vesqen.now.back").assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.nav.settings").assertIsSelected()

        composeRule.onNodeWithTag("vesqen.nav.chain").performClick()
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.back").performClick()
        composeRule.onNodeWithTag("vesqen.nav.chain").assertIsSelected()
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
        openLinerNotes()
        composeRule.onNodeWithTag("vesqen.now.info").performScrollTo().performClick()
        composeRule.onNodeWithTag("vesqen.track-details").assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.track-details").assertDoesNotExist()
        // Back closes the open notes before it leaves Now.
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        assertLinerNotesState(R.string.now_notes_collapsed)
        composeRule.onNodeWithTag("vesqen.now.back").assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsSelected()

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        openLinerNotes()
        composeRule.onNodeWithTag("vesqen.now.open-chain").performScrollTo().performClick()
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

        composeRule.onNodeWithTag("vesqen.mini-player").assertHeightIsEqualTo(64.dp)
        // The controls draw 40 dp circles; their touch targets must stay 48 dp.
        listOf("previous", "play-pause", "next").forEach { control ->
            composeRule.onNodeWithTag("vesqen.mini-player.$control")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        composeRule.onAllNodesWithText(context.getString(R.string.system_mixed)).assertCountEquals(0)
    }

    // The mini player on the smallest and common phone widths, a tablet width and large text: the
    // controls keep real 48 dp targets side by side and the title keeps room to read (#143 Q20, Q13).
    // The title gets the capsule width minus 218 dp of fixed parts (insets, cover, gap, three 48 dp
    // controls), so these minimums sit a few dp under that and fail if the title is squeezed again.
    // #35: at 320 dp and 200 % that left one letter, so Previous steps aside and the title gains 48 dp.
    @Test
    fun mini_player_fits_a_320dp_window_at_200_percent_text() =
        assertMiniPlayerFits(320.dp, 2f, minTitleWidth = 110.dp, showsPrevious = false)

    @Test
    fun mini_player_fits_a_360dp_window_at_130_percent_text() = assertMiniPlayerFits(360.dp, 1.3f, minTitleWidth = 104.dp)

    @Test
    fun mini_player_fits_a_411dp_window_at_default_text() = assertMiniPlayerFits(411.dp, 1f, minTitleWidth = 156.dp)

    @Test
    fun mini_player_fits_a_600dp_window_at_default_text() = assertMiniPlayerFits(600.dp, 1f, minTitleWidth = 200.dp)

    private fun assertMiniPlayerFits(width: Dp, fontScale: Float, minTitleWidth: Dp, showsPrevious: Boolean = true) {
        val title = "A deliberately long local track title"
        render(
            state = grantedState(
                tracks = sampleTracks,
                playback = PlaybackSnapshot(
                    isControllerReady = true,
                    trackId = 1,
                    title = title,
                    artist = "A deliberately long local artist name",
                    hasPrevious = true,
                    hasNext = true,
                ),
            ),
            containerWidth = width,
            containerHeight = 720.dp,
            fontScale = fontScale,
        )

        val player = composeRule.onNodeWithTag("vesqen.mini-player").fetchSemanticsNode().boundsInRoot
        var previousRight = composeRule.onNodeWithTag("vesqen.mini-player.open-now").fetchSemanticsNode().boundsInRoot.right
        if (!showsPrevious) composeRule.onAllNodesWithTag("vesqen.mini-player.previous").assertCountEquals(0)
        listOfNotNull("previous".takeIf { showsPrevious }, "play-pause", "next").forEach { control ->
            val bounds = composeRule.onNodeWithTag("vesqen.mini-player.$control")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue("$control overlaps the control before it at $width", bounds.left >= previousRight - 0.5f)
            previousRight = bounds.right
        }
        assertTrue("The next button leaves the mini player at $width", previousRight <= player.right + 0.5f)
        composeRule.onNodeWithText(title, useUnmergedTree = true).assertWidthIsAtLeast(minTitleWidth)
    }

    @Test
    fun mini_player_stops_growing_on_wide_windows() {
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
            containerWidth = 1000.dp,
            containerHeight = 600.dp,
        )

        composeRule.onNodeWithTag("vesqen.mini-player").assertWidthIsEqualTo(560.dp)
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
    fun compact_navigation_labels_keep_bottom_breathing_room() {
        render(
            state = grantedState(),
            containerWidth = 360.dp,
            containerHeight = 720.dp,
        )

        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val settingsLabelBounds = composeRule.onNodeWithText(
            context.getString(R.string.destination_settings),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val minimumBottomSpace = with(fixtureDensity) { 3.dp.toPx() }
        val bottomSpace = rootBounds.bottom - settingsLabelBounds.bottom
        assertTrue(
            "Compact navigation labels must not sit on the bottom edge: " +
                "root=$rootBounds, label=$settingsLabelBounds, bottomSpace=$bottomSpace",
            bottomSpace >= minimumBottomSpace,
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
        composeRule.onNodeWithTag("vesqen.now.notes-toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        assertFocusedNowControlsAreFullyVisible()
        openLinerNotes()
        // Open notes scroll inside their own region; transport never leaves the screen.
        composeRule.onNodeWithTag("vesqen.now.notes.source").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.info").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.open-chain").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.play-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        assertFooterActionsDoNotOverlap(
            "vesqen.now.playback-order",
            "vesqen.now.previous",
            "vesqen.now.play-pause",
            "vesqen.now.next",
            "vesqen.now.favorite",
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
        composeRule.onNodeWithTag("vesqen.now.notes-toggle").assertIsDisplayed()
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
        composeRule.onNodeWithTag("vesqen.now.notes-toggle").assertIsDisplayed()
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
        composeRule.onNodeWithTag("vesqen.now.notes-toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        assertArtworkStageIsUsable()
        assertFocusedNowControlsAreFullyVisible()
        assertLandscapeIdentityStartsBelowTheCommandBand()
    }

    @Test
    fun shown_lyrics_with_open_liner_notes_keep_now_controls_visible_at_320dp_and_large_text() =
        assertLyricsWithOpenLinerNotes(320.dp, 533.dp, fontScale = 2f)

    @Test
    fun shown_lyrics_with_open_liner_notes_keep_now_controls_visible_at_360dp() =
        assertLyricsWithOpenLinerNotes(360.dp, 720.dp, fontScale = 1f)

    // At 360x720 the open notes leave the stage under its 96 dp minimum, so the cover and the lyrics
    // both give way there; a common tall phone keeps them beside the notes.
    @Test
    fun shown_lyrics_stay_beside_open_liner_notes_on_a_tall_phone() =
        assertLyricsWithOpenLinerNotes(412.dp, 915.dp, fontScale = 1f, lyricsBesideOpenNotes = true)

    @Test
    fun shown_lyrics_with_open_liner_notes_keep_now_controls_visible_in_landscape() =
        assertLyricsWithOpenLinerNotes(640.dp, 320.dp, fontScale = 1f)

    @Test
    fun a_failed_lyrics_import_on_now_is_a_dialog_at_320dp_large_text_with_open_notes() =
        assertLyricsImportResultsOnNow(320.dp, 533.dp, fontScale = 2f, openNotes = true)

    @Test
    fun a_failed_lyrics_import_on_now_is_a_dialog_in_landscape() =
        assertLyricsImportResultsOnNow(640.dp, 320.dp, fontScale = 1f, openNotes = false)

    @Test
    fun a_lyrics_import_result_in_the_library_is_a_snackbar() {
        var uiState by mutableStateOf(grantedState(tracks = sampleTracks))
        render(state = uiState, stateProvider = { uiState }, onLyricsImportNoticeShown = { id -> if (uiState.lyricsImportNotice?.id == id) uiState = uiState.copy(lyricsImportNotice = null) },)

        uiState = uiState.copy(lyricsImportNotice = LyricsImportNotice(1, 1, LyricsImportResult.NO_LYRICS))
        composeRule.onNodeWithTag("vesqen.lyrics-notice").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.lyrics_import_no_lyrics)).assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.lyrics-import-result").assertCountEquals(0)
    }

    // #169 Y24: a notice from the Library would cover Now's transport or liner notes.
    @Test
    fun a_lyrics_notice_from_the_library_goes_when_now_opens() {
        var uiState by mutableStateOf(grantedState(tracks = sampleTracks, playback = pausedLyricsPlayback()))
        render(state = uiState, stateProvider = { uiState }, onLyricsImportNoticeShown = { id -> if (uiState.lyricsImportNotice?.id == id) uiState = uiState.copy(lyricsImportNotice = null) },)

        uiState = uiState.copy(lyricsImportNotice = LyricsImportNotice(1, 1, LyricsImportResult.NO_LYRICS))
        composeRule.onNodeWithTag("vesqen.lyrics-notice").assertIsDisplayed()
        // A stopped clock keeps the notice well inside its 4 s, so only opening Now can remove it.
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.mainClock.autoAdvance = true

        composeRule.onNodeWithTag("vesqen.now.player-page").assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.lyrics-notice").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.lyrics-import-result").assertCountEquals(0)
        assertFocusedNowControlsAreFullyVisible()
    }

    // #191 C1: while a song plays, the notice sits above the mini-player, whose controls stay usable.
    @Test
    fun a_lyrics_notice_in_the_library_leaves_the_mini_player_uncovered() {
        var uiState by mutableStateOf(grantedState(tracks = sampleTracks, playback = pausedLyricsPlayback()))
        var playPauses = 0
        render(
            state = uiState,
            stateProvider = { uiState },
            onPlayPause = { playPauses++ },
            onLyricsImportNoticeShown = { id -> if (uiState.lyricsImportNotice?.id == id) uiState = uiState.copy(lyricsImportNotice = null) },
        )

        uiState = uiState.copy(lyricsImportNotice = LyricsImportNotice(1, 1, LyricsImportResult.IMPORTED))
        val notice = composeRule.onNodeWithTag("vesqen.lyrics-notice").assertIsDisplayed().getBoundsInRoot()
        val miniPlayer = composeRule.onNodeWithTag("vesqen.mini-player").assertIsDisplayed().getBoundsInRoot()
        assertTrue("The notice $notice overlaps the mini-player $miniPlayer", notice.bottom <= miniPlayer.top)
        composeRule.onNodeWithTag("vesqen.mini-player.play-pause").performClick()
        assertEquals(1, playPauses)
        composeRule.onNodeWithTag("vesqen.lyrics-notice").assertIsDisplayed()
    }

    // #191 D5: after process death Now comes back before the player does; the result waits until the
    // player says which song is on Now, and then it is the imported one.
    @Test
    fun a_lyrics_import_on_now_waits_for_the_player_to_name_its_song() {
        var uiState by mutableStateOf(grantedState(tracks = sampleTracks, playback = pausedLyricsPlayback()))
        var lyrics by mutableStateOf(NowLyricsState(shown = false))
        var noticesShown = 0
        render(
            state = uiState,
            stateProvider = { uiState },
            lyricsProvider = { lyrics },
            onToggleLyrics = { lyrics = lyrics.copy(shown = !lyrics.shown) },
            onLyricsImportNoticeShown = { id ->
                noticesShown++
                if (uiState.lyricsImportNotice?.id == id) uiState = uiState.copy(lyricsImportNotice = null)
            },
        )
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()

        uiState = uiState.copy(
            playback = PlaybackSnapshot(),
            lyricsImportNotice = LyricsImportNotice(1, 1, LyricsImportResult.IMPORTED),
        )
        composeRule.waitForIdle()
        assertEquals(0, noticesShown)
        composeRule.onAllNodesWithTag("vesqen.lyrics-notice").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.lyrics-import-result").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.lyrics-imported-announcement", useUnmergedTree = true).assertCountEquals(0)

        uiState = uiState.copy(playback = pausedLyricsPlayback())
        composeRule.waitForIdle()
        assertEquals(1, noticesShown)
        assertTrue(lyrics.shown)
        composeRule.onAllNodesWithTag("vesqen.lyrics-imported-announcement", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithTag("vesqen.lyrics-notice").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.lyrics-import-result").assertCountEquals(0)
    }

    // #191 D5: the song changed while the picker was open. Turning on the new song's lyrics would
    // claim they were imported, so a dialog says what happened instead.
    @Test
    fun a_lyrics_import_for_a_song_no_longer_on_now_is_a_dialog() {
        var uiState by mutableStateOf(grantedState(tracks = sampleTracks, playback = pausedLyricsPlayback()))
        var lyrics by mutableStateOf(NowLyricsState(shown = false))
        render(
            state = uiState,
            stateProvider = { uiState },
            lyricsProvider = { lyrics },
            onToggleLyrics = { lyrics = lyrics.copy(shown = !lyrics.shown) },
            onLyricsImportNoticeShown = { id -> if (uiState.lyricsImportNotice?.id == id) uiState = uiState.copy(lyricsImportNotice = null) },
        )
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        uiState = uiState.copy(playback = pausedLyricsPlayback().copy(trackId = 2, title = "Long Light"))

        uiState = uiState.copy(lyricsImportNotice = LyricsImportNotice(1, 1, LyricsImportResult.IMPORTED))
        composeRule.onNodeWithTag("vesqen.lyrics-import-result").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.lyrics_imported)).assertIsDisplayed()
        assertFalse(lyrics.shown)
        composeRule.onAllNodesWithTag("vesqen.lyrics-imported-announcement", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithText(context.getString(R.string.lyrics_import_result_ok)).performClick()
        composeRule.onAllNodesWithTag("vesqen.lyrics-import-result").assertCountEquals(0)

        uiState = uiState.copy(lyricsImportNotice = LyricsImportNotice(2, 1, LyricsImportResult.UNREADABLE))
        composeRule.onNodeWithText(context.getString(R.string.lyrics_import_unreadable)).assertIsDisplayed()
        assertFalse(lyrics.shown)
    }

    // #191 D11: the cleared-queue and lyrics notices share one place, so a lyrics notice waits for
    // Undo instead of covering it.
    @Test
    fun a_lyrics_notice_waits_for_the_undo_notice() {
        var uiState by mutableStateOf(grantedState(tracks = sampleTracks).copy(queueClearedOffer = 1))
        var undone = 0
        render(
            state = uiState,
            stateProvider = { uiState },
            onUndoClearQueue = {
                undone++
                uiState = uiState.copy(queueClearedOffer = null)
            },
            onLyricsImportNoticeShown = { id -> if (uiState.lyricsImportNotice?.id == id) uiState = uiState.copy(lyricsImportNotice = null) },
        )
        composeRule.onNodeWithTag("vesqen.queue-cleared").assertIsDisplayed()

        uiState = uiState.copy(lyricsImportNotice = LyricsImportNotice(1, 1, LyricsImportResult.IMPORTED))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vesqen.queue-cleared").assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.lyrics-notice").assertCountEquals(0)

        composeRule.onNodeWithTag("vesqen.queue-cleared.undo").performClick()
        assertEquals(1, undone)
        composeRule.onNodeWithTag("vesqen.lyrics-notice").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.lyrics_imported)).assertIsDisplayed()
    }

    // #169 Y25: from the cover, a successful import shows the lyrics and is announced once, by a node
    // that leaves again; lines that change later announce nothing.
    @Test
    fun a_successful_lyrics_import_on_now_shows_the_lyrics_and_is_announced_once() {
        var uiState by mutableStateOf(grantedState(tracks = sampleTracks, playback = pausedLyricsPlayback()))
        var lyrics by mutableStateOf(NowLyricsState(shown = false))
        render(
            state = uiState,
            stateProvider = { uiState },
            containerWidth = 412.dp,
            containerHeight = 915.dp,
            lyricsProvider = { lyrics },
            onToggleLyrics = { lyrics = lyrics.copy(shown = !lyrics.shown) },
            loadLyrics = {
                TrackLyrics.Found(LyricsSource.Imported, Lyrics.Synced((0 until 30).map { LyricLine(it * 10_000L, "Line $it") }))
            },
            onLyricsImportNoticeShown = { id -> if (uiState.lyricsImportNotice?.id == id) uiState = uiState.copy(lyricsImportNotice = null) },
        )
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onAllNodesWithTag("vesqen.now.lyrics").assertCountEquals(0)
        val liveRegions = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        composeRule.onAllNodes(liveRegions, useUnmergedTree = true).assertCountEquals(0)

        uiState = uiState.copy(lyricsImportNotice = LyricsImportNotice(1, 1, LyricsImportResult.IMPORTED))
        composeRule.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed()
        composeRule.onAllNodes(liveRegions, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("vesqen.lyrics-imported-announcement", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(hasContentDescription(context.getString(R.string.lyrics_imported)))
        composeRule.onAllNodesWithTag("vesqen.lyrics-notice").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.lyrics-import-result").assertCountEquals(0)
        assertFocusedNowControlsAreFullyVisible()

        uiState = uiState.copy(playback = uiState.playback.copy(positionMs = 50_000))
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.onAllNodesWithTag("vesqen.lyrics-imported-announcement", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodes(liveRegions, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed()
    }

    /**
     * #169 Y17, Y25: Now's edges hold controls at large text and in landscape, so a failed import is
     * a dialog there and a successful one turns the lyrics on with one announcement, never a notice
     * over a control.
     */
    private fun assertLyricsImportResultsOnNow(width: Dp, height: Dp, fontScale: Float, openNotes: Boolean) {
        var uiState by mutableStateOf(grantedState(tracks = sampleTracks, playback = pausedLyricsPlayback()))
        var lyrics by mutableStateOf(NowLyricsState(shown = false))
        render(
            state = uiState,
            stateProvider = { uiState },
            containerWidth = width,
            containerHeight = height,
            fontScale = fontScale,
            lyricsProvider = { lyrics },
            onToggleLyrics = { lyrics = lyrics.copy(shown = !lyrics.shown) },
            onLyricsImportNoticeShown = { id -> if (uiState.lyricsImportNotice?.id == id) uiState = uiState.copy(lyricsImportNotice = null) },
        )
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        if (openNotes) openLinerNotes()

        uiState = uiState.copy(lyricsImportNotice = LyricsImportNotice(1, 1, LyricsImportResult.UNREADABLE))
        composeRule.onNodeWithTag("vesqen.lyrics-import-result").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.lyrics_import_unreadable)).assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.lyrics-notice").assertCountEquals(0)
        composeRule.onNodeWithText(context.getString(R.string.lyrics_import_result_ok)).performClick()
        composeRule.onAllNodesWithTag("vesqen.lyrics-import-result").assertCountEquals(0)
        assertFalse(lyrics.shown)

        uiState = uiState.copy(lyricsImportNotice = LyricsImportNotice(2, 1, LyricsImportResult.IMPORTED))
        composeRule.waitForIdle()
        assertTrue(lyrics.shown)
        composeRule.onAllNodesWithTag("vesqen.lyrics-imported-announcement", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithTag("vesqen.lyrics-notice").assertCountEquals(0)
        composeRule.onAllNodesWithTag("vesqen.lyrics-import-result").assertCountEquals(0)
        assertFocusedNowControlsAreFullyVisible()
    }

    private fun pausedLyricsPlayback() = PlaybackSnapshot(
        isControllerReady = true,
        trackId = 1,
        title = "Dawn Signal",
        artist = "Mori",
        durationMs = 245_000,
        positionMs = 30_000,
        hasPrevious = true,
        hasNext = true,
    )

    /** #169: lyrics in the cover's place with the liner notes open and closed again. */
    private fun assertLyricsWithOpenLinerNotes(
        width: Dp,
        height: Dp,
        fontScale: Float,
        lyricsBesideOpenNotes: Boolean = false,
    ) {
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
            containerWidth = width,
            containerHeight = height,
            fontScale = fontScale,
            lyrics = NowLyricsState(shown = true),
            loadLyrics = {
                TrackLyrics.Found(LyricsSource.Imported, Lyrics.Synced((0 until 30).map { LyricLine(it * 10_000L, "Line $it") }))
            },
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed()
        openLinerNotes()
        if (lyricsBesideOpenNotes) {
            // The cover's open size (nowPortraitLayout.notesArtwork, 96–164 dp), not a weighted share.
            val pane = composeRule.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Lyrics beside open notes are ${pane.height}", pane.height >= 96.dp && pane.height <= 164.dp)
        }
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        assertFocusedNowControlsAreFullyVisible()
        composeRule.onNodeWithTag("vesqen.now.notes-toggle").performClick()
        assertLinerNotesState(R.string.now_notes_collapsed)
        composeRule.onNodeWithTag("vesqen.now.lyrics").assertIsDisplayed()
        assertFocusedNowControlsAreFullyVisible()
    }

    @Test
    fun focused_player_takes_the_window_from_the_bottom_bar() = assertFocusedPlayerTakesTheWindow(360.dp, 720.dp)

    @Test
    fun focused_player_takes_the_window_from_the_rail() = assertFocusedPlayerTakesTheWindow(720.dp, 720.dp)

    private fun assertFocusedPlayerTakesTheWindow(width: Dp, height: Dp) {
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
            containerWidth = width,
            containerHeight = height,
            darkTheme = false,
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.focus-surface").assertIsDisplayed()
        // DESIGN.md "Focus mode": the navigation yields to the player, and the player's Back
        // brings it back on the page Now was opened from.
        composeRule.onAllNodesWithTag("vesqen.nav.library").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.now.previous").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.next").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.back").performClick()
        composeRule.onNodeWithTag("vesqen.nav.library").assertIsDisplayed().assertIsSelected()
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
        composeRule.onNodeWithTag("vesqen.now.notes-toggle").assertIsDisplayed()
        assertArtworkClearsTransportDock()
        assertFocusedNowControlsAreFullyVisible()
    }

    @Test
    fun focused_now_shows_the_artwork_fallback_on_plain_paper_at_320dp_with_large_text() {
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
            // 640 dp keeps room for a cover at 2x text once phone system bars are subtracted;
            // shorter windows deliberately drop it (see nowPortraitLayout).
            containerWidth = 320.dp,
            containerHeight = 640.dp,
            fontScale = 2f,
            darkTheme = false,
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
        // The cover is a button that shows the lyrics (#169), so its contents merge into it.
        composeRule.onAllNodesWithTag("vesqen.album-artwork.fallback", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("vesqen.now.player-page").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        assertFocusedNowControlsAreFullyVisible()
    }

    @Test
    fun focused_now_survives_an_unreadable_artwork_uri_without_tinting() {
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
        composeRule.onNodeWithTag("vesqen.now.artwork-stage").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.now.notes-toggle").assertIsDisplayed()
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
        composeRule.onNodeWithTag("vesqen.library.album.play")
            .assertContentDescriptionEquals(context.getString(R.string.play_album, "Quiet Rooms"))
            .performClick()

        assertEquals(listOf(1L, 2L), queuedTrackIds)
    }

    @Test
    fun albums_show_the_recent_shelf_above_the_full_list_and_open_the_album_page() {
        fun track(id: Long, title: String, album: String, artist: String, number: Int, added: Long) = AudioTrack(
            id = id,
            contentUri = "content://media/external/audio/media/$id",
            title = title,
            artist = artist,
            album = album,
            albumArtist = artist,
            durationMs = 240_000,
            trackNumber = number,
            dateModifiedSeconds = added,
        )
        val older = track(11, "Glass Harbor", "Low Tide Archive", "Maren Holt", 1, added = 100)
        val newer = track(21, "Brass Lantern", "Copper Hours", "Otis Vale", 1, added = 200)
        val newerSecond = track(22, "Slow Furnace", "Copper Hours", "Otis Vale", 2, added = 200)
        var queued = emptyList<Long>()
        var startIndex = -1
        render(
            state = grantedState(tracks = listOf(older, newer, newerSecond)),
            onPlayQueue = { tracks, index ->
                queued = tracks.map(AudioTrack::id)
                startIndex = index
            },
        )

        composeRule.onNodeWithTag("vesqen.library.mode.albums").performClick()
        fun onShelf(title: String) = composeRule.onNode(
            hasText(title) and hasAnyAncestor(hasTestTag("vesqen.library.shelf")),
        )
        // The shelf leads with the newest album; the full list below still holds every album.
        assertTrue(
            "Recently added must lead the shelf",
            onShelf("Copper Hours").fetchSemanticsNode().boundsInRoot.left <
                onShelf("Low Tide Archive").fetchSemanticsNode().boundsInRoot.left,
        )
        composeRule.onNodeWithText(context.getString(R.string.library_all_albums)).assertIsDisplayed()
        composeRule.onAllNodesWithText("Low Tide Archive").assertCountEquals(2)

        onShelf("Copper Hours").performClick()
        composeRule.onNodeWithTag("vesqen.library.album.title").assertTextEquals("Copper Hours")
        composeRule.onNodeWithText("01").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.library.album.play").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(21L, 22L), queued)
            assertEquals(0, startIndex)
        }
        composeRule.onNodeWithTag("vesqen.library.track.22").performClick()
        composeRule.runOnIdle { assertEquals(1, startIndex) }

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onAllNodesWithTag("vesqen.library.album").assertCountEquals(0)
        composeRule.onNodeWithTag("vesqen.library.shelf").assertIsDisplayed()
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

    // #165: Clear queue stays one tap (#136 R7); the notice after it puts the queue back. The offer is
    // the view model's (#191 D2), played here as it sets and ends it.
    @Test
    fun clearing_the_queue_offers_undo_until_another_queue_starts() {
        val queued = twoTrackQueue()
        var playback by mutableStateOf(queued)
        var offer by mutableStateOf<Long?>(null)
        var cleared = 0
        var undone = 0
        render(
            state = grantedState(tracks = sampleTracks, playback = queued),
            stateProvider = { grantedState(tracks = sampleTracks, playback = playback).copy(queueClearedOffer = offer) },
            onClearQueue = {
                cleared++
                playback = PlaybackSnapshot(isControllerReady = true)
                offer = cleared.toLong()
            },
            onUndoClearQueue = {
                undone++
                offer = null
                playback = queued
            },
            containerWidth = 320.dp,
            fontScale = 2f,
        )

        fun clearFromNow() {
            composeRule.onNodeWithTag("vesqen.nav.now").performClick()
            composeRule.onNodeWithTag("vesqen.now.queue").performClick()
            composeRule.onNodeWithText(context.getString(R.string.clear_queue)).performClick()
        }
        clearFromNow()
        assertEquals(1, cleared)
        composeRule.onNodeWithTag("vesqen.queue-cleared").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.queue_cleared)).assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.queue-cleared.undo")
            .assertTextEquals(context.getString(R.string.queue_cleared_undo))
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, undone)
        composeRule.onAllNodesWithTag("vesqen.queue-cleared").assertCountEquals(0)
        // Now shows the queue's track again.
        composeRule.onNodeWithTag("vesqen.now.title").assertIsDisplayed()

        // A queue started while the notice shows takes the cleared one's place, so the notice goes.
        composeRule.onNodeWithTag("vesqen.now.queue").performClick()
        composeRule.onNodeWithText(context.getString(R.string.clear_queue)).performClick()
        composeRule.onNodeWithTag("vesqen.queue-cleared").assertIsDisplayed()
        playback = queued
        offer = null
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("vesqen.queue-cleared").assertCountEquals(0)
        assertEquals(2 to 1, cleared to undone)
    }

    // #165 X3: a catalog rebuild drops the cleared queue, whose IDs name other songs now, so the notice
    // goes with the Undo it offered. The view model ends the offer on the rebuild (#191 D2).
    @Test
    fun a_catalog_rebuild_takes_the_undo_notice_away() {
        var playback by mutableStateOf(twoTrackQueue())
        var offer by mutableStateOf<Long?>(null)
        var undone = 0
        val rebuilt = FakeLibraryRebuildNotice(due = false)
        render(
            state = grantedState(tracks = sampleTracks, playback = playback),
            stateProvider = { grantedState(tracks = sampleTracks, playback = playback).copy(queueClearedOffer = offer) },
            onClearQueue = {
                playback = PlaybackSnapshot(isControllerReady = true)
                offer = 1
            },
            onUndoClearQueue = { undone++ },
            rebuildNotice = rebuilt,
        )
        composeRule.onNodeWithTag("vesqen.nav.now").performClick()
        composeRule.onNodeWithTag("vesqen.now.queue").performClick()
        composeRule.onNodeWithText(context.getString(R.string.clear_queue)).performClick()
        composeRule.onNodeWithTag("vesqen.queue-cleared").assertIsDisplayed()

        composeRule.runOnIdle {
            rebuilt.emit(true)
            offer = null
        }
        composeRule.onNodeWithTag("vesqen.library.rebuilt-dialog").assertIsDisplayed()
        composeRule.onAllNodesWithTag("vesqen.queue-cleared").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(0, undone) }
    }

    private fun twoTrackQueue() = PlaybackSnapshot(
        isControllerReady = true,
        trackId = 1,
        title = "Dawn Signal",
        artist = "Mori",
        durationMs = 245_000,
        queueIndex = 0,
        queueSize = 2,
        queue = listOf(
            PlaybackQueueItem(1, "Dawn Signal", "Mori", true, listIndex = 0),
            PlaybackQueueItem(2, "Long Light", "Mori", false, listIndex = 1),
        ),
    )

    // Now's long title scrolls. Marquee ignores the system animation scale, so with animations off the
    // title stays still and ends in an ellipsis (#165). The mini player keeps one truncated line either
    // way; the full title is in Now (owner decision, 2026-10-29, #143 Q13). Marquee does not advance
    // under this rule's test clock, so these check which titles have it.
    @Test
    fun a_long_now_title_scrolls_with_animations_on() {
        render(state = longTitleState(), containerWidth = 320.dp, fontScale = 2f, motionPolicy = VesqenMotionPolicy(reduceMotion = false))

        assertTitleScrolls(miniPlayerTitle(), "mini player", scrolls = false)
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        assertTitleScrolls(composeRule.onNodeWithTag("vesqen.now.title", useUnmergedTree = true), "Now", scrolls = true)
    }

    @Test
    fun a_long_now_title_stays_still_with_animations_off() {
        render(state = longTitleState(), containerWidth = 320.dp, fontScale = 2f, motionPolicy = VesqenMotionPolicy(reduceMotion = true))

        assertTitleScrolls(miniPlayerTitle(), "mini player", scrolls = false)
        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        assertTitleScrolls(composeRule.onNodeWithTag("vesqen.now.title", useUnmergedTree = true), "Now", scrolls = false)
    }

    private fun miniPlayerTitle() = composeRule.onNode(
        hasText(LongTitle) and hasAnyAncestor(hasTestTag("vesqen.mini-player")),
        useUnmergedTree = true,
    )

    private fun longTitleState() = grantedState(
        tracks = sampleTracks,
        playback = PlaybackSnapshot(
            isControllerReady = true,
            trackId = 1,
            title = LongTitle,
            artist = "Mori",
            durationMs = 245_000,
            hasNext = true,
        ),
    )

    /** A title that does not fit either scrolls (a marquee, cut without an ellipsis) or ends in an ellipsis. */
    private fun assertTitleScrolls(title: SemanticsNodeInteraction, name: String, scrolls: Boolean) {
        val marquee = title.fetchSemanticsNode().layoutInfo.getModifierInfo().any { "Marquee" in it.modifier::class.java.simpleName }
        assertEquals("$name title has a marquee", scrolls, marquee)
        assertEquals("$name title ends in an ellipsis", !scrolls, titleLayout(title).isLineEllipsized(0))
    }

    private fun titleLayout(title: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    @Test
    fun now_queue_action_opens_an_editable_queue_sheet() {
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
                        PlaybackQueueItem(1, "Dawn Signal", "Mori", true, listIndex = 0),
                        PlaybackQueueItem(2, "Long Light", "Mori", false, listIndex = 1),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag("vesqen.mini-player.open-now").performClick()
        composeRule.onNodeWithTag("vesqen.now.queue").performClick()

        composeRule.onNodeWithTag("vesqen.queue.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.queue.item.0").assertIsDisplayed()
        composeRule.onNodeWithTag("vesqen.queue.item.1").assertIsDisplayed()
    }

    // #165 X4: at 320 dp and 200 % text the title kept about one letter a line beside Clear queue and
    // Close. The sheet's window takes no width or text size from here, so the header is measured alone,
    // at the sheet's content width: the window less its 24 dp margins.
    @Test
    fun the_queue_title_stays_whole_beside_or_above_clear_queue() {
        var width by mutableStateOf(272.dp)
        var fontScale by mutableStateOf(2f)
        lateinit var shownDensity: Density
        composeRule.setContent {
            val density = LocalDensity.current
            // A tablet's width on a phone: fitted as render() does.
            val windowWidth = LocalWindowInfo.current.containerSize.width
            shownDensity = Density(minOf(density.density, windowWidth / width.value), fontScale)
            CompositionLocalProvider(LocalDensity provides shownDensity) {
                VesqenTheme {
                    Box(Modifier.width(width)) { QueueSheetHeader(clearEnabled = true, onClearQueue = {}, onClose = {}) }
                }
            }
        }
        fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        // Whether Clear queue stays beside the title; null where it depends on the font.
        listOf(
            Triple(272.dp, 2f, false),
            Triple(272.dp, 1f, true),
            Triple(363.dp, 1f, true),
            Triple(363.dp, 2f, null),
            Triple(552.dp, 2f, true),
        ).forEach { (sheetWidth, scale, beside) ->
            width = sheetWidth
            fontScale = scale
            composeRule.waitForIdle()
            val case = "$sheetWidth at $scale"
            assertSingleCompleteLine(context.getString(R.string.queue))
            assertSingleCompleteLine(context.getString(R.string.clear_queue))
            assertFooterActionsDoNotOverlap("vesqen.queue.title", "vesqen.queue.clear", "vesqen.queue.close")
            val minimumPx = with(shownDensity) { 48.dp.toPx() } - 0.5f
            listOf("vesqen.queue.clear", "vesqen.queue.close").forEach { tag ->
                assertTrue("$case: $tag is under 48 dp", bounds(tag).height >= minimumPx && bounds(tag).width >= minimumPx)
            }
            val title = bounds("vesqen.queue.title")
            val clear = bounds("vesqen.queue.clear")
            if (beside != null) assertEquals(case, beside, clear.top < title.bottom)
            if (clear.top >= title.bottom) {
                // On its own line, the label starts where the title does.
                val label = composeRule.onNodeWithText(context.getString(R.string.clear_queue), useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                assertEquals(case, title.left, label.left, 1f)
            }
        }
    }

    @Test
    fun queue_current_row_says_playing_while_playing() {
        composeRule.setContent {
            VesqenTheme {
                QueueSheet(
                    snapshot = PlaybackSnapshot(
                        isControllerReady = true,
                        isPlaying = true,
                        queue = listOf(PlaybackQueueItem(1, "Dawn Signal", "Mori", true, listIndex = 0)),
                    ),
                    onDismiss = {},
                    onPlayItem = {},
                    onRemoveItem = {},
                    onMoveItem = { _, _ -> },
                    onClearQueue = {},
                )
            }
        }

        composeRule.onNodeWithTag("vesqen.queue.item.0").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.playing)),
        )
    }

    @Test
    fun queue_rows_play_on_tap_mark_the_current_track_and_edit_in_place() {
        val played = mutableListOf<Int>()
        val moved = mutableListOf<Pair<Int, Int>>()
        val removed = mutableListOf<Int>()
        composeRule.setContent {
            VesqenTheme {
                QueueSheet(
                    snapshot = PlaybackSnapshot(
                        isControllerReady = true,
                        queue = listOf(
                            // Shuffled: rows follow the play order, edits name the list index (#158).
                            PlaybackQueueItem(1, "Dawn Signal", "Mori", true, listIndex = 2),
                            PlaybackQueueItem(2, "Long Light", "Mori", false, listIndex = 0),
                        ),
                    ),
                    onDismiss = {},
                    onPlayItem = { played += it },
                    onRemoveItem = { removed += it },
                    onMoveItem = { from, to -> moved += from to to },
                    onClearQueue = {},
                )
            }
        }

        // Nothing is playing in this snapshot, so the current row says Paused, like the library's rows.
        composeRule.onNodeWithTag("vesqen.queue.item.0").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.paused)),
        )
        composeRule.onNodeWithTag("vesqen.queue.item.1").performClick()
        // The first row cannot move up and the last cannot move down; the others edit in place.
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.move_up))[0].assertIsNotEnabled()
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.move_down))[1].assertIsNotEnabled()
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.move_up))[1].performClick()
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.remove_from_queue))[0].performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(0), played)
            assertEquals(listOf(0 to 2), moved)
            assertEquals(listOf(2), removed)
        }
    }

    private fun assertFocusedNowControlsAreFullyVisible() {
        val transportTags = arrayOf(
            "vesqen.now.playback-order",
            "vesqen.now.previous",
            "vesqen.now.play-pause",
            "vesqen.now.next",
            "vesqen.now.favorite",
        )
        assertNodesAreFullyVisibleIn(
            containerTag = "vesqen.now.focus-surface",
            tags = arrayOf("vesqen.now.back", "vesqen.now.queue"),
        )
        assertNodesAreFullyVisibleIn(
            containerTag = "vesqen.now.player-page",
            tags = arrayOf("vesqen.now.title", "vesqen.now.progress", *transportTags, "vesqen.now.notes-toggle"),
        )
        assertFooterActionsDoNotOverlap(*transportTags, "vesqen.now.notes-toggle")

        val title = composeRule.onNodeWithTag("vesqen.now.title").fetchSemanticsNode()
        val primaryTransport = composeRule.onNodeWithTag("vesqen.now.play-pause").fetchSemanticsNode()
        assertTrue(
            "Now title must remain a single transport-row height",
            title.size.height <= primaryTransport.size.height,
        )
    }

    private fun openLinerNotes() {
        val toggle = composeRule.onNodeWithTag("vesqen.now.notes-toggle")
        val state = toggle.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        if (state != context.getString(R.string.now_notes_expanded)) toggle.performClick()
        assertLinerNotesState(R.string.now_notes_expanded)
    }

    private fun assertLinerNotesState(@androidx.annotation.StringRes state: Int) {
        composeRule.onNodeWithTag("vesqen.now.notes-toggle").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(state)),
        )
    }

    private fun assertSingleCompleteLine(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        // The String Text semantics adapter rebuilds a paragraph at maxWidth while keeping the
        // original, narrower layoutSize. Compare glyph advance, not paragraph width.
        val textWidth = layout.getLineRight(0) - layout.getLineLeft(0)
        assertTrue("$text must fit on one line", layout.lineCount == 1)
        assertTrue(
            "$text must be complete: size=${layout.size}, textWidth=$textWidth",
            textWidth <= layout.size.width + 1f && !layout.didOverflowHeight && !layout.isLineEllipsized(0),
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
            "vesqen.now.output-mode",
            "vesqen.now.back",
            "vesqen.now.lyrics-toggle",
            "vesqen.now.more",
            "vesqen.now.progress",
            "vesqen.now.previous",
            "vesqen.now.play-pause",
            "vesqen.now.next",
            "vesqen.now.playback-order",
            "vesqen.now.favorite",
            "vesqen.now.queue",
            "vesqen.now.notes-toggle",
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

    private val summaryMetricIds = ChainSummaryMetricIds

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

    private fun chainSummaryText(textResource: Int): SemanticsNodeInteraction {
        val text = context.getString(textResource)
        composeRule.onNodeWithTag("vesqen.chain.summary-list").performScrollToNode(hasText(text))
        return composeRule.onNodeWithText(text)
    }

    private fun assertChainNumericReadoutsStayStable(
        containerWidth: Dp,
        unitDisplayMode: ChainUnitDisplayMode,
    ) {
        val metricId = TelemetryMetricCatalog.PLAYBACK_ESTIMATED_TOTAL_BUFFERED_DURATION
        val readings = listOf(9_000L, 99_000L, 999_900L, 1_000_000L, 99_999_900L, 1_000_000_000L)
        val telemetry = FakePlaybackTelemetry(bufferedDurationSnapshot(readings.first()))
        val preferences = InMemoryChainDashboardPreferencesRepository(
            ChainDashboardPreferences(
                selectedMetricIds = listOf(metricId),
                pinnedMetricIds = listOf(metricId),
                metricOrder = listOf(metricId),
                viewMode = ChainMetricViewMode.COMPACT,
                unitDisplayMode = unitDisplayMode,
            ),
        )
        render(
            state = activePlaybackState(),
            playbackTelemetry = telemetry,
            chainPreferencesRepository = preferences,
            containerWidth = containerWidth,
            containerHeight = 720.dp,
        )
        composeRule.onNodeWithTag("vesqen.nav.settings").performClick()
        composeRule.onNodeWithTag("vesqen.settings")
            .performScrollToNode(hasTestTag("vesqen.settings.playback-chain"))
        composeRule.onNodeWithTag("vesqen.settings.playback-chain").performClick()

        // The summary pins the underrun count; the advanced dashboard pins the buffer duration.
        val coreRootTag = "vesqen.chain.pinned.underruns"
        val coreValueTag = "vesqen.chain.pinned-value.underruns"
        val coreHeights = mutableSetOf<Int>()
        composeRule.onNodeWithTag("vesqen.chain.summary-list")
            .performScrollToNode(hasTestTag(coreRootTag))
        readings.forEach { reading ->
            telemetry.publish(bufferedDurationSnapshot(reading))
            val expected = formatTelemetryReading(
                context,
                TelemetryReading.Integer(reading, TelemetryUnit.COUNT),
                unitDisplayMode,
            )
            waitForChainValue(coreValueTag, expected)
            assertSingleLineChainValue(coreValueTag, expected)
            coreHeights += composeRule.onNodeWithTag(coreRootTag).fetchSemanticsNode().size.height
        }
        assertEquals("Core readout height must not change at a digit or unit boundary", 1, coreHeights.size)

        openAdvancedChain()
        val metricRootTag = "vesqen.chain.metric.${metricId.value}"
        val metricValueTag = "vesqen.chain.metric-value.${metricId.value}"
        val metricHeights = mutableSetOf<Int>()
        readings.asReversed().forEach { reading ->
            telemetry.publish(bufferedDurationSnapshot(reading))
            chainNode(metricRootTag)
            val expected = formatTelemetryReading(
                context,
                TelemetryReading.Integer(reading, TelemetryUnit.MILLISECONDS),
                unitDisplayMode,
            )
            waitForChainValue(metricValueTag, expected)
            assertSingleLineChainValue(metricValueTag, expected)
            metricHeights += composeRule.onNodeWithTag(metricRootTag).fetchSemanticsNode().size.height
        }
        assertEquals("Metric card height must not change at a digit or unit boundary", 1, metricHeights.size)
    }

    private fun waitForChainValue(tag: String, expected: String) {
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertTextEquals(expected)
            }.isSuccess
        }
    }

    private fun assertSingleLineChainValue(tag: String, expected: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .assertTextEquals(expected)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals("$tag must stay on one line", 1, layout.lineCount)
        assertTrue("$tag must show the complete value", !layout.hasVisualOverflow)
        assertTrue("$tag must align its reading with the left-aligned label", abs(layout.getLineLeft(0)) <= 1f)
    }

    private fun render(
        state: VesqenUiState,
        stateProvider: (() -> VesqenUiState)? = null,
        onPrevious: () -> Unit = {},
        onPlayPause: () -> Unit = {},
        onRefreshPlaybackPosition: () -> Unit = {},
        onNext: () -> Unit = {},
        onCyclePlaybackOrder: () -> Unit = {},
        onSetUsbOutputMode: (UsbOutputMode) -> Unit = {},
        onRequestMusicAccess: () -> Unit = {},
        onOpenAppSettings: () -> Unit = {},
        onOpenNotificationSettings: () -> Unit = {},
        onAddLibraryFolder: () -> Unit = {},
        onPlayQueue: (List<AudioTrack>, Int) -> Unit = { _, _ -> },
        onCreatePlaylist: (String) -> Unit = {},
        onRemoveLibraryFolder: (String) -> Unit = {},
        onResumeLibraryScan: () -> Unit = {},
        onDeletePlaylist: (Long) -> Unit = {},
        onClearQueue: () -> Unit = {},
        onUndoClearQueue: () -> Unit = {},
        onQueueClearedOfferEnded: (Long) -> Unit = {},
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
        appUpdater: AppUpdater? = null,
        usageStatistics: UsageStatistics? = null,
        deviceReporter: DeviceReporter? = null,
        reportUploadAvailable: Boolean = false,
        premiumAccess: PremiumAccess? = null,
        activationCodes: ActivationCodes? = null,
        crashPrompt: CrashPrompt? = null,
        supportPrompt: SupportPrompt? = null,
        supportUrl: String? = null,
        rebuildNotice: LibraryRebuildNotice? = null,
        lyrics: NowLyricsState = NowLyricsState(),
        lyricsProvider: (() -> NowLyricsState)? = null,
        onToggleLyrics: () -> Unit = {},
        loadLyrics: suspend (AudioTrack) -> TrackLyrics = { TrackLyrics.None(folderHint = false) },
        onLyricsImportNoticeShown: (Long) -> Unit = {},
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
                        onDeletePlaylist = onDeletePlaylist,
                        onClearQueue = onClearQueue,
                        onUndoClearQueue = onUndoClearQueue,
                        onQueueClearedOfferEnded = onQueueClearedOfferEnded,
                        onPrevious = onPrevious,
                        onPlayPause = onPlayPause,
                        onRefreshPlaybackPosition = onRefreshPlaybackPosition,
                        onNext = onNext,
                        onSeek = {},
                        onCyclePlaybackOrder = onCyclePlaybackOrder,
                        onSetUsbOutputMode = onSetUsbOutputMode,
                        premiumAccess = premiumAccess,
                        activationCodes = activationCodes,
                        onAddLibraryFolder = onAddLibraryFolder,
                        onRemoveLibraryFolder = onRemoveLibraryFolder,
                        onResumeLibraryScan = onResumeLibraryScan,
                        motionPolicy = motionPolicy,
                        lyrics = lyricsProvider?.invoke() ?: lyrics,
                        loadLyrics = loadLyrics,
                        onToggleLyrics = onToggleLyrics,
                        onLyricsImportNoticeShown = onLyricsImportNoticeShown,
                        versionName = versionName,
                        versionCode = versionCode,
                        playbackTelemetry = playbackTelemetry,
                        chainPreferencesRepository = chainPreferencesRepository,
                        diagnosticRecorder = diagnosticRecorder,
                        diagnosticExportFeedback = diagnosticExportFeedback,
                        onRequestDiagnosticExport = onRequestDiagnosticExport,
                        verificationRegistryState = verificationRegistryState,
                        onImportVerificationRegistry = onImportVerificationRegistry,
                        appUpdater = appUpdater,
                        usageStatistics = usageStatistics,
                        deviceReporter = deviceReporter,
                        reportUploadAvailable = reportUploadAvailable,
                        crashPrompt = crashPrompt,
                        supportPrompt = supportPrompt,
                        supportUrl = supportUrl,
                        rebuildNotice = rebuildNotice,
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

    /** [value] as the buffered duration in milliseconds and as the underrun count. */
    private fun bufferedDurationSnapshot(value: Long): TelemetrySnapshot {
        val snapshot = chainTelemetrySnapshot()
        val epochMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
        fun metric(id: TelemetryMetricId, unit: TelemetryUnit) = TelemetryMetric(
            id = id,
            section = TelemetrySection.PLAYBACK,
            evidence = TelemetryEvidence.Measured(
                reading = TelemetryReading.Integer(value, unit),
                source = TelemetryDataSource(TelemetrySourceId("test.telemetry")),
                observedAtEpochMs = epochMs,
                observedAtElapsedRealtimeMs = elapsedMs,
            ),
        )
        val replaced = setOf(TelemetryMetricCatalog.PLAYBACK_ESTIMATED_TOTAL_BUFFERED_DURATION, TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT)
        return snapshot.copy(
            capturedAtEpochMs = epochMs,
            capturedAtElapsedRealtimeMs = elapsedMs,
            metrics = snapshot.metrics.filterNot { it.id in replaced } +
                metric(TelemetryMetricCatalog.PLAYBACK_ESTIMATED_TOTAL_BUFFERED_DURATION, TelemetryUnit.MILLISECONDS) +
                metric(TelemetryMetricCatalog.PLAYBACK_UNDERRUN_COUNT, TelemetryUnit.COUNT),
        )
    }

    private companion object {
        const val LongTitle = "A deliberately long local track title"
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

/** Counts what the app shell asks of the crash question; no files or records behind it. */
private class FakeCrashPrompt(due: Boolean, checked: Boolean = true) : CrashPrompt {
    private val mutableDue = MutableStateFlow(due)
    override val due: StateFlow<Boolean> = mutableDue.asStateFlow()
    private val mutableChecked = MutableStateFlow(checked)
    override val checked: StateFlow<Boolean> = mutableChecked.asStateFlow()
    fun emitChecked() { mutableChecked.value = true }
    var shown = 0
        private set
    var declined = 0
        private set
    fun emit(value: Boolean) { mutableDue.value = value }
    override fun markShown() { shown++; mutableDue.value = false }
    override fun decline() { declined++; mutableDue.value = false }
}

/** No app handles this scheme, so "Support" reports that no browser could open it. */
private const val UnopenableSupportUrl = "vesqen-test-unopenable://support"

/** Counts what the app shell asks of the support question; no preferences or library behind it. */
private class FakeLibraryRebuildNotice(due: Boolean) : LibraryRebuildNotice {
    private val mutableDue = MutableStateFlow(due)
    override val due: StateFlow<Boolean> = mutableDue.asStateFlow()
    var shown = 0
        private set
    fun emit(value: Boolean) { mutableDue.value = value }
    override fun markShown() { shown++; mutableDue.value = false }
}

private class FakeSupportPrompt(due: Boolean) : SupportPrompt {
    private val mutableDue = MutableStateFlow(due)
    override val due: StateFlow<Boolean> = mutableDue.asStateFlow()
    var checks = 0
        private set
    var shown = 0
        private set
    var finished = 0
        private set
    override fun check() { checks++ }
    override fun markShown() { shown++; mutableDue.value = false }
    override fun finish() { finished++ }
}

private class MemoryPremiumStore(private var trial: TrialRecord = TrialRecord()) : PremiumStore {
    private var introduction = false
    override fun readTrial() = trial
    override fun writeTrial(record: TrialRecord) { trial = record }
    override fun strictIntroductionShown() = introduction
    override fun markStrictIntroductionShown() { introduction = true }
}

/** Accepts exactly [valid], however it is spaced or cased; anything else is not genuine. */
private class FakeActivationCodes(private val valid: String) : ActivationCodes {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<ActivationState>(ActivationState.None)
    private val mutableResult = kotlinx.coroutines.flow.MutableStateFlow<ActivationResult?>(null)
    override val state = mutableState
    override val lastResult = mutableResult
    override fun activate(text: String): ActivationResult {
        val result = if (text.uppercase().filter(Char::isLetterOrDigit) == valid.filter(Char::isLetterOrDigit)) {
            mutableState.value = ActivationState.Active(valid)
            ActivationResult.ACTIVATED
        } else {
            ActivationResult.NOT_GENUINE
        }
        mutableResult.value = result
        return result
    }
}
