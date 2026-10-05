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

/**
 * At most one attempt per UTC day, the server's day: a rolling 24 hours would skip a person who
 * opens the app a little earlier each day. A clock once set far ahead must not block until real
 * time catches up with the stamp it left.
 */
internal fun usageAttemptDue(now: Long, last: Long?): Boolean {
    if (now < 0) return false
    if (last == null || last > now + USAGE_DAY_MS) return true
    return now >= last && Math.floorDiv(now, USAGE_DAY_MS) != Math.floorDiv(last, USAGE_DAY_MS)
}

/**
 * Device strings in the server's accepted alphabet (letters, digits, space and "._()+-", at most
 * 160), so an unusual ROM string is still counted. Self-built ROMs put the builder's login after
 * "eng." or "userdebug."; it is dropped.
 */
internal fun usageText(raw: String): String = raw
    .replace(Regex("(?i)\\b(eng|userdebug)\\.[A-Za-z0-9_-]+"), "$1")
    .map { if (it.isLetterOrDigit() || it in " ._()+-") it else '_' }
    .joinToString("").trim().take(160).ifEmpty { "unknown" }

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
