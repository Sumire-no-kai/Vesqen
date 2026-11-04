package io.github.sumirenokai.vesqen.usb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.sumirenokai.vesqen.usb.descriptor.UacDescriptorParser
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacParseResult
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamEngine
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlanResult
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlanner
import java.io.File

/** What the M7-2 probes share: finding the device, asking for permission, planning and reporting. */
abstract class UsbProbeBaseActivity : Activity() {
    protected val usbManager: UsbManager by lazy { getSystemService(UsbManager::class.java) }
    protected val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }
    protected abstract val logTag: String

    private lateinit var output: TextView
    private val log = StringBuilder()

    @Volatile
    protected var cancelled = false
        private set

    private val permissionResult = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device != null && usbManager.hasPermission(device)) {
                onDevice(device)
            } else {
                report("USB permission denied")
            }
        }
    }

    /** Called on the main thread once permission is there. */
    protected abstract fun onDevice(device: UsbDevice)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        output = TextView(this).apply { setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(output) })
        ContextCompat.registerReceiver(
            this,
            permissionResult,
            IntentFilter(ACTION_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val devices = usbManager.deviceList.values.filter { it.isAudio() }
        when {
            devices.isEmpty() -> report("No USB audio device attached")
            devices.size > 1 -> report("More than one USB audio device attached")
            usbManager.hasPermission(devices.single()) -> onDevice(devices.single())
            else -> usbManager.requestPermission(
                devices.single(),
                // Mutable: the system adds the device and the answer to this intent.
                PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent(ACTION_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
    }

    override fun onDestroy() {
        cancelled = true
        unregisterReceiver(permissionResult)
        super.onDestroy()
    }

    /** Reports why when there is no plan. */
    protected fun planFor(connection: UsbDeviceConnection, bits: Int): Pair<UacFunction, StreamingPlan>? {
        val raw = connection.rawDescriptors ?: return null.also { report("No descriptors") }
        val parsed = UacDescriptorParser.parse(raw)
        if (parsed !is UacParseResult.Parsed) return null.also { report("Descriptors refused: $parsed") }
        val function = parsed.topology.functions.firstOrNull { candidate ->
            candidate.streamingInterfaces.any { streaming -> streaming.alternateSettings.any { it.isPlayback } }
        } ?: return null.also { report("No playback function") }

        val speed = UsbStreamEngine.busSpeed(connection.fileDescriptor)
        report("Bus speed: $speed")
        return when (val result = StreamingPlanner.plan(function, speed, SAMPLE_RATE_HZ, bits)) {
            is StreamingPlanResult.Planned -> (function to result.plan).also { report("Plan: ${result.plan}") }
            is StreamingPlanResult.Refused -> null.also { report("No plan: ${result.reason}") }
        }
    }

    protected fun usbRoutePresent() = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any(::isUsbRoute)

    protected fun isUsbRoute(device: AudioDeviceInfo) =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET

    protected fun report(message: String) {
        Log.i(logTag, message)
        synchronized(log) { log.appendLine(message) }
        runOnUiThread { output.append(message + "\n") }
    }

    protected fun finishReport(device: UsbDevice, name: String) {
        val directory = File(filesDir, "usb-probe").apply { mkdirs() }
        File(directory, "${device.identity()}-$name.txt").writeText(synchronized(log) { log.toString() })
        report("Finished")
    }

    protected fun UsbDevice.identity() = "%04x-%04x".format(vendorId, productId)

    protected fun UsbDevice.interfaceWith(number: Int, alternate: Int): UsbInterface? =
        (0 until interfaceCount).map(::getInterface).firstOrNull { it.id == number && it.alternateSetting == alternate }

    private fun UsbDevice.isAudio() = deviceClass == UsbConstants.USB_CLASS_AUDIO ||
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    protected companion object {
        const val SAMPLE_RATE_HZ = 48_000
        private const val ACTION_PERMISSION = "io.github.sumirenokai.vesqen.usb.PROBE_PERMISSION"
    }
}
