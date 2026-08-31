package io.github.sumirenokai.vesqen.ui.navigation

/**
 * Small, pure navigation reducer shared by toolbar and Android Back behavior.
 *
 * Full-player expansion is a focused detour from Library. Chain opened from Now is the one
 * contextual exception: Android Back first restores the player, then returns to Library.
 */
data class VesqenNavigationState(
    val destination: VesqenDestination = VesqenDestination.LIBRARY,
    val returnDestination: VesqenDestination = VesqenDestination.LIBRARY,
) {
    fun selectTopLevel(destination: VesqenDestination): VesqenNavigationState = copy(
        destination = destination,
        returnDestination = VesqenDestination.LIBRARY,
    )

    fun openChainFromNow(): VesqenNavigationState = copy(
        destination = VesqenDestination.CHAIN,
        returnDestination = VesqenDestination.NOW,
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
