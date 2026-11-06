package io.github.sumirenokai.vesqen.usb.session

import io.github.sumirenokai.vesqen.usb.control.Uac1ControlClient
import io.github.sumirenokai.vesqen.usb.descriptor.UacControlAccess
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.engine.UsbStream
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamEngine
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure.Reason
import java.nio.ByteBuffer
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The native stream behind a session; the app uses [UsbStreamEngine], tests a fake. */
fun interface UsbStreamFactory {
    sealed interface Creation {
        data class Created(val stream: UsbStream) : Creation

        data class Unavailable(val reason: String) : Creation
    }

    fun create(fileDescriptor: Int, plan: StreamingPlan): Creation

    companion object {
        val NATIVE = UsbStreamFactory { fileDescriptor, plan ->
            when (val creation = UsbStreamEngine.create(fileDescriptor, plan)) {
                is UsbStreamEngine.Creation.Created -> Creation.Created(creation.engine)
                is UsbStreamEngine.Creation.LibraryUnavailable -> Creation.Unavailable("the native library did not load: ${creation.reason}")
                UsbStreamEngine.Creation.PlanRefused -> Creation.Unavailable("the native stream refused the plan")
            }
        }
    }
}

/**
 * M7-3: holds one USB audio device from takeover to release, across every output written to it
 * (architecture "接管与释放" and "输出退休与销毁"). All USB control work, the stream's creation and
 * destruction, and the checks below run in order on [control], one thread, and nothing past the
 * safety barrier happens while URBs may still be the kernel's. A session is used once: after it is
 * released, a new takeover needs a new session.
 */
