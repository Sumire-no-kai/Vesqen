package io.github.sumirenokai.vesqen.usb.session

import io.github.sumirenokai.vesqen.usb.engine.UsbStreamStats
import java.nio.ByteBuffer

/**
 * One output's way into a session's stream, for the Media3 output that owns it. It writes under its
 * own epoch, so once it is released, or another output took the stream, nothing it writes is accepted.
 */
interface UsbOutputHandle {
    val epoch: Long

    /**
     * Bytes accepted; 0 while the last output's URBs are coming back, the ring is full, or the session gave the
     * device back on purpose; -1 once this output was released or ended, or the session failed.
     */
    fun write(buffer: ByteBuffer, offset: Int, size: Int): Int

    fun setRunning(running: Boolean)

    fun markEndOfStream()

    /** This output's own counters, or null while the stream has not reached its epoch. */
    fun readStats(): UsbStreamStats?

    /** Why the session stopped, once it has. */
    val failure: DirectUsbFailure?

    /** Ends this output at once. It never waits for URBs, and calling it again does nothing. */
    fun release()
}
