package io.github.sumirenokai.vesqen.usb.engine

import java.nio.ByteBuffer

/** What a session needs from the native stream: [UsbStreamEngine] on a phone, a fake in tests. */
interface UsbStream {
    fun start(): Boolean

    /** 0 while the ring is full or the last output's URBs are coming back; -1 for a stale epoch or an ended stream. */
    fun write(epoch: Long, buffer: ByteBuffer, offset: Int, size: Int): Int

    fun setRunning(epoch: Long, running: Boolean)

    fun markEndOfStream(epoch: Long)

    /** Ends the current output and returns the next one's epoch, or -1 once destroyed. Never waits. */
    fun retireOutput(): Long

    fun readStats(): UsbStreamStats?

    /** False means URBs are still the kernel's: nothing was freed, and the call can be repeated. */
    fun destroy(timeoutMs: Int): Boolean
}
