package io.github.sumirenokai.vesqen.usage

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.WeekFields

internal const val USAGE_DAY_MS = 86_400_000L
internal const val RECENT_USB_MS = 7 * USAGE_DAY_MS

internal data class UsagePreferences(
    val enabled: Boolean,
    val consent: UsageConsent = UsageConsent.UNDECIDED,
    val introductionCompleted: Boolean = false,
    val lastAttemptEpochMs: Long? = null,
    val lastUsbEpochMs: Long? = null,
)
internal interface UsagePreferencesStore {
    fun read(): UsagePreferences?
    /** Durable before HTTP; failure must prevent transmission. Called only on IO. */
    fun write(value: UsagePreferences)
}
internal data class UsageFacts(
    val appVersion: String,
    val channel: String,
    val androidVersion: String,
    val manufacturer: String,
    val model: String,
    val romBuild: String,
    /** Null means not observable, including no eligible output. Never equate API presence with support. */
    val bitPerfectMixer: Boolean?,
)
internal data class UsagePing(val facts: UsageFacts, val recentUsbAudio: Boolean,
    val firstToday: Boolean, val firstThisWeek: Boolean, val firstThisMonth: Boolean) {
    fun fields(): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to 1, "appVersion" to facts.appVersion, "channel" to facts.channel,
        "androidVersion" to facts.androidVersion, "manufacturer" to facts.manufacturer,
        "model" to facts.model, "romBuild" to facts.romBuild, "bitPerfectMixer" to facts.bitPerfectMixer,
        "recentUsbAudio" to recentUsbAudio, "firstToday" to firstToday,
        "firstThisWeek" to firstThisWeek, "firstThisMonth" to firstThisMonth,
    )
}

internal fun usageAttemptDue(now: Long, last: Long?): Boolean = now >= 0 && (last == null ||
    (now >= last && now - last >= USAGE_DAY_MS))

/** UTC and ISO Monday weeks are shared with the server. Only booleans leave the device. */
internal fun usagePing(facts: UsageFacts, now: Long, preferences: UsagePreferences): UsagePing {
    val date = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
    val previous = preferences.lastAttemptEpochMs?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val week = WeekFields.ISO
    val firstWeek = previous == null || date.get(week.weekBasedYear()) != previous.get(week.weekBasedYear()) ||
        date.get(week.weekOfWeekBasedYear()) != previous.get(week.weekOfWeekBasedYear())
    return UsagePing(facts, preferences.lastUsbEpochMs?.let { now >= it && now - it <= RECENT_USB_MS } == true,
        previous != date, firstWeek, previous == null || previous.year != date.year || previous.month != date.month)
}

internal fun interface UsageTransport {
    /** Null is a successful response without update data. No retries or redirects. */
    suspend fun send(ping: UsagePing): String?
}
