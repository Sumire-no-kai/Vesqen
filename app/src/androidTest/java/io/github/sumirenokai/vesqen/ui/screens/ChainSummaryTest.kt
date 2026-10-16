package io.github.sumirenokai.vesqen.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventKind
import io.github.sumirenokai.vesqen.telemetry.TelemetryEventSeverity
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryPowerMode
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import io.github.sumirenokai.vesqen.ui.chain.ChainObservationState
import io.github.sumirenokai.vesqen.ui.chain.ChainUnitDisplayMode
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry.CapturedAtMs
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry.measured
import io.github.sumirenokai.vesqen.ui.chain.formatTelemetryReading
import io.github.sumirenokai.vesqen.ui.chain.telemetryEvidenceSource
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #72 in the Chain summary: each segment states only what its evidence supports. The summary is
 * rendered with a fixed clock so a slow device cannot turn the fixture stale mid-test.
 */
class ChainSummaryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val playing = PlaybackSnapshot(
        isControllerReady = true,
        isPlaying = true,
        trackId = 1,
        title = "Dawn Signal",
        artist = "Mori",
        album = "Quiet Rooms",
        durationMs = 245_000,
    )

    @Test
    fun an_untouched_lossless_stream_is_unchanged_in_vesqens_part_only() {
        show(FakeAppSegmentTelemetry.snapshot())

        segment(AppSegment).assertTextContains(string(R.string.chain_segment_unchanged_lossless), substring = true)
        // Off Bluetooth the system's part only marks the boundary; the declaration card covers it.
        segment(SystemSegment).assertTextEquals(string(R.string.chain_segment_system))
        processing().assertTextContains(string(R.string.chain_processing_none), substring = true)
    }

    @Test
    fun a_changed_speed_is_named_in_the_verdict_and_at_the_processing_station() {
        show(snapshot(Metrics.PROCESSING_SPEED to TelemetryReading.Decimal(125.0, TelemetryUnit.PERCENT)))
        val speed = string(R.string.chain_condition_speed)

        segment(AppSegment).assertTextContains(context.getString(R.string.chain_segment_modified, speed), substring = true)
        processing().assertTextContains(context.getString(R.string.chain_processing_changed, speed), substring = true)
    }

    @Test
    fun bluetooth_is_lossy_or_unknown_in_the_systems_part_without_changing_vesqens() {
        show(snapshot(Metrics.ROUTE_SELECTED_SYSTEM_TYPE to TelemetryReading.Text("bluetooth_a2dp")))

        segment(SystemSegment).assertTextContains(string(R.string.chain_segment_bluetooth), substring = true)
        segment(AppSegment).assertTextContains(string(R.string.chain_segment_unchanged_lossless), substring = true)
    }

    @Test
    fun a_settling_track_change_or_old_data_withholds_the_verdict() {
        val changed = TelemetryEvent(
            sequence = 1,
            kind = TelemetryEventKind.MEDIA_ITEM_CHANGED,
            severity = TelemetryEventSeverity.INFO,
            occurredAtEpochMs = CapturedAtMs - 500,
            code = "test.event",
        )
        var snapshot by mutableStateOf(FakeAppSegmentTelemetry.snapshot(recentEvents = listOf(changed)))
        var now by mutableStateOf(CapturedAtMs + 100)
        show(snapshotProvider = { snapshot }, nowProvider = { now })
        segment(AppSegment).assertTextContains(unknown(R.string.chain_segment_reason_transition), substring = true)

        composeRule.runOnIdle {
            snapshot = FakeAppSegmentTelemetry.snapshot()
            // Two missed one-second samples make the snapshot too old to judge.
            now = CapturedAtMs + 2_001
        }
        segment(AppSegment).assertTextContains(unknown(R.string.chain_segment_reason_stale), substring = true)
    }

    @Test
    fun the_last_playback_is_not_judged_and_pinned_metrics_wait_for_playback() {
        show(FakeAppSegmentTelemetry.snapshot(), playback = playing.copy(isPlaying = false, showsPauseAction = false))

        segment(AppSegment).assertTextContains(string(R.string.chain_segment_waiting), substring = true)
        processing().assertTextContains(string(R.string.chain_segment_waiting), substring = true)
        scrollTo("vesqen.chain.pinned")
        ChainPinnedReading.entries.forEach { reading ->
            pinned("value", reading).assertTextEquals("—")
            pinned("detail", reading).assertTextEquals("")
            pinned("evidence", reading).assertTextEquals("")
        }
        // Only the notice says that nothing is playing; the cards do not repeat it.
        composeRule.onAllNodes(hasText(string(R.string.chain_idle_title)) and hasAnyAncestor(hasTestTag("vesqen.chain.pinned")))
            .assertCountEquals(0)
    }

    @Test
    fun pinned_cards_sum_up_the_source_the_handoff_the_output_and_underruns() {
        show(
            snapshot(
                Metrics.SOURCE_CODEC_LABEL to TelemetryReading.Text("FLAC"),
                Metrics.SOURCE_BIT_DEPTH to TelemetryReading.Integer(16, TelemetryUnit.BITS),
                Metrics.SOURCE_SAMPLE_RATE to TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ),
                Metrics.PLAYBACK_UNDERRUN_COUNT to TelemetryReading.Integer(0, TelemetryUnit.COUNT),
            ),
        )

        scrollTo("vesqen.chain.pinned")
        pinned("value", ChainPinnedReading.SOURCE).assertTextEquals("FLAC")
        pinned("detail", ChainPinnedReading.SOURCE).assertTextEquals(
            "${reading(TelemetryReading.Integer(16, TelemetryUnit.BITS))} · ${reading(TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ))}",
        )
        pinned("value", ChainPinnedReading.HANDOFF).assertTextEquals(reading(TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ)))
        pinned("detail", ChainPinnedReading.HANDOFF).assertTextEquals("pcm-float")
        pinned("value", ChainPinnedReading.OUTPUT).assertTextEquals(string(R.string.chain_pinned_output_speaker))
        pinned("value", ChainPinnedReading.UNDERRUNS).assertTextEquals(reading(TelemetryReading.Integer(0, TelemetryUnit.COUNT)))
        ChainPinnedReading.entries.forEach { pinned("evidence", it).assertTextEquals(string(R.string.chain_confidence_measured)) }
    }

    @Test
    fun a_lossy_source_is_described_by_its_bit_rate() {
        show(
            snapshot(
                Metrics.SOURCE_CODEC_LABEL to TelemetryReading.Text("MP3"),
                Metrics.SOURCE_AVERAGE_BITRATE to TelemetryReading.Integer(320_000, TelemetryUnit.BITS_PER_SECOND),
                Metrics.SOURCE_SAMPLE_RATE to TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ),
            ),
        )

        scrollTo("vesqen.chain.pinned")
        pinned("value", ChainPinnedReading.SOURCE).assertTextEquals("MP3")
        pinned("detail", ChainPinnedReading.SOURCE).assertTextEquals(
            "${reading(TelemetryReading.Integer(320_000, TelemetryUnit.BITS_PER_SECOND))} · " +
                reading(TelemetryReading.Integer(44_100, TelemetryUnit.HERTZ)),
        )
    }

    @Test
    fun the_output_card_names_the_reported_bluetooth_codec() {
        show(
            snapshot(
                Metrics.ROUTE_SELECTED_SYSTEM_TYPE to TelemetryReading.Text("bluetooth"),
                Metrics.ROUTE_BLUETOOTH_CODEC to TelemetryReading.Text("LDAC"),
                Metrics.ROUTE_BLUETOOTH_CONFIGURATION to TelemetryReading.Text("96000hz,32bit,stereo,ldac_990kbps"),
            ),
        )

        scrollTo("vesqen.chain.pinned")
        pinned("value", ChainPinnedReading.OUTPUT).assertTextEquals("LDAC")
        pinned("detail", ChainPinnedReading.OUTPUT).assertTextEquals(
            listOf(
                context.getString(R.string.chain_unit_khz, "96"),
                context.getString(R.string.chain_unit_bits, "32"),
                context.getString(R.string.chain_unit_kbps, "990"),
            ).joinToString(" · "),
        )
    }

    @Test
    fun pinned_metrics_state_the_cadence_lower_power_really_uses() {
        show(FakeAppSegmentTelemetry.snapshot(), powerMode = TelemetryPowerMode.LOW_POWER)

        scrollTo("vesqen.chain.pinned")
        composeRule.onNodeWithText(
            context.getString(R.string.chain_pinned_refresh, string(R.string.chain_refresh_2s)),
        ).assertExists()
    }

    @Test
    fun pinned_values_keep_their_size_with_large_text() {
        show(FakeAppSegmentTelemetry.snapshot(), fontScale = 1.3f)

        scrollTo("vesqen.chain.pinned")
        val layouts = mutableListOf<TextLayoutResult>()
        pinned("value", ChainPinnedReading.HANDOFF)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        // A line taller than its box overflows at every size, and autosize then falls back to 12 sp.
        assertTrue("fontSize=${layout.layoutInput.style.fontSize}", layout.layoutInput.style.fontSize.value >= 24f)
        assertFalse(layout.hasVisualOverflow)
    }

    @Test
    fun a_strict_failure_offers_system_output_while_it_can_be_chosen() {
        var requests = 0
        var playback by mutableStateOf(
            playing.copy(
                usbOutputStatus = UsbOutputStatus(
                    mode = UsbOutputMode.STRICT_BIT_PERFECT,
                    phase = UsbOutputPhase.FAILED,
                    failure = UsbOutputFailure.NO_USB_AUDIO_DEVICE,
                    generation = 1,
                ),
            ),
        )
        show(playbackProvider = { playback }, onUseSystemOutput = { requests++ })

        composeRule.onNodeWithTag("vesqen.chain.use-system").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, requests)
            playback = playback.copy(canSetUsbOutputMode = false)
        }
        composeRule.onNodeWithTag("vesqen.chain.use-system").assertIsNotEnabled()
        composeRule.runOnIdle { playback = playback.copy(usbOutputStatus = UsbOutputStatus(), canSetUsbOutputMode = true) }
        composeRule.onAllNodesWithTag("vesqen.chain.use-system").assertCountEquals(0)
    }

    @Test
    fun tapping_a_station_reveals_its_evidence_one_station_at_a_time() {
        show(FakeAppSegmentTelemetry.snapshot())
        val flags = listOf(
            Metrics.PROCESSING_SPEED, Metrics.PROCESSING_PITCH, Metrics.PROCESSING_PLAYER_VOLUME,
            Metrics.PROCESSING_SKIP_SILENCE, Metrics.PROCESSING_REPLAY_GAIN_ACTIVE, Metrics.PROCESSING_EQUALIZER_ACTIVE,
            Metrics.PROCESSING_LOUDNESS_ACTIVE, Metrics.PROCESSING_CROSSFADE_ACTIVE, Metrics.PROCESSING_APP_DSP_ACTIVE,
        )
        val source = telemetryEvidenceSource(context, measured(TelemetryReading.Text("fixture")))
        val audioTrackRate = "vesqen.chain.core.${Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE.value}"

        flags.forEach { composeRule.onAllNodesWithTag(processingFlag(it), useUnmergedTree = true).assertCountEquals(0) }
        processing().performClick()
        flags.forEach { composeRule.onNodeWithTag(processingFlag(it), useUnmergedTree = true).assertExists() }

        scrollTo(audioTrackRate)
        composeRule.onNodeWithTag(audioTrackRate).assert(!hasText(source, substring = true)).performClick()
        composeRule.onNodeWithTag(audioTrackRate).assertTextContains(source, substring = true)
        flags.forEach { composeRule.onAllNodesWithTag(processingFlag(it), useUnmergedTree = true).assertCountEquals(0) }
    }

    private fun show(
        snapshot: TelemetrySnapshot = FakeAppSegmentTelemetry.snapshot(),
        playback: PlaybackSnapshot = playing,
        powerMode: TelemetryPowerMode = TelemetryPowerMode.STANDARD,
        snapshotProvider: () -> TelemetrySnapshot = { snapshot },
        nowProvider: () -> Long = { CapturedAtMs + 100 },
        playbackProvider: () -> PlaybackSnapshot = { playback },
        onUseSystemOutput: () -> Unit = {},
        fontScale: Float? = null,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                VesqenTheme {
                    ChainSummaryScreen(
                        playback = playbackProvider(),
                        observationState = ChainObservationState.Content(snapshotProvider()),
                        nowElapsedRealtimeMs = nowProvider(),
                        refreshInterval = TelemetryRefreshInterval.ONE_SECOND,
                        powerMode = powerMode,
                        unitDisplayMode = ChainUnitDisplayMode.AUTO,
                        onOpenAdvanced = {},
                        onUseSystemOutput = onUseSystemOutput,
                        onRetry = {},
                    )
                }
            }
        }
    }

    private fun snapshot(vararg readings: Pair<TelemetryMetricId, TelemetryReading>): TelemetrySnapshot =
        FakeAppSegmentTelemetry.snapshot(readings.associate { (id, reading) -> id to measured(reading) })

    private fun pinned(part: String, reading: ChainPinnedReading): SemanticsNodeInteraction =
        composeRule.onNodeWithTag("vesqen.chain.pinned-$part.${reading.tag}", useUnmergedTree = true)

    private fun reading(value: TelemetryReading): String = formatTelemetryReading(context, value)

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag("vesqen.chain.summary-list").performScrollToNode(hasTestTag(tag))
    }

    private fun segment(tag: String): SemanticsNodeInteraction {
        scrollTo(tag)
        return composeRule.onNodeWithTag(tag)
    }

    private fun processing(): SemanticsNodeInteraction = segment("vesqen.chain.processing")

    private fun processingFlag(id: TelemetryMetricId) = "vesqen.chain.processing.${id.value}"

    private fun string(resource: Int) = context.getString(resource)

    private fun unknown(reason: Int) = context.getString(R.string.chain_segment_unknown, string(reason))

    private companion object {
        const val AppSegment = "vesqen.chain.segment.app"
        const val SystemSegment = "vesqen.chain.segment.system"
    }
}