class DirectUsbSession(
    private val device: UsbDeviceIdentity,
    private val link: UsbLink,
    private val function: UacFunction,
    private val markers: TakeoverMarkerStore,
    private val control: ScheduledExecutorService,
    private val streams: UsbStreamFactory = UsbStreamFactory.NATIVE,
    private val timing: Timing = Timing(),
    private val listener: Listener = object : Listener {},
    private val nanoTime: () -> Long = System::nanoTime,
) {
    data class Timing(
        /** How often a running stream is checked for errors and for retirements that never finish. */
        val monitorPeriodMs: Long = 250,
        /** An output's retirement must be acknowledged within this, or the session fails closed. */
        val retireTimeoutMs: Long = 1_000,
        val destroyTimeoutMs: Int = 1_000,
        /** How long to wait before trying again to reclaim URBs that are still the kernel's. */
        val destroyRetryMs: Long = 1_000,
    )

    interface Listener {
        fun onFailed(failure: DirectUsbFailure) = Unit

        /** Every interface is back, or the record says which step failed. [failure] is null after a plain release. */
        fun onReleased(cleanup: CleanupRecord, failure: DirectUsbFailure?) = Unit
    }

    sealed interface State {
        data object Idle : State

        data object TakingOver : State

        data class Streaming(val plan: StreamingPlan) : State

        /** Giving the device back. While URBs are still the kernel's this lasts, and nothing else may take over. */
        data class Releasing(val failure: DirectUsbFailure?) : State

        data class Released(val cleanup: CleanupRecord, val failure: DirectUsbFailure?) : State
    }

    sealed interface Takeover {
        data class Ready(val volumeBefore: Int, val volumeNow: Int) : Takeover

        data class Failed(val failure: DirectUsbFailure) : Takeover
    }

    @Volatile
    var state: State = State.Idle
        private set

    @Volatile
    private var stream: UsbStream? = null

    private val client = Uac1ControlClient(link.control)
    private val takeOverAsked = AtomicBoolean()
    private val claimed = mutableListOf<Int>()
    private var streamingInterface: Int? = null
    private var restoreVolume: (() -> Boolean)? = null
    private var monitor: ScheduledFuture<*>? = null
    private val cleanupSteps = mutableListOf<CleanupStep>()

    private val epochLock = Any()
    private var newestEpoch = UsbStreamEngine.FIRST_EPOCH
    private var newestEpochTaken = false
    private var retiredAtNanos: Long? = null
    private var finishedTotals = UsbStreamTotals.NONE

    /** Over every output so far, the current one included. */
    fun totals(): UsbStreamTotals = synchronized(epochLock) { finishedTotals } + (stream?.readStats()?.totals() ?: UsbStreamTotals.NONE)

    fun takeOver(plan: StreamingPlan, startVolume: Int, onDone: (Takeover) -> Unit) {
        check(takeOverAsked.compareAndSet(false, true)) { "A session takes over once" }
        control.execute { takeOverNow(plan, startVolume, onDone) }
    }

    private fun takeOverNow(plan: StreamingPlan, startVolume: Int, onDone: (Takeover) -> Unit) {
        if (state != State.Idle) return onDone(failed(Reason.USB_ENGINE_UNAVAILABLE, "the session was released before its takeover"))
        state = State.TakingOver
        when (val result = claimAndStart(plan, startVolume)) {
            is Takeover.Ready -> {
                state = State.Streaming(plan)
                monitor = control.scheduleWithFixedDelay(::check, timing.monitorPeriodMs, timing.monitorPeriodMs, TimeUnit.MILLISECONDS)
                onDone(result)
            }
            is Takeover.Failed -> {
                state = State.Releasing(result.failure)
                cleanUp()
                onDone(result)
            }
        }
    }

    /**
     * A new output for Media3. The first takes the stream's first epoch; any later one first ends the
     * output before it. Null unless the session is streaming. Never waits.
     */
    fun openOutput(): UsbOutputHandle? {
        val current = stream ?: return null
        if (state !is State.Streaming) return null
        return synchronized(epochLock) {
            if (newestEpochTaken) retireNewest(current)
            newestEpochTaken = true
            Output(newestEpoch)
        }
    }

    /** Gives the device back. The callback comes once every interface is released, which can wait for the kernel. */
    fun release() = control.execute {
        if (state is State.Streaming || state == State.Idle) {
            state = State.Releasing(null)
            cleanUp()
        }
    }

    /** The system said the device is gone. */
    fun onDeviceDetached() = control.execute { fail(DirectUsbFailure(Reason.DEVICE_DISCONNECTED, "the device was detached")) }

    private fun claimAndStart(plan: StreamingPlan, startVolume: Int): Takeover {
        markers.write(TakeoverMarker(device, volumeBefore = null, mutedBefore = null))
        val interfaces = (listOf(function.controlInterface) + function.streamingInterfaces.map { it.interfaceNumber }).distinct()
        for (number in interfaces) {
            if (!link.claim(number)) return failed(Reason.INTERFACE_CLAIM_FAILED, "interface $number")
            claimed += number
        }

        val terminalLink = function.streamingInterfaces.firstOrNull { it.interfaceNumber == plan.interfaceNumber }
            ?.alternateSettings?.firstOrNull { it.alternateSetting == plan.alternateSetting }?.terminalLink
        val unit = terminalLink?.let { terminal -> function.featureUnitsAfter(terminal).firstOrNull { it.masterVolume != UacControlAccess.ABSENT } }
            ?: return failed(Reason.VOLUME_CONTROL_FAILED, "no master volume on the path")
        val controlInterface = function.controlInterface
        val range = client.volumeRange(unit.id, controlInterface)
        val volumeBefore = client.volume(unit.id, controlInterface)
        if (range == null || volumeBefore == null) return failed(Reason.VOLUME_CONTROL_FAILED, "the volume could not be read")
        val mutedBefore = client.mute(unit.id, controlInterface)
        markers.write(TakeoverMarker(device, volumeBefore, mutedBefore))
        restoreVolume = {
            val mutedAgain = mutedBefore != true || client.setMute(unit.id, controlInterface, true)
            client.setVolume(unit.id, controlInterface, volumeBefore) && mutedAgain
        }
        val target = range.settingAtOrBelow(startVolume)
        val set = client.setVolume(unit.id, controlInterface, target)
        val readBack = client.volume(unit.id, controlInterface)
        // The device may round to a setting of its own, so one step above is still the request.
        if (!set || readBack == null || readBack > target + range.resolution) {
            return failed(Reason.VOLUME_CONTROL_FAILED, "set $target, reads back $readBack")
        }
        if (mutedBefore == true && !client.setMute(unit.id, controlInterface, false)) {
            return failed(Reason.VOLUME_CONTROL_FAILED, "the device stayed muted")
        }

        if (!link.selectAlternate(plan.interfaceNumber, plan.alternateSetting)) {
            return failed(Reason.INTERFACE_CLAIM_FAILED, "alternate setting ${plan.alternateSetting} of interface ${plan.interfaceNumber}")
        }
        streamingInterface = plan.interfaceNumber
        // UAC1 addresses the rate to the endpoint, which only exists once its setting is active.
        if (plan.endpointSetsSampleRate) {
            if (!client.setSampleRate(plan.endpointAddress, plan.sampleRateHz)) {
                return failed(Reason.SAMPLE_RATE_SET_FAILED, "${plan.sampleRateHz} Hz")
            }
            val rate = client.sampleRate(plan.endpointAddress)
            if (rate != plan.sampleRateHz) return failed(Reason.SAMPLE_RATE_READBACK_MISMATCH, "set ${plan.sampleRateHz}, reads back $rate")
        }

        val created = when (val creation = streams.create(link.fileDescriptor, plan)) {
            is UsbStreamFactory.Creation.Created -> creation.stream
            is UsbStreamFactory.Creation.Unavailable -> return failed(Reason.USB_ENGINE_UNAVAILABLE, creation.reason)
        }
        stream = created
        if (!created.start()) return failed(Reason.USB_ENGINE_UNAVAILABLE, "the stream thread did not start")
        return Takeover.Ready(volumeBefore, readBack)
    }

    private fun failed(reason: Reason, detail: String) = Takeover.Failed(DirectUsbFailure(reason, detail))

    /** On [control]: errors the stream reported, and retirements that never finish. */
    private fun check() {
        if (state !is State.Streaming) return
        val stats = stream?.readStats() ?: return
        stats.asFailure()?.let { return fail(it) }
        val waitingSince = synchronized(epochLock) { retiredAtNanos } ?: return
        if (stats.epoch == stats.requestedEpoch) {
            synchronized(epochLock) { if (newestEpoch == stats.epoch) retiredAtNanos = null }
        } else if (nanoTime() - waitingSince > TimeUnit.MILLISECONDS.toNanos(timing.retireTimeoutMs)) {
            fail(DirectUsbFailure(Reason.USB_TRANSFER_FAILED, "the last output's URBs were not back within ${timing.retireTimeoutMs} ms"))
        }
    }

    private fun fail(failure: DirectUsbFailure) {
        if (state !is State.Streaming) return
        state = State.Releasing(failure)
        listener.onFailed(failure)
        cleanUp()
    }

    /**
     * On [control], in the architecture's order. The native stream's destruction is the safety barrier:
     * until every URB is back, nothing is closed or released, and the attempt is repeated later.
     */
    private fun cleanUp() {
        monitor?.cancel(false)
        monitor = null
        val current = stream
        if (current != null) {
            if (!current.destroy(timing.destroyTimeoutMs)) {
                try {
                    control.schedule(::cleanUp, timing.destroyRetryMs, TimeUnit.MILLISECONDS)
                } catch (stopped: RejectedExecutionException) {
                    // The owner shut the control thread down. The session stays releasing and keeps the
                    // device, its marker included, so the next start recovers it (architecture, "一直无法确认").
                    listener.onFailed(DirectUsbFailure(Reason.USB_TRANSFER_FAILED, "URBs still in the kernel when control stopped: $stopped"))
                }
                return
            }
            stream = null
            cleanupSteps += CleanupStep("stream reclaimed", true)
        }
        streamingInterface?.let { number ->
            cleanupSteps += CleanupStep("alternate setting 0 on interface $number", link.selectAlternate(number, 0))
            streamingInterface = null
        }
        restoreVolume?.let { restore ->
            cleanupSteps += CleanupStep("volume restored", runCatching(restore).getOrDefault(false))
            restoreVolume = null
        }
        var allReleased = true
        claimed.asReversed().forEach { number ->
            val released = link.release(number)
            allReleased = allReleased && released
            cleanupSteps += CleanupStep("interface $number released", released)
        }
        claimed.clear()
        link.close()
        // Only a device with every interface back may lose its marker; otherwise the next start recovers it.
        if (allReleased) markers.clear()
        cleanupSteps += CleanupStep("takeover marker cleared", allReleased)
        val record = CleanupRecord(cleanupSteps.toList())
        val failure = (state as? State.Releasing)?.failure
        state = State.Released(record, failure)
        listener.onReleased(record, failure)
    }

    private fun retireNewest(current: UsbStream) {
        current.readStats()?.takeIf { it.epoch == newestEpoch }?.let { finishedTotals += it.totals() }
        newestEpoch = current.retireOutput()
        newestEpochTaken = false
        retiredAtNanos = nanoTime()
    }

    private fun retire(output: Output) {
        val current = stream ?: return
        synchronized(epochLock) {
            if (output.epoch == newestEpoch && newestEpochTaken) retireNewest(current)
        }
    }

    private inner class Output(override val epoch: Long) : UsbOutputHandle {
        @Volatile
        private var released = false

        override fun write(buffer: ByteBuffer, offset: Int, size: Int): Int {
            if (released || state !is State.Streaming) return -1
            return stream?.write(epoch, buffer, offset, size) ?: -1
        }

        override fun setRunning(running: Boolean) {
            if (!released) stream?.setRunning(epoch, running)
        }

        override fun markEndOfStream() {
            if (!released) stream?.markEndOfStream(epoch)
        }

        override fun readStats(): UsbStreamStats? = stream?.readStats()?.takeIf { it.epoch == epoch }

        override val failure: DirectUsbFailure?
            get() = when (val current = state) {
                is State.Releasing -> current.failure
                is State.Released -> current.failure
                else -> null
            }

        /** A second call finds the epoch already ended, so it does nothing. */
        override fun release() {
            released = true
            retire(this)
        }
    }
}

private fun UsbStreamStats.totals() = UsbStreamTotals(mediaFramesLost, underruns, packetsFailed, mediaFramesDelivered)
