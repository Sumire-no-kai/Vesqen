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
) {
    fun selectTopLevel(destination: VesqenDestination): VesqenNavigationState = copy(
        destination = destination,
        returnDestination = VesqenDestination.LIBRARY,
    )

    fun openDetail(destination: VesqenDestination): VesqenNavigationState {
        require(destination.isSecondaryDetail) { "$destination is not a secondary detail" }
        return copy(
            destination = destination,
            returnDestination = this.destination,
        )
    }

    fun openChain(): VesqenNavigationState = openDetail(VesqenDestination.CHAIN)

    fun openAbout(): VesqenNavigationState = openDetail(VesqenDestination.ABOUT)

    fun back(): VesqenNavigationState = if (destination == VesqenDestination.LIBRARY) {
        this
    } else {
        copy(
            destination = returnDestination,
            returnDestination = VesqenDestination.LIBRARY,
        )
    }
}
