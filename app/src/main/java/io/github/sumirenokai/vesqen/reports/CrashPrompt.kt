package io.github.sumirenokai.vesqen.reports

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #113: after a crash or an ANR, the next app open asks once whether to send a report. Nothing is
 * sent from here: "Review and send" only opens the device report, where the user previews and sends.
 */
interface CrashPrompt {
    /** True when a crash or ANR happened after the question was last asked. */
    val due: StateFlow<Boolean>

    /** The question is on screen. The crashes it covers are never offered again. */
    fun markShown()

    /** "No thanks": the saved crash records are deleted. */
    fun decline()
}

/**
 * Decides once per process whether the question is due, from Vesqen's own crash records and, from
 * Android 11, Android's records of native crashes and ANRs ([systemCrashTimes], type and time only).
 * Only crashes newer than the last question and inside the seven-day retention count.
 */
internal class CrashReportRuntime(
    private val scope: CoroutineScope,
    private val store: CrashReportStore,
    private val clock: () -> Long = System::currentTimeMillis,
    systemCrashTimes: () -> List<Long>,
) : CrashPrompt {
    private val mutableDue = MutableStateFlow(false)
    override val due: StateFlow<Boolean> = mutableDue.asStateFlow()
    private val availability = AtomicReference(CrashHistoryAvailability.AVAILABLE)
    private val pendingThrough = AtomicReference<Long?>(null)

    init {
        scope.launch(Dispatchers.IO) {
            storage {
                val now = clock()
                val askedThrough = store.promptedThrough()
                val own = store.read(now).map { it.occurredAtEpochMs }
                val system = systemCrashTimes().filter { it <= now && now - it <= CrashReportStore.MAX_AGE_MS }
                val newest = (own + system).filter { it > askedThrough }.maxOrNull()
                pendingThrough.set(newest)
                mutableDue.value = newest != null
            }
        }
    }

    override fun markShown() {
        mutableDue.value = false
        val through = pendingThrough.getAndSet(null) ?: return
        scope.launch(Dispatchers.IO) { storage { store.setPromptedThrough(through) } }
    }

    override fun decline() {
        markShown()
        scope.launch(Dispatchers.IO) { storage { store.deleteAll() } }
    }

    /** The records a report may include, read when the report is created. */
    suspend fun snapshot(): CrashHistorySnapshot = withContext(Dispatchers.IO) {
        val records = storage { store.read(clock()) }.orEmpty()
        CrashHistorySnapshot(records, availability.get())
    }

    /** A report holding [records] was uploaded, shared or emailed; they have done their job. */
    fun delivered(records: List<CrashRecord>) {
        if (records.isEmpty()) return
        scope.launch(Dispatchers.IO) { storage { store.delete(records.map(CrashRecord::id)) } }
    }

    private inline fun <T> storage(block: () -> T): T? = try {
        block().also {
            availability.compareAndSet(CrashHistoryAvailability.STORAGE_UNAVAILABLE, CrashHistoryAvailability.AVAILABLE)
            if (store.discardedUnreadableRecord) {
                availability.compareAndSet(CrashHistoryAvailability.AVAILABLE, CrashHistoryAvailability.RECORD_DISCARDED)
            }
        }
    } catch (_: IOException) {
        // Reported in the next report's crash section; the question simply is not asked.
        availability.set(CrashHistoryAvailability.STORAGE_UNAVAILABLE)
        null
    }
}
