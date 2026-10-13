package io.github.sumirenokai.vesqen.reports

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #113: records an uncaught exception, then always hands it to the handler that was installed
 * before, which on Android logs the crash and ends the process. Nothing is sent from here.
 */
internal class CrashRecordingHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val record: (Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val recorded = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, crash: Throwable) {
        try {
            // One record per process: the first crash ends it, and a second thread failing while
            // that happens adds nothing new.
            if (recorded.compareAndSet(false, true)) record(crash)
        } catch (failure: Throwable) {
            // Recording must never replace or hide the crash. The failure goes along with it, so
            // Android's crash log shows both; any Throwable counts, including running out of memory.
            crash.addSuppressed(failure)
        } finally {
            // Android's runtime always installs a default handler before the app starts.
            previous?.uncaughtException(thread, crash)
        }
    }

    companion object {
        /** The record holds the exception's types and frames, the app version and the time; the thread is only passed on. */
        fun install(directory: () -> File, appVersion: String, versionCode: Int, clock: () -> Long = System::currentTimeMillis) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashRecordingHandler(previous) { crash ->
                val now = clock()
                val id = CrashReportStore.recordId(now)
                CrashReportStore(directory()).write(CrashCapture.record(crash, now, appVersion, versionCode, id))
            })
        }
    }
}
