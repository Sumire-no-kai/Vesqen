package io.github.sumirenokai.vesqen.playback

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import io.github.sumirenokai.vesqen.BuildConfig

/** Receives Android's default USB application grant. The running service rechecks the device itself. */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DIRECT_USB_ENABLED && intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            sendBroadcast(Intent(ACTION_RECHECK).setPackage(packageName))
        }
        finish()
    }

    internal companion object {
        const val ACTION_RECHECK = "io.github.sumirenokai.vesqen.playback.RECHECK_USB_ATTACHMENT"

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun syncEnabled(context: Context) {
            val preferences = context.getSharedPreferences(UsbOutputCoordinator.PREFERENCES_NAME, Context.MODE_PRIVATE)
            val mode = preferences.getString(UsbOutputCoordinator.MODE_KEY, null)
            val enabled = BuildConfig.DIRECT_USB_ENABLED && mode == UsbOutputMode.STRICT_BIT_PERFECT.name
            val component = ComponentName(context, UsbAttachActivity::class.java)
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (context.packageManager.getComponentEnabledSetting(component) != state) {
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
