package io.github.sumirenokai.vesqen.ui.screens

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.R
import io.github.sumirenokai.vesqen.playback.DIRECT_USB_INTRODUCTION
import io.github.sumirenokai.vesqen.playback.SYSTEM_USB_VOLUME_NOT_RESTORED
import io.github.sumirenokai.vesqen.playback.DirectUsbDetails
import io.github.sumirenokai.vesqen.playback.DirectUsbVolumeControl
import io.github.sumirenokai.vesqen.playback.PlaybackSnapshot
import io.github.sumirenokai.vesqen.playback.UsbOutputFailureOrigin
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.ui.StrictUsbFailureDialog
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** M7-5: all translations keep the first-use decision operable, including at 200% text. */
@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = 33)
class DirectUsbPresentationUiTest(private val language: String, private val largeDark: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = compose.activity
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val locales get() = instrumentation.targetContext.getSystemService(LocaleManager::class.java)
    private lateinit var previousLocales: android.os.LocaleList
    private lateinit var previousLanguage: Locale
    private var previousScale = 1f

    @Before fun configureActivityAndDialogWindows() {
        previousLocales = locales.applicationLocales
        previousLanguage = context.resources.configuration.locales[0]
        previousScale = context.resources.configuration.fontScale
        locales.applicationLocales = android.os.LocaleList.forLanguageTags(language)
        setFontScale(if (largeDark) 2f else 1f)
        compose.waitUntil(10_000) {
            val config = context.resources.configuration
            config.locales[0] == Locale.forLanguageTag(language) && config.fontScale == if (largeDark) 2f else 1f
        }
    }

    @After fun restoreEmulatorConfiguration() {
        locales.applicationLocales = previousLocales
        setFontScale(previousScale)
        compose.waitUntil(10_000) {
            val config = context.resources.configuration
            config.locales[0] == previousLanguage && config.fontScale == previousScale
        }
    }

    // Dialog windows read Android's configuration again, so overriding only Compose locals would
    // test the surrounding page in one language and the dialog in another.
    private fun setFontScale(scale: Float) {
        val command = instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $scale")
        ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
    }
    private val prompt = UsbOutputStatus(mode = UsbOutputMode.STRICT_BIT_PERFECT, phase = UsbOutputPhase.APPLYING,
        decisionCode = DIRECT_USB_INTRODUCTION, deviceName = "Test DAC",
        failureOrigin = UsbOutputFailureOrigin.USER_PLAYBACK, generation = 17)

    private fun content(block: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            VesqenTheme(darkTheme = largeDark, content = block)
        }
    }

    @Test fun firstUseCanBeCancelledAndARepeatedAttemptMustBeConfirmed() {
        val state = mutableStateOf(prompt)
        val answers = mutableListOf<Pair<Long, Boolean>>()
        content {
            StrictUsbFailureDialog(state.value, true, {}, onAnswerUsbOutputConsent = { status, accepted ->
                answers += status.generation to accepted
            })
        }
        compose.onNodeWithTag("vesqen.output.introduction-confirm").assertIsDisplayed()
        capture("introduction", "vesqen.output.introduction-dialog")
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.onNodeWithTag("vesqen.output.introduction-dialog").assertDoesNotExist()
        compose.runOnUiThread { state.value = prompt.copy(generation = 18) }
        compose.onNodeWithTag("vesqen.output.introduction-confirm").performClick()
        assertEquals(listOf(17L to false, 18L to true), answers)
    }

    @Test fun settingsRetainsTheFirstUseInformation() {
        content {
            SettingsScreen(outputStatus = prompt.copy(directUsbAvailable = true), onSetUsbOutputMode = {},
                onOpenPlaybackChain = {}, onImportVerificationRegistry = {}, onOpenAbout = {}, versionName = "Test")
        }
        compose.onNodeWithTag("vesqen.settings.direct-usb-info").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.usb_direct_intro_body)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(android.R.string.ok)).assertIsDisplayed().performClick()
    }

    @Test fun configuredAudioDoesNotDescribeAnOngoingTakeover() {
        val status = prompt.copy(phase = UsbOutputPhase.APPLYING, decisionCode = "direct_usb.configured_not_playing")
        content {
            Column {
                Text(outputClaimTitle(PlaybackSnapshot(usbOutputStatus = status)))
                Text(strictUsbOutputBody(status))
            }
        }
        compose.onNodeWithText(context.getString(R.string.usb_output_waiting_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.usb_output_waiting)).assertIsDisplayed()
    }

    @Test fun aFixedDacDoesNotPromiseThatPhoneKeysCanLowerItsVolume() {
        val control = DirectUsbVolumeControl().apply { publish(null, fixed = true) }
        val status = prompt.copy(phase = UsbOutputPhase.ACTIVE, decisionCode = "direct_usb.active",
            directDetails = DirectUsbDetails(wireSampleRateHz = 48_000, wireBitDepth = 24))
        content {
            CompositionLocalProvider(LocalDirectUsbVolume provides control) {
                Text(strictUsbOutputBody(status))
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_strict_usb_active_direct_fixed,
            "Test DAC", context.getString(R.string.usb_wire_format, "48 kHz", 24))).assertIsDisplayed()
    }

    @Test fun systemVolumeWarningShowsTheDeviceNameAndCanBeDismissed() {
        val status = prompt.copy(
            mode = UsbOutputMode.SYSTEM,
            phase = UsbOutputPhase.SYSTEM,
            decisionCode = SYSTEM_USB_VOLUME_NOT_RESTORED,
        )
        content {
            StrictUsbFailureDialog(status, true, { error("Dismissing the warning must not change modes") })
        }
        compose.onNodeWithText(context.getString(R.string.usb_system_volume_not_restored_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.usb_system_volume_not_restored_body, "Test DAC"))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.usb_system_volume_not_restored_ok))
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("vesqen.output.system-volume-warning-dialog").assertDoesNotExist()
    }

    /** Opt-in review evidence, outside the app's normal data and playback paths. */
    private fun capture(name: String, tag: String) {
        if (InstrumentationRegistry.getArguments().getString("m7Capture") != "true") return
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.filesDir, "m7-review").apply { mkdirs() }
        File(directory, "$name-$language-$largeDark.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}, largeDark={1}")
        fun configurations(): List<Array<Any>> = listOf("en", "zh-CN", "zh-Hant", "ja").flatMap { language ->
            listOf(arrayOf(language, false), arrayOf(language, true))
        }
    }
}
