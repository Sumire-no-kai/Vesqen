package io.github.sumirenokai.vesqen.updates

import android.app.Application
import kotlinx.coroutines.CoroutineScope

/** Google Play installs this build's updates, so it has no updater and no install permission. */
@Suppress("UNUSED_PARAMETER")
internal fun createUpdateRuntime(application: Application, scope: CoroutineScope): UpdateRuntime? = null
