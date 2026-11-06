package io.github.sumirenokai.vesqen.usb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.sumirenokai.vesqen.usb.descriptor.UacDescriptorParser
import io.github.sumirenokai.vesqen.usb.descriptor.UacParseResult
import io.github.sumirenokai.vesqen.usb.descriptor.describe
import java.io.File

/**
 * M7-1: saves each attached USB audio device's raw descriptors and what they parse to. Opening the
 * device is enough for this; it claims no interface, so the system keeps using the device.
 *
 * ```
 * adb shell am start -n io.github.sumirenokai.vesqen.devicetest/io.github.sumirenokai.vesqen.usb.UsbDescriptorDumpActivity
 * adb shell run-as io.github.sumirenokai.vesqen.devicetest ls files/usb-descriptors
 * ```
 */
class UsbDescriptorDumpActivity : Activity() {
    private val usbManager by lazy { getSystemService(UsbManager::class.java) }
    private lateinit var output: TextView

    private val permissionResult = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dumpNext(askedFor = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java))
        }
    }

    private val done = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(output) })
        ContextCompat.registerReceiver(
            this,
            permissionResult,
            IntentFilter(ACTION_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        dumpNext()
    }

    override fun onDestroy() {
        unregisterReceiver(permissionResult)
        super.onDestroy()
    }

    private fun dumpNext(askedFor: UsbDevice? = null) {
        if (askedFor != null && !usbManager.hasPermission(askedFor)) {
            report("USB permission denied for ${identity(askedFor)}")
            done += askedFor.deviceId
        }
        val device = usbManager.deviceList.values.firstOrNull { it.isAudio() && it.deviceId !in done }
        when {
            device != null && usbManager.hasPermission(device) -> {
                done += device.deviceId
                dump(device)
                dumpNext()
            }
            device != null -> usbManager.requestPermission(
                device,
                // Mutable: the system adds the device and the answer to this intent.
                PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent(ACTION_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            done.isEmpty() -> report("No USB audio device attached")
            else -> report("Finished")
        }
    }

    private fun dump(device: UsbDevice) {
        val name = identity(device)
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            report("Could not open $name")
            return
        }
        val raw = try {
            connection.rawDescriptors
        } finally {
            connection.close()
        }
        if (raw == null) {
            report("$name returned no descriptors")
            return
        }
        val text = when (val result = UacDescriptorParser.parse(raw)) {
            is UacParseResult.Parsed -> result.topology.describe()
            is UacParseResult.Malformed -> "Malformed at byte ${result.offset}: ${result.reason}"
        }
        val directory = File(filesDir, "usb-descriptors").apply { mkdirs() }
        File(directory, "$name.bin").writeBytes(raw)
        File(directory, "$name.txt").writeText(text)
        report("$name: ${raw.size} bytes\n$text")
    }

    private fun identity(device: UsbDevice) = "%04x-%04x".format(device.vendorId, device.productId)

    private fun report(message: String) {
        Log.i(TAG, message)
        output.append(message + "\n")
    }

    private fun UsbDevice.isAudio() = deviceClass == UsbConstants.USB_CLASS_AUDIO ||
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    private companion object {
        const val TAG = "VesqenUsbDescriptors"
        const val ACTION_PERMISSION = "io.github.sumirenokai.vesqen.usb.DESCRIPTOR_PERMISSION"
    }
}
