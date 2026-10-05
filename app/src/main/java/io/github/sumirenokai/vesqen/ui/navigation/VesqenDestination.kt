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
    CHAIN(R.string.destination_chain, "vesqen.nav.chain"),
    ABOUT(R.string.settings_about, "vesqen.nav.about-detail"),
    PRIVACY_POLICY(R.string.privacy_policy_title, "vesqen.nav.privacy-detail"),
    LICENSES(R.string.licenses_title, "vesqen.nav.licenses-detail"),
    USAGE_STATISTICS(R.string.usage_title, "vesqen.nav.usage-detail"),
    DEVICE_REPORT(R.string.report_title, "vesqen.nav.report-detail"),
}

// Bottom-bar order from the B artboard (owner decision 2026-10-03: Chain is a top-level tab).
internal val TopLevelDestinations = listOf(
    VesqenDestination.LIBRARY,
    VesqenDestination.NOW,
    VesqenDestination.CHAIN,
    VesqenDestination.SETTINGS,
)

/** 0 for top-level surfaces; details opened from a detail sit one level deeper than their parent. */
internal val VesqenDestination.detailDepth: Int
    get() = when (this) {
        VesqenDestination.ABOUT, VesqenDestination.LICENSES, VesqenDestination.USAGE_STATISTICS,
        VesqenDestination.DEVICE_REPORT -> 1
        VesqenDestination.PRIVACY_POLICY -> 2
        else -> 0
    }

internal val VesqenDestination.isSecondaryDetail: Boolean
    get() = detailDepth > 0

/** Horizontal transitions move right along the bottom bar and further right into details. */
internal val VesqenDestination.navigationOrder: Int
    get() = TopLevelDestinations.indexOf(this).takeIf { it >= 0 } ?: (TopLevelDestinations.size + detailDepth)
