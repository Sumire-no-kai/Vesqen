package io.github.sumirenokai.vesqen.ui.navigation

/**
 * Small, pure navigation reducer shared by toolbar and Android Back behavior.
 *
 * Full-player expansion is a focused detour from Library. Playback Chain is a secondary detail
 * route and returns to whichever top-level surface opened it.
 */
data class VesqenNavigationState(
    val destination: VesqenDestination = VesqenDestination.LIBRARY,
    val returnDestination: VesqenDestination = VesqenDestination.LIBRARY,
) {
    fun selectTopLevel(destination: VesqenDestination): VesqenNavigationState = copy(
        destination = destination,
        returnDestination = VesqenDestination.LIBRARY,
    )

    fun openChain(): VesqenNavigationState = copy(
        destination = VesqenDestination.CHAIN,
        returnDestination = destination,
    )

    fun back(): VesqenNavigationState = if (destination == VesqenDestination.LIBRARY) {
        this
    } else {
        copy(
            destination = returnDestination,
            returnDestination = VesqenDestination.LIBRARY,
        )
    }
}
