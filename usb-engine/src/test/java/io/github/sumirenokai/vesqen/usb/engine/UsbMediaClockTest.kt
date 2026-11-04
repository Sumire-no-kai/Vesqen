package io.github.sumirenokai.vesqen.usb.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbMediaClockTest {
    private val clock = UsbMediaClock(48_000)
    private val millisecond = 1_000_000L

    @Test
    fun `nothing has played before any media is submitted`() {
        assertEquals(0, clock.positionFrames(processed = 0, submitted = 0, running = true, nowNanos = 5 * millisecond))
    }

    @Test
    fun `time fills in between completed URBs`() {
        clock.positionFrames(processed = 1536, submitted = 6144, running = true, nowNanos = 0)
        assertEquals(1536 + 480L, clock.positionFrames(1536, 6144, true, 10 * millisecond))
    }

    @Test
    fun `time cannot pass what was submitted`() {
        clock.positionFrames(1536, 3072, true, 0)
        assertEquals(3072, clock.positionFrames(1536, 3072, true, 1000 * millisecond))
    }

    @Test
    fun `a completed URB moves the base`() {
        clock.positionFrames(0, 6144, true, 0)
        assertEquals(1536, clock.positionFrames(1536, 6144, true, 1 * millisecond))
        assertEquals(1536 + 48L, clock.positionFrames(1536, 6144, true, 2 * millisecond))
    }

    @Test
    fun `it never goes back when a URB completes late`() {
        clock.positionFrames(0, 6144, true, 0)
        assertEquals(2400, clock.positionFrames(0, 6144, true, 50 * millisecond))
        assertEquals(2400, clock.positionFrames(1536, 6144, true, 51 * millisecond))
    }

    @Test
    fun `paused time does not count, but URBs still in flight do`() {
        clock.positionFrames(1536, 6144, true, 0)
        assertEquals(1536, clock.positionFrames(1536, 6144, false, 100 * millisecond))
        assertEquals(1536, clock.positionFrames(1536, 6144, false, 200 * millisecond))
        assertEquals(3072, clock.positionFrames(3072, 6144, false, 230 * millisecond))
    }

    @Test
    fun `time counts again from the moment it resumes`() {
        clock.positionFrames(1536, 6144, false, 0)
        assertEquals(1536, clock.positionFrames(1536, 6144, true, 1000 * millisecond))
        assertEquals(1536 + 480L, clock.positionFrames(1536, 6144, true, 1010 * millisecond))
    }
}
