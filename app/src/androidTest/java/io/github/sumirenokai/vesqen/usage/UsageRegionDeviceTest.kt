package io.github.sumirenokai.vesqen.usage

import org.junit.Assert.*
import org.junit.Test

/** The device's ICU time zone table, which feeds [UsageRegionRules] through [usageRegionFor] (#136 R24). */
class UsageRegionDeviceTest {
    @Test fun timeZonesInConsentRegionsAskFirstEvenWhenTheLanguageNamesAnotherCountry() {
        mapOf(
            "Europe/Berlin" to "DE", "Europe/Paris" to "FR", "Europe/Helsinki" to "FI", "Europe/Dublin" to "IE",
            "Atlantic/Canary" to "ES", "Africa/Ceuta" to "ES", "Atlantic/Azores" to "PT", "Europe/London" to "GB",
            "Asia/Shanghai" to "CN", "Asia/Urumqi" to "CN", "Asia/Seoul" to "KR",
        ).forEach { (zone, country) ->
            assertEquals(zone, country, timeZoneCountry(zone))
            // A US-English tablet with no SIM: the time zone is the only signal pointing at the region.
            assertEquals(zone, UsageRegionPolicy.EXPLICIT_CONSENT, usageRegionFor(listOf("US"), zone))
            assertEquals(zone, UsageRegionPolicy.EXPLICIT_CONSENT, usageRegionFor(emptyList(), zone))
        }
    }

    @Test fun otherTimeZonesNeitherAskNorTurnAnUnreadableDeviceOn() {
        mapOf(
            "America/New_York" to "US", "Asia/Tokyo" to "JP", "Europe/Zurich" to "CH", "Asia/Hong_Kong" to "HK",
            "Asia/Taipei" to "TW", "Australia/Sydney" to "AU",
        ).forEach { (zone, country) ->
            assertEquals(zone, country, timeZoneCountry(zone))
            assertEquals(zone, UsageRegionPolicy.DEFAULT_ENABLED, usageRegionFor(listOf("US"), zone))
            // Only the locale or telephony can establish a default-on region (#143 Q3).
            assertEquals(zone, UsageRegionPolicy.REGION_UNAVAILABLE, usageRegionFor(emptyList(), zone))
        }
        assertEquals("001", timeZoneCountry("Etc/UTC"))
        assertEquals(UsageRegionPolicy.DEFAULT_ENABLED, usageRegionFor(listOf("US"), "Etc/UTC"))
        assertNull(timeZoneCountry("GMT+01:00"))
        assertEquals(UsageRegionPolicy.REGION_UNAVAILABLE, usageRegionFor(emptyList(), "GMT+01:00"))
    }
}
