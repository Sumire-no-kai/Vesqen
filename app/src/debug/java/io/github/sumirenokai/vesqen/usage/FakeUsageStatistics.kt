package io.github.sumirenokai.vesqen.usage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeUsageStatistics(initial: UsageStatisticsSnapshot = UsageStatisticsSnapshot(status = UsageSettingsStatus.READY)) : UsageStatistics {
    private val mutable = MutableStateFlow(initial)
    override val snapshot = mutable.asStateFlow()
    fun emit(value: UsageStatisticsSnapshot) { mutable.value = value }
    override fun completeIntroduction(enabled: Boolean) {
        setEnabled(enabled)
        // As DefaultUsageStatistics: confirming the default in a default-on region is not consent.
        val consent = if (enabled && mutable.value.regionPolicy == UsageRegionPolicy.DEFAULT_ENABLED) {
            UsageConsent.DEFAULT_ENABLED
        } else {
            mutable.value.consent
        }
        mutable.value = mutable.value.copy(consent = consent, introductionRequired = false)
    }
    override fun setEnabled(enabled: Boolean) {
        mutable.value = mutable.value.copy(enabled = enabled, consent = if (enabled) UsageConsent.ACCEPTED else UsageConsent.DECLINED)
    }
}
