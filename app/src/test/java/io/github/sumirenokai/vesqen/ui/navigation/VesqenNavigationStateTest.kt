package io.github.sumirenokai.vesqen.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class VesqenNavigationStateTest {
    @Test
    fun `back from full player returns to library`() {
        val now = VesqenNavigationState().selectTopLevel(VesqenDestination.NOW)

        assertEquals(VesqenDestination.LIBRARY, now.back().destination)
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
}
