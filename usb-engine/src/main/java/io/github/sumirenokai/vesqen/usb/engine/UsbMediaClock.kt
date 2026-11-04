package io.github.sumirenokai.vesqen.usb.engine

/**
 * Playback position in media frames. Completed URBs only move it in steps, so time fills in between
 * them. It never passes what has been submitted, so silence cannot advance it, and it never goes back.
 */
class UsbMediaClock(private val sampleRateHz: Int) {
    private var anchorFrames = 0L
    private var anchorNanos = 0L
    private var wasRunning = false
    private var position = 0L

    fun positionFrames(processed: Long, submitted: Long, running: Boolean, nowNanos: Long): Long {
        if (processed != anchorFrames || running != wasRunning) {
            anchorFrames = processed
            anchorNanos = nowNanos
            wasRunning = running
        }
        val elapsed = if (running) (nowNanos - anchorNanos) * sampleRateHz / NANOS_PER_SECOND else 0L
        position = maxOf(position, minOf(anchorFrames + elapsed, submitted))
        return position
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
