package io.github.sumirenokai.vesqen.ui.screens

import androidx.activity.ComponentActivity
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.sumirenokai.vesqen.playback.DirectUsbVolume
import io.github.sumirenokai.vesqen.playback.DirectUsbVolumeControl
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
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
