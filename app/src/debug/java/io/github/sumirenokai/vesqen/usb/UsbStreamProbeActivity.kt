package io.github.sumirenokai.vesqen.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import io.github.sumirenokai.vesqen.usb.control.Uac1ControlClient
import io.github.sumirenokai.vesqen.usb.control.UsbControlTransfer
import io.github.sumirenokai.vesqen.usb.descriptor.UacControlAccess
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamEngine
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * M7-2: Vesqen's own USB path without Media3. `inspect` claims the interfaces, reads what only a
 * claimed device answers, and releases them. It sends no audio. `play` also sends a quiet test
 * signal: 480 Hz left and 600 Hz right at -20 dBFS, after setting the device's volume to -30 dB.
 * `system` sends nothing itself: it plays a very quiet tone through `AudioTrack` before, during and
 * after the claim, to show where system audio goes around a takeover.
 *
 * ```
 * adb shell am start -n io.github.sumirenokai.vesqen.devicetest/io.github.sumirenokai.vesqen.usb.UsbStreamProbeActivity --es mode inspect
 * adb shell am start -n io.github.sumirenokai.vesqen.devicetest/io.github.sumirenokai.vesqen.usb.UsbStreamProbeActivity --es mode play --ei seconds 60 --ei bits 16
 * adb shell am start -n io.github.sumirenokai.vesqen.devicetest/io.github.sumirenokai.vesqen.usb.UsbStreamProbeActivity --es mode system
 * ```
 *
 * `--ei volume` gives the device's volume in 1/256 dB, -2560 being -10 dB. With `inspect` it is only
 * set and read back, and stays that way. With `play` it replaces the -30 dB of the test signal.
 *
 * ```
 * adb shell am start -n io.github.sumirenokai.vesqen.devicetest/io.github.sumirenokai.vesqen.usb.UsbStreamProbeActivity --es mode inspect --ei volume -2560
 * adb logcat -s VesqenUsbProbe
 * ```
 */
class UsbStreamProbeActivity : UsbProbeBaseActivity() {
    override val logTag = "VesqenUsbProbe"

    override fun onDevice(device: UsbDevice) {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_INSPECT
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 60).coerceIn(1, MAX_SECONDS)
        val bits = intent.getIntExtra(EXTRA_BITS, 16)
        thread(name = "usb-control") {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                report("Could not open the device")
            } else {
                // Stays open across the takeover, as another app's track would.
                val background = if (mode == MODE_SYSTEM) SystemTone("Track opened before the claim", AudioAttributes.USAGE_MEDIA) else null
                try {
                    probe(device, connection, mode, seconds, bits, background)
                } finally {
                    connection.close()
                }
                reportRouteReturn()
                background?.run {
                    measure("after the release")
                    close()
                    SystemTone("Track opened after the release", AudioAttributes.USAGE_MEDIA).run {
                        measure("after the release")
                        close()
                    }
                }
            }
            finishReport(device, mode)
        }
    }

    private fun probe(
        device: UsbDevice,
        connection: UsbDeviceConnection,
        mode: String,
        seconds: Int,
        bits: Int,
        background: SystemTone?,
    ) {
        report("Device ${device.identity()}, USB route present: ${usbRoutePresent()}")
        val (function, plan) = planFor(connection, bits) ?: return
        background?.measure("before the claim")

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
            if (background != null) {
                exerciseSystemAudio(background)
            } else {
                exercise(device, connection, function, plan, mode == MODE_PLAY, seconds)
            }
        } finally {
            claimed.asReversed().forEach { usbInterface ->
                report("Released interface ${usbInterface.id}: ${connection.releaseInterface(usbInterface)}")
            }
        }
    }

    private fun exerciseSystemAudio(background: SystemTone) {
        background.measure("during the claim")
        listOf(
            "Media track opened during the claim" to AudioAttributes.USAGE_MEDIA,
            "Notification track opened during the claim" to AudioAttributes.USAGE_NOTIFICATION,
        ).forEach { (name, usage) ->
            SystemTone(name, usage).run {
                measure("during the claim")
                close()
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
        val requestedVolume = intent.getIntExtra(EXTRA_VOLUME, NO_VOLUME)
        if (!play && requestedVolume != NO_VOLUME && unit != null && range != null) {
            val target = range.settingAtOrBelow(requestedVolume)
            val set = control.setVolume(unit.id, controlInterface, target)
            report("Volume set to $target: written $set, reads back ${control.volume(unit.id, controlInterface)}")
        }

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
                val start = range.settingAtOrBelow(if (requestedVolume == NO_VOLUME) START_VOLUME else requestedVolume)
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

    /** Very quiet, because the system may send it to the phone's speaker at the media volume. */
    private inner class SystemTone(private val name: String, usage: Int) {
        private val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        @Volatile
        private var writing = true
        private val writer = thread(name = "system-tone") {
            val samples = ShortArray(LEFT_PERIOD_FRAMES * 2) { index ->
                (Short.MAX_VALUE * SYSTEM_TONE_LEVEL * sin(2 * PI * (index / 2) / LEFT_PERIOD_FRAMES)).roundToInt().toShort()
            }
            track.play()
            var offset = 0
            while (writing) {
                val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) {
                    report("$name: write error $written")
                    break
                }
                offset = (offset + written) % samples.size
                if (written == 0) SystemClock.sleep(WRITE_RETRY_MS)
            }
        }

        fun measure(moment: String) {
            val before = track.playbackHeadPosition
            SystemClock.sleep(SYSTEM_TONE_MS)
            val advanced = track.playbackHeadPosition - before
            val routed = track.routedDevice?.let { "type ${it.type}, ${it.productName}" }
            report("$name, $moment: routed to $routed, head advanced $advanced frames in $SYSTEM_TONE_MS ms, USB route present ${usbRoutePresent()}")
        }

        fun close() {
            writing = false
            writer.join()
            track.stop()
            track.release()
        }
    }

    private fun reportRouteReturn() {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < ROUTE_RETURN_TIMEOUT_MS) {
            if (usbRoutePresent()) return report("USB route back after ${SystemClock.elapsedRealtime() - started} ms")
            SystemClock.sleep(WRITE_RETRY_MS)
        }
        report("USB route not back after $ROUTE_RETURN_TIMEOUT_MS ms")
    }

    private companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_BITS = "bits"
        const val EXTRA_VOLUME = "volume"

        /** Outside the signed 16 bits of a volume setting. */
        const val NO_VOLUME = Int.MIN_VALUE
        const val MODE_INSPECT = "inspect"
        const val MODE_PLAY = "play"
        const val MODE_SYSTEM = "system"
        const val MAX_SECONDS = 3600

        /** -30 dB in the spec's 1/256 dB (D4). */
        const val START_VOLUME = -30 * 256
        const val TEST_LEVEL = 0.1

        /** -46 dBFS. */
        const val SYSTEM_TONE_LEVEL = 0.005
        const val SYSTEM_TONE_MS = 3000L
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
