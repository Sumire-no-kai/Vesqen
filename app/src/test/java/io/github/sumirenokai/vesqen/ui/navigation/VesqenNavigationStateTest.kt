package io.github.sumirenokai.vesqen.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

        assertThrows(IllegalArgumentException::class.java) { privacy.openChain() }
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
    fun `bottom bar order is library now chain settings`() {
        assertEquals(
            listOf(VesqenDestination.LIBRARY, VesqenDestination.NOW, VesqenDestination.CHAIN, VesqenDestination.SETTINGS),
            TopLevelDestinations,
        )
        assertEquals(true, VesqenDestination.CHAIN.navigationOrder < VesqenDestination.SETTINGS.navigationOrder)
        assertEquals(true, VesqenDestination.ABOUT.navigationOrder > VesqenDestination.SETTINGS.navigationOrder)
    }
}
