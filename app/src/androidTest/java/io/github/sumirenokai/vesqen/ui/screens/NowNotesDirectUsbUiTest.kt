package io.github.sumirenokai.vesqen.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.DirectUsbDetails
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.telemetry.FakePlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnavailableReason
import io.github.sumirenokai.vesqen.telemetry.TelemetryUnit
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry.CapturedAtMs
import io.github.sumirenokai.vesqen.ui.chain.FakeAppSegmentTelemetry.measured
import io.github.sumirenokai.vesqen.ui.chain.formatTelemetryReading
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import org.junit.Rule
import org.junit.Test

/** On Vesqen's own USB output the liner notes say what goes to the DAC, as the Chain page does (iQOO 9, 2026-11-10). */
class NowNotesDirectUsbUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theNotesUseTheDacEvenWhenAndroidSelectsBluetooth() {
        val noTrack = TelemetryEvidence.Unavailable(TelemetryUnavailableReason.SOURCE_DID_NOT_REPORT, CapturedAtMs)
        val telemetry = FakePlaybackTelemetry(
            FakeAppSegmentTelemetry.speakerSession(
                mapOf(
                    Metrics.PLAYBACK_AUDIO_TRACK_SAMPLE_RATE to noTrack,
                    Metrics.PLAYBACK_AUDIO_TRACK_ENCODING to noTrack,
                    Metrics.ROUTE_SELECTED_SYSTEM_TYPE to measured(TelemetryReading.Text("bluetooth_a2dp")),
                    Metrics.USB_DIRECT_WIRE_SAMPLE_RATE to measured(TelemetryReading.Integer(48_000, TelemetryUnit.HERTZ)),
                    Metrics.USB_DIRECT_WIRE_BIT_DEPTH to measured(TelemetryReading.Integer(24, TelemetryUnit.BITS)),
                    Metrics.USB_DIRECT_UNDERRUNS to measured(TelemetryReading.Integer(3, TelemetryUnit.COUNT)),
                    // Android's own count, which describes nothing that plays here.
                    Metrics.PLAYBACK_UNDERRUN_COUNT to measured(TelemetryReading.Integer(0, TelemetryUnit.COUNT)),
                ),
            ),
        )
        val status = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.ACTIVE,
            deviceName = "JBL Flip 7",
            decisionCode = "direct_usb.active",
            generation = 1,
            directDetails = DirectUsbDetails(wireSampleRateHz = 48_000, wireBitDepth = 24),
        )
        compose.setContent {
            VesqenTheme(darkTheme = false) {
                Box(Modifier.size(411.dp, 860.dp)) {
                    NowScreen(
                        snapshot = PlaybackSnapshot(
                            isControllerReady = true, isPlaying = true, trackId = 1, queueEntryId = "a",
                            title = "Song", artist = "Artist", album = "Album",
                            durationMs = 200_000, positionMs = 10_000, queueSize = 1, usbOutputStatus = status,
                        ),
                        currentTrack = null, artworkTrack = null, onBackToLibrary = {}, onOpenChain = {},
                        onCyclePlaybackOrder = {}, onPrevious = {}, onPlayPause = {}, onNext = {},
                        onSeek = { _, _ -> }, onPlayQueueIndex = {}, onRemoveQueueItem = {},
                        onMoveQueueItem = { _, _ -> }, onClearQueue = {}, onRetryPlayback = {},
                        onToggleOrientation = {}, showOrientationToggle = false, isLandscape = false,
                        motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                        playbackTelemetry = telemetry,
                    )
                }
            }
        }
        compose.onNodeWithTag("vesqen.now.notes-toggle").performClick()
        compose.waitForIdle()

        val bits = formatTelemetryReading(context, TelemetryReading.Integer(24, TelemetryUnit.BITS))
        val rate = formatTelemetryReading(context, TelemetryReading.Integer(48_000, TelemetryUnit.HERTZ))
        compose.onNodeWithTag("vesqen.now.notes.audio-track")
            .assertTextContains(context.getString(R.string.now_notes_handoff_direct))
            .assertTextContains("$bits · $rate")
        compose.onNodeWithTag("vesqen.now.notes.route").assertTextContains(
            context.resources.getQuantityString(R.plurals.now_notes_route_underruns, 3, "JBL Flip 7", "3"),
        )
        compose.onNodeWithTag("vesqen.now.notes.bluetooth-codec").assertDoesNotExist()
        compose.onNodeWithTag("vesqen.now.notes.output-format").assertTextContains(
            context.getString(R.string.now_notes_output_format_bit_perfect, context.getString(R.string.usb_wire_format, "48 kHz", 24)),
        )
    }
}
