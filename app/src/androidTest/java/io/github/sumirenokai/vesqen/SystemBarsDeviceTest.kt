package io.github.sumirenokai.vesqen

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import io.github.sumirenokai.vesqen.ui.components.SheetWithoutNavigationBand
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SystemBarsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Three-button navigation sits on the page's own color, not on Android's white band (#261 V35). */
    @Test
    fun theNavigationBarGetsNoContrastBand() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        compose.runOnUiThread {
            compose.activity.enableVesqenEdgeToEdge()
            assertFalse(compose.activity.window.isNavigationBarContrastEnforced)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun aSheetGetsNoContrastBandEither() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        var window: android.view.Window? = null
        compose.setContent {
            ModalBottomSheet(onDismissRequest = {}) {
                SheetWithoutNavigationBand()
                window = (LocalView.current.parent as? DialogWindowProvider)?.window
            }
        }
        compose.waitForIdle()
        val sheetWindow = checkNotNull(window) { "A sheet should have its own window" }
        compose.runOnUiThread { assertFalse(sheetWindow.isNavigationBarContrastEnforced) }
    }
}
