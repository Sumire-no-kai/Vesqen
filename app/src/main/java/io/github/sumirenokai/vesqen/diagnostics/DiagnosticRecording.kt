package io.github.sumirenokai.vesqen.diagnostics

import io.github.sumirenokai.vesqen.telemetry.PlaybackTelemetry
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvidence
import io.github.sumirenokai.vesqen.telemetry.TelemetryEvent
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetric
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricId
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricSelection
import io.github.sumirenokai.vesqen.telemetry.TelemetryObservation
import io.github.sumirenokai.vesqen.telemetry.TelemetryPowerMode
import io.github.sumirenokai.vesqen.telemetry.TelemetryReading
import io.github.sumirenokai.vesqen.telemetry.TelemetryRefreshInterval
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot
import io.github.sumirenokai.vesqen.telemetry.UsbAudioInterfaceReading
import io.github.sumirenokai.vesqen.telemetry.UsbAudioOutputEndpointReading
import io.github.sumirenokai.vesqen.telemetry.UsbHostDeviceReading
import io.github.sumirenokai.vesqen.telemetry.UsbInventoryReading
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

data class DiagnosticTimestamp(
    val epochMs: Long,
    val elapsedRealtimeMs: Long,
) {
    init {
        require(epochMs >= 0) { "Diagnostic wall-clock timestamps cannot be negative" }
        require(elapsedRealtimeMs >= 0) { "Diagnostic monotonic timestamps cannot be negative" }
    }
}

