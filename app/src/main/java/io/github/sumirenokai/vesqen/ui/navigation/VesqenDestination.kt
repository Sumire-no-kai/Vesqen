package io.github.sumirenokai.vesqen.ui.navigation

import androidx.annotation.StringRes
import io.github.sumirenokai.vesqen.R

/** Stable semantic destinations. Display labels are supplied by localized resources. */
enum class VesqenDestination(
    @StringRes val labelRes: Int,
    val testTag: String,
) {
    LIBRARY(R.string.destination_library, "vesqen.nav.library"),
    NOW(R.string.destination_now, "vesqen.nav.now"),
    SETTINGS(R.string.destination_settings, "vesqen.nav.settings"),
    CHAIN(R.string.destination_chain, "vesqen.nav.chain-detail"),
}

internal val TopLevelDestinations = listOf(
    VesqenDestination.LIBRARY,
    VesqenDestination.NOW,
    VesqenDestination.SETTINGS,
)
