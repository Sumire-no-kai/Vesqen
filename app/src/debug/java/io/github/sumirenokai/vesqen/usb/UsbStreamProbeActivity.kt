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
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.sumirenokai.vesqen.usb.control.Uac1ControlClient
import io.github.sumirenokai.vesqen.usb.control.UsbControlTransfer
import io.github.sumirenokai.vesqen.usb.descriptor.UacControlAccess
import io.github.sumirenokai.vesqen.usb.descriptor.UacDescriptorParser
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacParseResult
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamEngine
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlanResult
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlanner
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * M7-2: Vesqen's own USB path without Media3. `inspect` claims the interfaces, reads what only a
 * claimed device answers, and releases them. It sends no audio. `play` also sends a quiet test
 * signal: 480 Hz left and 600 Hz right at -20 dBFS, after setting the device's volume to -30 dB.
 *
 * ```
 * adb shell am start -n io.github.sumirenokai.vesqen.devicetest/io.github.sumirenokai.vesqen.usb.UsbStreamProbeActivity --es mode inspect
 * adb shell am start -n io.github.sumirenokai.vesqen.devicetest/io.github.sumirenokai.vesqen.usb.UsbStreamProbeActivity --es mode play --ei seconds 60 --ei bits 16
 * adb logcat -s VesqenUsbProbe
 * ```
 */
