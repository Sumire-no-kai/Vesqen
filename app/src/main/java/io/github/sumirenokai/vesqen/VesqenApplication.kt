package io.github.sumirenokai.vesqen

import android.app.Application
import io.github.sumirenokai.vesqen.diagnostics.AndroidDiagnosticRecordingStore
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

    private val developerDiagnosticRecorder: DiagnosticRecorder? by lazy {
        if (BuildConfig.DEVELOPER_DIAGNOSTICS_ENABLED) {
            DiagnosticRecorder(
                playbackTelemetry = playbackTelemetry,
                scope = applicationScope,
                store = AndroidDiagnosticRecordingStore(this),
            ).also(DiagnosticRecorder::restore)
        } else {
            null
        }
    }

    internal val diagnosticRecorder: DiagnosticRecorder
        get() = checkNotNull(developerDiagnosticRecorder) {
            "Developer diagnostics are unavailable in this build"
        }

    internal val diagnosticRecorderOrNull: DiagnosticRecorder?
        get() = developerDiagnosticRecorder

    override fun onCreate() {
        super.onCreate()
        developerDiagnosticRecorder
        applicationScope.launch { outputVerificationRepository.load() }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
