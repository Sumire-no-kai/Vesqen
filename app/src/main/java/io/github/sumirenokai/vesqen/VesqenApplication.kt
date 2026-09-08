package io.github.sumirenokai.vesqen

import android.app.Application
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecorder
import io.github.sumirenokai.vesqen.playback.PlaybackHistoryRecorder
import io.github.sumirenokai.vesqen.playback.UsbOutputStateRepository
import io.github.sumirenokai.vesqen.telemetry.AndroidPlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.PlaybackTelemetry
import io.github.sumirenokai.vesqen.verification.AndroidOutputVerificationRepository
import io.github.sumirenokai.vesqen.verification.OutputVerificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VesqenApplication : Application() {
    private val applicationJob = SupervisorJob()
    private val applicationScope = CoroutineScope(applicationJob + Dispatchers.Default)

    val usbOutputStateRepository = UsbOutputStateRepository()

    val outputVerificationRepository: OutputVerificationRepository by lazy {
        AndroidOutputVerificationRepository(this)
    }

    internal val telemetryRuntime: AndroidPlaybackTelemetry by lazy {
        AndroidPlaybackTelemetry(
            this,
            usbOutputStateRepository,
            outputVerificationLookup = outputVerificationRepository::match,
        )
    }

    val playbackTelemetry: PlaybackTelemetry
        get() = telemetryRuntime

    internal val playbackHistoryRecorder: PlaybackHistoryRecorder by lazy {
        PlaybackHistoryRecorder(this, applicationScope)
    }

    val diagnosticRecorder: DiagnosticRecorder by lazy {
        DiagnosticRecorder(playbackTelemetry, applicationScope)
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { outputVerificationRepository.load() }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
