package io.github.sumirenokai.vesqen.usage

import io.github.sumirenokai.vesqen.service.ServiceState
import kotlinx.coroutines.flow.StateFlow

enum class UsageConsent { UNDECIDED, DEFAULT_ENABLED, ACCEPTED, DECLINED }
enum class UsageRegionPolicy { EXPLICIT_CONSENT, DEFAULT_ENABLED, REGION_UNAVAILABLE }
enum class UsageSettingsStatus { LOADING, READY, STORAGE_UNAVAILABLE }

data class UsageStatisticsSnapshot(
    val status: UsageSettingsStatus = UsageSettingsStatus.LOADING,
    val enabled: Boolean = false,
    val consent: UsageConsent = UsageConsent.UNDECIDED,
    val regionPolicy: UsageRegionPolicy = UsageRegionPolicy.REGION_UNAVAILABLE,
    val introductionRequired: Boolean = true,
    val endpointConfigured: Boolean = false,
    /** #96: the owner's switch as last read. Null until it has been read; the UI then looks normal. */
    val service: ServiceState? = null,
)

/** UI owns explanation/consent copy. Neither toggle nor confirmation causes an immediate upload. */
interface UsageStatistics {
    val snapshot: StateFlow<UsageStatisticsSnapshot>
    fun completeIntroduction(enabled: Boolean)
    /** In a consent region, an explicit true selection records consent; false withdraws it. */
    fun setEnabled(enabled: Boolean)
}
