package io.github.sumirenokai.vesqen.usb.session

import io.github.sumirenokai.vesqen.usb.control.Uac1ControlClient
import io.github.sumirenokai.vesqen.usb.control.UacVolumeRange
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

        fun onReconfigured(plan: StreamingPlan) = Unit

        /** Every interface is back, or the record says which step failed. [failure] is null after a plain release. */
        fun onReleased(cleanup: CleanupRecord, failure: DirectUsbFailure?) = Unit
    }

    sealed interface State {
        data object Idle : State

        data object TakingOver : State

        data class Streaming(val plan: StreamingPlan) : State

        data object Reconfiguring : State

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
    private var restorePending = false

    /** The path's master volume, known once the takeover has read it. */
    private class VolumeControl(val unitId: Int, val controlInterface: Int, val range: UacVolumeRange)

    private var volumeControl: VolumeControl? = null

    /** The settings the device's hardware volume accepts, or null before the takeover is ready. */
    @Volatile
    var volumeRange: UacVolumeRange? = null
        private set
    private var monitor: ScheduledFuture<*>? = null
    private val cleanupSteps = mutableListOf<CleanupStep>()

    /** Only the session that wrote the marker clears it; one refused before claiming leaves another's alone. */
    private var markerWritten = false
    @Volatile private var detached = false

    private val epochLock = Any()
    private var newestEpoch = UsbStreamEngine.FIRST_EPOCH
    private var newestEpochTaken = false
    private var retiredAtNanos: Long? = null
    @Volatile private var finalTotals = UsbStreamTotals.NONE
    @Volatile private var finalStats: UsbStreamStats? = null
    @Volatile private var finishedStream: UsbStream? = null
    private var pendingOutput: RequestedOutput? = null
    private val releaseCallbacks = mutableListOf<(CleanupRecord) -> Unit>()

    fun totals(): UsbStreamTotals = finalTotals + (stream?.readStats()?.sessionTotals ?: UsbStreamTotals.NONE)

    fun takeOver(plan: StreamingPlan, startVolume: Int, onDone: (Takeover) -> Unit) {
        check(takeOverAsked.compareAndSet(false, true)) { "A session takes over once" }
        control.execute { takeOverNow(plan, startVolume, onDone) }
    }

    private fun takeOverNow(plan: StreamingPlan, startVolume: Int, onDone: (Takeover) -> Unit) {
        if (state != State.Idle) return onDone(failed(Reason.USB_ENGINE_UNAVAILABLE, "the session was released before its takeover"))
        state = State.TakingOver
        val attempt = try {
            claimAndStart(plan, startVolume)
        } catch (_: SecurityException) {
            failed(Reason.USB_PERMISSION_REVOKED, "USB access was revoked")
        } catch (error: IllegalStateException) {
            failed(Reason.USB_TRANSFER_FAILED, "USB connection unavailable: ${error.javaClass.simpleName}")
        }
        val result = if (detached) failed(Reason.DEVICE_DISCONNECTED, "the device was detached") else attempt
        when (result) {
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
    fun openOutput(): UsbOutputHandle? = synchronized(epochLock) {
        val current = stream ?: return@synchronized null
        if (state !is State.Streaming) return@synchronized null
        if (newestEpochTaken) retireNewest(current)
        newestEpochTaken = true
        Output(newestEpoch, current)
    }

    /** A new wire configuration starts only after the sink drained and released the previous output. */
    fun openOutput(plan: StreamingPlan): UsbOutputHandle {
        val request = RequestedOutput(plan)
        control.execute {
            if (request.released) return@execute
            when (val current = state) {
                is State.Streaming -> {
                    if (current.plan == plan) {
                        request.install(openOutput())
                    } else {
                        pendingOutput = request
                        synchronized(epochLock) { state = State.Reconfiguring }
                        monitor?.cancel(false)
                        monitor = null
                        reconfigure()
                    }
                }
                State.Reconfiguring -> {
                    pendingOutput = request
                    if (stream == null) reconfigure()
                }
                else -> request.refusal = DirectUsbFailure(Reason.USB_ENGINE_UNAVAILABLE, "the session is no longer streaming")
            }
        }
        return request
    }

    private fun reconfigure() {
        if (state != State.Reconfiguring) return
        val current = stream
        if (current != null) {
            if (!current.destroy(timing.destroyTimeoutMs)) {
                control.schedule(::reconfigure, timing.destroyRetryMs, TimeUnit.MILLISECONDS)
                return
            }
            finalStats = current.readStats()
            finishedStream = current
            finalTotals += finalStats?.sessionTotals ?: UsbStreamTotals.NONE
            stream = null
        }
        val request = checkNotNull(pendingOutput)
        if (request.released) {
            pendingOutput = null
            return
        }
        val failure = try {
            when {
                detached -> DirectUsbFailure(Reason.DEVICE_DISCONNECTED, "the device was detached")
                streamingInterface?.let { !link.selectAlternate(it, 0) } == true ->
                    DirectUsbFailure(Reason.INTERFACE_CLAIM_FAILED, "the old alternate setting could not be reset")
                else -> startStream(request.plan)
            }
        } catch (_: SecurityException) {
            DirectUsbFailure(Reason.USB_PERMISSION_REVOKED, "USB access was revoked")
        } catch (_: IllegalStateException) {
            DirectUsbFailure(Reason.USB_TRANSFER_FAILED, "USB connection unavailable during reconfiguration")
        }
        if (failure != null) {
            fail(failure)
            return
        }
        synchronized(epochLock) {
            newestEpoch = UsbStreamEngine.FIRST_EPOCH
            newestEpochTaken = false
            retiredAtNanos = null
        }
        state = State.Streaming(request.plan)
        pendingOutput = null
        listener.onReconfigured(request.plan)
        request.install(openOutput())
        monitor = control.scheduleWithFixedDelay(::check, timing.monitorPeriodMs, timing.monitorPeriodMs, TimeUnit.MILLISECONDS)
    }

    private inner class RequestedOutput(val plan: StreamingPlan) : UsbOutputHandle {
        @Volatile var released = false
        @Volatile var refusal: DirectUsbFailure? = null
        @Volatile private var output: UsbOutputHandle? = null
        private var running = false
        private var ended = false
        @Synchronized fun install(ready: UsbOutputHandle?) {
            if (released) {
                ready?.release()
            } else {
                output = ready
                if (ended) ready?.markEndOfStream()
                ready?.setRunning(running)
            }
        }
        override val epoch: Long get() = output?.epoch ?: 0
        override val failure: DirectUsbFailure? get() = refusal ?: output?.failure ?: when (val current = state) {
            is State.Releasing -> current.failure
            is State.Released -> current.failure
            else -> null
        }
        override fun write(buffer: ByteBuffer, offset: Int, size: Int): Int =
            if (released || failure != null) -1 else output?.write(buffer, offset, size) ?: 0
        override fun readStats(): UsbStreamStats? = output?.readStats()
        @Synchronized override fun setRunning(running: Boolean) {
            this.running = running
            output?.setRunning(running)
        }
        @Synchronized override fun markEndOfStream() {
            ended = true
            output?.markEndOfStream()
        }
        @Synchronized override fun release() { released = true; output?.release() }
    }

    /**
     * Sets the hardware volume to the device's setting at or below [target] (1/256 dB) and answers what
     * the device reads back, or null when the session is not streaming. A device that no longer answers
     * the read plays at an unknown level, so the session then fails closed (D4).
     */
    fun setVolume(target: Int, onDone: (Int?) -> Unit) = control.execute {
        val volume = volumeControl
        if ((state !is State.Streaming && state != State.Reconfiguring) || volume == null) return@execute onDone(null)
        val readBack = try {
            client.setVolume(volume.unitId, volume.controlInterface, volume.range.settingAtOrBelow(target))
            client.volume(volume.unitId, volume.controlInterface)
        } catch (_: SecurityException) {
            onDone(null)
            fail(DirectUsbFailure(Reason.USB_PERMISSION_REVOKED, "USB access was revoked"))
            return@execute
        } catch (_: IllegalStateException) {
            null
        }
        onDone(readBack)
        if (readBack == null) fail(DirectUsbFailure(Reason.VOLUME_CONTROL_FAILED, "the volume could not be read after a change"))
    }

    /** Gives the device back. The callback comes once every interface is released, which can wait for the kernel. */
    fun release(onReleased: ((CleanupRecord) -> Unit)? = null) = control.execute {
        val done = state as? State.Released
        if (done != null) {
            onReleased?.invoke(done.cleanup)
            return@execute
        }
        if (onReleased != null) releaseCallbacks.add(onReleased)
        if (state is State.Streaming || state == State.Reconfiguring || state == State.Idle) {
            synchronized(epochLock) { state = State.Releasing(null) }
            cleanUp()
        }
    }

    /** The system said the device is gone. */
    fun onDeviceDetached() {
        detached = true
        control.execute { fail(DirectUsbFailure(Reason.DEVICE_DISCONNECTED, "the device was detached")) }
    }

    private fun claimAndStart(plan: StreamingPlan, startVolume: Int): Takeover {
        if (detached) return failed(Reason.DEVICE_DISCONNECTED, "the device was detached")
        val taken = markers.acquire(device, function.controlInterface)
            ?: return failed(Reason.USB_TRANSFER_FAILED, "the takeover marker is unavailable or awaiting recovery")
        markerWritten = true
        restorePending = taken.volumeBefore != null
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
        if (taken.volumeUnitId != null && taken.volumeUnitId != unit.id) {
            return failed(Reason.VOLUME_CONTROL_FAILED, "another volume control has pending recovery")
        }
        val range = client.volumeRange(unit.id, controlInterface)
        val volumeNow = client.volume(unit.id, controlInterface)
        if (range == null || volumeNow == null) return failed(Reason.VOLUME_CONTROL_FAILED, "the volume could not be read")
        // A record taken over from a process that died holds the volume from before that process's takeover;
        // the device still has the level that process set.
        val volumeBefore = taken.volumeBefore ?: volumeNow
        val mutedBefore = if (taken.volumeBefore != null) taken.mutedBefore else client.mute(unit.id, controlInterface)
        if (!markers.write(TakeoverMarker(device, volumeBefore, mutedBefore, controlInterface, unit.id))) {
            return failed(Reason.USB_TRANSFER_FAILED, "the original hardware volume could not be saved")
        }
        restorePending = true
        restoreVolume = {
            client.restoreSettings(unit.id, controlInterface, volumeBefore, mutedBefore)
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
        volumeControl = VolumeControl(unit.id, controlInterface, range)
        volumeRange = range

        val startFailure = startStream(plan)
        return if (startFailure == null) Takeover.Ready(volumeBefore, readBack) else Takeover.Failed(startFailure)
    }

    private fun startStream(plan: StreamingPlan): DirectUsbFailure? {
        val terminal = function.streamingInterfaces.firstOrNull { it.interfaceNumber == plan.interfaceNumber }
            ?.alternateSettings?.firstOrNull { it.alternateSetting == plan.alternateSetting }?.terminalLink
        val unit = terminal?.let { function.featureUnitsAfter(it).firstOrNull { unit -> unit.masterVolume != UacControlAccess.ABSENT } }
        if (unit?.id != volumeControl?.unitId) {
            return DirectUsbFailure(Reason.VOLUME_CONTROL_FAILED, "the new stream uses another volume control")
        }
        if (!link.selectAlternate(plan.interfaceNumber, plan.alternateSetting)) {
            return DirectUsbFailure(Reason.INTERFACE_CLAIM_FAILED, "alternate setting ${plan.alternateSetting} of interface ${plan.interfaceNumber}")
        }
        streamingInterface = plan.interfaceNumber
        // UAC1 addresses the rate to the endpoint, which only exists once its setting is active.
        if (plan.endpointSetsSampleRate) {
            if (!client.setSampleRate(plan.endpointAddress, plan.sampleRateHz)) {
                return DirectUsbFailure(Reason.SAMPLE_RATE_SET_FAILED, "${plan.sampleRateHz} Hz")
            }
            val rate = client.sampleRate(plan.endpointAddress)
            if (rate != plan.sampleRateHz) return DirectUsbFailure(Reason.SAMPLE_RATE_READBACK_MISMATCH, "set ${plan.sampleRateHz}, reads back $rate")
        }

        val created = when (val creation = streams.create(link.fileDescriptor, plan)) {
            is UsbStreamFactory.Creation.Created -> creation.stream
            is UsbStreamFactory.Creation.Unavailable -> return DirectUsbFailure(Reason.USB_ENGINE_UNAVAILABLE, creation.reason)
        }
        stream = created
        if (detached) return DirectUsbFailure(Reason.DEVICE_DISCONNECTED, "the device was detached")
        if (!created.start()) return DirectUsbFailure(Reason.USB_ENGINE_UNAVAILABLE, "the stream thread did not start")
        return null
    }

    private fun failed(reason: Reason, detail: String) = Takeover.Failed(DirectUsbFailure(reason, detail))

    /** On [control]: errors the stream reported, and retirements that never finish. */
    private fun check() {
        if (state !is State.Streaming) return
        val connectionFailure = try {
            when {
                !link.connected -> DirectUsbFailure(Reason.DEVICE_DISCONNECTED, "the device was detached")
                !link.permissionGranted -> DirectUsbFailure(Reason.USB_PERMISSION_REVOKED, "USB access was revoked")
                else -> null
            }
        } catch (_: SecurityException) {
            DirectUsbFailure(Reason.USB_PERMISSION_REVOKED, "USB access was revoked")
        } catch (_: IllegalStateException) {
            DirectUsbFailure(Reason.USB_TRANSFER_FAILED, "USB connection unavailable")
        }
        if (connectionFailure != null) return fail(connectionFailure)
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
        if (state !is State.Streaming && state != State.Reconfiguring) return
        synchronized(epochLock) { state = State.Releasing(failure) }
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
            finalStats = current.readStats()
            finishedStream = current
            finalTotals += finalStats?.sessionTotals ?: UsbStreamTotals.NONE
            stream = null
            cleanupSteps += CleanupStep("stream reclaimed", true)
        }
        var allReleased = true
        streamingInterface?.let { number ->
            allReleased = cleanupStep("alternate setting 0 on interface $number") { link.selectAlternate(number, 0) }
            streamingInterface = null
        }
        restoreVolume?.let { restore ->
            restorePending = !cleanupStep("volume restored", restore)
            restoreVolume = null
        }
        claimed.asReversed().forEach { number ->
            val released = cleanupStep("interface $number released") { link.release(number) }
            allReleased = allReleased && released
        }
        claimed.clear()
        val closed = cleanupStep("connection closed") { link.close(); true }
        allReleased = allReleased && closed
        // Only a device with every interface back may lose its marker; otherwise the next start recovers it.
        if (markerWritten) {
            cleanupSteps += CleanupStep("takeover marker cleared", markers.finish(allReleased, settingsRestored = !restorePending))
        }
        val record = CleanupRecord(cleanupSteps.toList())
        val failure = (state as? State.Releasing)?.failure
        state = State.Released(record, failure)
        listener.onReleased(record, failure)
        releaseCallbacks.forEach { it(record) }
        releaseCallbacks.clear()
    }

    private fun cleanupStep(name: String, action: () -> Boolean): Boolean {
        val succeeded = try {
            action()
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
        cleanupSteps += CleanupStep(name, succeeded)
        return succeeded
    }

    private fun retireNewest(current: UsbStream) {
        newestEpoch = current.retireOutput()
        newestEpochTaken = false
        retiredAtNanos = nanoTime()
    }

    private fun retire(output: Output) {
        val current = stream ?: return
        if (current !== output.owner) return
        synchronized(epochLock) {
            if (stream === output.owner && state is State.Streaming && output.epoch == newestEpoch && newestEpochTaken) retireNewest(current)
        }
    }

    private inner class Output(override val epoch: Long, val owner: UsbStream) : UsbOutputHandle {
        @Volatile
        private var released = false

        // State changes acquire this lock before destruction. A playback call can only use a live stream.
        override fun write(buffer: ByteBuffer, offset: Int, size: Int): Int = synchronized(epochLock) {
            if (released) return@synchronized -1
            if (state !is State.Streaming || stream !== owner) return@synchronized if (failure == null) 0 else -1
            owner.write(epoch, buffer, offset, size)
        }

        override fun setRunning(running: Boolean) = synchronized(epochLock) {
            if (!released && stream === owner && state is State.Streaming) owner.setRunning(epoch, running)
        }

        override fun markEndOfStream() = synchronized(epochLock) {
            if (!released && stream === owner && state is State.Streaming) owner.markEndOfStream(epoch)
        }

        private var lastStats: UsbStreamStats? = null
        override fun readStats(): UsbStreamStats? = synchronized(epochLock) {
            val stats = if (stream === owner && state is State.Streaming) owner.readStats()
                else finalStats.takeIf { finishedStream === owner } ?: lastStats
            stats?.takeIf { it.epoch == epoch }?.also { lastStats = it }
        }

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
