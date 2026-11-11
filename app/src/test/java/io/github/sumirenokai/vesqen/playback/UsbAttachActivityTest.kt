package io.github.sumirenokai.vesqen.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import io.github.sumirenokai.vesqen.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsbAttachActivityTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun strictSelectionEnablesTheEntryAndSystemSelectionDisablesIt() {
        val component = ComponentName(context, UsbAttachActivity::class.java)
        UsbAttachActivity.syncEnabled(context, UsbOutputMode.STRICT_BIT_PERFECT)
        assertEquals(if (BuildConfig.DIRECT_USB_ENABLED) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(component))
        UsbAttachActivity.syncEnabled(context, UsbOutputMode.SYSTEM)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(component))
    }

    @Test fun restartingSynchronizesTheEntryFromTheSavedSelection() {
        context.getSharedPreferences(UsbOutputCoordinator.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putString(UsbOutputCoordinator.MODE_KEY, UsbOutputMode.STRICT_BIT_PERFECT.name).commit()
        UsbAttachActivity.syncEnabled(context)
        val enabled = context.packageManager.getComponentEnabledSetting(ComponentName(context, UsbAttachActivity::class.java))
        assertEquals(BuildConfig.DIRECT_USB_ENABLED, enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }

    @Test fun attachmentFinishesAndSignalsWithoutStartingPlaybackService() {
        val app = shadowOf(context)
        val broadcasts = app.broadcastIntents.size
        val activity = Robolectric.buildActivity(UsbAttachActivity::class.java,
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, "untrusted"))
            .create().get()
        assertTrue(activity.isFinishing)
        val signals = app.broadcastIntents.drop(broadcasts)
        assertEquals(if (BuildConfig.DIRECT_USB_ENABLED) 1 else 0, signals.size)
        signals.singleOrNull()?.let {
            assertEquals(UsbAttachActivity.ACTION_RECHECK, it.action)
            assertEquals(context.packageName, it.`package`)
            assertNull(it.extras)
        }
        assertNull(app.nextStartedService)
    }
}
