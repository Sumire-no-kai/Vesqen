package io.github.sumirenokai.vesqen.ui

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneOrientationPolicyTest {
    @Test
    fun `non-player surfaces stay portrait`() {
        PlayerOrientationOverride.entries.forEach { playerOverride ->
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                requestedPhoneOrientation(
                    hasFocusedPlayer = false,
                    playerOverride = playerOverride,
                ),
            )
        }
    }

    @Test
    fun `focused player follows the system until the user explicitly overrides it`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER,
            requestedPhoneOrientation(true, PlayerOrientationOverride.FOLLOW_SYSTEM),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requestedPhoneOrientation(true, PlayerOrientationOverride.FORCE_PORTRAIT),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedPhoneOrientation(true, PlayerOrientationOverride.FORCE_LANDSCAPE),
        )
    }
}
