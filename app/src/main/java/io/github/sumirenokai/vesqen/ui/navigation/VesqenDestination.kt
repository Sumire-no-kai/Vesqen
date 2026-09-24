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
    ABOUT(R.string.settings_about, "vesqen.nav.about-detail"),
    PRIVACY_POLICY(R.string.privacy_policy_title, "vesqen.nav.privacy-detail"),
}

internal val TopLevelDestinations = listOf(
    VesqenDestination.LIBRARY,
    VesqenDestination.NOW,
    VesqenDestination.SETTINGS,
)

/** 0 for top-level surfaces; details opened from a detail sit one level deeper than their parent. */
internal val VesqenDestination.detailDepth: Int
    get() = when (this) {
        VesqenDestination.CHAIN, VesqenDestination.ABOUT -> 1
        VesqenDestination.PRIVACY_POLICY -> 2
        else -> 0
    }

internal val VesqenDestination.isSecondaryDetail: Boolean
    get() = detailDepth > 0
