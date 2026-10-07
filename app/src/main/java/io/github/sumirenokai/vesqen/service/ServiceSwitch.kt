package io.github.sumirenokai.vesqen.service

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** #96: the network reports the owner can pause remotely. Playback and updates never depend on it. */
enum class ReportService { USAGE_PINGS, REPORT_UPLOADS }

/** One entry of the owner's switch file. Anything the app does not recognise reads as [PAUSED]. */
enum class ServiceState { ENABLED, PAUSED, RETIRED }

data class ServiceStatus(val usagePings: ServiceState, val reportUploads: ServiceState) {
    fun of(service: ReportService): ServiceState = when (service) {
        ReportService.USAGE_PINGS -> usagePings
        ReportService.REPORT_UPLOADS -> reportUploads
    }
}

/** What the app last learned, for the UI. Null means nothing is known, so the service looks normal. */
data class KnownServiceStatus(val usagePings: ServiceState? = null, val reportUploads: ServiceState? = null) {
    fun of(service: ReportService): ServiceState? = when (service) {
        ReportService.USAGE_PINGS -> usagePings
        ReportService.REPORT_UPLOADS -> reportUploads
    }
}

/** Reads the owner's switch file once. Null when it cannot be read: the caller then sends nothing. */
fun interface ServiceSwitchSource {
    suspend fun read(): ServiceStatus?
}

internal data class StoredServiceSwitch(val known: KnownServiceStatus, val versionCode: Long)

internal interface ServiceSwitchStore {
    fun read(): StoredServiceSwitch?
    fun write(value: StoredServiceSwitch)
}

/**
 * Asks the switch right before a send and never on a timer. The switch can only stop sending:
 * callers check their own settings and consent first, and send only on [ServiceState.ENABLED].
 *
 * The file is never read on behalf of a RETIRED service again in this app version, because its
 * settings are hidden and the person could no longer turn the request off; a newer version reads it. One reading is
 * reused for [ReuseMs], so repeated upload attempts do not fetch the file each time.
 */
internal class ServiceSwitchGate(
    private val source: ServiceSwitchSource,
    private val store: ServiceSwitchStore,
    private val versionCode: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var reading: Pair<Long, ServiceStatus>? = null
    private val mutable = MutableStateFlow(
        store.read()?.takeIf { it.versionCode == versionCode }?.known ?: KnownServiceStatus(),
    )
    val known: StateFlow<KnownServiceStatus> = mutable.asStateFlow()

    /** The switch for [service] now: null when the file could not be read. */
    suspend fun check(service: ReportService): ServiceState? = mutex.withLock {
        if (mutable.value.of(service) == ServiceState.RETIRED) return@withLock ServiceState.RETIRED
        val now = clock()
        val status = reading?.takeIf { now - it.first in 0 until ReuseMs }?.second
            ?: source.read()?.also { reading = now to it }
            ?: return@withLock null
        val next = KnownServiceStatus(status.usagePings, status.reportUploads)
        if (next != mutable.value) {
            mutable.value = next
            store.write(StoredServiceSwitch(next, versionCode))
        }
        status.of(service)
    }

    companion object {
        const val ReuseMs = 10 * 60_000L
    }
}

/** Remembers the last switch reading so a pause or retirement still shows after a restart. */
internal class AndroidServiceSwitchStore(context: Context) : ServiceSwitchStore {
    private val preferences = context.getSharedPreferences("service_switch", Context.MODE_PRIVATE)

    override fun read(): StoredServiceSwitch? {
        if (!preferences.contains("version")) return null
        fun state(key: String) = preferences.getString(key, null)?.let { stored -> ServiceState.entries.firstOrNull { it.name == stored } }
        return StoredServiceSwitch(KnownServiceStatus(state("usage"), state("reports")), preferences.getLong("version", -1))
    }

    // A lost write only means the UI shows the old state until the next reading.
    override fun write(value: StoredServiceSwitch) {
        preferences.edit {
            putString("usage", value.known.usagePings?.name)
            putString("reports", value.known.reportUploads?.name)
            putLong("version", value.versionCode)
        }
    }
}