fun interface DiagnosticClock {
    fun now(): DiagnosticTimestamp

    companion object {
        val System: DiagnosticClock = DiagnosticClock {
            DiagnosticTimestamp(
                epochMs = java.lang.System.currentTimeMillis(),
                elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
    }
}

data class DiagnosticRecordingLimits(
    val maxSnapshots: Int = DEFAULT_MAX_SNAPSHOTS,
    val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {
    init {
        require(maxSnapshots > 0) { "A diagnostic recording must retain at least one snapshot" }
        require(maxEvents > 0) { "A diagnostic recording must retain at least one event" }
    }

    companion object {
        /** Thirty minutes at the recorder's fixed one-second observation interval. */
        const val DEFAULT_MAX_SNAPSHOTS = 1_800
        const val DEFAULT_MAX_EVENTS = 4_096
    }
}

enum class DiagnosticRecordingTermination {
    USER_STOPPED,
    PLAYBACK_STOPPED,
    SOURCE_COMPLETED,
    SOURCE_FAILED,
    OWNER_CANCELLED,
}

class DiagnosticRecording internal constructor(
    val id: String,
    val startedAt: DiagnosticTimestamp,
    val stoppedAt: DiagnosticTimestamp,
    val termination: DiagnosticRecordingTermination,
    val limits: DiagnosticRecordingLimits,
    val snapshots: List<TelemetrySnapshot>,
    val events: List<TelemetryEvent>,
    val droppedSnapshotCount: Long,
    val droppedEventCount: Long,
    /** Numeric sequence discontinuities observed within tracked event streams; not proof of loss. */
    val observedEventSequenceGapCount: Long,
) {
    init {
        require(id.matches(RECORDING_ID_PATTERN)) { "Diagnostic recording ids must be export-safe" }
        require(stoppedAt.elapsedRealtimeMs >= startedAt.elapsedRealtimeMs) {
            "A diagnostic recording cannot stop before it starts"
        }
        require(snapshots.size <= limits.maxSnapshots) { "Snapshot retention exceeded its fixed limit" }
        require(events.size <= limits.maxEvents) { "Event retention exceeded its fixed limit" }
        require(droppedSnapshotCount >= 0) { "Dropped snapshot count cannot be negative" }
        require(droppedEventCount >= 0) { "Dropped event count cannot be negative" }
        require(observedEventSequenceGapCount >= 0) { "Observed event sequence gap count cannot be negative" }
    }

    companion object {
        private val RECORDING_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}

data class DiagnosticRecordingProgress(
    val id: String,
    val startedAt: DiagnosticTimestamp,
    val snapshotCount: Int,
    val eventCount: Int,
    val droppedSnapshotCount: Long,
    val droppedEventCount: Long,
    val observedEventSequenceGapCount: Long,
)

sealed interface DiagnosticRecordingState {
    data object Idle : DiagnosticRecordingState

    data class Active(val progress: DiagnosticRecordingProgress) : DiagnosticRecordingState

    data class Stopping(val progress: DiagnosticRecordingProgress) : DiagnosticRecordingState

    data class Stopped(val recording: DiagnosticRecording) : DiagnosticRecordingState
}

enum class DiagnosticExportFailure {
    NO_STOPPED_RECORDING,
    DESTINATION_UNAVAILABLE,
    WRITE_FAILED,
}

sealed interface DiagnosticExportResult {
    data class Success(
        val snapshotCount: Int,
        val eventCount: Int,
    ) : DiagnosticExportResult

    data class Failure(val reason: DiagnosticExportFailure) : DiagnosticExportResult
}

/**
 * Owns one protected diagnostic recording at a time.
 *
 * Starting never discards a stopped recording: callers must explicitly [clear] it first. The
 * recorder's observation demand is fixed, requests the complete catalog at one-second cadence,
 * and remains active across playback-session changes. Cancelling that collection always seals the
 * current buffers into an immutable stopped recording. Pass an application-owned [scope] when the
 * recording must survive screen and ViewModel replacement; an idle or stopped instance launches no
 * sampling work.
 */
class DiagnosticRecorder(
    private val playbackTelemetry: PlaybackTelemetry,
    private val scope: CoroutineScope,
    private val limits: DiagnosticRecordingLimits = DiagnosticRecordingLimits(),
    private val clock: DiagnosticClock = DiagnosticClock.System,
    private val idFactory: (DiagnosticTimestamp) -> String = { startedAt ->
        "recording-${startedAt.epochMs}-${startedAt.elapsedRealtimeMs}"
    },
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<DiagnosticRecordingState>(DiagnosticRecordingState.Idle)
    private var activeSession: ActiveSession? = null
    private var stoppedRecording: DiagnosticRecording? = null
    private var activeExportCount = 0

    val state: StateFlow<DiagnosticRecordingState> = mutableState.asStateFlow()

    /** Returns false rather than replacing an active or not-yet-cleared stopped recording. */
    fun start(): Boolean = synchronized(lock) {
        if (activeSession != null || stoppedRecording != null) return@synchronized false

        val startedAt = clock.now()
        val recordingId = idFactory(startedAt)
        require(recordingId.matches(RECORDING_ID_PATTERN)) {
            "Diagnostic recording ids must contain only letters, digits, dot, underscore, or dash"
        }
        val session = ActiveSession(recordingId, startedAt)
        activeSession = session
        mutableState.value = DiagnosticRecordingState.Active(session.progress())
        session.job = scope.launch {
            var termination: DiagnosticRecordingTermination? = null
            try {
                playbackTelemetry.observe(RECORDING_OBSERVATION).collect { snapshot ->
                    if (capture(session, snapshot)) throw NoActivePlaybackSignal
                }
                termination = DiagnosticRecordingTermination.SOURCE_COMPLETED
            } catch (_: NoActivePlaybackSignal) {
                termination = DiagnosticRecordingTermination.PLAYBACK_STOPPED
            } catch (cancelled: CancellationException) {
                termination = synchronized(lock) {
                    session.requestedTermination
                        ?: DiagnosticRecordingTermination.OWNER_CANCELLED
                }
                throw cancelled
            } catch (_: Exception) {
                termination = DiagnosticRecordingTermination.SOURCE_FAILED
            } finally {
                // Errors are deliberately not swallowed, but a source that aborts with one must
                // never be reported as a clean completion.
                finish(session, termination ?: DiagnosticRecordingTermination.SOURCE_FAILED)
            }
        }
        session.job.invokeOnCompletion { failure ->
            // A scope can already be cancelled before an undispatched block begins, in which case
            // its finally block is never entered. The idempotent completion hook closes that gap.
            val termination = synchronized(lock) {
                when {
                    session.requestedTermination != null -> session.requestedTermination!!
                    failure is CancellationException -> DiagnosticRecordingTermination.OWNER_CANCELLED
                    failure != null -> DiagnosticRecordingTermination.SOURCE_FAILED
                    else -> DiagnosticRecordingTermination.SOURCE_COMPLETED
                }
            }
            finish(session, termination)
        }
        true
    }

    /**
     * Stops the active recording and waits until cancellation has sealed its immutable snapshot.
     * Concurrent callers await the same result. If already stopped, the retained result is returned.
     */
    suspend fun stop(): DiagnosticRecording? {
        val session = synchronized(lock) {
            stoppedRecording?.let { return it }
            val current = activeSession ?: return null
            if (current.requestedTermination == null) {
                current.requestedTermination = DiagnosticRecordingTermination.USER_STOPPED
                mutableState.value = DiagnosticRecordingState.Stopping(current.progress())
                current.job.cancel()
            }
            current
        }
        return session.finished.await()
    }

    /** Active, stopping, or currently exporting recordings cannot be cleared. */
    fun clear(): Boolean = synchronized(lock) {
        if (
            activeSession != null ||
            stoppedRecording == null ||
            activeExportCount > 0
        ) return@synchronized false
        stoppedRecording = null
        mutableState.value = DiagnosticRecordingState.Idle
        true
    }

    /** Writes off the UI thread without closing [output]. Export failure never clears the recording. */
    suspend fun exportTo(output: OutputStream): DiagnosticExportResult = withContext(Dispatchers.IO) {
        val recording = acquireRecordingForExport()
            ?: return@withContext DiagnosticExportResult.Failure(
                DiagnosticExportFailure.NO_STOPPED_RECORDING,
            )
        try {
            writeDiagnosticRecording(
                recording,
                output.cancellationChecked(currentCoroutineContext()[Job]),
            )
        } finally {
            releaseRecordingAfterExport()
        }
    }

    internal fun retainedRecording(): DiagnosticRecording? = synchronized(lock) { stoppedRecording }

    internal fun acquireRecordingForExport(): DiagnosticRecording? = synchronized(lock) {
        stoppedRecording?.also { activeExportCount++ }
    }

    internal fun releaseRecordingAfterExport() {
        synchronized(lock) {
            check(activeExportCount > 0) { "Diagnostic export lease underflow" }
            activeExportCount--
        }
    }

    /**
     * Captures one sample and returns true when a sustained lack of an active playback session
     * should automatically seal the recording. A short grace window tolerates MediaController or
     * service reconnection without leaving a hidden recorder running after the queue is cleared.
     */
    private fun capture(session: ActiveSession, source: TelemetrySnapshot): Boolean {
        // StateFlow may replay a cached sample captured before the explicit Start action. A
        // diagnostic recording is a temporal boundary, so neither that snapshot nor its events
        // belong to the recording.
        if (source.capturedAtElapsedRealtimeMs <= session.startedAt.elapsedRealtimeMs) return false
        val snapshot = source.immutableCopyWithoutEvents()
        val sourceEvents = source.recentEvents.asSequence()
            .filter { event ->
                event.occurredAtElapsedRealtimeMs > session.startedAt.elapsedRealtimeMs
            }
            .map(TelemetryEvent::immutableCopy)
            .toList()
        return synchronized(lock) {
            if (activeSession !== session || session.requestedTermination != null) {
                return@synchronized false
            }

            if (session.snapshots.size == limits.maxSnapshots) {
                session.snapshots.removeFirst()
                session.droppedSnapshotCount++
            }
            session.snapshots.addLast(snapshot)

            sourceEvents.forEach(session::captureEvent)
            val shouldAutoSeal = session.updatePlaybackPresence(
                hasActivePlayback = source.playbackSessionId != null,
                capturedAtElapsedRealtimeMs = source.capturedAtElapsedRealtimeMs,
            )
            if (shouldAutoSeal) {
                session.requestedTermination = DiagnosticRecordingTermination.PLAYBACK_STOPPED
                mutableState.value = DiagnosticRecordingState.Stopping(session.progress())
            } else {
                mutableState.value = DiagnosticRecordingState.Active(session.progress())
            }
            shouldAutoSeal
        }
    }

    private fun finish(
        session: ActiveSession,
        termination: DiagnosticRecordingTermination,
    ) {
        val recording = synchronized(lock) {
            if (activeSession !== session) return
            val stoppedAt = clock.now().let { candidate ->
                if (candidate.elapsedRealtimeMs >= session.startedAt.elapsedRealtimeMs) {
                    candidate
                } else {
                    DiagnosticTimestamp(candidate.epochMs, session.startedAt.elapsedRealtimeMs)
                }
            }
            val sealed = DiagnosticRecording(
                id = session.id,
                startedAt = session.startedAt,
                stoppedAt = stoppedAt,
                termination = termination,
                limits = limits,
                snapshots = immutableList(session.snapshots),
                events = immutableList(session.events),
                droppedSnapshotCount = session.droppedSnapshotCount,
                droppedEventCount = session.droppedEventCount,
                observedEventSequenceGapCount = session.observedEventSequenceGapCount,
            )
            activeSession = null
            stoppedRecording = sealed
            mutableState.value = DiagnosticRecordingState.Stopped(sealed)
            sealed
        }
        session.finished.complete(recording)
    }

    private inner class ActiveSession(
        val id: String,
        val startedAt: DiagnosticTimestamp,
    ) {
        val snapshots = ArrayDeque<TelemetrySnapshot>(limits.maxSnapshots.coerceAtMost(64))
        val events = ArrayDeque<TelemetryEvent>(limits.maxEvents.coerceAtMost(64))
        val finished = CompletableDeferred<DiagnosticRecording>()
        lateinit var job: Job
        var latestEventSequence: Long? = null
        var requestedTermination: DiagnosticRecordingTermination? = null
        var noActivePlaybackSinceElapsedRealtimeMs: Long? = null
        var droppedSnapshotCount = 0L
        var droppedEventCount = 0L
        var observedEventSequenceGapCount = 0L

        fun captureEvent(event: TelemetryEvent) {
            val latestSequence = latestEventSequence
            if (latestSequence != null && event.sequence <= latestSequence) return
            if (latestSequence != null && event.sequence - latestSequence > 1) {
                observedEventSequenceGapCount = observedEventSequenceGapCount.saturatedAdd(
                    event.sequence - latestSequence - 1,
                )
            }
            latestEventSequence = event.sequence

            if (events.size == limits.maxEvents) {
                events.removeFirst()
                droppedEventCount++
            }
            events.addLast(event)
        }

        fun updatePlaybackPresence(
            hasActivePlayback: Boolean,
            capturedAtElapsedRealtimeMs: Long,
        ): Boolean {
            if (hasActivePlayback) {
                noActivePlaybackSinceElapsedRealtimeMs = null
                return false
            }
            val absentSince = noActivePlaybackSinceElapsedRealtimeMs
            if (absentSince == null || capturedAtElapsedRealtimeMs < absentSince) {
                noActivePlaybackSinceElapsedRealtimeMs = capturedAtElapsedRealtimeMs
                return false
            }
            return capturedAtElapsedRealtimeMs - absentSince >= NO_ACTIVE_PLAYBACK_GRACE_MS
        }

        fun progress() = DiagnosticRecordingProgress(
            id = id,
            startedAt = startedAt,
            snapshotCount = snapshots.size,
            eventCount = events.size,
            droppedSnapshotCount = droppedSnapshotCount,
            droppedEventCount = droppedEventCount,
            observedEventSequenceGapCount = observedEventSequenceGapCount,
        )
    }

    companion object {
        val RECORDING_OBSERVATION = TelemetryObservation(
            refreshInterval = TelemetryRefreshInterval.ONE_SECOND,
            derivedWindowMs = 5_000,
            powerMode = TelemetryPowerMode.STANDARD,
            selection = TelemetryMetricSelection.Explicit(
                immutableSet(TelemetryMetricCatalog.allIds),
            ),
        )

        private val RECORDING_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
        private const val NO_ACTIVE_PLAYBACK_GRACE_MS = 2_000L
    }
}

private object NoActivePlaybackSignal : Exception(null, null, false, false)

private fun TelemetrySnapshot.immutableCopyWithoutEvents(): TelemetrySnapshot = TelemetrySnapshot(
    capturedAtEpochMs = capturedAtEpochMs,
    capturedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs,
    playbackSessionId = playbackSessionId,
    metrics = immutableList(metrics.map(TelemetryMetric::immutableCopy)),
    recentEvents = emptyList(),
)

private fun TelemetryMetric.immutableCopy(): TelemetryMetric = copy(evidence = evidence.immutableCopy())

private fun TelemetryEvidence.immutableCopy(): TelemetryEvidence = when (this) {
    is TelemetryEvidence.Measured -> copy(
        reading = reading.immutableCopy(),
        source = source.copy(detail = null),
    )
    is TelemetryEvidence.Derived -> copy(
        reading = reading.immutableCopy(),
        source = source.copy(detail = null),
        inputMetricIds = immutableSet(inputMetricIds),
        operands = immutableMap(operands),
    )
    is TelemetryEvidence.Estimated -> copy(
        reading = reading.immutableCopy(),
        source = source.copy(detail = null),
        inputMetricIds = immutableSet(inputMetricIds),
    )
    is TelemetryEvidence.Unavailable -> copy(source = source?.copy(detail = null), detail = null)
}

private fun TelemetryReading.immutableCopy(): TelemetryReading = when (this) {
    is TelemetryReading.Text,
    is TelemetryReading.Flag,
    is TelemetryReading.Integer,
    is TelemetryReading.Decimal -> this
    is TelemetryReading.UsbInventory -> TelemetryReading.UsbInventory(
        value = UsbInventoryReading(
            hostDevices = immutableList(
                value.hostDevices.map { device ->
                    UsbHostDeviceReading(
                        snapshotKey = device.snapshotKey,
                        manufacturerName = device.manufacturerName,
                        productName = device.productName,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        permissionGranted = device.permissionGranted,
                        audioInterfaces = immutableList(
                            device.audioInterfaces.map { audioInterface ->
                                UsbAudioInterfaceReading(
                                    interfaceClass = audioInterface.interfaceClass,
                                    interfaceSubclass = audioInterface.interfaceSubclass,
                                    interfaceProtocol = audioInterface.interfaceProtocol,
                                )
                            },
                        ),
                    )
                },
            ),
            audioOutputEndpoints = immutableList(
                value.audioOutputEndpoints.map { endpoint ->
                    UsbAudioOutputEndpointReading(
                        snapshotKey = endpoint.snapshotKey,
                        productName = endpoint.productName,
                        type = endpoint.type,
                        sampleRatesHz = immutableList(endpoint.sampleRatesHz),
                        arbitrarySampleRate = endpoint.arbitrarySampleRate,
                        channelCounts = immutableList(endpoint.channelCounts),
                        arbitraryChannelCount = endpoint.arbitraryChannelCount,
                        encodings = immutableList(endpoint.encodings),
                        arbitraryEncoding = endpoint.arbitraryEncoding,
                    )
                },
            ),
        ),
    )
}

private fun TelemetryEvent.immutableCopy(): TelemetryEvent = copy(
    relatedMetricIds = immutableSet(relatedMetricIds),
)

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private fun Long.saturatedAdd(nonNegativeValue: Long): Long =
    if (Long.MAX_VALUE - this < nonNegativeValue) Long.MAX_VALUE else this + nonNegativeValue
