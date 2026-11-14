package io.github.sumirenokai.vesqen.usb.session

import io.github.sumirenokai.vesqen.usb.control.UacRateRange
import io.github.sumirenokai.vesqen.usb.control.UsbControlTransfer
import io.github.sumirenokai.vesqen.usb.descriptor.SampleRates
import io.github.sumirenokai.vesqen.usb.descriptor.UacAltSetting
import io.github.sumirenokai.vesqen.usb.descriptor.UacClockEntity
import io.github.sumirenokai.vesqen.usb.descriptor.UacControlAccess
import io.github.sumirenokai.vesqen.usb.descriptor.UacEndpoint
import io.github.sumirenokai.vesqen.usb.descriptor.UacEndpointUsage
import io.github.sumirenokai.vesqen.usb.descriptor.UacFeatureUnit
import io.github.sumirenokai.vesqen.usb.descriptor.UacFormat
import io.github.sumirenokai.vesqen.usb.descriptor.UacFunction
import io.github.sumirenokai.vesqen.usb.descriptor.UacInputTerminal
import io.github.sumirenokai.vesqen.usb.descriptor.UacOutputTerminal
import io.github.sumirenokai.vesqen.usb.descriptor.UacPcmKind
import io.github.sumirenokai.vesqen.usb.descriptor.UacStreamingInterface
import io.github.sumirenokai.vesqen.usb.descriptor.UacSyncType
import io.github.sumirenokai.vesqen.usb.descriptor.UacVersion
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * #284: a UAC2 device's clock is asked for its rates once claimed, set while the streaming interface
 * is at alternate setting 0, read back and checked for validity; its volume uses CUR and RANGE.
 * Written to Audio Device Class 2.0, to be measured with the reference DAC.
 */
