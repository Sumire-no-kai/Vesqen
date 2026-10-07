package io.github.sumirenokai.vesqen.usage

import io.github.sumirenokai.vesqen.service.ReportService
import io.github.sumirenokai.vesqen.service.ServiceState
import io.github.sumirenokai.vesqen.service.ServiceSwitchGate
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DefaultUsageStatistics(
    private val scope: CoroutineScope,
    private val store: UsagePreferencesStore,
    private val region: () -> UsageRegionPolicy,
    private val endpointConfigured: Boolean,
    private val online: () -> Boolean,
    private val facts: () -> UsageFacts,
    private val transport: UsageTransport,
    private val acceptUpdate: (String) -> Unit = {},
    /** A successful ping without update data: the updater checks on its own instead. */
    private val missingUpdate: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** #96: asked right before each ping. Null only in tests that do not exercise the switch. */
    private val serviceSwitch: ServiceSwitchGate? = null,
) : UsageStatistics {
    private val mutex = Mutex()
    private val pending = AtomicBoolean(false)
    @Volatile private var preferences: UsagePreferences? = null
    private val mutable = MutableStateFlow(UsageStatisticsSnapshot(endpointConfigured = endpointConfigured))
    override val snapshot = mutable.asStateFlow()

    private val initialization = scope.launch { mutex.withLock { storage {
        val policy = region()
        val loaded = store.read() ?: UsagePreferences(enabled = policy == UsageRegionPolicy.DEFAULT_ENABLED)
        val saved = if (loaded.consent == UsageConsent.UNDECIDED) loaded.copy(introductionCompleted = false) else loaded
        // Entering a consent region cannot carry a default-on choice across the boundary.
        val next = if (policy != UsageRegionPolicy.DEFAULT_ENABLED && saved.consent == UsageConsent.DEFAULT_ENABLED)
            saved.copy(enabled = false, consent = UsageConsent.UNDECIDED, introductionCompleted = false) else saved
        store.write(next)
        preferences = next
        publish(policy)
    } } }

    override fun completeIntroduction(enabled: Boolean) = update(enabled, completeIntroduction = true)
    override fun setEnabled(enabled: Boolean) = update(enabled, completeIntroduction = false)

    private fun update(enabled: Boolean, completeIntroduction: Boolean) {
        scope.launch { initialization.join(); mutex.withLock { storage {
            val saved = preferences ?: return@storage
            val policy = region()
            val next = saved.copy(enabled = enabled,
                consent = if (!enabled) UsageConsent.DECLINED else if (policy == UsageRegionPolicy.DEFAULT_ENABLED && completeIntroduction)
                    UsageConsent.DEFAULT_ENABLED else UsageConsent.ACCEPTED,
                introductionCompleted = saved.introductionCompleted || completeIntroduction)
            store.write(next)
            preferences = next
            publish(policy)
        } } }
    }

    /** Called through the updater's foreground hook. True suppresses its duplicate manifest GET. */
    suspend fun onForeground(): Boolean {
        initialization.join()
        // A report upload may have read the switch since; show its current word on usage too.
        refreshService()
        val state = mutable.value
        if (state.status != UsageSettingsStatus.READY || !state.enabled || state.introductionRequired ||
            !endpointConfigured || !online()) return false
        // Retired by the owner: no attempt and no switch reading in this app version (#96).
        if (serviceSwitch?.known?.value?.usagePings == ServiceState.RETIRED) return false
        if (pending.get()) return true
        if (!usageAttemptDue(clock(), preferences?.lastAttemptEpochMs)) return false
        if (!pending.compareAndSet(false, true)) return true
        scope.launch {
            try {
                val ping = mutex.withLock { storage {
                    var saved = preferences ?: return@storage null
                    val policy = region()
                    if (policy != UsageRegionPolicy.DEFAULT_ENABLED && saved.consent != UsageConsent.ACCEPTED) {
                        saved = saved.copy(enabled = false, introductionCompleted = false, consent = UsageConsent.UNDECIDED)
                        store.write(saved); preferences = saved; publish(policy); return@storage null
                    }
                    val now = clock()
                    if (!saved.enabled || !saved.introductionCompleted || !online() || !usageAttemptDue(now, saved.lastAttemptEpochMs)) return@storage null
                    val prepared = usagePing(facts(), now, saved)
                    // Reserve before HTTP: connection loss/ack loss/process death cannot cause daily retries.
                    val reserved = saved.copy(lastAttemptEpochMs = now)
                    store.write(reserved); preferences = reserved
                    prepared
                } }
                if (ping != null && mutable.value.enabled && mutable.value.status == UsageSettingsStatus.READY) {
                    // The switch can only stop the ping. Paused, retired or unreadable: nothing is sent,
                    // and the updater checks on its own as it does when statistics are off.
                    if (serviceSwitch != null && serviceSwitch.check(ReportService.USAGE_PINGS).also { refreshService() } != ServiceState.ENABLED) {
                        missingUpdate()
                        return@launch
                    }
                    var failed = false
                    // A failed ping skips today's automatic update check (#78): the ping was that
                    // day's only request. A successful one without update data hands it back.
                    val manifest = try { transport.send(ping) }
                    catch (_: IOException) { null.also { failed = true } /* No retry, logging or playback dependency. */ }
                    // OEM per-app firewalls surface as SecurityException from DNS, not IOException.
                    catch (_: SecurityException) { null.also { failed = true } }
                    if (manifest != null) acceptUpdate(manifest) else if (!failed) missingUpdate()
                }
            } finally { pending.set(false) }
        }
        return true
    }

    fun recordUsbAudioConnection() {
        scope.launch { initialization.join(); mutex.withLock { storage {
            val saved = preferences ?: return@storage
            if (!saved.enabled) return@storage
            val next = saved.copy(lastUsbEpochMs = clock())
            store.write(next); preferences = next
        } } }
    }

    private fun publish(policy: UsageRegionPolicy) {
        val saved = requireNotNull(preferences)
        mutable.value = UsageStatisticsSnapshot(UsageSettingsStatus.READY, saved.enabled, saved.consent,
            policy, !saved.introductionCompleted, endpointConfigured, serviceSwitch?.known?.value?.usagePings)
    }
    /** No collector: the switch changes only on a reading, and each reading is followed by this. */
    private fun refreshService() {
        val service = serviceSwitch?.known?.value?.usagePings
        if (mutable.value.service != service) mutable.value = mutable.value.copy(service = service)
    }

    private inline fun <T> storage(block: () -> T): T? = try { block() }
    catch (_: IOException) { mutable.value = mutable.value.copy(status = UsageSettingsStatus.STORAGE_UNAVAILABLE); null }
}
