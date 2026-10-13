package io.github.sumirenokai.vesqen

import io.github.sumirenokai.vesqen.updates.UpdateRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What both builds do when the app comes to the foreground (#117): the daily usage ping first,
 * then the GitHub build's update check, told whether the ping's reply will answer it.
 *
 * Counts the started instances of the main activity, so only the first of overlapping windows
 * triggers, and returning after every window stopped triggers again. Called on the main thread.
 */
internal class ForegroundTrigger(
    private val scope: CoroutineScope,
    /** Starts the daily usage ping. True: its task answers the update check, so the updater skips its own request. */
    private val usage: suspend () -> Boolean,
    /** The GitHub build's updater; null in the Play build, which Google Play updates. */
    private val updates: () -> UpdateRuntime?,
) {
    private var started = 0

    fun activityStarted() {
        if (started++ == 0) scope.launch {
            val usageRequestExpected = usage()
            updates()?.onForeground(usageRequestExpected)
        }
    }

    fun activityStopped() {
        started--
    }
}
