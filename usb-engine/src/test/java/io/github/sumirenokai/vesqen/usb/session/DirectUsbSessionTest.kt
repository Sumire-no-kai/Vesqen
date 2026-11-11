package io.github.sumirenokai.vesqen.usb.session

import io.github.sumirenokai.vesqen.usb.control.UacVolumeRange
import io.github.sumirenokai.vesqen.usb.control.UsbControlTransfer
import io.github.sumirenokai.vesqen.usb.descriptor.UacDescriptorParser
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacParseResult
import io.github.sumirenokai.vesqen.usb.engine.UsbStream
import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlanResult
import io.github.sumirenokai.vesqen.usb.plan.StreamingPlanner
import io.github.sumirenokai.vesqen.usb.plan.UsbBusSpeed
import io.github.sumirenokai.vesqen.usb.session.DirectUsbFailure.Reason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The session against a fake JBL Flip 7, a fake stream and a fake marker store, on a real control thread. */
class DirectUsbSessionTest {
    private val events = CopyOnWriteArrayList<String>()
    private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "usb-control") }
    private val device = FakeUac1Device(events)
    private val link = FakeLink(events, device)
    private val stream = FakeStream(events)
    private val markers = FakeMarkers(events)
    private val failures = CopyOnWriteArrayList<DirectUsbFailure>()
    private val released = CountDownLatch(1)
    private val releasedWith = AtomicReference<Pair<CleanupRecord, DirectUsbFailure?>>()
    private var streams = UsbStreamFactory { _, _ -> UsbStreamFactory.Creation.Created(stream.also { events += "stream created" }) }
    private val session by lazy {
        DirectUsbSession(
            device = UsbDeviceIdentity(deviceId = 1003, vendorId = 0x0ecb, productId = 0x2107),
            link = link,
            function = JBL,
            markers = markers,
            control = control,
            streams = streams,
            timing = DirectUsbSession.Timing(monitorPeriodMs = 10, retireTimeoutMs = 150, destroyTimeoutMs = 1, destroyRetryMs = 20),
            listener = object : DirectUsbSession.Listener {
                override fun onFailed(failure: DirectUsbFailure) {
                    failures += failure
                }

                override fun onReleased(cleanup: CleanupRecord, failure: DirectUsbFailure?) {
                    releasedWith.set(cleanup to failure)
                    released.countDown()
                }
            },
        )
    }

    @Test
    fun `a failed restoration retains the original level after a user volume change`() {
        takeOver()
        assertEquals(0, setVolume(0))
        device.failVolumeWrites = true
        val (record, _) = release()
        assertEquals("volume restored", record.firstFailure?.name)
        assertTrue(events.containsAll(listOf("release 2", "release 1", "close")))
        assertEquals(-2560, markers.persisted?.volumeBefore)
        device.failVolumeWrites = false
        val retried = DirectUsbSession(
            UsbDeviceIdentity(1003, 0x0ecb, 0x2107), FakeLink(events, device), JBL, markers, control,
            streams = { _, _ -> UsbStreamFactory.Creation.Created(FakeStream(events)) },
        )
        assertEquals(-2560, (takeOver(retried) as DirectUsbSession.Takeover.Ready).volumeBefore)
        retried.release()
        control.submit {}.get(2, TimeUnit.SECONDS)
        assertNull(markers.persisted)
        assertTrue(events.contains("set volume -2560"))
    }

    @Test
    fun `startup recovery restores the saved settings and rebinds without creating a stream`() {
        markers.persisted = TakeoverMarker(UsbDeviceIdentity(1003, 0x0ecb, 0x2107), -1280, true)
        val record = recover()
        assertNull(record.firstFailure)
        assertTrue(events.containsAll(listOf("claim 1", "claim 2", "alternate 2/0", "set volume -1280", "release 2", "release 1", "close")))
        assertFalse(events.any { it.startsWith("stream ") || it.startsWith("set rate ") })
        assertNull(markers.persisted)
    }

    @Test
    fun `startup recovery keeps the saved volume after a failed write and can retry`() {
        val marker = TakeoverMarker(UsbDeviceIdentity(1003, 0x0ecb, 0x2107), -1280, false)
        markers.persisted = marker
        device.muted = true
        device.failVolumeWrites = true
        assertEquals("volume restored", recover().firstFailure?.name)
        assertTrue(events.containsAll(listOf("release 2", "release 1", "close")))
        assertEquals(marker, markers.persisted)
        assertTrue("a failed level write must leave the DAC muted", device.muted)
        device.failVolumeWrites = false
        assertNull(recover().firstFailure)
        assertNull(markers.persisted)
    }

    @Test
    fun `recovery releases successful claims when a later interface refuses`() {
        markers.persisted = TakeoverMarker(UsbDeviceIdentity(1003, 0x0ecb, 0x2107), -1280, false)
        link.failingClaims += 2
        assertEquals("interface 2 reclaimed", recover().firstFailure?.name)
        assertTrue(events.containsAll(listOf("release 1", "close")))
        assertFalse(events.contains("release 2"))
        assertTrue(markers.isOccupied())
    }

    @Test
    fun `a different connection cannot receive the old saved settings`() {
        val marker = TakeoverMarker(UsbDeviceIdentity(999, 0x0ecb, 0x2107), -1280, false)
        markers.persisted = marker
        assertEquals("recovery ownership", recover().firstFailure?.name)
        assertEquals(listOf("close"), events.toList())
        assertEquals(marker, markers.persisted)
    }

    @Test
    fun `a rebuilt service waits until the previous session finishes cleanup`() {
        takeOver()
        var idle = false
        markers.whenIdle { idle = true }
        assertFalse(idle)
        session.release()
        assertTrue(released.await(2, TimeUnit.SECONDS))
        control.submit {}.get(2, TimeUnit.SECONDS)
        assertTrue(idle)
        assertNull(markers.persisted)
    }

    @Test
    fun `startup recovery touches only the saved audio function of a composite device`() {
        val other = JBL.copy(controlInterface = 5, streamingInterfaces = JBL.streamingInterfaces.map { it.copy(interfaceNumber = 6) })
        markers.persisted = TakeoverMarker(UsbDeviceIdentity(1003, 0x0ecb, 0x2107), null, null, JBL.controlInterface)
        assertNull(recover(listOf(other, JBL)).firstFailure)
        assertEquals(listOf("claim 1", "claim 2"), events.filter { it.startsWith("claim ") })
    }

    @Test
    fun `a legacy marker cannot force claim one of several possible functions`() {
        val marker = TakeoverMarker(UsbDeviceIdentity(1003, 0x0ecb, 0x2107), null, null)
        markers.persisted = marker
        assertTrue(recover(listOf(JBL, JBL.copy(controlInterface = 5))).firstFailure != null)
        assertFalse(events.any { it.startsWith("claim ") })
        assertEquals(marker, markers.persisted)
    }

    @Test
    fun `an unsuccessful alternate reset preserves the recovery record`() {
        val marker = TakeoverMarker(UsbDeviceIdentity(1003, 0x0ecb, 0x2107), null, null)
        markers.persisted = marker
        link.refuseAlternateZero = true
        assertEquals("alternate setting 0 on interface 2", recover().firstFailure?.name)
        assertEquals(marker, markers.persisted)
        assertTrue(events.containsAll(listOf("release 1", "release 2", "close")))
    }

    private fun recover(functions: List<UacFunction> = listOf(JBL)) =
        UsbTakeoverRecovery(UsbDeviceIdentity(1003, 0x0ecb, 0x2107), link, functions, markers).recover()

    @Test
    fun `an unavailable USB manager cannot silently stop monitoring an active stream`() {
        takeOver()
        link.probeUnavailable = true
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(Reason.USB_TRANSFER_FAILED, failures.single().reason)
        assertTrue(events.containsAll(listOf("release 2", "release 1", "close")))
    }

    @Test
    fun `a detach delivered during stream creation cannot publish a successful takeover`() {
        streams = UsbStreamFactory { _, _ ->
            session.onDeviceDetached()
            UsbStreamFactory.Creation.Created(stream)
        }
        val result = takeOver()
        assertTrue(result is DirectUsbSession.Takeover.Failed)
        assertEquals(Reason.DEVICE_DISCONNECTED, (result as DirectUsbSession.Takeover.Failed).failure.reason)
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertFalse(events.contains("stream started"))
    }

    @Test
    fun `an unsuccessful alternate reset on live release preserves the marker for startup`() {
        takeOver()
        link.refuseAlternateZero = true
        val (record, _) = release()
        assertEquals("alternate setting 0 on interface 2", record.firstFailure?.name)
        assertTrue(markers.isOccupied())
    }

    @Test
    fun `permission removal fails closed and releases every claimed interface`() {
        takeOver()
        link.permissionGranted = false
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(Reason.USB_PERMISSION_REVOKED, failures.single().reason)
        assertTrue(events.containsAll(listOf("stream destroyed", "release 2", "release 1", "close")))
        assertTrue(session.openOutput() == null)
    }

    @Test
    fun `permission removal during a claim still releases earlier interfaces`() {
        link.throwingClaims += 2
        val result = takeOver() as DirectUsbSession.Takeover.Failed
        assertEquals(Reason.USB_PERMISSION_REVOKED, result.failure.reason)
        assertTrue(events.containsAll(listOf("claim 1", "claim 2", "release 1", "close")))
    }

    @Test
    fun `an exception releasing one interface does not skip the rest or erase recovery`() {
        takeOver()
        link.throwingReleases += 2
        val (record, _) = release()
        assertEquals("interface 2 released", record.firstFailure?.name)
        assertTrue(events.containsAll(listOf("release 2", "release 1", "close")))
        assertEquals(-2560, markers.persisted?.volumeBefore)
        assertTrue(markers.isOccupied())
    }

    @Test
    fun `a removed device is identified as detached even when permission has disappeared`() {
        takeOver()
        link.permissionGranted = false
        link.connected = false
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(Reason.DEVICE_DISCONNECTED, failures.single().reason)
    }

    @Test
    fun `an intentional release keeps the last media position while focus is held elsewhere`() {
        takeOver()
        val output = checkNotNull(session.openOutput())
        val before = checkNotNull(output.readStats())
        release()
        assertEquals(before.epoch, output.readStats()?.epoch)
        assertEquals(before.mediaFramesProcessed, output.readStats()?.mediaFramesProcessed)
        assertEquals(0, output.write(ByteBuffer.allocateDirect(4), 0, 4))
        assertNull(output.failure)
    }

    @Test
    fun `reconfiguration keeps the original marker and hardware level until final release`() {
        val next = FakeStream(events)
        var creations = 0
        streams = UsbStreamFactory { _, _ -> UsbStreamFactory.Creation.Created(if (creations++ == 0) stream else next) }
        takeOver()
        val original = markers.persisted
        setVolume(-5000)
        events.clear()
        val output = session.openOutput(PLAN.copy(sampleRateHz = 44_100, ringFrames = 22_050))
        assertTrue(waitUntil { output.readStats() != null })
        assertTrue(events.indexOf("stream destroyed") < events.indexOf("set rate 44100"))
        assertEquals(2, creations)
        assertEquals(original, markers.persisted)
        assertFalse(events.any { it.startsWith("claim ") || it.startsWith("release ") || it.startsWith("set volume ") })
        release()
        assertTrue(events.contains("set volume -2560"))
        assertNull(markers.persisted)
    }

    @Test
    fun `a newer requested format replaces a released request while URBs are outstanding`() {
        val next = FakeStream(events)
        var creations = 0
        streams = UsbStreamFactory { _, _ -> UsbStreamFactory.Creation.Created(if (creations++ == 0) stream else next) }
        takeOver()
        stream.destroyAnswers.addAll(listOf(false, false, false, true))
        events.clear()
        val obsolete = session.openOutput(PLAN.copy(sampleRateHz = 44_100))
        assertTrue(waitUntil { "stream not destroyed" in events })
        obsolete.release()
        val latest = session.openOutput(PLAN)
        assertTrue(waitUntil { latest.readStats() != null })
        assertFalse(events.contains("set rate 44100"))
        assertEquals(2, creations)
        assertEquals(-1, obsolete.write(ByteBuffer.allocateDirect(4), 0, 4))
        assertEquals(4, latest.write(ByteBuffer.allocateDirect(4), 0, 4))
    }

    @Test
    fun `playback operations do not enter a stream while its destruction waits`() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val playback = Executors.newSingleThreadExecutor()
        takeOver()
        val old = checkNotNull(session.openOutput())
        stream.onDestroy = { entered.countDown(); finish.await(5, TimeUnit.SECONDS) }
        stream.onRunning = { finish.await(5, TimeUnit.SECONDS) }
        session.openOutput(PLAN.copy(sampleRateHz = 44_100))
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            playback.submit {
                old.setRunning(false)
                old.markEndOfStream()
                assertEquals(0, old.write(ByteBuffer.allocateDirect(4), 0, 4))
                old.readStats()
                old.release()
            }.get(2, TimeUnit.SECONDS)
        } finally {
            finish.countDown()
            playback.shutdownNow()
            control.submit {}.get(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a request abandoned during blocking destruction is not configured after the barrier`() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val next = FakeStream(events)
        var creations = 0
        streams = UsbStreamFactory { _, _ -> UsbStreamFactory.Creation.Created(if (creations++ == 0) stream else next) }
        takeOver()
        stream.onDestroy = { entered.countDown(); finish.await(5, TimeUnit.SECONDS) }
        events.clear()
        val obsolete = session.openOutput(PLAN.copy(sampleRateHz = 44_100))
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            obsolete.release()
        } finally {
            finish.countDown()
        }
        control.submit {}.get(2, TimeUnit.SECONDS)
        assertEquals(1, creations)
        assertFalse(events.contains("set rate 44100"))
        val latest = session.openOutput(PLAN)
        assertTrue(waitUntil { latest.readStats() != null })
        assertEquals(2, creations)
    }

    @Test
    fun `session totals include each native stream once across a rate change and release`() {
        val next = FakeStream(events)
        var creations = 0
        streams = UsbStreamFactory { _, _ -> UsbStreamFactory.Creation.Created(if (creations++ == 0) stream else next) }
        takeOver()
        stream.delivered[0] = 100
        stream.lost[0] = 2
        assertEquals(UsbStreamTotals(2, 0, 0, 100), session.totals())
        val output = session.openOutput(PLAN.copy(sampleRateHz = 44_100))
        assertTrue(waitUntil { output.readStats() != null })
        assertEquals(UsbStreamTotals(2, 0, 0, 100), session.totals())
        next.delivered[0] = 25
        next.lost[0] = 3
        assertEquals(UsbStreamTotals(5, 0, 0, 125), session.totals())
        release()
        assertEquals(UsbStreamTotals(5, 0, 0, 125), session.totals())
    }

    @Test
    fun `the same wire plan opens another epoch without recreating the stream`() {
        var creations = 0
        streams = UsbStreamFactory { _, _ -> creations++; UsbStreamFactory.Creation.Created(stream) }
        takeOver()
        events.clear()
        val output = session.openOutput(PLAN)
        assertTrue(waitUntil { output.readStats() != null })
        assertEquals(1, creations)
        assertFalse(events.any { it.startsWith("alternate ") || it.startsWith("set rate ") || it == "stream destroyed" })
    }

    @Test
    fun `a rate change cannot cross the old native safety barrier`() {
        val next = FakeStream(events)
        var creations = 0
        streams = UsbStreamFactory { _, _ -> UsbStreamFactory.Creation.Created(if (creations++ == 0) stream else next) }
        takeOver()
        stream.destroyAnswers.addAll(listOf(false, false, true))
        events.clear()
        val output = session.openOutput(PLAN.copy(sampleRateHz = 44_100))
        assertTrue(waitUntil { "stream not destroyed" in events })
        assertEquals(1, creations)
        assertFalse(events.any { it.startsWith("set rate ") || it.startsWith("alternate ") })
        assertEquals(0, output.write(ByteBuffer.allocateDirect(4), 0, 4))
        assertTrue(waitUntil { output.readStats() != null })
        assertEquals(2, creations)
    }

    @Test
    fun `an old output cannot stop or retire a new stream with the same epoch number`() {
        val next = FakeStream(events)
        var creations = 0
        streams = UsbStreamFactory { _, _ -> UsbStreamFactory.Creation.Created(if (creations++ == 0) stream else next) }
        takeOver()
        val old = checkNotNull(session.openOutput())
        val output = session.openOutput(PLAN.copy(sampleRateHz = 44_100))
        assertTrue(waitUntil { output.readStats() != null })
        val retirements = next.retirements
        assertEquals(0, old.write(ByteBuffer.allocateDirect(4), 0, 4))
        assertTrue(next.writes.isEmpty())
        old.release()
        old.setRunning(false)
        assertEquals(retirements, next.retirements)
        assertEquals(4, output.write(ByteBuffer.allocateDirect(4), 0, 4))
    }

    @Test
    fun `a failed sampling rate readback during a switch releases all interfaces`() {
        takeOver()
        device.rateReadBack = 48_000
        val output = session.openOutput(PLAN.copy(sampleRateHz = 44_100))
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(Reason.SAMPLE_RATE_READBACK_MISMATCH, output.failure?.reason)
        assertTrue(events.containsAll(listOf("release 1", "release 2", "close")))
    }

    @Test
    fun `release while reconfiguration waits cannot restart the stream later`() {
        takeOver()
        stream.destroyAnswers.addAll(listOf(false, false, true))
        events.clear()
        val output = session.openOutput(PLAN.copy(sampleRateHz = 44_100))
        assertTrue(waitUntil { "stream not destroyed" in events })
        session.release()
        assertTrue(released.await(2, TimeUnit.SECONDS))
        control.submit {}.get(2, TimeUnit.SECONDS)
        assertTrue(session.state is DirectUsbSession.State.Released)
        assertFalse(events.contains("set rate 44100"))
        assertEquals(0, output.write(ByteBuffer.allocateDirect(4), 0, 4))
    }

    @After
    fun stopControl() {
        control.shutdownNow()
    }

    @Test
    fun `the takeover writes the marker before claiming and starts the stream last`() {
        assertEquals(DirectUsbSession.Takeover.Ready(volumeBefore = -2560, volumeNow = -15400), takeOver())

        assertEquals(
            listOf(
                "marker volume null",
                "claim 1",
                "claim 2",
                "marker volume -2560",
                "set volume -15400",
                "alternate 2/1",
                "set rate 48000",
                "stream created",
                "stream started",
            ),
            events.filterNot { it.startsWith("read") },
        )
        assertTrue(session.state is DirectUsbSession.State.Streaming)
    }

    @Test
    fun `a release stops the stream first and clears the marker only after every interface is back`() {
        takeOver()
        events.clear()

        val (record, failure) = release()

        assertEquals(
            listOf("stream destroyed", "alternate 2/0", "set volume -2560", "read 2/129", "read 1/129", "release 2", "release 1", "close", "marker cleared"),
            events,
        )
        assertNull(failure)
        assertNull(record.firstFailure)
        assertTrue(session.state is DirectUsbSession.State.Released)
    }

    @Test
    fun `nothing past the safety barrier happens while URBs are still the kernel's`() {
        takeOver()
        stream.destroyAnswers.addAll(listOf(false, false))
        events.clear()

        session.release()
        assertTrue(waitUntil { events.count { it == "stream not destroyed" } == 2 })
        assertEquals(listOf("stream not destroyed", "stream not destroyed"), events.toList().take(2))
        assertTrue(session.state is DirectUsbSession.State.Releasing)

        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("stream not destroyed", "stream not destroyed", "stream destroyed", "alternate 2/0"), events.take(4))
    }

    @Test
    fun `a failed step is recorded and the rest of the release still runs`() {
        takeOver()
        device.failVolumeWrites = true

        val (record, _) = release()

        assertEquals("volume restored", record.firstFailure?.name)
        assertTrue(events.containsAll(listOf("release 2", "release 1", "close")))
        assertEquals(-2560, markers.persisted?.volumeBefore)
    }

    @Test
    fun `an interface that does not come back keeps the marker for the next start`() {
        takeOver()
        link.failingReleases += 2

        val (record, _) = release()

        assertEquals("interface 2 released", record.firstFailure?.name)
        assertFalse(events.contains("marker cleared"))
        assertFalse(record.steps.single { it.name == "takeover marker cleared" }.succeeded)
    }

    @Test
    fun `a claim that fails gives back what was claimed and says which interface`() {
        link.failingClaims += 2

        val result = takeOver()

        assertEquals(DirectUsbSession.Takeover.Failed(DirectUsbFailure(Reason.INTERFACE_CLAIM_FAILED, "interface 2")), result)
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("release 1", "close", "marker cleared"), events.filter { it.startsWith("release") || it == "close" || it.startsWith("marker cleared") })
        assertFalse(events.contains("stream created"))
    }

    @Test
    fun `a volume that does not read back fails the takeover and restores the volume`() {
        device.volumeReadBackOffset = 2 * 154

        val result = takeOver() as DirectUsbSession.Takeover.Failed

        assertEquals(Reason.VOLUME_CONTROL_FAILED, result.failure.reason)
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertTrue(events.contains("set volume -2560"))
        assertFalse(events.contains("alternate 2/1"))
    }

    @Test
    fun `an engine that is not available fails closed with its reason`() {
        streams = UsbStreamFactory { _, _ -> UsbStreamFactory.Creation.Unavailable("no vesqen_usb") }

        val result = takeOver() as DirectUsbSession.Takeover.Failed

        assertEquals(DirectUsbFailure(Reason.USB_ENGINE_UNAVAILABLE, "no vesqen_usb"), result.failure)
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertTrue(events.containsAll(listOf("alternate 2/0", "set volume -2560", "release 2", "release 1", "marker cleared")))
    }

    @Test
    fun `each output takes the next epoch and an output still open is ended first`() {
        takeOver()

        val first = checkNotNull(session.openOutput())
        val second = checkNotNull(session.openOutput())
        second.release()
        val third = checkNotNull(session.openOutput())

        assertEquals(listOf(0L, 1L, 2L), listOf(first.epoch, second.epoch, third.epoch))
        assertEquals(2, stream.retirements)
        assertEquals(-1, first.write(BUFFER, 0, 4))
        assertEquals(-1, second.write(BUFFER, 0, 4))
        assertEquals(4, third.write(BUFFER, 0, 4))
        assertEquals(listOf(2L), stream.writes.map { it.first }.distinct())
    }

    @Test
    fun `releasing an output twice ends it once`() {
        takeOver()
        val output = checkNotNull(session.openOutput())

        output.release()
        output.release()

        assertEquals(1, stream.retirements)
    }

    @Test
    fun `an output reads only its own counters`() {
        takeOver()
        stream.acknowledgeRetirements = false
        val first = checkNotNull(session.openOutput())
        first.release()
        val second = checkNotNull(session.openOutput())

        assertNull(second.readStats())
        assertEquals(0, second.write(BUFFER, 0, 4))
        stream.acknowledgeRetirements = true
        assertEquals(1L, second.readStats()?.epoch)
    }

    @Test
    fun `a retirement that is not acknowledged in time fails the session closed`() {
        takeOver()
        stream.acknowledgeRetirements = false
        stream.destroyAnswers.addAll(List(3) { false })
        checkNotNull(session.openOutput()).release()
        val next = checkNotNull(session.openOutput())
        assertEquals(0, next.write(BUFFER, 0, 4))

        assertTrue(waitUntil { failures.isNotEmpty() })

        assertEquals(Reason.USB_TRANSFER_FAILED, failures.single().reason)
        assertEquals(-1, next.write(BUFFER, 0, 4))
        assertEquals(Reason.USB_TRANSFER_FAILED, next.failure?.reason)
        // The URBs are still out, so the interfaces stay claimed until they come back.
        assertFalse(events.contains("release 2"))
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(Reason.USB_TRANSFER_FAILED, releasedWith.get().second?.reason)
    }

    @Test
    fun `a retirement acknowledged in time is no failure`() {
        takeOver()
        stream.acknowledgeRetirements = false
        checkNotNull(session.openOutput()).release()
        val next = checkNotNull(session.openOutput())
        Thread.sleep(40)
        stream.acknowledgeRetirements = true

        Thread.sleep(300)

        assertTrue(failures.isEmpty())
        assertEquals(4, next.write(BUFFER, 0, 4))
    }

    @Test
    fun `a device the stream lost fails the session as disconnected`() {
        takeOver()
        val output = checkNotNull(session.openOutput())

        stream.failure = UsbStreamStats.Failure.DEVICE_GONE

        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(Reason.DEVICE_DISCONNECTED, failures.single().reason)
        assertEquals(Reason.DEVICE_DISCONNECTED, output.failure?.reason)
    }

    @Test
    fun `a detach from the system fails the session as disconnected`() {
        takeOver()

        session.onDeviceDetached()

        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(DirectUsbFailure(Reason.DEVICE_DISCONNECTED, "the device was detached"), failures.single())
    }

    @Test
    fun `totals add up every output of the session`() {
        takeOver()
        val first = checkNotNull(session.openOutput())
        stream.lost[0L] = 3
        first.release()
        checkNotNull(session.openOutput())
        stream.lost[1L] = 2

        assertEquals(5L, session.totals().lostFrames)
    }

    @Test
    fun `no takeover starts while the previous session still waits for its URBs`() {
        // #289: a rescheduled cleanup left the control thread free, and its late marker clear erased B's.
        takeOver()
        stream.destroyAnswers.addAll(List(5) { false })
        session.release()
        assertTrue(waitUntil { events.contains("stream not destroyed") })

        val second = nextSession()
        val refused = takeOver(second)

        assertEquals(DirectUsbSession.Takeover.Failed::class, refused::class)
        assertEquals(Reason.USB_TRANSFER_FAILED, (refused as DirectUsbSession.Takeover.Failed).failure.reason)
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(
            "Only the first session wrote and cleared the marker",
            listOf("marker volume null", "marker volume -2560", "marker cleared"),
            events.filter { it.startsWith("marker") },
        )

        // Once the first session has given everything back, the next takeover goes ahead.
        events.clear()
        val third = nextSession()
        assertEquals(DirectUsbSession.Takeover.Ready::class, takeOver(third)::class)
        assertEquals("marker volume -2560", events.last { it.startsWith("marker") })
    }

    @Test
    fun `a retry after a refused takeover still waits for the session that holds the device`() {
        // Several refused sessions may go away while the original owner is still releasing.
        takeOver()
        stream.destroyAnswers.addAll(List(10) { false })
        session.release()
        assertTrue(waitUntil { events.contains("stream not destroyed") })
        val second = nextSession()
        assertEquals(DirectUsbSession.Takeover.Failed::class, takeOver(second)::class)

        val third = nextSession()
        assertEquals(DirectUsbSession.Takeover.Failed::class, takeOver(third)::class)
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(
            "Neither retry wrote the marker the first session clears",
            listOf("marker volume null", "marker volume -2560", "marker cleared"),
            events.filter { it.startsWith("marker") },
        )

        events.clear()
        assertEquals(DirectUsbSession.Takeover.Ready::class, takeOver(nextSession())::class)
        assertEquals("marker volume -2560", events.last { it.startsWith("marker") })
    }

    @Test
    fun `another service cannot replace a marker while the old executor is stopped`() {
        takeOver()
        stream.destroyAnswers.add(false)
        stream.onDestroy = { control.shutdown() }
        session.release()
        assertTrue(waitUntil { failures.isNotEmpty() })
        assertTrue(session.state is DirectUsbSession.State.Releasing)

        val recreatedControl = Executors.newSingleThreadScheduledExecutor()
        try {
            val newLink = FakeLink(events, FakeUac1Device(events)).also { it.failingClaims += 1 }
            val next = DirectUsbSession(UsbDeviceIdentity(1003, 0x0ecb, 0x2107), newLink, JBL, markers, recreatedControl)
            events.clear()
            assertTrue(takeOver(next) is DirectUsbSession.Takeover.Failed)
            assertEquals(listOf("close"), events.toList())
            assertEquals(-2560, markers.persisted?.volumeBefore)
        } finally {
            recreatedControl.shutdownNow()
        }
    }

    @Test
    fun `a failed interface release preserves the old marker on the next attempt`() {
        takeOver()
        link.failingReleases += 2
        val (cleanup, _) = release()
        assertEquals("interface 2 released", cleanup.firstFailure?.name)
        val original = checkNotNull(markers.persisted)
        assertTrue(takeOver(nextSession()) is DirectUsbSession.Takeover.Failed)
        assertEquals(original, markers.persisted)
    }

    @Test
    fun `a marker a dead process left for this connection is taken over with its saved volume`() {
        // The process died while it held the DAC: the device still has the level it set (-2560 in the fake).
        markers.persisted = TakeoverMarker(UsbDeviceIdentity(1003, 0x0ecb, 0x2107), volumeBefore = -1280, mutedBefore = false)

        assertEquals(DirectUsbSession.Takeover.Ready(volumeBefore = -1280, volumeNow = -15400), takeOver())
        assertEquals(listOf("marker volume -1280", "marker volume -1280"), events.filter { it.startsWith("marker") })
        events.clear()
        release()
        assertTrue(events.containsAll(listOf("set volume -1280", "marker cleared")))
    }

    @Test
    fun `a marker left for another connected device still blocks the takeover`() {
        val other = TakeoverMarker(UsbDeviceIdentity(2001, 0x1234, 0x5678), volumeBefore = -1280, mutedBefore = false)
        markers.persisted = other

        assertTrue(takeOver() is DirectUsbSession.Takeover.Failed)
        assertEquals(other, markers.persisted)
        assertFalse(events.any { it.startsWith("claim") })
    }

    @Test
    fun `a volume change goes to the setting at or below the request and answers the read-back`() {
        assertNull(session.volumeRange)
        takeOver()
        assertEquals(UacVolumeRange(minimum = -15400, maximum = 0, resolution = 154), session.volumeRange)
        events.clear()

        assertEquals(-5082, setVolume(-5000))
        assertEquals(0, setVolume(500))
        assertEquals(-15400, setVolume(-20000))
        assertEquals(listOf("set volume -5082", "set volume 0", "set volume -15400"), events.filter { it.startsWith("set volume") })
        assertTrue(session.state is DirectUsbSession.State.Streaming)

        // The release still restores the level from before the takeover, not the user's last one.
        setVolume(-5000)
        events.clear()
        release()
        assertEquals(listOf("set volume -2560"), events.filter { it.startsWith("set volume") })
    }

    @Test
    fun `a refused volume write answers the unchanged level and playback goes on`() {
        takeOver()
        device.failVolumeWrites = true

        assertEquals(-15400, setVolume(-5000))
        assertTrue(session.state is DirectUsbSession.State.Streaming)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a volume that cannot be read back after a change fails closed`() {
        takeOver()
        device.failVolumeReads = true

        assertNull(setVolume(-5000))
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(DirectUsbFailure.Reason.VOLUME_CONTROL_FAILED, failures.single().reason)
    }

    @Test
    fun `a volume change outside streaming answers nothing and writes nothing`() {
        assertNull(setVolume(-5000))
        takeOver()
        release()
        events.clear()
        assertNull(setVolume(-5000))
        assertFalse(events.any { it.startsWith("set volume") })
    }

    @Test
    fun `a final packet delivered between Kotlin polls survives an epoch reset`() {
        takeOver()
        val first = checkNotNull(session.openOutput())
        stream.delivered[0] = 3
        stream.acknowledgeRetirements = false
        first.release()
        stream.delivered[0] = 4
        stream.acknowledgeRetirements = true
        assertEquals(1L, stream.readStats().epoch)
        assertEquals(4L, session.totals().deliveredFrames)
        release()
        assertEquals(4L, session.totals().deliveredFrames)
    }

    /** Another session sharing the control thread and marker store. */
    private fun nextSession(): DirectUsbSession {
        val nextStream = FakeStream(CopyOnWriteArrayList())
        return DirectUsbSession(
            device = UsbDeviceIdentity(deviceId = 1004, vendorId = 0x0ecb, productId = 0x2107),
            link = FakeLink(CopyOnWriteArrayList(), FakeUac1Device(CopyOnWriteArrayList())),
            function = JBL,
            markers = markers,
            control = control,
            streams = { _, _ -> UsbStreamFactory.Creation.Created(nextStream) },
            timing = DirectUsbSession.Timing(monitorPeriodMs = 10, retireTimeoutMs = 150, destroyTimeoutMs = 1, destroyRetryMs = 20),
        )
    }

    @Test
    fun `an output native has not yet retired is counted once`() {
        // #296: the retired epoch went into the finished totals and was still read live until native moved on.
        takeOver()
        val first = checkNotNull(session.openOutput())
        stream.lost[0L] = 3
        stream.acknowledgeRetirements = false
        first.release()
        assertEquals(3L, session.totals().lostFrames)

        // Packets of the retiring output can still finish during the handoff.
        stream.lost[0L] = 4
        assertEquals(4L, session.totals().lostFrames)

        checkNotNull(session.openOutput())
        stream.lost[1L] = 2
        stream.acknowledgeRetirements = true
        assertEquals(6L, session.totals().lostFrames)
        assertEquals(6L, session.totals().lostFrames)
    }

    @Test
    fun `a control thread stopped while URBs are out leaves the device held and says so`() {
        takeOver()
        stream.destroyAnswers.add(false)
        stream.onDestroy = { control.shutdown() }

        session.release()

        assertTrue(waitUntil { failures.isNotEmpty() })
        assertEquals(Reason.USB_TRANSFER_FAILED, failures.single().reason)
        assertTrue(session.state is DirectUsbSession.State.Releasing)
        assertFalse(events.contains("release 2"))
        assertFalse(events.contains("marker cleared"))
    }

    @Test
    fun `an output of a session given back on purpose takes nothing and does not fail`() {
        takeOver()
        val output = checkNotNull(session.openOutput())

        release()

        assertEquals(0, output.write(BUFFER, 0, 4))
        assertNull(output.failure)
        assertNull(session.openOutput())
    }

    @Test
    fun `an older output released late leaves the newer one alone`() {
        takeOver()
        val older = checkNotNull(session.openOutput())
        val newer = checkNotNull(session.openOutput())

        older.release()

        assertEquals(1, stream.retirements)
        assertEquals(4, newer.write(BUFFER, 0, 4))
    }

    @Test
    fun `a second release does not run the cleanup again`() {
        takeOver()

        release()
        session.release()
        control.submit {}.get(2, TimeUnit.SECONDS)

        assertEquals(1, events.count { it == "close" })
        assertEquals(1, events.count { it == "marker cleared" })
    }

    @Test
    fun `a muted device is unmuted for the takeover and muted again on release`() {
        device.muted = true

        takeOver()
        assertTrue(events.contains("set mute false"))
        assertFalse(device.muted)
        release()

        assertTrue(device.muted)
        assertTrue(events.indexOf("set mute true") > events.indexOf("stream destroyed"))
    }

    @Test
    fun `a device that stays muted fails the takeover`() {
        device.muted = true
        device.failMuteWrites = true

        val result = takeOver() as DirectUsbSession.Takeover.Failed

        assertEquals(DirectUsbFailure(Reason.VOLUME_CONTROL_FAILED, "the device stayed muted"), result.failure)
    }

    @Test
    fun `a rate that is not taken or does not read back fails the takeover`() {
        device.rateReadBack = 44_100

        val result = takeOver() as DirectUsbSession.Takeover.Failed

        assertEquals(DirectUsbFailure(Reason.SAMPLE_RATE_READBACK_MISMATCH, "set 48000, reads back 44100"), result.failure)
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertTrue(events.contains("alternate 2/0"))
    }

    @Test
    fun `a rate the device refuses fails the takeover`() {
        device.failRateWrites = true

        val result = takeOver() as DirectUsbSession.Takeover.Failed

        assertEquals(DirectUsbFailure(Reason.SAMPLE_RATE_SET_FAILED, "48000 Hz"), result.failure)
    }

    @Test(expected = IllegalStateException::class)
    fun `a session takes over only once`() {
        takeOver()
        session.takeOver(PLAN, START_VOLUME) {}
    }

    private fun takeOver(target: DirectUsbSession = session): DirectUsbSession.Takeover {
        val result = AtomicReference<DirectUsbSession.Takeover>()
        val done = CountDownLatch(1)
        target.takeOver(PLAN, START_VOLUME) {
            result.set(it)
            done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        return result.get()
    }

    private fun setVolume(target: Int): Int? {
        val result = AtomicReference<Int?>()
        val done = CountDownLatch(1)
        session.setVolume(target) {
            result.set(it)
            done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        return result.get()
    }

    private fun release(): Pair<CleanupRecord, DirectUsbFailure?> {
        session.release()
        assertTrue(released.await(2, TimeUnit.SECONDS))
        return releasedWith.get()
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(200) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private class FakeLink(private val events: MutableList<String>, override val control: UsbControlTransfer) : UsbLink {
        var refuseAlternateZero = false
        val failingClaims = mutableSetOf<Int>()
        val failingReleases = mutableSetOf<Int>()
        val throwingClaims = mutableSetOf<Int>()
        val throwingReleases = mutableSetOf<Int>()
        @Volatile var probeUnavailable = false
        @Volatile override var permissionGranted = true
            get() {
                check(!probeUnavailable) { "USB manager unavailable" }
                return field
            }
        @Volatile override var connected = true
        override val fileDescriptor = 42

        override fun claim(interfaceNumber: Int): Boolean {
            events += "claim $interfaceNumber"
            if (interfaceNumber in throwingClaims) throw SecurityException("revoked")
            return interfaceNumber !in failingClaims
        }

        override fun release(interfaceNumber: Int): Boolean {
            events += "release $interfaceNumber"
            if (interfaceNumber in throwingReleases) throw SecurityException("revoked")
            return interfaceNumber !in failingReleases
        }

        override fun selectAlternate(interfaceNumber: Int, alternateSetting: Int): Boolean {
            events += "alternate $interfaceNumber/$alternateSetting"
            return alternateSetting != 0 || !refuseAlternateZero
        }

        override fun close() {
            events += "close"
        }
    }

    /** Answers UAC1 volume, mute and sampling-frequency requests as the JBL Flip 7 does. */
    private class FakeUac1Device(private val events: MutableList<String>) : UsbControlTransfer {
        @Volatile
        var failVolumeWrites = false

        @Volatile
        var failVolumeReads = false

        /** Added to every volume read after the first write, as a device that rounds badly would. */
        @Volatile
        var volumeReadBackOffset = 0

        @Volatile
        var muted = false

        @Volatile
        var failMuteWrites = false

        @Volatile
        var failRateWrites = false

        /** What a rate read answers instead of the rate set, when not null. */
        @Volatile
        var rateReadBack: Int? = null
        private var volume = -2560
        private var volumeWritten = false
        private var rate = 44_100

        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            val control = value shr 8
            return when (requestType) {
                0xA1 -> {
                    if (failVolumeReads && control == 0x02 && request == 0x81) return -1
                    val answer = when {
                        control == 0x02 && request == 0x82 -> -15400
                        control == 0x02 && request == 0x83 -> 0
                        control == 0x02 && request == 0x84 -> 154
                        control == 0x02 && request == 0x81 -> volume + if (volumeWritten) volumeReadBackOffset else 0
                        control == 0x01 && request == 0x81 -> if (muted) 1 else 0
                        else -> return -1
                    }
                    events += "read $control/$request"
                    buffer[0] = answer.toByte()
                    if (length > 1) buffer[1] = (answer shr 8).toByte()
                    length
                }
                0x21 -> {
                    if (control == 0x01) {
                        if (failMuteWrites) return -1
                        muted = buffer[0].toInt() != 0
                        events += "set mute $muted"
                    }
                    if (control == 0x02) {
                        if (failVolumeWrites) return -1
                        volume = (buffer[0].toInt() and 0xFF) or (buffer[1].toInt() shl 8)
                        volumeWritten = true
                        events += "set volume $volume"
                    }
                    length
                }
                0xA2 -> {
                    val answer = rateReadBack ?: rate
                    buffer[0] = answer.toByte()
                    buffer[1] = (answer shr 8).toByte()
                    buffer[2] = (answer shr 16).toByte()
                    3
                }
                0x22 -> {
                    if (failRateWrites) return -1
                    rate = (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8) or ((buffer[2].toInt() and 0xFF) shl 16)
                    events += "set rate $rate"
                    3
                }
                else -> -1
            }
        }
    }

    /** Keeps epochs as the native stream does; whether a retirement is acknowledged is up to the test. */
    private class FakeStream(private val events: MutableList<String>) : UsbStream {
        @Volatile
        var acknowledgeRetirements = true

        @Volatile
        var failure = UsbStreamStats.Failure.NONE
        val destroyAnswers = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()

        /** Runs inside destroy, before it answers. */
        @Volatile
        var onDestroy: () -> Unit = {}
        var onRunning: () -> Unit = {}
        val writes = CopyOnWriteArrayList<Pair<Long, Int>>()
        val lost = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        val delivered = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        private var requested = 0L
        private var acknowledged = 0L
        var retirements = 0
            private set

        override fun start(): Boolean {
            events += "stream started"
            return true
        }

        @Synchronized
        override fun write(epoch: Long, buffer: ByteBuffer, offset: Int, size: Int): Int {
            settle()
            if (epoch != requested) return -1
            if (acknowledged != requested) return 0
            writes += epoch to size
            return size
        }

        override fun setRunning(epoch: Long, running: Boolean) = onRunning()

        override fun markEndOfStream(epoch: Long) = Unit

        @Synchronized
        override fun retireOutput(): Long {
            retirements++
            return ++requested
        }

        @Synchronized
        override fun readStats(): UsbStreamStats {
            settle()
            return UsbStreamStats(
                mediaFramesWritten = 0, mediaFramesSubmitted = 0, mediaFramesProcessed = 0, mediaFramesDelivered = 0,
                mediaFramesLost = lost[acknowledged] ?: 0, silenceFramesInserted = 0, usbFramesDelivered = 0, underruns = 0,
                urbsCompleted = 0, packetsFailed = 0, failure = failure, failureErrno = 0, threadNice = 0, threadSchedulerPolicy = 0,
                epoch = acknowledged, requestedEpoch = requested,
                sessionTotals = UsbStreamTotals(lost.values.sum(), 0, 0, delivered.values.sum()),
            )
        }

        override fun destroy(timeoutMs: Int): Boolean {
            onDestroy()
            val answer = destroyAnswers.poll() ?: true
            events += if (answer) "stream destroyed" else "stream not destroyed"
            return answer
        }

        private fun settle() {
            if (acknowledgeRetirements) acknowledged = requested
        }
    }

    private class FakeMarkers(private val events: MutableList<String>) : TakeoverMarkerStore() {
        var persisted: TakeoverMarker? = null
        override fun read() = persisted
        override fun hasPendingRecovery() = persisted != null
        override fun write(marker: TakeoverMarker): Boolean {
            persisted = marker
            events += "marker volume ${marker.volumeBefore}"
            return true
        }

        override fun clear(): Boolean {
            persisted = null
            events += "marker cleared"
            return true
        }
    }

    private companion object {
        const val START_VOLUME = -15400
        val BUFFER: ByteBuffer = ByteBuffer.allocateDirect(4)
        val JBL: UacFunction = run {
            val hex = checkNotNull(DirectUsbSessionTest::class.java.getResource("/descriptors/jbl-flip-7-0ecb-2107.hex")).readText()
                .filterNot(Char::isWhitespace)
            val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            (UacDescriptorParser.parse(raw) as UacParseResult.Parsed).topology.functions.single()
        }
        val PLAN: StreamingPlan = (StreamingPlanner.plan(JBL, UsbBusSpeed.FULL, 48_000, 16) as StreamingPlanResult.Planned).plan
    }
}
