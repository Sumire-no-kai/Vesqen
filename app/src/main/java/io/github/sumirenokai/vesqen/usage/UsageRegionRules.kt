package io.github.sumirenokai.vesqen.usage

import java.util.Locale

/** Product policy, not a claim of legal compliance. Extend only after the owner's #70 review. */
object UsageRegionRules {
    val explicitConsentCountries: Set<String> = setOf(
        // EU-27
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU",
        "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        // EU outermost regions and Åland, which carry their own ISO codes
        "RE", "GP", "MQ", "GF", "YT", "MF", "AX",
        // EEA and the UK
        "IS", "LI", "NO", "GB",
        // Mainland China (PIPL has no legitimate-interest basis) and South Korea: owner decision
        // 2026-10-07 after the #70 review. Hong Kong, Macao and Taiwan carry their own codes.
        "CN", "KR",
    )

    /**
     * [countries] holds the system locales and each SIM or network country the device can read.
     * Any consent country asks first; no readable country asks too. [timeZoneCountry] can only add
     * a reason to ask first: a time zone elsewhere never makes an otherwise unreadable device
     * default-on (#143 Q3).
     */
    fun evaluate(countries: Collection<String>, timeZoneCountry: String? = null): UsageRegionPolicy {
        if (timeZoneCountry?.let(::normalize) in explicitConsentCountries) return UsageRegionPolicy.EXPLICIT_CONSENT
        val normalized = countries.mapNotNull(::normalize)
        return when {
            normalized.any(explicitConsentCountries::contains) -> UsageRegionPolicy.EXPLICIT_CONSENT
            normalized.isEmpty() -> UsageRegionPolicy.REGION_UNAVAILABLE
            else -> UsageRegionPolicy.DEFAULT_ENABLED
        }
    }

    private fun normalize(country: String): String? = country.trim().uppercase(Locale.ROOT)
        .let { if (it == "UK") "GB" else it }.takeIf { it.matches(Regex("[A-Z]{2}")) }
}
