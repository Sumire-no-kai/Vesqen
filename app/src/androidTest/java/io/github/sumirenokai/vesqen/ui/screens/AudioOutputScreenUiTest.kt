package io.github.sumirenokai.vesqen.ui.screens

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.DsdConversion
import io.github.sumirenokai.vesqen.playback.DsdConversionSettings
import io.github.sumirenokai.vesqen.playback.DsdOutputRateMode
import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** #249: the Audio output page's DSD settings and the strict status it took over from Settings. */
class AudioOutputScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before @After
    fun clearSettings() {
        context.getSharedPreferences("dsd_conversion", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun show(status: UsbOutputStatus = UsbOutputStatus(), onOpenPremium: (() -> Unit)? = null): DsdConversionSettings {
        val settings = DsdConversionSettings(context)
        compose.setContent {
            VesqenTheme(darkTheme = false) {
                CompositionLocalProvider(LocalDsdConversionSettings provides settings) {
                    AudioOutputScreen(outputStatus = status, onSetUsbOutputMode = {}, onBack = {}, onOpenPremium = onOpenPremium)
                }
            }
        }
        return settings
    }

    @Test fun theGainSwitchRevealsASliderThatSpeaksItsValue() {
        val settings = show()
        compose.onNodeWithTag("vesqen.audio-output.dsd-gain-level").assertDoesNotExist()
        compose.onNodeWithTag("vesqen.audio-output.dsd-gain").performScrollTo().performClick()
        compose.onNodeWithTag("vesqen.audio-output.dsd-gain").assertIsOn()
        compose.onNodeWithTag("vesqen.audio-output.dsd-gain-level")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(context.getString(R.string.settings_dsd_gain_level))))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.usb_volume_state, dsdGainNumber(3f))))
        compose.runOnIdle { assertEquals(DsdConversion(gainDb = 3f), settings.conversion()) }
    }

    @Test fun theOutputRateIsAChoiceOfTwo() {
        val settings = show()
        compose.onNodeWithTag("vesqen.audio-output.dsd-rate.uniform").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("vesqen.audio-output.dsd-rate.multiple").performScrollTo().performClick()
        compose.onNodeWithTag("vesqen.audio-output.dsd-rate.multiple").assertIsSelected()
        compose.runOnIdle { assertEquals(DsdOutputRateMode.MULTIPLE, settings.values.value.rateMode) }
    }

    /** Strict output does not play DSD, so the DSD settings, which apply under System output, stay adjustable. */
    @Test fun strictOutputLeavesTheDsdSettingsAdjustable() {
        show(UsbOutputStatus(mode = UsbOutputMode.STRICT_BIT_PERFECT))
        compose.onNodeWithTag("vesqen.audio-output.dsd-gain").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("vesqen.audio-output.dsd-rate.multiple").performScrollTo().assertIsEnabled()
    }

    @Test fun anEndedTrialOffersProFeaturesInTheStrictStatus() {
        var opened = false
        show(
            UsbOutputStatus(mode = UsbOutputMode.STRICT_BIT_PERFECT, phase = UsbOutputPhase.FAILED, failure = UsbOutputFailure.TRIAL_ENDED),
            onOpenPremium = { opened = true },
        )
        compose.onNodeWithTag("vesqen.audio-output.open-premium").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