class UsbStreamProbeActivity : Activity() {
    private val usbManager by lazy { getSystemService(UsbManager::class.java) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private lateinit var output: TextView
    private val log = StringBuilder()

    @Volatile
    private var cancelled = false

    private val permissionResult = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device != null && usbManager.hasPermission(device)) {
                runOnControlThread(device)
            } else {
                report("USB permission denied")
            }
        }
    }

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
            usbManager.hasPermission(devices.single()) -> runOnControlThread(devices.single())
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

    private fun runOnControlThread(device: UsbDevice) {
        val play = intent.getStringExtra(EXTRA_MODE) == MODE_PLAY
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 60).coerceIn(1, MAX_SECONDS)
        val bits = intent.getIntExtra(EXTRA_BITS, 16)
        thread(name = "usb-control") {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                report("Could not open the device")
            } else {
                try {
                    probe(device, connection, play, seconds, bits)
                } finally {
                    connection.close()
                }
                reportRouteReturn()
            }
            val directory = File(filesDir, "usb-probe").apply { mkdirs() }
            File(directory, "${device.identity()}-${if (play) MODE_PLAY else MODE_INSPECT}.txt").writeText(log.toString())
            report("Finished")
        }
    }

    private fun probe(device: UsbDevice, connection: UsbDeviceConnection, play: Boolean, seconds: Int, bits: Int) {
        report("Device ${device.identity()}, USB route present: ${usbRoutePresent()}")
        val raw = connection.rawDescriptors ?: return report("No descriptors")
        val parsed = UacDescriptorParser.parse(raw)
        if (parsed !is UacParseResult.Parsed) return report("Descriptors refused: $parsed")
        val function = parsed.topology.functions.firstOrNull { candidate ->
            candidate.streamingInterfaces.any { streaming -> streaming.alternateSettings.any { it.isPlayback } }
        } ?: return report("No playback function")

        val speed = UsbStreamEngine.busSpeed(connection.fileDescriptor)
        report("Bus speed: $speed")
        val plan = when (val result = StreamingPlanner.plan(function, speed, SAMPLE_RATE_HZ, bits)) {
            is StreamingPlanResult.Planned -> result.plan
            is StreamingPlanResult.Refused -> return report("No plan: ${result.reason}")
        }
        report("Plan: $plan")

        val claimed = mutableListOf<UsbInterface>()
        try {
            listOf(function.controlInterface, plan.interfaceNumber).forEach { number ->
                val usbInterface = device.interfaceWith(number, alternate = 0)
                    ?: return report("Interface $number is missing from the Android device")
                val started = SystemClock.elapsedRealtime()
                if (!connection.claimInterface(usbInterface, true)) return report("Could not claim interface $number")
                claimed += usbInterface
                report("Claimed interface $number in ${SystemClock.elapsedRealtime() - started} ms")
            }
            SystemClock.sleep(ROUTE_SETTLE_MS)
            report("USB route present after claiming: ${usbRoutePresent()}")
            exercise(device, connection, function, plan, play, seconds)
        } finally {
            claimed.asReversed().forEach { usbInterface ->
                report("Released interface ${usbInterface.id}: ${connection.releaseInterface(usbInterface)}")
            }
        }
    }

    private fun exercise(
        device: UsbDevice,
        connection: UsbDeviceConnection,
        function: UacFunction,
        plan: StreamingPlan,
        play: Boolean,
        seconds: Int,
    ) {
        val control = Uac1ControlClient(
            UsbControlTransfer { requestType, request, value, index, buffer, length, timeoutMs ->
                connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
            },
        )
        val link = function.streamingInterfaces
            .first { it.interfaceNumber == plan.interfaceNumber }.alternateSettings
            .first { it.alternateSetting == plan.alternateSetting }.terminalLink
        val unit = link?.let { terminal ->
            function.featureUnitsAfter(terminal).firstOrNull { it.masterVolume != UacControlAccess.ABSENT }
        }
        val controlInterface = function.controlInterface
        val range = unit?.let { control.volumeRange(it.id, controlInterface) }
        val volumeBefore = unit?.let { control.volume(it.id, controlInterface) }
        val muteBefore = unit?.let { control.mute(it.id, controlInterface) }
        report("Feature unit ${unit?.id}: range $range, volume $volumeBefore (1/256 dB), mute $muteBefore")

        val streaming = device.interfaceWith(plan.interfaceNumber, plan.alternateSetting)
            ?: return report("Alternate setting ${plan.alternateSetting} is missing from the Android device")
        val closed = device.interfaceWith(plan.interfaceNumber, alternate = 0)
            ?: return report("Alternate setting 0 is missing from the Android device")
        // UAC1 addresses the rate to the endpoint, which only exists once its setting is active.
        report("Selected alternate setting ${plan.alternateSetting}: ${connection.setInterface(streaming)}")
        try {
            val rate = configureRate(control, plan)
            if (play) {
                if (rate != plan.sampleRateHz && plan.endpointSetsSampleRate) return report("Not playing: the rate reads back as $rate")
                if (unit == null || range == null || volumeBefore == null) return report("Not playing: the volume is unknown")
                val start = range.settingAtOrBelow(START_VOLUME)
                val set = control.setVolume(unit.id, controlInterface, start)
                val readBack = control.volume(unit.id, controlInterface)
                report("Start volume $start (1/256 dB): written $set, reads back $readBack")
                // The device may round to a setting of its own, so one step above is still the request.
                if (!set || readBack == null || readBack > start + range.resolution) return report("Not playing: the volume was not set")
                try {
                    if (muteBefore == true) report("Unmuted: ${control.setMute(unit.id, controlInterface, false)}")
                    stream(connection, plan, seconds)
                } finally {
                    if (muteBefore == true) report("Muted again: ${control.setMute(unit.id, controlInterface, true)}")
                    report("Volume restored to $volumeBefore: ${control.setVolume(unit.id, controlInterface, volumeBefore)}")
                }
            }
        } finally {
            report("Closed the stream with alternate setting 0: ${connection.setInterface(closed)}")
        }
    }

    private fun configureRate(control: Uac1ControlClient, plan: StreamingPlan): Int? {
        if (!plan.endpointSetsSampleRate) {
            report("The endpoint has no sampling frequency control")
            return null
        }
        val set = control.setSampleRate(plan.endpointAddress, plan.sampleRateHz)
        val readBack = control.sampleRate(plan.endpointAddress)
        report("Sample rate ${plan.sampleRateHz}: written $set, reads back $readBack")
        return readBack
    }

    private fun stream(connection: UsbDeviceConnection, plan: StreamingPlan, seconds: Int) {
        val engine = UsbStreamEngine.create(connection.fileDescriptor, plan) ?: return report("The native engine is not available")
        val frames = testSignal(plan)
        if (!engine.start()) {
            report("The stream did not start")
            engine.destroy(STOP_TIMEOUT_MS)
            return
        }
        val total = seconds * plan.sampleRateHz.toLong() / TEST_PERIOD_FRAMES * TEST_PERIOD_FRAMES
        var writtenFrames = 0L
        var offset = 0
        var nextReport = SystemClock.elapsedRealtime() + REPORT_INTERVAL_MS
        var startedAt = 0L
        while (!cancelled && writtenFrames < total) {
            val accepted = engine.write(frames, offset, frames.capacity() - offset)
            if (accepted < 0) break
            offset = (offset + accepted) % frames.capacity()
            writtenFrames += accepted / plan.frameBytes
            if (startedAt == 0L && writtenFrames >= plan.ringFrames / 2) {
                engine.setRunning(true)
                startedAt = SystemClock.elapsedRealtime()
                report("Media started")
            }
            if (SystemClock.elapsedRealtime() >= nextReport) {
                engine.readStats()?.let { report(it.summary()) }
                nextReport += REPORT_INTERVAL_MS
            }
            if (accepted == 0) SystemClock.sleep(WRITE_RETRY_MS)
        }
        if (startedAt == 0L) engine.setRunning(true)
        engine.markEndOfStream()
        val deadline = SystemClock.elapsedRealtime() + END_OF_STREAM_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val stats = engine.readStats() ?: break
            if (cancelled || stats.failure != UsbStreamStats.Failure.NONE || stats.mediaFramesProcessed >= stats.mediaFramesWritten) break
            SystemClock.sleep(WRITE_RETRY_MS)
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        report("Final: ${engine.readStats()?.summary()}, after $elapsed ms")
        var stopped = engine.destroy(STOP_TIMEOUT_MS)
        report("Stream stopped and reclaimed: $stopped")
        // The URBs are still the kernel's, so the connection cannot close over them.
        while (!stopped) stopped = engine.destroy(STOP_TIMEOUT_MS)
    }

    /** Whole periods of both tones, so the signal starts and ends on a zero and can loop. */
    private fun testSignal(plan: StreamingPlan): ByteBuffer {
        val fullScale = 1 shl (plan.bitResolution - 1)
        val amplitude = fullScale * TEST_LEVEL
        val buffer = ByteBuffer.allocateDirect(TEST_PERIOD_FRAMES * plan.frameBytes)
        val bytes = ByteArray(plan.subslotBytes)
        for (frame in 0 until TEST_PERIOD_FRAMES) {
            listOf(LEFT_PERIOD_FRAMES, RIGHT_PERIOD_FRAMES).forEach { period ->
                val sample = (amplitude * sin(2 * PI * frame / period)).roundToInt()
                // UAC places a sample at the top of its subslot, little endian.
                val aligned = sample shl ((plan.subslotBytes * 8) - plan.bitResolution)
                for (index in bytes.indices) bytes[index] = (aligned shr (index * 8)).toByte()
                buffer.put(bytes)
            }
        }
        buffer.clear()
        return buffer
    }

    private fun UsbStreamStats.summary() =
        "written $mediaFramesWritten, processed $mediaFramesProcessed, delivered $mediaFramesDelivered, lost $mediaFramesLost, " +
            "silence $silenceFramesInserted, usb $usbFramesDelivered, underruns $underruns, urbs $urbsCompleted, " +
            "failed packets $packetsFailed, failure $failure ($failureErrno), nice $threadNice, policy $threadSchedulerPolicy"

    private fun reportRouteReturn() {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < ROUTE_RETURN_TIMEOUT_MS) {
            if (usbRoutePresent()) return report("USB route back after ${SystemClock.elapsedRealtime() - started} ms")
            SystemClock.sleep(WRITE_RETRY_MS)
        }
        report("USB route not back after $ROUTE_RETURN_TIMEOUT_MS ms")
    }

    private fun usbRoutePresent() = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        log.appendLine(message)
        runOnUiThread { output.append(message + "\n") }
    }

    private fun UsbDevice.identity() = "%04x-%04x".format(vendorId, productId)

    private fun UsbDevice.isAudio() = deviceClass == UsbConstants.USB_CLASS_AUDIO ||
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    private fun UsbDevice.interfaceWith(number: Int, alternate: Int): UsbInterface? =
        (0 until interfaceCount).map(::getInterface).firstOrNull { it.id == number && it.alternateSetting == alternate }

    private companion object {
        const val TAG = "VesqenUsbProbe"
        const val ACTION_PERMISSION = "io.github.sumirenokai.vesqen.usb.PROBE_PERMISSION"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_BITS = "bits"
        const val MODE_INSPECT = "inspect"
        const val MODE_PLAY = "play"
        const val MAX_SECONDS = 3600
        const val SAMPLE_RATE_HZ = 48_000

        /** -30 dB in the spec's 1/256 dB (D4). */
        const val START_VOLUME = -30 * 256
        const val TEST_LEVEL = 0.1
        const val LEFT_PERIOD_FRAMES = 100
        const val RIGHT_PERIOD_FRAMES = 80
        const val TEST_PERIOD_FRAMES = 400
        const val ROUTE_SETTLE_MS = 1000L
        const val ROUTE_RETURN_TIMEOUT_MS = 10_000L
        const val REPORT_INTERVAL_MS = 5000L
        const val WRITE_RETRY_MS = 10L
        const val END_OF_STREAM_TIMEOUT_MS = 3000L
        const val STOP_TIMEOUT_MS = 1000
    }
}
