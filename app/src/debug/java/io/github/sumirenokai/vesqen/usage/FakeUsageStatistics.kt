package io.github.sumirenokai.vesqen.usage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeUsageStatistics(initial: UsageStatisticsSnapshot = UsageStatisticsSnapshot(status = UsageSettingsStatus.READY)) : UsageStatistics {
    private val mutable = MutableStateFlow(initial)
    override val snapshot = mutable.asStateFlow()
    fun emit(value: UsageStatisticsSnapshot) { mutable.value = value }
    override fun completeIntroduction(enabled: Boolean) { setEnabled(enabled); mutable.value = mutable.value.copy(introductionRequired = false) }
    override fun setEnabled(enabled: Boolean) {
        mutable.value = mutable.value.copy(enabled = enabled, consent = if (enabled) UsageConsent.ACCEPTED else UsageConsent.DECLINED)
    }
}
