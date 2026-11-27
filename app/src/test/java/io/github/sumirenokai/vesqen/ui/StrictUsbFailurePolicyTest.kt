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

    @Test fun `only a failed undo of the mixer setting leaves out the generic advice`() {
        UsbOutputFailure.entries.forEach { failure ->
            assertEquals(failure.name, failure != UsbOutputFailure.MIXER_CLEAR_FAILED, failure.dialogAddsGenericAdvice())
        }
    }

    @Test fun `a lapsed confirmation does not suggest another DAC`() {
        fun failed(failure: UsbOutputFailure, code: String) = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.FAILED,
            failure = failure,
            decisionCode = code,
        )
        listOf("direct_usb.introduction_confirmation_failed", "direct_usb.fixed_volume_confirmation_failed").forEach { code ->
            assertFalse(code, failed(UsbOutputFailure.PLATFORM_ERROR, code).dialogAddsGenericAdvice())
        }
        assertTrue(failed(UsbOutputFailure.PLATFORM_ERROR, "direct_usb.usb_transfer_failed").dialogAddsGenericAdvice())
        assertFalse(failed(UsbOutputFailure.MIXER_CLEAR_FAILED, "mixer_clear_failed").dialogAddsGenericAdvice())
    }

    @Test fun `a DSD stop on either strict path suggests no other DAC`() {
        fun failed(code: String) = UsbOutputStatus(
            mode = UsbOutputMode.STRICT_BIT_PERFECT,
            phase = UsbOutputPhase.FAILED,
            failure = UsbOutputFailure.SOURCE_FORMAT_UNSUPPORTED,
            decisionCode = code,
        )
        assertTrue(failed("strict_usb.source_dsd").stoppedForDsd)
        assertTrue(failed("direct_usb.source_dsd").stoppedForDsd)
        assertFalse(failed("direct_usb.source_not_lossless").stoppedForDsd)
        assertFalse(failed("strict_usb.source_format_unsupported").stoppedForDsd)
    }

    @Test fun `a saved strict mode turns off even where it can never turn on`() {
        assertTrue(strictUsbSwitchEnabled(canSetMode = true, platformUnavailable = true, strictOn = true))
        assertFalse(strictUsbSwitchEnabled(canSetMode = true, platformUnavailable = true, strictOn = false))
        assertTrue(strictUsbSwitchEnabled(canSetMode = true, platformUnavailable = false, strictOn = false))
        assertFalse(strictUsbSwitchEnabled(canSetMode = false, platformUnavailable = false, strictOn = true))
    }
}
