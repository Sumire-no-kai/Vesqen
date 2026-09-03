package io.github.sumirenokai.vesqen

import android.app.Application
import io.github.sumirenokai.vesqen.telemetry.AndroidPlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.PlaybackTelemetry

class VesqenApplication : Application() {
    internal val telemetryRuntime: AndroidPlaybackTelemetry by lazy {
        AndroidPlaybackTelemetry(this)
    }

    val playbackTelemetry: PlaybackTelemetry
        get() = telemetryRuntime
}
