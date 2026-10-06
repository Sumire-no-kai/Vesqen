package io.github.sumirenokai.vesqen.usage

import io.github.sumirenokai.vesqen.service.ServiceState
import io.github.sumirenokai.vesqen.service.ServiceStatus
import io.github.sumirenokai.vesqen.service.ServiceSwitchGate
import io.github.sumirenokai.vesqen.service.ServiceSwitchSource
import io.github.sumirenokai.vesqen.service.ServiceSwitchStore
import io.github.sumirenokai.vesqen.service.StoredServiceSwitch
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UsageStatisticsTest {
    @Test fun regionsAreCentralizedAndAnySignalCanRequireConsent() {
        // EU-27, seven outermost regions with their own codes, the EEA three and the UK.
        assertEquals(38, UsageRegionRules.explicitConsentCountries.size)
        UsageRegionRules.explicitConsentCountries.forEach { country ->
            assertEquals(UsageRegionPolicy.EXPLICIT_CONSENT, UsageRegionRules.evaluate(listOf(country.lowercase(), "US")))
            assertEquals(UsageRegionPolicy.EXPLICIT_CONSENT, UsageRegionRules.evaluate(listOf("US", "CN", country)))
        }
        listOf("RE", "GP", "MQ", "GF", "YT", "MF", "AX").forEach {
            assertEquals(it, UsageRegionPolicy.EXPLICIT_CONSENT, UsageRegionRules.evaluate(listOf(it)))
        }
        assertEquals(UsageRegionPolicy.EXPLICIT_CONSENT, UsageRegionRules.evaluate(listOf("uk")))
        // A dual-SIM phone outside the EU is default-on, like any other phone there.
        assertEquals(UsageRegionPolicy.DEFAULT_ENABLED, UsageRegionRules.evaluate(listOf("CN", "cn", "CN")))
        assertEquals(UsageRegionPolicy.DEFAULT_ENABLED, UsageRegionRules.evaluate(listOf("AU")))
        assertEquals(UsageRegionPolicy.REGION_UNAVAILABLE, UsageRegionRules.evaluate(listOf("", "invalid")))
        assertEquals(UsageRegionPolicy.REGION_UNAVAILABLE, UsageRegionRules.evaluate(emptyList()))
        listOf("CH", "KR", "BR", "US", "CN").forEach { assertFalse(UsageRegionRules.explicitConsentCountries.contains(it)) }
    }

    @Test fun utcIsoWeekMonthAndYearBoundariesOnlyProduceBooleans() {
        val now = at("2027-01-01T12:00:00Z")
        val ping = usagePing(facts, now, ready.copy(lastAttemptEpochMs = at("2026-12-31T12:00:00Z")))
        assertTrue(ping.firstToday); assertFalse(ping.firstThisWeek); assertTrue(ping.firstThisMonth)
        assertTrue(usagePing(facts, at("2027-01-04T00:00:00Z"), ready.copy(lastAttemptEpochMs = now)).firstThisWeek)
        val sameDay = usagePing(facts, now, ready.copy(lastAttemptEpochMs = now - 1000))
        assertFalse(sameDay.firstToday); assertFalse(sameDay.firstThisWeek); assertFalse(sameDay.firstThisMonth)
        // One attempt per UTC day: a different UTC date is due even if less than 24 h passed.
        assertFalse(usageAttemptDue(now, now - 1000))
        assertFalse(usageAttemptDue(now, at("2027-01-01T00:00:00Z")))
        assertTrue(usageAttemptDue(now, at("2026-12-31T23:59:59Z")))
        assertTrue(usageAttemptDue(at("2027-01-02T00:00:01Z"), at("2027-01-01T23:59:00Z")))
        // A slightly ahead stamp waits; one a day or more ahead (clock was wrong) does not.
        assertFalse(usageAttemptDue(now, now + 1000))
        assertTrue(usageAttemptDue(now, now + USAGE_DAY_MS + 1))
    }

    @Test fun deviceStringsAreCleanedForTheServer() {
        assertEquals("PD2171_A_15.0.20.1", usageText("PD2171_A_15.0.20.1"))
        assertEquals("lineage_x-userdebug 14 AP2A eng.20240901", usageText("lineage_x-userdebug 14 AP2A eng.john.20240901"))
        assertEquals("ROM_build_ 1", usageText("ROM/build# 1"))
        assertEquals(160, usageText("x".repeat(300)).length)
        assertEquals("unknown", usageText("  "))
    }

    @Test fun payloadHasOnlyReviewedFieldsAndRecentUsbExpires() {
        val ping = usagePing(facts, RECENT_USB_MS + 100, ready.copy(lastUsbEpochMs = 100))
        assertTrue(ping.recentUsbAudio)
        assertFalse(usagePing(facts, RECENT_USB_MS + 101, ready.copy(lastUsbEpochMs = 100)).recentUsbAudio)
        assertFalse(usagePing(facts, 99, ready.copy(lastUsbEpochMs = 100)).recentUsbAudio)
        assertEquals(setOf("schemaVersion", "appVersion", "channel", "androidVersion", "manufacturer", "model", "romBuild", "bitPerfectMixer", "recentUsbAudio", "firstToday", "firstThisWeek", "firstThisMonth"), ping.fields().keys)
        assertNull(ping.fields()["bitPerfectMixer"])
    }

    @Test fun coldStartForegroundWaitsForPreferencesWithoutLosingItsOneDailyOpportunity() = runBlocking {
        val store = MemoryStore(ready)
        var sends = 0
        val engine = DefaultUsageStatistics(this, store, { UsageRegionPolicy.DEFAULT_ENABLED }, true,
            { true }, { facts }, UsageTransport { sends++; null }, clock = { USAGE_DAY_MS * 10 })
        assertEquals(UsageSettingsStatus.LOADING, engine.snapshot.value.status)
        assertTrue(engine.onForeground())
        yield()
        assertEquals(1, sends)
        assertFalse(engine.onForeground())
    }

    @Test fun defaultOnStillWaitsForFirstIntroductionAndExplicitConsentDefaultsOff() = runBlocking {
        for (policy in UsageRegionPolicy.entries) {
            val store = MemoryStore()
            val engine = engine(store, region = { policy })
            assertEquals(policy == UsageRegionPolicy.DEFAULT_ENABLED, engine.snapshot.value.enabled)
            assertTrue(engine.snapshot.value.introductionRequired)
            assertFalse(engine.onForeground())
            engine.completeIntroduction(true)
            assertTrue(engine.onForeground())
            assertEquals(UsageSettingsStatus.READY, engine.snapshot.value.status)
            assertEquals(if (policy == UsageRegionPolicy.DEFAULT_ENABLED) UsageConsent.DEFAULT_ENABLED else UsageConsent.ACCEPTED, store.value!!.consent)
        }
    }

    @Test fun failureIsSilentAndAttemptReservationSurvivesProcessRecreation() = runBlocking {
        val store = MemoryStore(ready)
        var sends = 0
        val engine = engine(store, transport = UsageTransport { sends++; throw IOException("network") })
        assertTrue(engine.onForeground())
        assertEquals(UsageSettingsStatus.READY, engine.snapshot.value.status)
        assertFalse(engine.onForeground())
        val reopened = engine(store, transport = UsageTransport { sends++; null })
        assertFalse(reopened.onForeground())
        assertEquals(1, sends)
        assertEquals(USAGE_DAY_MS * 10, store.value!!.lastAttemptEpochMs)
    }

    @Test fun offlineUnconfiguredDisabledAndCorruptConsentNeverSendOrReserve() = runBlocking {
        for (mode in 0..3) {
            val store = MemoryStore(if (mode == 2) ready.copy(enabled = false, consent = UsageConsent.DECLINED) else if (mode == 3) ready.copy(consent = UsageConsent.UNDECIDED) else ready)
            var calls = 0
            val engine = engine(store, configured = mode != 1, online = { mode != 0 }, transport = UsageTransport { calls++; null })
            assertFalse(engine.onForeground())
            assertEquals(0, calls)
            assertNull(store.value!!.lastAttemptEpochMs)
        }
    }

    @Test fun withdrawalDoesNotWaitForNetworkAndOnlyOneRequestCanBeInFlight() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val store = MemoryStore(ready)
        var sends = 0
        val engine = engine(store, transport = UsageTransport { sends++; release.await(); null })
        assertTrue(engine.onForeground())
        assertTrue(engine.onForeground())
        engine.setEnabled(false)
        assertFalse(engine.snapshot.value.enabled)
        assertEquals(UsageConsent.DECLINED, store.value!!.consent)
        release.complete(Unit)
        assertEquals(1, sends)
    }

    @Test fun changedRegionRequiresConsentButPreviousExplicitChoiceSurvivesRestart() = runBlocking {
        var policy = UsageRegionPolicy.DEFAULT_ENABLED
        val store = MemoryStore(ready.copy(consent = UsageConsent.DEFAULT_ENABLED))
        val engine = engine(store, region = { policy })
        policy = UsageRegionPolicy.EXPLICIT_CONSENT
        engine.onForeground()
        assertFalse(engine.snapshot.value.enabled)
        assertTrue(engine.snapshot.value.introductionRequired)
        assertNull(store.value!!.lastAttemptEpochMs)
        engine.completeIntroduction(true)
        assertFalse(engine(store, region = { policy }).snapshot.value.introductionRequired)
    }

    @Test fun failedDurableReservationStopsTransmissionAndUpdateManifestUsesExistingSeam() = runBlocking {
        val store = MemoryStore(ready)
        var sent = false
        val engine = engine(store, transport = UsageTransport { sent = true; null })
        store.fail = true
        engine.onForeground()
        assertFalse(sent)
        assertEquals(UsageSettingsStatus.STORAGE_UNAVAILABLE, engine.snapshot.value.status)
        var accepted: String? = null
        var standalone = 0
        val success = DefaultUsageStatistics(CoroutineScope(coroutineContext + Dispatchers.Unconfined), MemoryStore(ready), { UsageRegionPolicy.DEFAULT_ENABLED }, true,
            { true }, { facts }, UsageTransport { "manifest" }, { accepted = it }, { standalone++ }, { USAGE_DAY_MS * 10 })
        success.onForeground()
        assertEquals("manifest", accepted)
        assertEquals(0, standalone)
    }

    @Test fun aSuccessfulPingWithoutUpdateDataHandsTheCheckBack() = runBlocking {
        fun CoroutineScope.run(transport: UsageTransport): Int {
            var standalone = 0
            DefaultUsageStatistics(CoroutineScope(coroutineContext + Dispatchers.Unconfined), MemoryStore(ready), { UsageRegionPolicy.DEFAULT_ENABLED }, true,
                { true }, { facts }, transport, {}, { standalone++ }, { USAGE_DAY_MS * 10 }).also { runBlocking { it.onForeground() } }
            return standalone
        }
        assertEquals(1, run(UsageTransport { null }))
        // A failed ping was the day's only request (#78): no extra update request follows it.
        assertEquals(0, run(UsageTransport { throw IOException("offline") }))
        // OEM firewalls raise SecurityException from DNS; it must not crash playback.
        assertEquals(0, run(UsageTransport { throw SecurityException("Permission denied") }))
    }

    @Test fun theServiceSwitchCanOnlyStopThePingAndHandsTheUpdateCheckBack() = runBlocking {
        fun CoroutineScope.run(status: ServiceStatus?, preferences: UsagePreferences = ready): Triple<Int, Int, Int> {
            var sends = 0
            var standalone = 0
            val source = CountingSource(status)
            DefaultUsageStatistics(CoroutineScope(coroutineContext + Dispatchers.Unconfined), MemoryStore(preferences),
                { UsageRegionPolicy.DEFAULT_ENABLED }, true, { true }, { facts }, UsageTransport { sends++; null }, {},
                { standalone++ }, { USAGE_DAY_MS * 10 }, ServiceSwitchGate(source, MemorySwitchStore(), 12),
            ).also { runBlocking { it.onForeground() } }
            return Triple(sends, standalone, source.reads)
        }
        // Enabled: the ping goes out, then hands the update check back as before.
        assertEquals(Triple(1, 1, 1), run(switch(ServiceState.ENABLED)))
        // Paused, retired or unreadable: nothing is sent, and the updater checks on its own.
        listOf(switch(ServiceState.PAUSED), switch(ServiceState.RETIRED), null).forEach { assertEquals(Triple(0, 1, 1), run(it)) }
        // Statistics turned off: the switch file is never read.
        assertEquals(Triple(0, 0, 0), run(switch(ServiceState.ENABLED), ready.copy(enabled = false, consent = UsageConsent.DECLINED)))
    }

    @Test fun aRetiredPingLeavesTheUiAndIsNotAttemptedAgain() = runBlocking {
        var now = USAGE_DAY_MS * 10
        val source = CountingSource(switch(ServiceState.RETIRED))
        var sends = 0
        val engine = DefaultUsageStatistics(CoroutineScope(coroutineContext + Dispatchers.Unconfined), MemoryStore(ready),
            { UsageRegionPolicy.DEFAULT_ENABLED }, true, { true }, { facts }, UsageTransport { sends++; null },
            clock = { now }, serviceSwitch = ServiceSwitchGate(source, MemorySwitchStore(), 12))
        engine.onForeground()
        yield()
        assertEquals(ServiceState.RETIRED, engine.snapshot.value.service)
        now += USAGE_DAY_MS
        assertFalse(engine.onForeground())
        assertEquals(1, source.reads)
        assertEquals(0, sends)
    }

    @Test fun fakeExposesSelectionsWithoutStorageOrNetwork() {
        val fake = FakeUsageStatistics()
        fake.completeIntroduction(false)
        assertFalse(fake.snapshot.value.enabled)
        assertFalse(fake.snapshot.value.introductionRequired)
        fake.setEnabled(true)
        assertEquals(UsageConsent.ACCEPTED, fake.snapshot.value.consent)
        val defaultOn = FakeUsageStatistics(UsageStatisticsSnapshot(UsageSettingsStatus.READY, regionPolicy = UsageRegionPolicy.DEFAULT_ENABLED))
        defaultOn.completeIntroduction(true)
        assertEquals(UsageConsent.DEFAULT_ENABLED, defaultOn.snapshot.value.consent)
    }

    private fun CoroutineScope.engine(store: MemoryStore, region: () -> UsageRegionPolicy = { UsageRegionPolicy.DEFAULT_ENABLED },
        configured: Boolean = true, online: () -> Boolean = { true }, transport: UsageTransport = UsageTransport { null }) =
        DefaultUsageStatistics(CoroutineScope(coroutineContext + Dispatchers.Unconfined), store, region, configured, online, { facts }, transport, clock = { USAGE_DAY_MS * 10 })
    private class MemoryStore(var value: UsagePreferences? = null) : UsagePreferencesStore {
        var fail = false
        override fun read() = value
        override fun write(value: UsagePreferences) { if (fail) throw IOException("storage"); this.value = value }
    }
    private val ready = UsagePreferences(true, UsageConsent.ACCEPTED, true)
    private fun switch(usage: ServiceState) = ServiceStatus(usage, ServiceState.ENABLED)
    private class CountingSource(private val status: ServiceStatus?) : ServiceSwitchSource {
        var reads = 0
        override suspend fun read(): ServiceStatus? { reads++; return status }
    }
    private class MemorySwitchStore : ServiceSwitchStore {
        private var value: StoredServiceSwitch? = null
        override fun read() = value
        override fun write(value: StoredServiceSwitch) { this.value = value }
    }
    private val facts = UsageFacts("1.0.0-beta.2", "github", "16", "Example", "Model", "Build", null)
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
}
