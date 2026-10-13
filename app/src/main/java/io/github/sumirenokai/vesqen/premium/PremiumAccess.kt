package io.github.sumirenokai.vesqen.premium

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the paid features may do now (PRD "2026-10-13 收费方案", #119). */
sealed interface PremiumState {
    /** A build that does not charge yet (the betas and 1.x, until 2.0): everything works and no trial is counted. */
    data object NotCharging : PremiumState
    data object TrialNotStarted : PremiumState
    data class Trial(val endsAtEpochMs: Long, val daysLeft: Int) : PremiumState
    data object TrialEnded : PremiumState
    data object Unlocked : PremiumState

    val allowsPaidFeatures: Boolean get() = this != TrialEnded
}

internal data class TrialRecord(val startedAtEpochMs: Long? = null, val latestSeenEpochMs: Long = 0)

internal interface PremiumStore {
    fun readTrial(): TrialRecord
    fun writeTrial(record: TrialRecord)
    fun strictIntroductionShown(): Boolean
    fun markStrictIntroductionShown()
}

/**
 * #119: the trial and paid-feature state, shared by both channels.
 *
 * The trial starts the first time strict USB output actually plays; turning the switch on or a
 * request the system refuses does not start it. It is timed on this device only. The latest time
 * ever seen is kept, so moving the clock back does not extend it; reinstalling resets it, which is
 * an accepted cost of having no account. When [enforced] is false nothing is counted or locked.
 * [isUnlocked] is the channel's purchase (#120, #46); call [refresh] when it changes.
 */
class PremiumAccess internal constructor(
    private val store: PremiumStore,
    private val enforced: Boolean,
    private val isUnlocked: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var trial = store.readTrial()
    private val mutableState = MutableStateFlow(evaluate(clock()))
    private val mutableIntroductionShown = MutableStateFlow(store.strictIntroductionShown())

    val state: StateFlow<PremiumState> = mutableState.asStateFlow()

    /** Whether the one-time explanation shown when strict USB is first turned on was already seen. */
    val strictIntroductionShown: StateFlow<Boolean> = mutableIntroductionShown.asStateFlow()

    @Synchronized
    fun refresh(): PremiumState {
        val now = clock()
        // Only a running trial needs the clock floor; before it starts, nothing depends on time.
        if (enforced && trial.startedAtEpochMs != null && now > trial.latestSeenEpochMs) {
            trial = trial.copy(latestSeenEpochMs = now)
            store.writeTrial(trial)
        }
        return evaluate(now).also { mutableState.value = it }
    }

    /** Asked by the playback service each time it prepares strict output. */
    fun strictOutputAllowed(): Boolean = refresh().allowsPaidFeatures

    /** Strict output reached ACTIVE: the first time starts the trial. */
    @Synchronized
    fun onStrictOutputActive() {
        if (!enforced || isUnlocked() || trial.startedAtEpochMs != null) return
        val now = maxOf(clock(), trial.latestSeenEpochMs)
        trial = TrialRecord(startedAtEpochMs = now, latestSeenEpochMs = now)
        store.writeTrial(trial)
        mutableState.value = evaluate(now)
    }

    @Synchronized
    fun markStrictIntroductionShown() {
        if (mutableIntroductionShown.value) return
        store.markStrictIntroductionShown()
        mutableIntroductionShown.value = true
    }

    private fun evaluate(now: Long): PremiumState {
        if (!enforced) return PremiumState.NotCharging
        if (isUnlocked()) return PremiumState.Unlocked
        val startedAt = trial.startedAtEpochMs ?: return PremiumState.TrialNotStarted
        val endsAt = startedAt + TrialDurationMs
        val left = endsAt - maxOf(now, trial.latestSeenEpochMs)
        if (left <= 0) return PremiumState.TrialEnded
        return PremiumState.Trial(endsAt, ((left + DayMs - 1) / DayMs).toInt())
    }

    companion object {
        const val TrialDays = 7
        private const val DayMs = 24 * 60 * 60_000L
        const val TrialDurationMs = TrialDays * DayMs
    }
}

internal class AndroidPremiumStore(context: Context) : PremiumStore {
    private val preferences = context.getSharedPreferences("premium", Context.MODE_PRIVATE)

    override fun readTrial(): TrialRecord = TrialRecord(
        startedAtEpochMs = preferences.getLong(TrialStarted, -1).takeIf { it >= 0 },
        latestSeenEpochMs = preferences.getLong(LatestSeen, 0).coerceAtLeast(0),
    )

    override fun writeTrial(record: TrialRecord) {
        preferences.edit {
            if (record.startedAtEpochMs == null) remove(TrialStarted) else putLong(TrialStarted, record.startedAtEpochMs)
            putLong(LatestSeen, record.latestSeenEpochMs)
        }
    }

    override fun strictIntroductionShown(): Boolean = preferences.getBoolean(StrictIntroduction, false)

    override fun markStrictIntroductionShown() {
        preferences.edit { putBoolean(StrictIntroduction, true) }
    }

    private companion object {
        const val TrialStarted = "trial_started_at"
        const val LatestSeen = "latest_seen_at"
        const val StrictIntroduction = "strict_introduction_shown"
    }
}
