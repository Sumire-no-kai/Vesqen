package io.github.sumirenokai.vesqen.usb.engine

import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.plan.UsbBusSpeed
import java.nio.ByteBuffer

/** The order matches `enum Stat` in core/stream_engine.h. */
data class UsbStreamStats(
    val mediaFramesWritten: Long,
    val mediaFramesSubmitted: Long,
    val mediaFramesProcessed: Long,
    val mediaFramesDelivered: Long,
    val mediaFramesLost: Long,
    val silenceFramesInserted: Long,
    val usbFramesDelivered: Long,
    val underruns: Long,
    val urbsCompleted: Long,
    val packetsFailed: Long,
    val failure: Failure,
    val failureErrno: Long,
    val threadNice: Long,
    val threadSchedulerPolicy: Long,
) {
    /** The order matches `enum StreamFailure` in core/stream_engine.h. */
    enum class Failure { NONE, THREAD_START, SUBMIT, DEVICE_GONE, REAP }
}

/**
 * The native data plane. Its thread runs outside the JVM and never calls back, so progress and
 * errors are read through [readStats]. The file descriptor stays with UsbDeviceConnection, which
 * must stay open until [destroy] has returned true.
 */
class UsbStreamEngine private constructor(private var handle: Long) {
    /** Each failure keeps its own reason, so the own output can fail closed and say why (D5). */
    sealed interface Creation {
        data class Created(val engine: UsbStreamEngine) : Creation

        /** The native library did not load on this device, with the loader's message. */
        data class LibraryUnavailable(val reason: String) : Creation

        /** The native side checks the plan and the file descriptor again and refused them. */
        data object PlanRefused : Creation
    }

    private val lock = Any()

    fun start(): Boolean = synchronized(lock) { handle != 0L && nativeStart(handle) }

    /** Takes a direct buffer. Returns the bytes accepted, or -1 once the stream has ended. */
    fun write(buffer: ByteBuffer, offset: Int, size: Int): Int = synchronized(lock) {
        if (handle == 0L) -1 else nativeWrite(handle, buffer, offset, size)
    }

    fun setRunning(running: Boolean) = synchronized(lock) {
        if (handle != 0L) nativeSetRunning(handle, running)
    }

    fun markEndOfStream() = synchronized(lock) {
        if (handle != 0L) nativeMarkEndOfStream(handle)
    }

    fun readStats(): UsbStreamStats? = synchronized(lock) {
        if (handle == 0L) return null
        val values = LongArray(STAT_COUNT)
        nativeReadStats(handle, values)
        var index = 0
        UsbStreamStats(
            mediaFramesWritten = values[index++],
            mediaFramesSubmitted = values[index++],
            mediaFramesProcessed = values[index++],
            mediaFramesDelivered = values[index++],
            mediaFramesLost = values[index++],
            silenceFramesInserted = values[index++],
            usbFramesDelivered = values[index++],
            underruns = values[index++],
            urbsCompleted = values[index++],
            packetsFailed = values[index++],
            failure = UsbStreamStats.Failure.entries[values[index++].toInt()],
            failureErrno = values[index++],
            threadNice = values[index++],
            threadSchedulerPolicy = values[index],
        )
    }

    /**
     * Cancels and reclaims every URB, waiting up to [timeoutMs], and frees the native side. False
     * means URBs are still in the kernel: nothing has been freed, the connection has to stay open,
     * and the call can be repeated. The waiting happens outside the lock, so a writer never blocks on it.
     */
    fun destroy(timeoutMs: Int): Boolean {
        val current = synchronized(lock) { handle }
        if (current == 0L) return true
        if (!nativeStop(current, timeoutMs)) return false
        synchronized(lock) {
            handle = 0L
            nativeDestroy(current)
        }
        return true
    }

    companion object {
        private const val STAT_COUNT = 14

        /** Loaded on first use only (D5), so ordinary playback never depends on it. Null once loaded. */
        private val libraryError: String? by lazy {
            try {
                System.loadLibrary("vesqen_usb")
                null
            } catch (error: UnsatisfiedLinkError) {
                error.message ?: error.toString()
            }
        }

        fun create(fileDescriptor: Int, plan: StreamingPlan): Creation {
            libraryError?.let { return Creation.LibraryUnavailable(it) }
            val handle = nativeCreate(
                fileDescriptor,
                plan.endpointAddress,
                plan.sampleRateHz,
                plan.frameBytes,
                plan.serviceIntervalUs,
                plan.maxPacketBytes,
                plan.packetsPerUrb,
                plan.urbCount,
                plan.ringFrames,
            )
            return if (handle == 0L) Creation.PlanRefused else Creation.Created(UsbStreamEngine(handle))
        }

        fun busSpeed(fileDescriptor: Int): UsbBusSpeed =
            if (libraryError == null) UsbBusSpeed.fromKernel(nativeBusSpeed(fileDescriptor)) else UsbBusSpeed.UNKNOWN

        @JvmStatic
        private external fun nativeBusSpeed(fd: Int): Int

        @JvmStatic
        private external fun nativeCreate(
            fd: Int,
            endpoint: Int,
            sampleRateHz: Int,
            frameBytes: Int,
            serviceIntervalUs: Int,
            maxPacketBytes: Int,
            packetsPerUrb: Int,
            urbCount: Int,
            ringFrames: Int,
        ): Long

        @JvmStatic
        private external fun nativeStart(handle: Long): Boolean

        @JvmStatic
        private external fun nativeWrite(handle: Long, buffer: ByteBuffer, offset: Int, size: Int): Int

        @JvmStatic
        private external fun nativeSetRunning(handle: Long, running: Boolean)

        @JvmStatic
        private external fun nativeMarkEndOfStream(handle: Long)

        @JvmStatic
        private external fun nativeReadStats(handle: Long, out: LongArray)

        @JvmStatic
        private external fun nativeStop(handle: Long, timeoutMs: Int): Boolean

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}
