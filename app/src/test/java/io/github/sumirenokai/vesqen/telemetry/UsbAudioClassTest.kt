package io.github.sumirenokai.vesqen.telemetry

import android.hardware.usb.UsbConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioClassTest {
    @Test
    fun `USB audio classification accepts device or interface class only`() {
        assertTrue(isUsbAudioClass(UsbConstants.USB_CLASS_AUDIO, emptyList()))
        assertTrue(
            isUsbAudioClass(
                UsbConstants.USB_CLASS_PER_INTERFACE,
                listOf(UsbConstants.USB_CLASS_HID, UsbConstants.USB_CLASS_AUDIO),
            ),
        )
        assertFalse(
            isUsbAudioClass(
                UsbConstants.USB_CLASS_PER_INTERFACE,
                listOf(UsbConstants.USB_CLASS_HID, UsbConstants.USB_CLASS_MASS_STORAGE),
            ),
        )
    }
}
