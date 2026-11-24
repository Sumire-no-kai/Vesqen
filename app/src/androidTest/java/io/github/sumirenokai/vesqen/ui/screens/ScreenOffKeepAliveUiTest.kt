package io.github.sumirenokai.vesqen.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.playback.ScreenOffKeepAliveSettings
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.ui.LocalScreenOffKeepAlive
import io.github.sumirenokai.vesqen.ui.ScreenOffPlaybackDialog
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** D13 (#333): the keep-alive switch, the vivo system-setting row and the one-time guidance. */
class ScreenOffKeepAliveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun clearPreferences() {
        context.getSharedPreferences("direct_usb_keep_alive", 0).edit().clear().commit()
    }

    private fun setSettings(settings: ScreenOffKeepAliveSettings, mode: UsbOutputMode, directUsbAvailable: Boolean = true) {
        compose.setContent {
            VesqenTheme(darkTheme = false) {
                CompositionLocalProvider(LocalScreenOffKeepAlive provides settings) {
                    SettingsScreen(
                        outputStatus = UsbOutputStatus(mode = mode, phase = UsbOutputPhase.SYSTEM, directUsbAvailable = directUsbAvailable),
                        onSetUsbOutputMode = {},
                        onOpenPlaybackChain = {},
                        onImportVerificationRegistry = {},
                        onOpenAbout = {},
                        versionName = "2.0.0",
                    )
                }
            }
        }
    }

    @Test fun strictOwnOutputShowsTheSwitchOffByDefault() {
        val settings = ScreenOffKeepAliveSettings(context, vivoFamily = true)
        setSettings(settings, UsbOutputMode.STRICT_BIT_PERFECT)
        compose.onNodeWithTag("vesqen.settings.screen-off-keep-alive").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("vesqen.settings.screen-off-keep-alive").assertIsOn()
        assertTrue(settings.enabled.value)
        compose.onNodeWithTag("vesqen.settings.background-power").performScrollTo()
    }

    @Test fun otherPhonesGetNoVivoRow() {
        setSettings(ScreenOffKeepAliveSettings(context, vivoFamily = false), UsbOutputMode.STRICT_BIT_PERFECT)
        compose.onNodeWithTag("vesqen.settings.screen-off-keep-alive").performScrollTo()
        assertEquals(0, compose.onAllNodes(hasTestTag("vesqen.settings.background-power")).fetchSemanticsNodes().size)
    }

    @Test fun systemOutputShowsNeither() = assertNeither(UsbOutputMode.SYSTEM, directUsbAvailable = true)

    @Test fun strictWithoutTheOwnOutputShowsNeither() = assertNeither(UsbOutputMode.STRICT_BIT_PERFECT, directUsbAvailable = false)

    private fun assertNeither(mode: UsbOutputMode, directUsbAvailable: Boolean) {
        setSettings(ScreenOffKeepAliveSettings(context, vivoFamily = true), mode, directUsbAvailable)
        for (tag in listOf("vesqen.settings.screen-off-keep-alive", "vesqen.settings.background-power")) {
            assertEquals(tag, 0, compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size)
        }
    }

    @Test fun theGuidanceButtonsReportTheChoice() {
        val choices = mutableListOf<String>()
        compose.setContent {
            VesqenTheme(darkTheme = false) {
                ScreenOffPlaybackDialog(
                    onOpenSystemSettings = { choices += "settings" },
                    onKeepAlive = { choices += "keep-alive" },
                    onNotNow = { choices += "not now" },
                )
            }
        }
        compose.onNodeWithTag("vesqen.screen-off-guidance.keep-alive").performClick()
        compose.onNodeWithTag("vesqen.screen-off-guidance.not-now").performClick()
        compose.onNodeWithTag("vesqen.screen-off-guidance.settings").performClick()
        assertEquals(listOf("keep-alive", "not now", "settings"), choices)
    }
}
