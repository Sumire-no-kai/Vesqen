package io.github.sumirenokai.vesqen.usb.chain

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.session.AndroidUsbLink
import io.github.sumirenokai.vesqen.usb.session.CleanupRecord
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure
import io.github.sumirenokai.vesqen.usb.session.DirectUsbSession
import io.github.sumirenokai.vesqen.usb.session.TakeoverMarker
import io.github.sumirenokai.vesqen.usb.session.TakeoverMarkerStore
import io.github.sumirenokai.vesqen.usb.session.UsbDeviceIdentity
import io.github.sumirenokai.vesqen.usb.session.UsbOutputHandle
import io.github.sumirenokai.vesqen.usb.session.UsbStreamTotals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The chain probe's device. A [DirectUsbSession] takes over once, so each takeover, the first and
 * the one after a simulated focus loss, gets a session of its own on a newly opened connection.
 */
internal class ChainUsb(
    private val device: UsbDevice,
    private val usbManager: UsbManager,
    firstConnection: UsbDeviceConnection,
    private val function: UacFunction,
    private val plan: StreamingPlan,
    private val startVolume: Int,
    private val report: (String) -> Unit,
) {
    private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "usb-control") }
    private val markers = object : TakeoverMarkerStore {
        override fun write(marker: TakeoverMarker) = report("Takeover marker: $marker")

        override fun clear() = report("Takeover marker cleared")
    }
    private val opened = AtomicInteger()
    private val created = AtomicInteger()
    private var connection: UsbDeviceConnection? = firstConnection
    private val totalsLock = Any()
    private var finished = UsbStreamTotals.NONE
    private val counted = mutableSetOf<DirectUsbSession>()

    @Volatile
    private var session: DirectUsbSession? = null

    @Volatile
    private var released = CountDownLatch(0)

    @Volatile
    private var newest: UsbOutputHandle? = null

    @Volatile
    private var newestStats: UsbStreamStats? = null

    val isClaimed: Boolean get() = session?.state is DirectUsbSession.State.Streaming

    /** Outputs Media3 opened over the whole run. */
    val outputsOpened: Int get() = opened.get()

    /** Native streams the sessions created: one per takeover, however many outputs. */
    val streamsCreated: Int get() = created.get()

    fun takeOver(onDone: (Boolean) -> Unit = {}) {
        val link = connection ?: usbManager.openDevice(device) ?: return onDone(false).also { report("Could not open the device") }
        connection = null
        val done = CountDownLatch(1)
        released = done
        val next = DirectUsbSession(
            device = UsbDeviceIdentity(device.deviceId, device.vendorId, device.productId),
            link = AndroidUsbLink(device, link),
            function = function,
            markers = markers,
            control = control,
            listener = object : DirectUsbSession.Listener {
                override fun onFailed(failure: DirectUsbFailure) {
                    report("Session failed: ${failure.reason} (${failure.detail})")
                    session?.let(::count)
                }

                override fun onReleased(cleanup: CleanupRecord, failure: DirectUsbFailure?) {
                    report("Device given back: ${cleanup.steps.joinToString { "${it.name} ${if (it.succeeded) "ok" else "FAILED"}" }}")
                    done.countDown()
                }
            },
        )
        session = next
        next.takeOver(plan, startVolume) { result ->
            report("Takeover: $result")
            val ready = result is DirectUsbSession.Takeover.Ready
            if (ready) created.incrementAndGet()
            onDone(ready)
        }
    }

    /** Gives the device back. It is free once every interface is released, which [shutdown] waits for. */
    fun giveBack() {
        val current = session ?: return
        count(current)
        current.release()
    }

    /** Waits for the takeover, so the next output Media3 asks for has a stream to write to. */
    fun takeOverAndWait(): Boolean {
        val done = CountDownLatch(1)
        var ready = false
        takeOver {
            ready = it
            done.countDown()
        }
        done.await(RELEASE_WAIT_S, TimeUnit.SECONDS)
        return ready
    }

    /** A session's totals count once, taken while its stream still exists. */
    private fun count(current: DirectUsbSession) = synchronized(totalsLock) {
        if (counted.add(current)) finished += current.totals()
    }

    fun onDetached() {
        session?.onDeviceDetached()
    }

    /** For Media3's provider. */
    fun openOutput(): UsbOutputHandle? = session?.openOutput()?.also {
        opened.incrementAndGet()
        newest = it
        newestStats = null
    }

    /** The newest output's own counters, kept once known so a released output still reports them. */
    fun latestStats(): UsbStreamStats? = newest?.readStats()?.also { newestStats = it } ?: newestStats

    fun totals(): UsbStreamTotals = synchronized(totalsLock) {
        val current = session?.takeIf { it !in counted && it.state is DirectUsbSession.State.Streaming }
        finished + (current?.totals() ?: UsbStreamTotals.NONE)
    }

    /** Gives the device back if it is still held and waits until every interface is released. */
    fun shutdown() {
        if (isClaimed) giveBack()
        released.await(RELEASE_WAIT_S, TimeUnit.SECONDS)
        control.shutdown()
    }

    private companion object {
        const val RELEASE_WAIT_S = 5L
    }
}
