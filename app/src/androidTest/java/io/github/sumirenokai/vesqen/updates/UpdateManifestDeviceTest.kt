package io.github.sumirenokai.vesqen.updates

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Uses Android's actual JSON implementation, including its permissive number coercions. */
class UpdateManifestDeviceTest {
    private val release = UpdateRelease("1.0.0-beta.2", 11, 26, listOf("https://example.org/app.apk"), "a".repeat(64),
        mapOf(UpdateLanguage.ENGLISH to "Notes", UpdateLanguage.SIMPLIFIED_CHINESE to "说明"))
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("channel", "beta")
        .put("release", JSONObject(UpdateManifest.encodeRelease(release)))

    @Test fun schemaRoundTripAndStableChannelBoundary() {
        assertEquals(release, UpdateManifest.parse(manifest().toString(), UpdateChannel.BETA))
        assertNull(UpdateManifest.parse(manifest().put("release", JSONObject.NULL).toString(), UpdateChannel.BETA))
        rejects(manifest().put("channel", "stable"), UpdateChannel.STABLE)
        rejects(manifest(), UpdateChannel.STABLE)
    }

    @Test fun malformedNumbersMissingNotesAndUnsafeUrlsAreRejected() {
        for (code in listOf<Any>("11", 11.5, -1, 2_100_000_001L)) {
            val root = manifest(); root.getJSONObject("release").put("versionCode", code); rejects(root)
        }
        val missing = manifest(); missing.getJSONObject("release").getJSONObject("releaseNotes").remove("en"); rejects(missing)
        for (url in listOf("http://example.org/app.apk", "https://user:pass@example.org/app.apk", "file:///data/app.apk")) {
            val root = manifest(); root.getJSONObject("release").getJSONArray("apkUrls").put(0, url); rejects(root)
        }
    }

    private fun rejects(value: JSONObject, channel: UpdateChannel = UpdateChannel.BETA) {
        try { UpdateManifest.parse(value.toString(), channel); fail("Manifest accepted") }
        catch (failure: UpdateOperationException) { assertEquals(UpdateFailure.INVALID_MANIFEST, failure.reason) }
    }
}