class DirectUsbSessionUac2Test {
    private val events = CopyOnWriteArrayList<String>()
    private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "usb-control") }
    private val device = FakeUac2Device(events)

    @After fun stopControl() {
        control.shutdownNow()
    }

    @Test
    fun `the clock is set before the streaming setting and read back`() {
        device.rate = 44_100
        val session = session()
        assertTrue(takeOver(session, plan(48_000)) is DirectUsbSession.Takeover.Ready)
        assertTrue(events.indexOf("set clock 10 48000") in 0 until events.indexOf("alternate 1/1"))
        assertTrue(events.indexOf("valid 10") in events.indexOf("set clock 10 48000") until events.indexOf("alternate 1/1"))
        assertEquals(OFFERED, session.clockRates)
        assertEquals(48_000, device.rate)
    }

    @Test
    fun `a clock already at the rate is not written`() {
        device.rate = 48_000
        assertTrue(takeOver(session(), plan(48_000)) is DirectUsbSession.Takeover.Ready)
        assertFalse(events.any { it.startsWith("set clock") })
    }

    @Test
    fun `a rate the clock does not offer is refused before anything streams`() {
        device.rates = listOf(UacRateRange(44_100, 44_100, 0))
        val session = session()
        assertEquals(Reason.SAMPLE_RATE_UNSUPPORTED, failure(takeOver(session, plan(48_000))))
        assertEquals(device.rates, session.clockRates)
        assertFalse(events.contains("alternate 1/1"))
    }

    @Test
    fun `a clock that reads back another rate fails closed`() {
        device.readBack = 44_100
        assertEquals(Reason.SAMPLE_RATE_READBACK_MISMATCH, failure(takeOver(session(), plan(48_000))))
    }

    @Test
    fun `a refused clock write fails closed`() {
        device.refuseRateWrites = true
        assertEquals(Reason.SAMPLE_RATE_SET_FAILED, failure(takeOver(session(), plan(48_000))))
    }

    @Test
    fun `a clock that is not valid at the rate fails closed`() {
        device.valid = false
        assertEquals(Reason.SAMPLE_RATE_SET_FAILED, failure(takeOver(session(), plan(48_000))))
        assertFalse(events.contains("alternate 1/1"))
    }

    @Test
    fun `a clock that cannot be written runs only at its own rate`() {
        device.rate = 44_100
        val fixed = function(clocks = listOf(source(10, frequency = UacControlAccess.READ_ONLY)))
        assertEquals(Reason.SAMPLE_RATE_SET_FAILED, failure(takeOver(session(fixed), plan(48_000, fixed))))
        assertFalse(events.any { it.startsWith("set clock") })

        events.clear()
        device.rate = 48_000
        assertTrue(takeOver(session(fixed), plan(48_000, fixed)) is DirectUsbSession.Takeover.Ready)
    }

    @Test
    fun `a clock that cannot report its rate is not trusted`() {
        val silent = function(clocks = listOf(source(10, frequency = UacControlAccess.ABSENT)))
        assertEquals(Reason.SAMPLE_RATE_READBACK_MISMATCH, failure(takeOver(session(silent), plan(48_000, silent))))
    }

    @Test
    fun `the selector's current input decides which clock is set`() {
        device.selection = 2
        val selected = function(
            clocks = listOf(
                UacClockEntity.Selector(11, listOf(10, 12), UacControlAccess.WRITABLE),
                source(10),
                source(12),
            ),
            clockId = 11,
        )
        assertTrue(takeOver(session(selected), plan(48_000, selected)) is DirectUsbSession.Takeover.Ready)
        assertTrue(events.contains("set clock 12 48000"))
        assertFalse(events.any { it.startsWith("set clock 10") })
    }

    @Test
    fun `a selector that cannot report its input among several is refused`() {
        val unknown = function(
            clocks = listOf(UacClockEntity.Selector(11, listOf(10, 12), UacControlAccess.ABSENT), source(10), source(12)),
            clockId = 11,
        )
        assertEquals(Reason.SAMPLE_RATE_SET_FAILED, failure(takeOver(session(unknown), plan(48_000, unknown))))
    }

    @Test
    fun `volume uses uac2 range and current value requests`() {
        val ready = takeOver(session(), plan(48_000)) as DirectUsbSession.Takeover.Ready
        assertEquals(-2_560, ready.volumeBefore)
        assertEquals(START_VOLUME, ready.volumeNow)
        assertTrue(events.contains("range volume"))
        assertTrue(events.contains("set volume $START_VOLUME"))
    }

    @Test
    fun `another rate is set while the streaming setting is off`() {
        val session = session()
        assertTrue(takeOver(session, plan(48_000)) is DirectUsbSession.Takeover.Ready)
        events.clear()
        val output = session.openOutput(plan(96_000))
        assertTrue(waitUntil { events.contains("alternate 1/1") })
        assertEquals(listOf("alternate 1/0", "set clock 10 96000", "valid 10", "alternate 1/1"), events.filter { it.startsWith("alternate") || it.startsWith("set clock") || it.startsWith("valid") })
        output.release()
    }

    @Test
    fun `startup recovery restores a uac2 device's saved volume`() {
        device.volume = 0
        val markers = FakeMarkers().apply {
            persisted = TakeoverMarker(IDENTITY, volumeBefore = -2_560, mutedBefore = false, controlInterface = 0, volumeUnitId = UNIT)
        }
        val record = UsbTakeoverRecovery(IDENTITY, FakeLink(events, device), listOf(FUNCTION), markers).recover()
        assertEquals(null, record.firstFailure)
        assertTrue(events.contains("set volume -2560"))
        assertEquals(null, markers.persisted)
    }

    private fun session(function: UacFunction = FUNCTION) = DirectUsbSession(
        device = IDENTITY,
        link = FakeLink(events, device),
        function = function,
        markers = FakeMarkers(),
        control = control,
        streams = { _, _ -> UsbStreamFactory.Creation.Created(FakeStream(events)) },
        timing = DirectUsbSession.Timing(monitorPeriodMs = 10, retireTimeoutMs = 150, destroyTimeoutMs = 1, destroyRetryMs = 20),
    )

    private fun takeOver(session: DirectUsbSession, plan: StreamingPlan): DirectUsbSession.Takeover {
        val result = AtomicReference<DirectUsbSession.Takeover>()
        val done = CountDownLatch(1)
        session.takeOver(plan, START_VOLUME) {
            result.set(it)
            done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        return result.get()
    }

    private fun failure(takeover: DirectUsbSession.Takeover): Reason = (takeover as DirectUsbSession.Takeover.Failed).failure.reason

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(200) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private class FakeLink(private val events: MutableList<String>, override val control: UsbControlTransfer) : UsbLink {
        override val fileDescriptor = 42

        override fun claim(interfaceNumber: Int) = true.also { events += "claim $interfaceNumber" }

        override fun release(interfaceNumber: Int) = true.also { events += "release $interfaceNumber" }

        override fun selectAlternate(interfaceNumber: Int, alternateSetting: Int) =
            true.also { events += "alternate $interfaceNumber/$alternateSetting" }

        override fun close() {
            events += "close"
        }
    }

    /** Clock sources 10 and 12, selector 11 and Feature Unit 2 on AudioControl interface 0. */
    private class FakeUac2Device(private val events: MutableList<String>) : UsbControlTransfer {
        @Volatile var rates = OFFERED
        @Volatile var rate = 44_100
        @Volatile var readBack: Int? = null
        @Volatile var refuseRateWrites = false
        @Volatile var valid = true
        @Volatile var selection = 1
        @Volatile var volume = -2_560
        private var muted = false

        override fun transfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            val entity = index shr 8
            val control = value shr 8
            if (requestType == 0x21) {
                val written = (0 until length).fold(0L) { sum, i -> sum or ((buffer[i].toLong() and 0xFF) shl (8 * i)) }
                when {
                    entity in CLOCKS && control == 1 -> {
                        if (refuseRateWrites) return -1
                        rate = written.toInt()
                        events += "set clock $entity $rate"
                    }
                    entity == UNIT && control == 2 -> {
                        volume = written.toInt().toShort().toInt()
                        events += "set volume $volume"
                    }
                    entity == UNIT && control == 1 -> muted = written != 0L
                    else -> return -1
                }
                return length
            }
            val answer: ByteArray = when {
                request == RANGE && entity in CLOCKS -> range(rates.map { listOf(it.minimum, it.maximum, it.resolution) }, 4)
                request == RANGE && entity == UNIT -> {
                    events += "range volume"
                    range(listOf(listOf(-15_360, 0, 256)), 2)
                }
                entity in CLOCKS && control == 1 -> bytes((readBack ?: rate).toLong(), 4)
                entity in CLOCKS && control == 2 -> {
                    events += "valid $entity"
                    bytes(if (valid) 1 else 0, 1)
                }
                entity == SELECTOR -> bytes(selection.toLong(), 1)
                entity == UNIT && control == 2 -> bytes(volume.toLong(), 2)
                entity == UNIT && control == 1 -> bytes(if (muted) 1 else 0, 1)
                else -> return -1
            }
            val sent = minOf(length, answer.size)
            answer.copyInto(buffer, 0, 0, sent)
            return sent
        }

        private fun range(triplets: List<List<Int>>, size: Int): ByteArray =
            bytes(triplets.size.toLong(), 2) + triplets.flatten().fold(ByteArray(0)) { all, part -> all + bytes(part.toLong(), size) }

        private fun bytes(value: Long, size: Int) = ByteArray(size) { (value shr (8 * it)).toByte() }
    }

    private class FakeStream(private val events: MutableList<String>) : UsbStream {
        private var requested = 0L

        override fun start() = true.also { events += "stream started" }

        override fun write(epoch: Long, buffer: ByteBuffer, offset: Int, size: Int) = size

        override fun setRunning(epoch: Long, running: Boolean) = Unit

        override fun markEndOfStream(epoch: Long) = Unit

        @Synchronized override fun retireOutput() = ++requested

        @Synchronized override fun readStats() = UsbStreamStats(
            mediaFramesWritten = 0, mediaFramesSubmitted = 0, mediaFramesProcessed = 0, mediaFramesDelivered = 0,
            mediaFramesLost = 0, silenceFramesInserted = 0, usbFramesDelivered = 0, underruns = 0, urbsCompleted = 0,
            packetsFailed = 0, failure = UsbStreamStats.Failure.NONE, failureErrno = 0, threadNice = 0,
            threadSchedulerPolicy = 0, epoch = requested, requestedEpoch = requested,
        )

        override fun destroy(timeoutMs: Int) = true.also { events += "stream destroyed" }
    }

    private class FakeMarkers : TakeoverMarkerStore() {
        var persisted: TakeoverMarker? = null
        override fun read() = persisted
        override fun hasPendingRecovery() = persisted != null
        override fun write(marker: TakeoverMarker) = true.also { persisted = marker }
        override fun clear() = true.also { persisted = null }
    }

    private companion object {
        const val START_VOLUME = -7_680
        val IDENTITY = UsbDeviceIdentity(deviceId = 2001, vendorId = 0x2fc6, productId = 0xf06a)
        const val UNIT = 2
        const val SELECTOR = 11
        const val RANGE = 0x02
        val CLOCKS = setOf(10, 12)
        val OFFERED = listOf(UacRateRange(44_100, 44_100, 0), UacRateRange(48_000, 192_000, 48_000))

        fun source(id: Int, frequency: UacControlAccess = UacControlAccess.WRITABLE) =
            UacClockEntity.Source(id, UacClockEntity.SourceType.INTERNAL_PROGRAMMABLE, frequency, UacControlAccess.READ_ONLY)

        /** USB streaming input terminal 1, Feature Unit 2 and a headphone output terminal 3 on one clock. */
        fun function(clocks: List<UacClockEntity> = listOf(source(10)), clockId: Int = 10) = UacFunction(
            version = UacVersion.UAC2,
            interfaceProtocol = 0x20,
            controlInterface = 0,
            inputTerminals = listOf(UacInputTerminal(id = 1, type = 0x0101, clockId = clockId)),
            outputTerminals = listOf(UacOutputTerminal(id = 3, type = 0x0302, sourceId = UNIT, clockId = clockId)),
            featureUnits = listOf(UacFeatureUnit(UNIT, 1, UacControlAccess.WRITABLE, UacControlAccess.WRITABLE, emptyList())),
            otherUnits = emptyList(),
            clockEntities = clocks,
            streamingInterfaces = listOf(
                UacStreamingInterface(
                    1,
                    listOf(
                        UacAltSetting(
                            version = UacVersion.UAC2,
                            alternateSetting = 1,
                            terminalLink = 1,
                            format = UacFormat(UacPcmKind.LINEAR_PCM, true, 2, 2, 16, SampleRates.RuntimeUnknown),
                            dataEndpoint = UacEndpoint(0x01, true, UacSyncType.ADAPTIVE, UacEndpointUsage.DATA, 192, 0, 1, null, 0),
                            feedbackEndpoint = null,
                            endpointSetsSampleRate = false,
                        ),
                    ),
                ),
            ),
        )

        val FUNCTION = function()

        fun plan(rate: Int, function: UacFunction = FUNCTION): StreamingPlan =
            (StreamingPlanner.plan(function, UsbBusSpeed.HIGH, rate, 16) as StreamingPlanResult.Planned).plan
    }
}
