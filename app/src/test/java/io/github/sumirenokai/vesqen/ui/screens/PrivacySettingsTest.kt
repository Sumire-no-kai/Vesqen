package io.github.sumirenokai.vesqen.ui.screens

import io.github.sumirenokai.vesqen.reports.DeviceReportFailure
import io.github.sumirenokai.vesqen.service.ServiceState
import io.github.sumirenokai.vesqen.usage.UsageSettingsStatus
import io.github.sumirenokai.vesqen.usage.UsageStatisticsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySettingsTest {
    private val due = UsageStatisticsSnapshot(
        status = UsageSettingsStatus.READY,
        introductionRequired = true,
        endpointConfigured = true,
    )

    @Test fun `the statistics explanation is due only when pings could really be sent`() {
        assertTrue(usageIntroductionDue(due))
        // Without a server Vesqen never sends statistics, so it never asks about them.
        assertFalse(usageIntroductionDue(due.copy(endpointConfigured = false)))
        assertFalse(usageIntroductionDue(due.copy(introductionRequired = false)))
        assertFalse(usageIntroductionDue(due.copy(status = UsageSettingsStatus.LOADING)))
        assertFalse(usageIntroductionDue(due.copy(status = UsageSettingsStatus.STORAGE_UNAVAILABLE)))
        // #96: once the owner retired statistics, nobody is asked about them.
        assertFalse(usageIntroductionDue(due.copy(service = ServiceState.RETIRED)))
        assertTrue(usageIntroductionDue(due.copy(service = ServiceState.PAUSED)))
    }

    @Test fun `every report failure has its own message`() {
        val labels = DeviceReportFailure.entries.map(::reportFailureLabel)
        assertEquals(DeviceReportFailure.entries.size, labels.toSet().size)
    }
}
