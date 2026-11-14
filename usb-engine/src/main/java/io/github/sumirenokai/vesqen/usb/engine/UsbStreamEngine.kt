package io.github.sumirenokai.vesqen.usb.engine

import io.github.sumirenokai.vesqen.usb.plan.StreamingPlan
import io.github.sumirenokai.vesqen.usb.plan.UsbBusSpeed
import io.github.sumirenokai.vesqen.usb.session.UsbStreamTotals
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
    /** The output the counters above belong to, or -1 while they are reset for the next one. */
    val epoch: Long,
    /** The newest output handed out; it differs from [epoch] until the last one's URBs are back. */
    val requestedEpoch: Long,
    val sessionTotals: UsbStreamTotals = UsbStreamTotals(mediaFramesLost, underruns, packetsFailed, mediaFramesDelivered),
    /** Furthest media-frame position delivered in this epoch, excluding lost and silence-only packets. */
    val lastDeliveredMediaFrame: Long = mediaFramesDelivered,
) {
    /** The order matches `enum StreamFailure` in core/stream_engine.h. */
    enum class Failure { NONE, THREAD_START, SUBMIT, DEVICE_GONE, REAP, FEEDBACK_TIMEOUT, FEEDBACK_OUT_OF_RANGE }
}

/**
 * The native data plane. Its thread runs outside the JVM and never calls back, so progress and
 * errors are read through [readStats]. The file descriptor stays with UsbDeviceConnection, which
 * must stay open until [destroy] has returned true.
 *
 * The stream outlives each output. The first output is [FIRST_EPOCH]; [retireOutput] ends it at once
 * and hands out the next, which can write nothing until the last one's URBs are back (D14).
 */
class UsbStreamEngine private constructor(private var handle: Long) : UsbStream {
    /** Each failure keeps its own reason, so the own output can fail closed and say why (D5). */
    sealed interface Creation {
        data class Created(val engine: UsbStreamEngine) : Creation

        /** The native library did not load on this device, with the loader's message. */
        data class LibraryUnavailable(val reason: String) : Creation

        /** The native side checks the plan and the file descriptor again and refused them. */
        data object PlanRefused : Creation
    }

    /** The speed comes from a native ioctl, so a library that did not load must not read as a slow bus (#276). */
    sealed interface SpeedQuery {
        /** UNKNOWN when the kernel could not tell. */
        data class Answered(val speed: UsbBusSpeed) : SpeedQuery

        data class LibraryUnavailable(val reason: String) : SpeedQuery
    }

    private val lock = Any()

    override fun start(): Boolean = synchronized(lock) { handle != 0L && nativeStart(handle) }

    /**
     * Takes a direct buffer. Returns the bytes accepted: 0 while the ring is full or the last output's
     * URBs are still coming back, -1 for an epoch that is not the newest or a stream that has ended.
     */
    override fun write(epoch: Long, buffer: ByteBuffer, offset: Int, size: Int): Int = synchronized(lock) {
        if (handle == 0L) -1 else nativeWrite(handle, epoch, buffer, offset, size)
    }

    /** Ignored for any epoch but the newest. */
    override fun setRunning(epoch: Long, running: Boolean) = synchronized(lock) {
        if (handle != 0L) nativeSetRunning(handle, epoch, running)
    }

    override fun markEndOfStream(epoch: Long) = synchronized(lock) {
        if (handle != 0L) nativeMarkEndOfStream(handle, epoch)
    }

    /** Ends the current output and returns the next one's epoch, or -1 once destroyed. Never waits for URBs. */
    override fun retireOutput(): Long = synchronized(lock) {
        if (handle == 0L) -1 else nativeRetireOutput(handle)
    }

    private var finalStats: UsbStreamStats? = null

    override fun readStats(): UsbStreamStats? = synchronized(lock) {
        if (handle == 0L) return finalStats
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
            threadSchedulerPolicy = values[index++],
            epoch = values[index++],
            requestedEpoch = values[index++],
            sessionTotals = UsbStreamTotals(values[index++], values[index++], values[index++], values[index++]),
            lastDeliveredMediaFrame = values[index],
        )
    }

    /**
     * Cancels and reclaims every URB, waiting up to [timeoutMs], and frees the native side. False
     * means URBs are still in the kernel: nothing has been freed, the connection has to stay open,
     * and the call can be repeated. The waiting happens outside the lock, so a writer never blocks on it.
     */
    override fun destroy(timeoutMs: Int): Boolean {
        val current = synchronized(lock) { handle }
        if (current == 0L) return true
        if (!nativeStop(current, timeoutMs)) return false
        synchronized(lock) {
            finalStats = readStats()
            handle = 0L
            nativeDestroy(current)
        }
        return true
    }

    companion object {
        const val FIRST_EPOCH = 0L
        private const val STAT_COUNT = 21

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
                plan.feedback?.endpointAddress ?: 0,
                plan.feedback?.maxPacketBytes ?: 0,
                plan.feedback?.serviceIntervalUs ?: 0,
                plan.feedback?.packetsPerUrb ?: 0,
                plan.feedback?.timeoutUs ?: 0,
            )
            return if (handle == 0L) Creation.PlanRefused else Creation.Created(UsbStreamEngine(handle))
        }

        fun busSpeed(fileDescriptor: Int): SpeedQuery = libraryError?.let(SpeedQuery::LibraryUnavailable)
            ?: SpeedQuery.Answered(UsbBusSpeed.fromKernel(nativeBusSpeed(fileDescriptor)))

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
            feedbackEndpoint: Int,
            feedbackMaxPacketBytes: Int,
            feedbackIntervalUs: Int,
            feedbackPacketsPerUrb: Int,
            feedbackTimeoutUs: Int,
        ): Long

        @JvmStatic
        private external fun nativeStart(handle: Long): Boolean

        @JvmStatic
        private external fun nativeWrite(handle: Long, epoch: Long, buffer: ByteBuffer, offset: Int, size: Int): Int

        @JvmStatic
        private external fun nativeSetRunning(handle: Long, epoch: Long, running: Boolean)

        @JvmStatic
        private external fun nativeMarkEndOfStream(handle: Long, epoch: Long)

        @JvmStatic
        private external fun nativeRetireOutput(handle: Long): Long

        @JvmStatic
        private external fun nativeReadStats(handle: Long, out: LongArray)

        @JvmStatic
        private external fun nativeStop(handle: Long, timeoutMs: Int): Boolean

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}
