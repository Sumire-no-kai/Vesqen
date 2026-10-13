package io.github.sumirenokai.vesqen

import io.github.sumirenokai.vesqen.updates.FakeAppUpdater
import io.github.sumirenokai.vesqen.updates.UpdateRuntime
import io.github.sumirenokai.vesqen.usage.DefaultUsageStatistics
import io.github.sumirenokai.vesqen.usage.USAGE_DAY_MS
import io.github.sumirenokai.vesqen.usage.UsageConsent
import io.github.sumirenokai.vesqen.usage.UsageFacts
import io.github.sumirenokai.vesqen.usage.UsagePreferences
import io.github.sumirenokai.vesqen.usage.UsagePreferencesStore
import io.github.sumirenokai.vesqen.usage.UsageRegionPolicy
import io.github.sumirenokai.vesqen.usage.UsageTransport
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** #117 moved "usage ping first, then the update check" into the app's foreground hook. */
class ForegroundTriggerTest {
    @Test fun theUpdateCheckWaitsForTheUsagePingAndIsToldWhetherItsReplyAnswersIt() = runBlocking {
        for (answered in listOf(true, false)) {
            val updates = RecordingUpdates()
            val ping = CompletableDeferred<Boolean>()
            ForegroundTrigger(unconfined(), usage = { ping.await() }, updates = { updates }).activityStarted()
            assertEquals(emptyList<Boolean>(), updates.told)
            ping.complete(answered)
            assertEquals(listOf(answered), updates.told)
        }
    }

    @Test fun onlyTheFirstOfOverlappingWindowsTriggersAndReturningTriggersAgain() = runBlocking {
        var runs = 0
        val trigger = ForegroundTrigger(unconfined(), usage = { runs++; false }, updates = { null })
        trigger.activityStarted()
        trigger.activityStarted()
        trigger.activityStopped()
        assertEquals(1, runs)
        trigger.activityStopped()
        trigger.activityStarted()
        assertEquals(2, runs)
    }

    @Test fun everyForegroundChecksForUpdatesOnceThroughThePingOrOnItsOwn() = runBlocking {
        // Statistics off: the updater checks on its own.
        assertEquals(listOf("own check"), foreground(ready.copy(enabled = false, consent = UsageConsent.DECLINED)))
        // Today's ping was already sent: the updater checks on its own, and its 24-hour rule decides.
        assertEquals(listOf("own check"), foreground(ready.copy(lastAttemptEpochMs = NOW)))
        // The reply carries the version file: no separate request.
        assertEquals(listOf("relayed"), foreground(transport = UsageTransport { "manifest" }))
        // A reply without it hands the check back.
        assertEquals(listOf("handed back"), foreground(transport = UsageTransport { null }))
        // The network dropped after the foreground check, so no ping went out (#118).
        val answers = ArrayDeque(listOf(true, false))
        assertEquals(listOf("handed back"), foreground(online = { answers.removeFirstOrNull() ?: false }))
        // A failed ping was that day's only request (#78).
        assertEquals(emptyList<String>(), foreground(transport = UsageTransport { throw IOException("offline") }))
    }

    @Test fun thePlayBuildSendsTheUsagePingWithoutAnUpdater() = runBlocking {
        var sends = 0
        val usage = statistics(MemoryStore(ready), UsageTransport { sends++; null }, { true }, null)
        ForegroundTrigger(unconfined(), usage = { usage.onForeground() }, updates = { null }).activityStarted()
        assertEquals(1, sends)
    }

    /** Wired like VesqenApplication: the usage task answers through the update runtime. */
    private fun CoroutineScope.foreground(preferences: UsagePreferences = ready, transport: UsageTransport = UsageTransport { null },
        online: () -> Boolean = { true }): List<String> {
        val updates = RecordingUpdates()
        val usage = statistics(MemoryStore(preferences), transport, online, updates)
        ForegroundTrigger(unconfined(), usage = { usage.onForeground() }, updates = { updates }).activityStarted()
        return updates.checks
    }

    private fun CoroutineScope.statistics(store: MemoryStore, transport: UsageTransport, online: () -> Boolean, updates: UpdateRuntime?) =
        DefaultUsageStatistics(unconfined(), store, { UsageRegionPolicy.DEFAULT_ENABLED }, true, online, { facts }, transport,
            acceptUpdate = { updates?.acceptUsageResponse(it) }, missingUpdate = { updates?.checkWithoutUsageResponse() },
            clock = { NOW })

    private fun CoroutineScope.unconfined() = CoroutineScope(coroutineContext + Dispatchers.Unconfined)

    /** What reaches the GitHub build's updater. Each entry in [checks] is one way the day's check happens. */
    private class RecordingUpdates : UpdateRuntime {
        val told = mutableListOf<Boolean>()
        val checks = mutableListOf<String>()
        override val updater = FakeAppUpdater()
        override fun onForeground(usageRequestExpected: Boolean) {
            told += usageRequestExpected
            // True means the usage task answers instead; false is the updater's own manifest request.
            if (!usageRequestExpected) checks += "own check"
        }
        override fun acceptUsageResponse(manifest: String) { checks += "relayed" }
        override fun checkWithoutUsageResponse() { checks += "handed back" }
    }

    private class MemoryStore(private var value: UsagePreferences?) : UsagePreferencesStore {
        override fun read() = value
        override fun write(value: UsagePreferences) { this.value = value }
    }

    private val ready = UsagePreferences(true, UsageConsent.ACCEPTED, true)
    private val facts = UsageFacts("1.0.0-beta.3", "github", "16", "Example", "Model", "Build", null)

    private companion object {
        const val NOW = USAGE_DAY_MS * 10
    }
}
