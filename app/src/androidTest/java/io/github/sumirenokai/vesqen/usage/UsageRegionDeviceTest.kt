package io.github.sumirenokai.vesqen.usage

import org.junit.Assert.*
import org.junit.Test

/** The device's ICU time zone table, which feeds [UsageRegionRules] through [timeZoneCountry] (#136 R24). */
class UsageRegionDeviceTest {
    @Test fun timeZonesInConsentRegionsAskFirstEvenWhenTheLanguageNamesAnotherCountry() {
        mapOf(
            "Europe/Berlin" to "DE", "Europe/Paris" to "FR", "Europe/Helsinki" to "FI", "Europe/Dublin" to "IE",
            "Atlantic/Canary" to "ES", "Africa/Ceuta" to "ES", "Atlantic/Azores" to "PT", "Europe/London" to "GB",
            "Asia/Shanghai" to "CN", "Asia/Urumqi" to "CN", "Asia/Seoul" to "KR",
        ).forEach { (zone, country) ->
            assertEquals(zone, country, timeZoneCountry(zone))
            // A US-English tablet with no SIM: the time zone is the only signal pointing at the region.
            assertEquals(zone, UsageRegionPolicy.EXPLICIT_CONSENT, UsageRegionRules.evaluate(listOf("US", timeZoneCountry(zone)!!)))
        }
    }

    @Test fun otherTimeZonesKeepTheDefaultAndZonesWithoutACountryAddNothing() {
        mapOf(
            "America/New_York" to "US", "Asia/Tokyo" to "JP", "Europe/Zurich" to "CH", "Asia/Hong_Kong" to "HK",
            "Asia/Taipei" to "TW", "Australia/Sydney" to "AU",
        ).forEach { (zone, country) ->
            assertEquals(zone, country, timeZoneCountry(zone))
            assertEquals(zone, UsageRegionPolicy.DEFAULT_ENABLED, UsageRegionRules.evaluate(listOf("US", timeZoneCountry(zone)!!)))
        }
        assertEquals("001", timeZoneCountry("Etc/UTC"))
        assertEquals(UsageRegionPolicy.DEFAULT_ENABLED, UsageRegionRules.evaluate(listOf("US", "001")))
        assertNull(timeZoneCountry("GMT+01:00"))
    }
}
