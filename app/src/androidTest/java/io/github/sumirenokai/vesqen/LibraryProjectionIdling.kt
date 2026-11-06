package io.github.sumirenokai.vesqen

import androidx.compose.ui.test.IdlingResource
import io.github.sumirenokai.vesqen.ui.screens.libraryProjectionSettled

/** The Library works its lists out off the main thread, where a Compose test cannot see the work (#223). */
internal object LibraryProjectionIdling : IdlingResource {
    override val isIdleNow: Boolean get() = libraryProjectionSettled.get()

    override fun getDiagnosticMessageIfBusy(): String = "The Library's lists are still being worked out"
}
