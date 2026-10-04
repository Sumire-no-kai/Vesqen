package io.github.sumirenokai.vesqen.usage

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UsageStatisticsTest {
    @Test fun regionsAreCentralizedAndEitherSystemOrSimCanRequireConsent() {
        assertEquals(31, UsageRegionRules.explicitConsentCountries.size)
        UsageRegionRules.explicitConsentCountries.forEach { country ->
            assertEquals(UsageRegionPolicy.EXPLICIT_CONSENT, UsageRegionRules.evaluate(listOf(country.lowercase()), listOf("US")))
            assertEquals(UsageRegionPolicy.EXPLICIT_CONSENT, UsageRegionRules.evaluate(listOf("US"), listOf(country)))
        }
        assertEquals(UsageRegionPolicy.EXPLICIT_CONSENT, UsageRegionRules.evaluate(listOf("uk"), emptyList()))
        assertEquals(UsageRegionPolicy.DEFAULT_ENABLED, UsageRegionRules.evaluate(listOf("AU"), emptyList()))
        assertEquals(UsageRegionPolicy.REGION_UNAVAILABLE, UsageRegionRules.evaluate(listOf("", "invalid"), emptyList()))
        assertEquals(UsageRegionPolicy.REGION_UNAVAILABLE, UsageRegionRules.evaluate(listOf("US"), listOf("US"), false))
        listOf("CH", "KR", "BR", "US", "CN").forEach { assertFalse(UsageRegionRules.explicitConsentCountries.contains(it)) }
    }

    @Test fun utcIsoWeekMonthAndYearBoundariesOnlyProduceBooleans() {
        val now = at("2027-01-01T12:00:00Z")
        val ping = usagePing(facts, now, ready.copy(lastAttemptEpochMs = at("2026-12-31T12:00:00Z")))
        assertTrue(ping.firstToday); assertFalse(ping.firstThisWeek); assertTrue(ping.firstThisMonth)
        assertTrue(usagePing(facts, at("2027-01-04T00:00:00Z"), ready.copy(lastAttemptEpochMs = now)).firstThisWeek)
        val sameDay = usagePing(facts, now, ready.copy(lastAttemptEpochMs = now - 1000))
        assertFalse(sameDay.firstToday); assertFalse(sameDay.firstThisWeek); assertFalse(sameDay.firstThisMonth)
        assertFalse(usageAttemptDue(now, now + 1000))
        assertFalse(usageAttemptDue(now, now - USAGE_DAY_MS + 1))
        assertTrue(usageAttemptDue(now, now - USAGE_DAY_MS))
    }

    @Test fun payloadHasOnlyReviewedFieldsAndRecentUsbExpires() {
        val ping = usagePing(facts, RECENT_USB_MS + 100, ready.copy(lastUsbEpochMs = 100))
        assertTrue(ping.recentUsbAudio)
        assertFalse(usagePing(facts, RECENT_USB_MS + 101, ready.copy(lastUsbEpochMs = 100)).recentUsbAudio)
        assertFalse(usagePing(facts, 99, ready.copy(lastUsbEpochMs = 100)).recentUsbAudio)
        assertEquals(setOf("schemaVersion", "appVersion", "channel", "androidVersion", "manufacturer", "model", "romBuild", "bitPerfectMixer", "recentUsbAudio", "firstToday", "firstThisWeek", "firstThisMonth"), ping.fields().keys)
        assertNull(ping.fields()["bitPerfectMixer"])
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
        val success = DefaultUsageStatistics(CoroutineScope(coroutineContext + Dispatchers.Unconfined), MemoryStore(ready), { UsageRegionPolicy.DEFAULT_ENABLED }, true,
            { true }, { facts }, UsageTransport { "manifest" }, { accepted = it }, { USAGE_DAY_MS * 10 })
        success.onForeground()
        assertEquals("manifest", accepted)
    }

    @Test fun fakeExposesSelectionsWithoutStorageOrNetwork() {
        val fake = FakeUsageStatistics()
        fake.completeIntroduction(false)
        assertFalse(fake.snapshot.value.enabled)
        assertFalse(fake.snapshot.value.introductionRequired)
        fake.setEnabled(true)
        assertEquals(UsageConsent.ACCEPTED, fake.snapshot.value.consent)
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
    private val facts = UsageFacts("1.0.0-beta.2", "github", "16", "Example", "Model", "Build", null)
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
}
