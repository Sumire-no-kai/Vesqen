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
            .openChainFromNow()

        val now = chain.back()
        assertEquals(VesqenDestination.NOW, now.destination)
        assertEquals(VesqenDestination.LIBRARY, now.back().destination)
    }

    @Test
    fun `top level chain returns to library`() {
        val chain = VesqenNavigationState().selectTopLevel(VesqenDestination.CHAIN)

        assertEquals(VesqenDestination.LIBRARY, chain.back().destination)
    }
}
