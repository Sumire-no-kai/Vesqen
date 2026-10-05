package io.github.sumirenokai.vesqen.ui

import io.github.sumirenokai.vesqen.playback.UsbOutputFailure
import io.github.sumirenokai.vesqen.playback.UsbOutputFailureOrigin
import io.github.sumirenokai.vesqen.playback.UsbOutputMode
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStatus
import io.github.sumirenokai.vesqen.ui.screens.strictUsbSwitchEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictUsbFailurePolicyTest {
    @Test fun `only failures that block a user action interrupt with a dialog`() {
        val interrupting = setOf(
            UsbOutputFailureOrigin.USER_PLAYBACK,
            UsbOutputFailureOrigin.TRACK_TRANSITION,
            UsbOutputFailureOrigin.USER_MODE_CHANGE,
        )
        (UsbOutputFailureOrigin.entries + null).forEach { origin ->
            val status = UsbOutputStatus(
                mode = UsbOutputMode.STRICT_BIT_PERFECT,
                phase = UsbOutputPhase.FAILED,
                failure = UsbOutputFailure.NO_USB_AUDIO_DEVICE,
                failureOrigin = origin,
            )
            assertEquals(origin.toString(), origin in interrupting, status.failureInterruptsUser())
        }
        assertFalse(UsbOutputStatus(failureOrigin = UsbOutputFailureOrigin.USER_PLAYBACK).failureInterruptsUser())
    }

    @Test fun `a saved strict mode turns off even where it can never turn on`() {
        assertTrue(strictUsbSwitchEnabled(canSetMode = true, platformUnavailable = true, strictOn = true))
        assertFalse(strictUsbSwitchEnabled(canSetMode = true, platformUnavailable = true, strictOn = false))
        assertTrue(strictUsbSwitchEnabled(canSetMode = true, platformUnavailable = false, strictOn = false))
        assertFalse(strictUsbSwitchEnabled(canSetMode = false, platformUnavailable = false, strictOn = true))
    }
}
