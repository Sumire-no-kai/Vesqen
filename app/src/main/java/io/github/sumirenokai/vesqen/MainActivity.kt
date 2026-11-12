package io.github.sumirenokai.vesqen

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.sumirenokai.vesqen.ui.theme.VesqenTheme
import io.github.sumirenokai.vesqen.ui.VesqenApp
import io.github.sumirenokai.vesqen.ui.screens.DirectUsbVolumeKeyOverlay
import io.github.sumirenokai.vesqen.ui.screens.LocalDirectUsbVolume
import io.github.sumirenokai.vesqen.ui.LocalScreenOffKeepAlive

class MainActivity : ComponentActivity() {
    /** Counts requests to show the Pro features page, such as after an activation link (#120). */
    private val premiumRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableVesqenEdgeToEdge()
        if (savedInstanceState == null) takePremiumRequest(intent)
        setContent {
            VesqenTheme {
                // UiAutomator sees test tags as resource IDs; the Baseline Profile generator finds screens by them (#50).
                val usbVolume = (application as VesqenApplication).directUsbVolume
                val keepAlive = (application as VesqenApplication).screenOffKeepAlive.takeIf { BuildConfig.DIRECT_USB_ENABLED }
                CompositionLocalProvider(LocalDirectUsbVolume provides usbVolume, LocalScreenOffKeepAlive provides keepAlive) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        VesqenApp(openPremiumRequest = premiumRequests.intValue)
                        DirectUsbVolumeKeyOverlay(usbVolume, Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
    }

    /**
     * While Vesqen's own USB output holds a DAC, the volume keys change its hardware volume (D4): the
     * stream volume the system would change does not act on that output. A held key repeats.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val presses = volumeKeyPresses(keyCode) ?: return super.onKeyDown(keyCode, event)
        return (application as VesqenApplication).directUsbVolume.pressKeys(presses) || super.onKeyDown(keyCode, event)
    }

    /** The key-up belongs to the press taken in [onKeyDown], so the system gets neither. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val taken = volumeKeyPresses(keyCode) != null && (application as VesqenApplication).directUsbVolume.state.value != null
        return taken || super.onKeyUp(keyCode, event)
    }

    private fun volumeKeyPresses(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> 1
        KeyEvent.KEYCODE_VOLUME_DOWN -> -1
        else -> null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takePremiumRequest(intent)
    }

    private fun takePremiumRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PREMIUM, false) == true) premiumRequests.intValue += 1
    }

    companion object {
        const val EXTRA_OPEN_PREMIUM = "io.github.sumirenokai.vesqen.OPEN_PREMIUM"
    }
}

/** Three-button navigation otherwise sits on Android's white band instead of the page's own color. */
internal fun ComponentActivity.enableVesqenEdgeToEdge() {
    enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
}
