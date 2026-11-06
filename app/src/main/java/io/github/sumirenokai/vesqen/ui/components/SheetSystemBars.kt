package io.github.sumirenokai.vesqen.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * A sheet is a window of its own, so the main window's setting does not reach it: three-button
 * navigation would sit on Android's white band there. Call it inside the sheet's content.
 */
@Composable
internal fun SheetWithoutNavigationBand() {
    val view = LocalView.current
    SideEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (view.parent as? DialogWindowProvider)?.window?.isNavigationBarContrastEnforced = false
        }
    }
}
