package io.github.sumirenokai.vesqen.usb.engine

import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JVM that runs these tests has no libvesqen_usb, like a device whose APK lacks its ABI. */
class UsbStreamEngineTest {
    @Test
    fun aLibraryThatDoesNotLoadIsReportedWithTheLoadersReason() {
        val creation = UsbStreamEngine.create(fileDescriptor = -1, plan = PLAN)

        assertTrue("$creation", creation is UsbStreamEngine.Creation.LibraryUnavailable)
        assertTrue((creation as UsbStreamEngine.Creation.LibraryUnavailable).reason, creation.reason.contains("vesqen_usb"))
    }

    @Test
    fun theSpeedQueryReportsTheMissingLibraryInsteadOfAnUnknownSpeed() {
        val speed = UsbStreamEngine.busSpeed(fileDescriptor = -1)

        assertTrue("$speed", speed is UsbStreamEngine.SpeedQuery.LibraryUnavailable)
        assertTrue((speed as UsbStreamEngine.SpeedQuery.LibraryUnavailable).reason, speed.reason.contains("vesqen_usb"))
    }

    private companion object {
        val PLAN = StreamingPlan(
            interfaceNumber = 2,
            alternateSetting = 1,
            endpointAddress = 3,
            endpointSetsSampleRate = true,
            sampleRateHz = 48_000,
            channels = 2,
            subslotBytes = 2,
            bitResolution = 16,
            serviceIntervalUs = 1_000,
            maxPacketBytes = 192,
            packetsPerUrb = 32,
            urbCount = 3,
            ringFrames = 24_000,
        )
    }
}
