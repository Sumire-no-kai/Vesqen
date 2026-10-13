package io.github.sumirenokai.vesqen

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.sumirenokai.vesqen.diagnostics.AndroidDiagnosticRecordingStore
import io.github.sumirenokai.vesqen.diagnostics.DiagnosticRecorder
import io.github.sumirenokai.vesqen.playback.PlaybackHistoryRecorder
import io.github.sumirenokai.vesqen.playback.UsbOutputPhase
import io.github.sumirenokai.vesqen.playback.UsbOutputStateRepository
import io.github.sumirenokai.vesqen.premium.AndroidPremiumStore
import io.github.sumirenokai.vesqen.premium.PremiumAccess
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

    /** Null in the Play build, which Google Play updates. Typed by the flavor's factory: GitHubUpdateRuntime in the GitHub build. */
    internal val updateRuntime by lazy {
        io.github.sumirenokai.vesqen.updates.createUpdateRuntime(this, applicationScope)
    }
    val appUpdater: io.github.sumirenokai.vesqen.updates.AppUpdater? get() = updateRuntime?.updater

    /** #96: one switch reader shared by usage pings and report uploads, so one reading serves both. */
    internal val serviceSwitch by lazy {
        io.github.sumirenokai.vesqen.service.ServiceSwitchGate(
            io.github.sumirenokai.vesqen.service.HttpsServiceSwitchSource(BuildConfig.SERVICE_SWITCH_URL),
            io.github.sumirenokai.vesqen.service.AndroidServiceSwitchStore(this),
            BuildConfig.VERSION_CODE.toLong(),
        )
    }
    val serviceStatus: kotlinx.coroutines.flow.StateFlow<io.github.sumirenokai.vesqen.service.KnownServiceStatus>
        get() = serviceSwitch.known

    private val usageRuntime by lazy {
        io.github.sumirenokai.vesqen.usage.AndroidUsageRuntime(
            this, applicationScope, { manifest -> updateRuntime?.acceptUsageResponse(manifest) },
            { updateRuntime?.checkWithoutUsageResponse() }, serviceSwitch,
        )
    }
    val usageStatistics: io.github.sumirenokai.vesqen.usage.UsageStatistics get() = usageRuntime.statistics

    val usbOutputStateRepository = UsbOutputStateRepository()

    /** #119: the trial and paid-feature state; the betas build it with enforcement off. */
    val premiumAccess: PremiumAccess by lazy {
        PremiumAccess(AndroidPremiumStore(this), enforced = BuildConfig.PREMIUM_ENFORCED)
    }

    internal val deviceReportRuntime by lazy {
        io.github.sumirenokai.vesqen.reports.AndroidDeviceReportRuntime(
            this, applicationScope, usbOutputStateRepository, { playbackTelemetry },
            uploader = io.github.sumirenokai.vesqen.reports.HttpsDeviceReportUploader(BuildConfig.USAGE_ENDPOINT, {
                serviceSwitch.check(io.github.sumirenokai.vesqen.service.ReportService.REPORT_UPLOADS)
            }),
        )
    }
    val deviceReporter: io.github.sumirenokai.vesqen.reports.DeviceReporter
        get() = deviceReportRuntime.reporter

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

    /** Both builds: the daily usage ping first, then the GitHub build's update check, which its reply may answer. */
    private val foreground = ForegroundTrigger(applicationScope, usage = { usageRuntime.onForeground() }, updates = { updateRuntime })
    private val foregroundHook = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity is MainActivity) foreground.activityStarted()
        }
        override fun onActivityStopped(activity: Activity) {
            if (activity is MainActivity) foreground.activityStopped()
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        deviceReportRuntime
        developerDiagnosticRecorder
        // The updater restores an interrupted installation; usage starts recording USB connections.
        updateRuntime
        usageRuntime
        // The trial starts the first time strict output actually plays (#119).
        usbOutputStateRepository.addListener { status ->
            if (status.phase == UsbOutputPhase.ACTIVE) premiumAccess.onStrictOutputActive()
        }
        registerActivityLifecycleCallbacks(foregroundHook)
        applicationScope.launch { outputVerificationRepository.load() }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
