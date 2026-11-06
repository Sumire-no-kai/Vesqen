package io.github.sumirenokai.vesqen.usb.chain

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import io.github.sumirenokai.vesqen.usb.control.Uac1ControlClient
import io.github.sumirenokai.vesqen.usb.control.UsbControlTransfer
import io.github.sumirenokai.vesqen.usb.descriptor.UacControlAccess
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamEngine
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal data class UsbStreamTotals(val lostFrames: Long, val underruns: Long, val failedPackets: Long, val deliveredFrames: Long)

/**
 * M7-2: holds the claimed interfaces across outputs. All USB control work and every wait for URBs
 * happens in order on one thread, so a new stream cannot start before the old one has come back.
 */
internal class DirectUsbSession(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val function: UacFunction,
    private val plan: StreamingPlan,
    private val startVolume: Int,
    private val report: (String) -> Unit,
) : UsbOutputStreams {
    private val control = Executors.newSingleThreadExecutor { Thread(it, "usb-control") }
    private val client = Uac1ControlClient(
        UsbControlTransfer { requestType, request, value, index, buffer, length, timeoutMs ->
            connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
        },
    )
    private val claimed = mutableListOf<UsbInterface>()
    private val opened = AtomicInteger()
    private val finished = mutableListOf<UsbStreamStats>()
    private var restoreDevice: (() -> Unit)? = null
    private var alternateSelected = false

    @Volatile
    private var currentStream: EngineStream? = null

    @Volatile
    var isClaimed = false
        private set

    @Volatile
    var lastClosedStats: UsbStreamStats? = null
        private set

    val streamsOpened: Int get() = opened.get()

    /** Over every stream of this session, including the one open now. */
    fun totals(): UsbStreamTotals {
        val all = synchronized(finished) { finished.toList() } + listOfNotNull(currentStream?.readStats())
        return UsbStreamTotals(
            lostFrames = all.sumOf { it.mediaFramesLost },
            underruns = all.sumOf { it.underruns },
            failedPackets = all.sumOf { it.packetsFailed },
            deliveredFrames = all.sumOf { it.mediaFramesDelivered },
        )
    }

    /** Stats of the stream open now, or of the last one closed. */
    fun latestStats(): UsbStreamStats? = currentStream?.readStats() ?: lastClosedStats

    fun takeOver(onDone: (Boolean) -> Unit = {}) = control.execute {
        val taken = runCatching(::claimAndConfigure).getOrElse {
            report("Takeover failed: $it")
            false
        }
        if (!taken) releaseAll()
        isClaimed = taken
        report("Takeover ${if (taken) "complete" else "failed"}")
        onDone(taken)
    }

    /** Returns the device to the system. The output stays and accepts nothing until it is replaced. */
    fun giveBack(onDone: () -> Unit = {}) = control.execute {
        releaseAll()
        report("Device given back")
        onDone()
    }

    fun shutdown() {
        control.execute {
            releaseAll()
            connection.close()
        }
        control.shutdown()
    }

    override fun open(onOpened: (UsbOutputStream?) -> Unit) = control.execute {
        onOpened(if (isClaimed) openEngine() else null)
    }

    override fun close(stream: UsbOutputStream) = control.execute { closeNow(stream as EngineStream) }

    private fun openEngine(): EngineStream? {
        val engine = when (val creation = UsbStreamEngine.create(connection.fileDescriptor, plan)) {
            is UsbStreamEngine.Creation.Created -> creation.engine
            is UsbStreamEngine.Creation.LibraryUnavailable -> return null.also { report("The native engine is not available: ${creation.reason}") }
            UsbStreamEngine.Creation.PlanRefused -> return null.also { report("The native engine refused the plan") }
        }
        if (!engine.start()) {
            report("The stream did not start")
            reclaim(engine)
            return null
        }
        opened.incrementAndGet()
        return EngineStream(engine).also { currentStream = it }
    }

    private fun closeNow(stream: EngineStream) {
        val stats = stream.close(::reclaim)
        synchronized(finished) { stats?.let(finished::add) }
        if (stream === currentStream) {
            lastClosedStats = stats
            currentStream = null
        }
    }

    /** The URBs are the kernel's until they come back, so nothing may be freed before that. */
    private fun reclaim(engine: UsbStreamEngine) {
        while (!engine.destroy(STOP_TIMEOUT_MS)) report("URBs are still in the kernel")
    }

    private fun claimAndConfigure(): Boolean {
        listOf(function.controlInterface, plan.interfaceNumber).forEach { number ->
            val usbInterface = interfaceWith(number, alternate = 0)
            if (usbInterface == null || !connection.claimInterface(usbInterface, true)) {
                report("Could not claim interface $number")
                return false
            }
            claimed += usbInterface
        }
        val link = function.streamingInterfaces
            .first { it.interfaceNumber == plan.interfaceNumber }.alternateSettings
            .first { it.alternateSetting == plan.alternateSetting }.terminalLink
        val unit = link?.let { terminal ->
            function.featureUnitsAfter(terminal).firstOrNull { it.masterVolume != UacControlAccess.ABSENT }
        } ?: return false.also { report("No volume control on the path") }
        val controlInterface = function.controlInterface
        val range = client.volumeRange(unit.id, controlInterface)
        val volumeBefore = client.volume(unit.id, controlInterface)
        if (range == null || volumeBefore == null) return false.also { report("The volume could not be read") }
        val muteBefore = client.mute(unit.id, controlInterface)
        restoreDevice = {
            if (muteBefore == true) client.setMute(unit.id, controlInterface, true)
            client.setVolume(unit.id, controlInterface, volumeBefore)
        }
        val target = range.settingAtOrBelow(startVolume)
        val set = client.setVolume(unit.id, controlInterface, target)
        val readBack = client.volume(unit.id, controlInterface)
        report("Volume was $volumeBefore, set $target, reads back $readBack (1/256 dB)")
        // The device may round to a setting of its own, so one step above is still the request.
        if (!set || readBack == null || readBack > target + range.resolution) return false
        if (muteBefore == true) client.setMute(unit.id, controlInterface, false)

        val streaming = interfaceWith(plan.interfaceNumber, plan.alternateSetting)
        if (streaming == null || !connection.setInterface(streaming)) return false.also { report("Could not select the alternate setting") }
        alternateSelected = true
        // UAC1 addresses the rate to the endpoint, which only exists once its setting is active.
        if (plan.endpointSetsSampleRate) {
            client.setSampleRate(plan.endpointAddress, plan.sampleRateHz)
            val rate = client.sampleRate(plan.endpointAddress)
            report("Sample rate reads back $rate")
            if (rate != plan.sampleRateHz) return false
        }
        return true
    }

    private fun releaseAll() {
        isClaimed = false
        currentStream?.let(::closeNow)
        if (alternateSelected) {
            interfaceWith(plan.interfaceNumber, alternate = 0)?.let(connection::setInterface)
            alternateSelected = false
        }
        restoreDevice?.invoke()
        restoreDevice = null
        claimed.asReversed().forEach(connection::releaseInterface)
        claimed.clear()
    }

    private fun interfaceWith(number: Int, alternate: Int): UsbInterface? =
        (0 until device.interfaceCount).map(device::getInterface).firstOrNull { it.id == number && it.alternateSetting == alternate }

    /** M7-2 opens one engine per output and never retires one, so its only epoch is the first. */
    private class EngineStream(private val engine: UsbStreamEngine) : UsbOutputStream {
        @Volatile
        private var closed = false

        @Volatile
        private var finalStats: UsbStreamStats? = null

        /** What was cancelled here was never meant to arrive, so the stats are taken before that. */
        fun close(reclaim: (UsbStreamEngine) -> Unit): UsbStreamStats? {
            closed = true
            engine.readStats()?.let { finalStats = it }
            reclaim(engine)
            return finalStats
        }

        override fun write(buffer: ByteBuffer, offset: Int, size: Int): Int {
            if (closed) return 0
            val accepted = engine.write(EPOCH, buffer, offset, size)
            // The engine went away between the check and the call.
            return if (accepted < 0 && closed) 0 else accepted
        }

        override fun setRunning(running: Boolean) {
            if (!closed) engine.setRunning(EPOCH, running)
        }

        override fun markEndOfStream() {
            if (!closed) engine.markEndOfStream(EPOCH)
        }

        override fun readStats(): UsbStreamStats? {
            val stats = engine.readStats()
            if (stats != null) finalStats = stats
            return stats ?: finalStats
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 1000
        const val EPOCH = UsbStreamEngine.FIRST_EPOCH
    }
}
