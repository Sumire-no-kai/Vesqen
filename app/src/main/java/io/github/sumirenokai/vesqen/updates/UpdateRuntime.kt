package io.github.sumirenokai.vesqen.updates

/**
 * The self-updater as the shared app sees it. Only the GitHub build has one; Google Play updates
 * the Play build, and Play policy forbids an app updating itself any other way.
 */
interface UpdateRuntime {
    val updater: AppUpdater

    /** The app came to the foreground. True means #70's daily request will provide the manifest; no separate GET then. */
    fun onForeground(usageRequestExpected: Boolean)
    fun acceptUsageResponse(manifest: String)

    /** The expected usage request brought no update data (nothing sent, or relay not configured): check directly. */
    fun checkWithoutUsageResponse()
}
