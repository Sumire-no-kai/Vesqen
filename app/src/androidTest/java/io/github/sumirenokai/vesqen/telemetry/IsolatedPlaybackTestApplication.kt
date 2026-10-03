package io.github.sumirenokai.vesqen.telemetry

import androidx.test.platform.app.InstrumentationRegistry
import io.github.sumirenokai.vesqen.VesqenApplication

/** Refuse service tests before touching a user's queue, preferences, catalog or diagnostics. */
internal fun isolatedPlaybackTestApplication(): VesqenApplication {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    check(app.packageName == "io.github.sumirenokai.vesqen.devicetest") {
        "Playback device tests require the isolated deviceTest application"
    }
    return app as VesqenApplication
}
