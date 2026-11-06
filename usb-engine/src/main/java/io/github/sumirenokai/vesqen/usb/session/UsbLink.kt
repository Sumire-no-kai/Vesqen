package io.github.sumirenokai.vesqen.usb.session

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import io.github.sumirenokai.vesqen.usb.control.UsbControlTransfer

/** The opened device as a session uses it: [AndroidUsbLink] on a phone, a fake in tests. */
interface UsbLink {
    /** Lent to the native stream, so [close] must wait until the stream is destroyed. */
    val fileDescriptor: Int
    val control: UsbControlTransfer

    /** Claims the interface's alternate setting 0, taking it from the kernel driver. */
    fun claim(interfaceNumber: Int): Boolean

    /** Gives the interface back. Android then asks the kernel to bind its driver again. */
    fun release(interfaceNumber: Int): Boolean

    fun selectAlternate(interfaceNumber: Int, alternateSetting: Int): Boolean

    fun close()
}

class AndroidUsbLink(private val device: UsbDevice, private val connection: UsbDeviceConnection) : UsbLink {
    override val fileDescriptor: Int get() = connection.fileDescriptor

    override val control = UsbControlTransfer { requestType, request, value, index, buffer, length, timeoutMs ->
        connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
    }

    override fun claim(interfaceNumber: Int): Boolean =
        interfaceWith(interfaceNumber, alternate = 0)?.let { connection.claimInterface(it, true) } ?: false

    override fun release(interfaceNumber: Int): Boolean =
        interfaceWith(interfaceNumber, alternate = 0)?.let(connection::releaseInterface) ?: false

    override fun selectAlternate(interfaceNumber: Int, alternateSetting: Int): Boolean =
        interfaceWith(interfaceNumber, alternateSetting)?.let(connection::setInterface) ?: false

    override fun close() = connection.close()

    private fun interfaceWith(number: Int, alternate: Int): UsbInterface? = (0 until device.interfaceCount)
        .map(device::getInterface)
        .firstOrNull { it.id == number && it.alternateSetting == alternate }
}
