package io.github.sumirenokai.vesqen.ui.screens

import android.media.VolumeProvider
import android.media.session.MediaController
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.playback.DeviceVolumeSessionPlayer
import io.github.sumirenokai.vesqen.playback.DirectUsbVolume
import io.github.sumirenokai.vesqen.playback.DirectUsbVolumeControl
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.FIXED_VOLUME_CONFIRMATION
import io.github.sumirenokai.vesqen.playback.UsbOutputFailureOrigin
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.ui.StrictUsbFailureDialog
import io.github.sumirenokai.vesqen.ui.theme.VesqenMotionPolicy
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** D4: Now shows the DAC's hardware volume only while Vesqen's own USB output holds the DAC. */
class NowUsbVolumeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val control = DirectUsbVolumeControl()
    private val asked = mutableListOf<Int>()

    // The JBL Flip 7's range.
    private val jbl = DirectUsbVolume(current = -7700, minimum = -15400, maximum = 0, resolution = 154)

    private fun setNow(width: Int = 360, height: Int = 720) {
        control.attach { asked += it }
        compose.setContent {
            VesqenTheme(darkTheme = false) {
                CompositionLocalProvider(LocalDirectUsbVolume provides control) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        NowScreen(
                            snapshot = PlaybackSnapshot(
                                isControllerReady = true,
                                isPlaying = true,
                                trackId = 1,
                                queueEntryId = "a",
                                title = "Song",
                                artist = "Artist",
                                album = "Album",
                                durationMs = 200_000,
                                positionMs = 10_000,
                                queueSize = 1,
                            ),
                            currentTrack = null,
                            artworkTrack = null,
                            onBackToLibrary = {},
                            onOpenChain = {},
                            onCyclePlaybackOrder = {},
                            onPrevious = {},
                            onPlayPause = {},
                            onNext = {},
                            onSeek = { _, _ -> },
                            onPlayQueueIndex = {},
                            onRemoveQueueItem = {},
                            onMoveQueueItem = { _, _ -> },
                            onClearQueue = {},
                            onRetryPlayback = {},
                            onToggleOrientation = {},
                            showOrientationToggle = false,
                            isLandscape = false,
                            motionPolicy = VesqenMotionPolicy(reduceMotion = true),
                        )
                    }
                }
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun androidMediaSessionReportsRemoteFixedAndRestoresLocalOnRelease() {
        lateinit var player: ExoPlayer
        lateinit var wrapper: DeviceVolumeSessionPlayer
        lateinit var session: MediaSession
        lateinit var platform: MediaController
        compose.runOnUiThread {
            player = ExoPlayer.Builder(compose.activity).build()
            wrapper = DeviceVolumeSessionPlayer(player, control)
            session = MediaSession.Builder(compose.activity, wrapper).build()
            platform = MediaController(compose.activity, session.platformToken)
        }
        try {
            compose.runOnUiThread { control.publish(null, fixed = true) }
            compose.waitUntil(5_000) {
                platform.playbackInfo.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE &&
                    platform.playbackInfo.volumeControl == VolumeProvider.VOLUME_CONTROL_FIXED
            }
            compose.runOnUiThread { control.publish(jbl) }
            compose.waitUntil(5_000) { platform.playbackInfo.volumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE }
            compose.runOnUiThread { control.publish(null) }
            compose.waitUntil(5_000) { platform.playbackInfo.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL }
        } finally {
            compose.runOnUiThread {
                session.release()
                wrapper.stopReporting()
                player.release()
            }
        }
    }

    @Test
    fun fixedOutputShowsDeviceAdjustmentWithoutASlider() {
        setNow()
        compose.runOnUiThread { control.publish(null, fixed = true) }
        compose.onNodeWithTag("vesqen.usb-volume").assertDoesNotExist()
        compose.onNodeWithTag("vesqen.usb-volume.fixed").assertIsDisplayed()
        compose.runOnUiThread { control.publish(jbl) }
        compose.onNodeWithTag("vesqen.usb-volume.fixed").assertDoesNotExist()
        compose.onNodeWithTag("vesqen.usb-volume").assertIsDisplayed()
    }

    @Test
    fun cancellingAndConfirmingAnswerDifferentAttempts() {
        val prompt = androidx.compose.runtime.mutableStateOf(UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT, phase = UsbOutputPhase.AVAILABLE,
            decisionCode = FIXED_VOLUME_CONFIRMATION,
            failureOrigin = UsbOutputFailureOrigin.USER_PLAYBACK, deviceName = "Test DAC", generation = 1,
        ))
        val answers = mutableListOf<Pair<Long, Boolean>>()
        compose.setContent {
            StrictUsbFailureDialog(prompt.value, true, {}, onAnswerFixedVolume = { state, accept ->
                answers += state.generation to accept
            })
        }
        compose.onNodeWithText("Test DAC", substring = true).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(io.github.sumirenokai.vesqen.R.string.cancel)).performClick()
        compose.onNodeWithTag("vesqen.output.fixed-volume-dialog").assertDoesNotExist()
        compose.runOnUiThread { prompt.value = prompt.value.copy(generation = 2) }
        compose.onNodeWithTag("vesqen.output.fixed-volume-confirm").performClick()
        compose.onNodeWithTag("vesqen.output.fixed-volume-dialog").assertDoesNotExist()
        assertEquals(listOf(1L to false, 2L to true), answers)
    }

    @Test
    fun theBarAppearsWithTheOwnOutputAndGoesWithIt() {
        setNow()
        compose.onNodeWithTag("vesqen.usb-volume").assertDoesNotExist()

        compose.runOnUiThread { control.publish(jbl) }
        compose.waitForIdle()
        compose.onNodeWithTag("vesqen.usb-volume").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("vesqen.usb-volume.value", useUnmergedTree = true).assertTextEquals("-30.1 dB")
        // The transport and the liner notes are still on the page with the extra row.
        compose.onNodeWithTag("vesqen.now.transport-dock").assertIsDisplayed()
        compose.onNodeWithTag("vesqen.now.progress").assertIsDisplayed()

        compose.runOnUiThread { control.publish(null) }
        compose.waitForIdle()
        compose.onNodeWithTag("vesqen.usb-volume").assertDoesNotExist()
    }

    @Test
    fun aSmallPhoneKeepsTheLinerNotesRowWholeUnderTheBar() {
        // 320 x 568 dp with the bar is a compact page with a cover; its rows were budgeted about 25 dp short.
        setNow(width = 320, height = 568)
        compose.runOnUiThread { control.publish(jbl) }
        compose.waitForIdle()
        compose.onNodeWithTag("vesqen.usb-volume").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("vesqen.now.notes-toggle").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aShortWindowKeepsTheLinerNotesRowWholeWithoutTheBar() {
        setNow(width = 320, height = 520)
        compose.onNodeWithTag("vesqen.now.notes-toggle").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aScreenReaderHearsTheNameAndTheLevel() {
        setNow()
        compose.runOnUiThread { control.publish(jbl) }
        compose.waitForIdle()
        val slider = compose.onNodeWithContentDescription("DAC volume").fetchSemanticsNode()
        assertEquals("-30.1 decibels", slider.config[SemanticsProperties.StateDescription])
    }

    @Test
    fun aTapAsksForADeviceSettingAndTheBarFollowsTheDevice() {
        setNow()
        compose.runOnUiThread { control.publish(jbl) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("DAC volume").performTouchInput { click(percentOffset(.95f, .5f)) }
        compose.waitForIdle()

        val request = asked.last()
        assertTrue("Request $request is a device setting near the maximum", request > -2310 && (request + 15400) % 154 == 0)
        // The device answered with another level: the bar shows that one once the drag has settled.
        compose.runOnUiThread { control.publish(jbl.copy(current = -3080)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag("vesqen.usb-volume.value", useUnmergedTree = true).assertTextEquals("-12.0 dB")
    }
}
