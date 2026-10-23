package io.github.sumirenokai.vesqen.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VesqenNavigationStateTest {
    @Test
    fun `back from full player returns to library`() {
        val now = VesqenNavigationState().selectTopLevel(VesqenDestination.NOW)

        assertEquals(VesqenDestination.LIBRARY, now.back().destination)
    }

    @Test
    fun `full player opened from settings returns to settings`() {
        val now = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .selectTopLevel(VesqenDestination.NOW)

        assertEquals(VesqenDestination.SETTINGS, now.back().destination)
    }

    @Test
    fun `chain opened in player preserves the player origin`() {
        val chain = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .selectTopLevel(VesqenDestination.NOW)
            .openChain()

        val now = chain.back()
        assertEquals(VesqenDestination.NOW, now.destination)
        assertEquals(VesqenDestination.SETTINGS, now.back().destination)
    }

    @Test
    fun `back from chain opened in player returns to player then library`() {
        val chain = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.NOW)
            .openChain()

        val now = chain.back()
        assertEquals(VesqenDestination.NOW, now.destination)
        assertEquals(VesqenDestination.LIBRARY, now.back().destination)
    }

    @Test
    fun `chain opened from settings returns to settings then library`() {
        val chain = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .openChain()

        val settings = chain.back()
        assertEquals(VesqenDestination.SETTINGS, settings.destination)
        assertEquals(VesqenDestination.LIBRARY, settings.back().destination)
    }

    @Test
    fun `about opened from settings returns to settings then library`() {
        val about = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .openAbout()

        val settings = about.back()
        assertEquals(VesqenDestination.SETTINGS, settings.destination)
        assertEquals(VesqenDestination.LIBRARY, settings.back().destination)
    }

    @Test
    fun `pages opened from settings return to settings then library`() {
        listOf(
            VesqenNavigationState::openLicenses,
            VesqenNavigationState::openPrivacyPolicy,
            VesqenNavigationState::openUsageStatistics,
            VesqenNavigationState::openDeviceReport,
        ).forEach { open ->
            val detail = open(VesqenNavigationState().selectTopLevel(VesqenDestination.SETTINGS))
            assertTrue(detail.destination.isSecondaryDetail)
            val settings = detail.back()
            assertEquals(VesqenDestination.SETTINGS, settings.destination)
            assertEquals(VesqenDestination.LIBRARY, settings.back().destination)
        }
    }

    @Test
    fun `device report opened from the crash question returns to where it was asked`() {
        val report = VesqenNavigationState().openDeviceReport()
        assertEquals(VesqenDestination.DEVICE_REPORT, report.destination)
        assertEquals(VesqenDestination.LIBRARY, report.back().destination)
        assertEquals(report, report.openDeviceReport())
    }

    @Test
    fun `device report opened over two details opens from settings`() {
        val report = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .openAbout()
            .openPrivacyPolicy()
            .openDeviceReport()

        assertEquals(VesqenDestination.DEVICE_REPORT, report.destination)
        val settings = report.back()
        assertEquals(VesqenDestination.SETTINGS, settings.destination)
        assertEquals(VesqenDestination.LIBRARY, settings.back().destination)
    }

    @Test
    fun `privacy policy opened from about unwinds through about and settings`() {
        val privacy = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .openAbout()
            .openPrivacyPolicy()

        val about = privacy.back()
        assertEquals(VesqenDestination.ABOUT, about.destination)
        val settings = about.back()
        assertEquals(VesqenDestination.SETTINGS, settings.destination)
        assertEquals(VesqenDestination.LIBRARY, settings.back().destination)
    }

    @Test
    fun `details nest at most two levels deep`() {
        val privacy = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .openAbout()
            .openPrivacyPolicy()

        // A tap that lands on a page while it slides away must not crash the app (#143 Q15).
        assertEquals(privacy, privacy.openChain())
        assertEquals(privacy, privacy.openLicenses())
    }

    @Test
    fun `opening the shown detail again changes nothing, so its links still work`() {
        // A fast double tap on Settings' About row reaches Settings again before About covers it.
        val about = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .openAbout()
        assertEquals(about, about.openAbout())

        val privacy = about.openAbout().openPrivacyPolicy()
        assertEquals(VesqenDestination.PRIVACY_POLICY, privacy.destination)
        assertEquals(VesqenDestination.ABOUT, privacy.back().destination)
        assertEquals(VesqenDestination.SETTINGS, privacy.back().back().destination)
    }

    @Test
    fun `chain selected from its tab returns to library and has no contextual origin`() {
        val chain = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .selectTopLevel(VesqenDestination.CHAIN)

        assertEquals(false, chain.chainOpenedInContext)
        assertEquals(VesqenDestination.LIBRARY, chain.back().destination)
    }

    @Test
    fun `chain opened in context reports its origin`() {
        val chain = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.NOW)
            .openChain()

        assertEquals(true, chain.chainOpenedInContext)
    }

    @Test
    fun `settings stays underneath the pages opened from it`() {
        val settings = VesqenNavigationState().selectTopLevel(VesqenDestination.SETTINGS)

        assertFalse(settings.settingsUnderneath)
        assertTrue(settings.openChain().settingsUnderneath)
        assertTrue(settings.openAbout().settingsUnderneath)
        assertTrue(settings.openAbout().openPrivacyPolicy().settingsUnderneath)
        assertTrue(settings.openLicenses().settingsUnderneath)
        assertTrue(settings.openPremium().settingsUnderneath)
        assertFalse(settings.openAbout().back().settingsUnderneath)
        // Pro features over a nested detail open from Settings, so Settings is underneath them too.
        assertTrue(VesqenNavigationState().openAbout().openPrivacyPolicy().openPremium().settingsUnderneath)
    }

    @Test
    fun `tapping the tab that is already shown changes nothing`() {
        // Re-selecting Chain over Settings once dropped its way back and rebuilt the page (#143 Q4).
        val chainOverSettings = VesqenNavigationState().selectTopLevel(VesqenDestination.SETTINGS).openChain()
        assertEquals(chainOverSettings, chainOverSettings.selectTopLevel(VesqenDestination.CHAIN))
        assertTrue(chainOverSettings.selectTopLevel(VesqenDestination.CHAIN).settingsUnderneath)
        assertEquals(VesqenDestination.SETTINGS, chainOverSettings.selectTopLevel(VesqenDestination.CHAIN).back().destination)

        val nowFromChain = VesqenNavigationState().selectTopLevel(VesqenDestination.CHAIN).selectTopLevel(VesqenDestination.NOW)
        assertEquals(nowFromChain, nowFromChain.selectTopLevel(VesqenDestination.NOW))
    }

    @Test
    fun `pages opened elsewhere do not have settings underneath`() {
        val library = VesqenNavigationState()

        assertFalse(library.selectTopLevel(VesqenDestination.CHAIN).settingsUnderneath)
        assertFalse(library.openAbout().settingsUnderneath)
        assertFalse(library.openDeviceReport().settingsUnderneath)
        assertFalse(library.selectTopLevel(VesqenDestination.NOW).openChain().settingsUnderneath)
        assertFalse(
            library.selectTopLevel(VesqenDestination.SETTINGS).selectTopLevel(VesqenDestination.NOW).settingsUnderneath,
        )
    }

    @Test
    fun `bottom bar order is library now chain settings`() {
        assertEquals(
            listOf(VesqenDestination.LIBRARY, VesqenDestination.NOW, VesqenDestination.CHAIN, VesqenDestination.SETTINGS),
            TopLevelDestinations,
        )
        assertEquals(true, VesqenDestination.CHAIN.navigationOrder < VesqenDestination.SETTINGS.navigationOrder)
        assertEquals(true, VesqenDestination.ABOUT.navigationOrder > VesqenDestination.SETTINGS.navigationOrder)
    }

    @Test
    fun `pro features opened from the player return to the player`() {
        val premium = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .selectTopLevel(VesqenDestination.NOW)
            .openPremium()

        assertEquals(VesqenDestination.PREMIUM, premium.destination)
        val now = premium.back()
        assertEquals(VesqenDestination.NOW, now.destination)
        assertEquals(VesqenDestination.SETTINGS, now.back().destination)
    }

    @Test
    fun `pro features opened over a nested detail open from settings`() {
        val premium = VesqenNavigationState()
            .selectTopLevel(VesqenDestination.SETTINGS)
            .openAbout()
            .openPrivacyPolicy()
            .openPremium()

        assertEquals(VesqenDestination.PREMIUM, premium.destination)
        assertEquals(VesqenDestination.SETTINGS, premium.back().destination)
        assertEquals(premium, premium.openPremium())
    }
}
