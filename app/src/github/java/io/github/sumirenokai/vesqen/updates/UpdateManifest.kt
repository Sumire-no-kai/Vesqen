package io.github.sumirenokai.vesqen.updates

import org.json.JSONException
import org.json.JSONObject

/** Bounded transport input; all fields are validated before they can drive a download. */
internal object UpdateManifest {
    const val MAX_BYTES = 128 * 1024
    private val versionPattern = Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?")

    fun parse(text: String, channel: UpdateChannel): UpdateRelease? {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val root = JSONObject(text)
            require(root.strictLong("schemaVersion") == 1L)
            require(root.strictString("channel") == channel.name.lowercase())
            require(root.has("release"))
            if (root.isNull("release")) return null
            return readRelease(root.getJSONObject("release")).also {
                require(channel != UpdateChannel.STABLE || defaultUpdateChannel(it.versionName) == UpdateChannel.STABLE)
            }
        } catch (failure: JSONException) {
            throw UpdateOperationException(UpdateFailure.INVALID_MANIFEST, failure)
        } catch (failure: IllegalArgumentException) {
            throw UpdateOperationException(UpdateFailure.INVALID_MANIFEST, failure)
        }
    }

    fun validate(release: UpdateRelease) {
        require(versionPattern.matches(release.versionName) && release.versionName.length <= 100)
        require(release.versionCode in 1..2_100_000_000L)
        require(release.minimumAndroidApi in 26..1000)
        require(release.sha256.matches(Regex("[0-9a-f]{64}")))
        require(release.apkUrls.size in 1..5 && release.apkUrls.all { it.length <= 4096 && isHttpsDownloadUrl(it) })
        require(UpdateLanguage.entries.all { release.releaseNotes[it]?.let { notes -> notes.length in 1..32_768 } == true })
    }

    fun encodeRelease(release: UpdateRelease): String = JSONObject().apply {
        put("versionName", release.versionName)
        put("versionCode", release.versionCode)
        put("minimumAndroidApi", release.minimumAndroidApi)
        put("apkUrls", org.json.JSONArray(release.apkUrls))
        put("sha256", release.sha256)
        put("releaseNotes", JSONObject().put("en", release.releaseNotes[UpdateLanguage.ENGLISH])
            .put("zh-CN", release.releaseNotes[UpdateLanguage.SIMPLIFIED_CHINESE]))
    }.toString()

    private fun JSONObject.strictString(key: String): String = get(key).also { require(it is String) } as String

    private fun JSONObject.strictLong(key: String): Long = get(key).let {
        require(it is Int || it is Long)
        (it as Number).toLong()
    }

    fun readRelease(value: JSONObject): UpdateRelease {
        val urls = value.getJSONArray("apkUrls")
        val notes = value.getJSONObject("releaseNotes")
        return UpdateRelease(value.strictString("versionName"), value.strictLong("versionCode"),
            value.strictLong("minimumAndroidApi").also { require(it in 26..1000) }.toInt(), (0 until urls.length()).map { urls.get(it).also { value -> require(value is String) } as String },
            value.strictString("sha256"), mapOf(UpdateLanguage.ENGLISH to notes.strictString("en"),
                UpdateLanguage.SIMPLIFIED_CHINESE to notes.strictString("zh-CN"))).also(::validate)
    }
}
