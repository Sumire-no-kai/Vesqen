package io.github.sumirenokai.vesqen.support

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * #151: now and then the GitHub build asks whether the listener wants to support development, and
 * says what the support pays for. Only how often it asked and the answer are kept, on this phone.
 */
interface SupportPrompt {
    /** True when the app open that called [check] should ask. */
    val due: StateFlow<Boolean>

    /** Decides again, off the main thread; called each time the app's screen is created. */
    fun check()

    /** The question is on screen. "Later", Back and closing it need nothing more. */
    fun markShown()

    /** "Support" or "Don't ask again": the question never comes back. */
    fun finish()
}

internal data class SupportPromptRecord(
    val asks: Int = 0,
    val lastAskedAtEpochMs: Long = 0,
    val finished: Boolean = false,
)

internal interface SupportPromptStore {
    fun read(): SupportPromptRecord
    fun write(record: SupportPromptRecord)
}

/** When to ask, as agreed with the owner on 2026-10-20 (#151). */
internal object SupportPromptRules {
    private const val DayMs = 24 * 60 * 60_000L
    const val MinInstalledMs = 7 * DayMs
    const val MinPlays = 30L
    const val AskAgainAfterMs = 60 * DayMs
    const val MaxAsks = 3

    /** The cheap checks come first, so [totalPlays] reads the library only when nothing else says no. */
    fun isDue(record: SupportPromptRecord, installedAtEpochMs: Long, nowEpochMs: Long, totalPlays: () -> Long): Boolean {
        if (record.finished || record.asks >= MaxAsks) return false
        if (nowEpochMs - installedAtEpochMs < MinInstalledMs) return false
        // A clock moved back before the last question counts as too soon, not as a fresh start.
        if (record.asks > 0 && nowEpochMs - record.lastAskedAtEpochMs < AskAgainAfterMs) return false
        return totalPlays() >= MinPlays
    }
}

internal class SupportPromptRuntime(
    private val scope: CoroutineScope,
    private val store: SupportPromptStore,
    private val installedAtEpochMs: () -> Long,
    private val totalPlays: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SupportPrompt {
    private val mutableDue = MutableStateFlow(false)
    override val due: StateFlow<Boolean> = mutableDue.asStateFlow()
    private val lock = Any()
    /** Bumped when the question is shown or answered; a check that began before then is stale. */
    private var answers = 0

    override fun check() {
        val startedAt = synchronized(lock) { answers }
        scope.launch(dispatcher) {
            val isDue = SupportPromptRules.isDue(store.read(), installedAtEpochMs(), clock(), totalPlays)
            // A check that read the record before the question was shown would set it due again, and
            // the next open in this process would ask within minutes instead of sixty days.
            synchronized(lock) { if (answers == startedAt) mutableDue.value = isDue }
        }
    }

    override fun markShown() {
        settle()
        val record = store.read()
        store.write(record.copy(asks = record.asks + 1, lastAskedAtEpochMs = clock()))
    }

    override fun finish() {
        settle()
        store.write(store.read().copy(finished = true))
    }

    private fun settle() = synchronized(lock) {
        answers++
        mutableDue.value = false
    }
}

internal class AndroidSupportPromptStore(context: Context) : SupportPromptStore {
    private val preferences = context.getSharedPreferences("support_prompt", Context.MODE_PRIVATE)

    override fun read(): SupportPromptRecord = SupportPromptRecord(
        asks = preferences.getInt(Asks, 0).coerceAtLeast(0),
        lastAskedAtEpochMs = preferences.getLong(LastAskedAt, 0),
        finished = preferences.getBoolean(Finished, false),
    )

    override fun write(record: SupportPromptRecord) {
        preferences.edit {
            putInt(Asks, record.asks)
            putLong(LastAskedAt, record.lastAskedAtEpochMs)
            putBoolean(Finished, record.finished)
        }
    }

    private companion object {
        const val Asks = "asks"
        const val LastAskedAt = "last_asked_at"
        const val Finished = "finished"
    }
}
