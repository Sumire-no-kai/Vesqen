package io.github.sumirenokai.vesqen.usage

import java.util.Locale

/** Product policy, not a claim of legal compliance. Extend only after the owner's #70 review. */
object UsageRegionRules {
    val explicitConsentCountries: Set<String> = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU",
        "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        "IS", "LI", "NO", "GB",
    )
    fun evaluate(systemCountries: Collection<String>, simCountries: Collection<String>, simCoverageComplete: Boolean = true): UsageRegionPolicy {
        val countries = (systemCountries + simCountries).map { it.trim().uppercase(Locale.ROOT) }
            .map { if (it == "UK") "GB" else it }.filter { it.matches(Regex("[A-Z]{2}")) }
        return when {
            countries.any(explicitConsentCountries::contains) -> UsageRegionPolicy.EXPLICIT_CONSENT
            countries.isEmpty() || !simCoverageComplete -> UsageRegionPolicy.REGION_UNAVAILABLE
            else -> UsageRegionPolicy.DEFAULT_ENABLED
        }
    }
}
