package io.github.sumirenokai.vesqen.ui.navigation

/**
 * Small, pure navigation reducer shared by toolbar and Android Back behavior.
 *
 * Full-player expansion is a focused detour from Library. Playback Chain and About are secondary
 * detail routes and return to whichever top-level surface opened them.
 */
data class VesqenNavigationState(
    val destination: VesqenDestination = VesqenDestination.LIBRARY,
    val returnDestination: VesqenDestination = VesqenDestination.LIBRARY,
    val playerReturnDestination: VesqenDestination = VesqenDestination.LIBRARY,
) {
    fun selectTopLevel(destination: VesqenDestination): VesqenNavigationState {
        require(!destination.isSecondaryDetail) { "$destination is not a top-level destination" }
        val playerOrigin = this.destination.takeIf {
            it != VesqenDestination.NOW && !it.isSecondaryDetail
        } ?: playerReturnDestination.takeIf { !it.isSecondaryDetail }
            ?: VesqenDestination.LIBRARY
        return copy(
            destination = destination,
            returnDestination = VesqenDestination.LIBRARY,
            playerReturnDestination = if (destination == VesqenDestination.NOW) playerOrigin else {
                VesqenDestination.LIBRARY
            },
        )
    }

    fun openDetail(destination: VesqenDestination): VesqenNavigationState {
        require(destination.isSecondaryDetail) { "$destination is not a secondary detail" }
        return copy(
            destination = destination,
            returnDestination = this.destination,
        )
    }

    fun openChain(): VesqenNavigationState = openDetail(VesqenDestination.CHAIN)

    fun openAbout(): VesqenNavigationState = openDetail(VesqenDestination.ABOUT)

    fun back(): VesqenNavigationState = when {
        destination == VesqenDestination.LIBRARY -> this
        destination.isSecondaryDetail -> copy(
            destination = returnDestination,
            returnDestination = VesqenDestination.LIBRARY,
        )
        destination == VesqenDestination.NOW -> copy(
            destination = playerReturnDestination,
            returnDestination = VesqenDestination.LIBRARY,
            playerReturnDestination = VesqenDestination.LIBRARY,
        )
        else -> copy(
            destination = VesqenDestination.LIBRARY,
            returnDestination = VesqenDestination.LIBRARY,
            playerReturnDestination = VesqenDestination.LIBRARY,
        )
    }
}
