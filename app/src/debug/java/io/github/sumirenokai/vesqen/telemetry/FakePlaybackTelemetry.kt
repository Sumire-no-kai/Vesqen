package io.github.sumirenokai.vesqen.telemetry

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/** Debug/test Adapter for previews, JVM tests and instrumentation tests. */
class FakePlaybackTelemetry(
    initialSnapshot: TelemetrySnapshot = TelemetrySnapshot.empty(),
) : PlaybackTelemetry {
    private val snapshots = MutableStateFlow(initialSnapshot)
    private val observations = CopyOnWriteArrayList<TelemetryObservation>()
    private val activeObservations = AtomicInteger()

    val observationHistory: List<TelemetryObservation>
        get() = observations.toList()

    val activeObservationCount: Int
        get() = activeObservations.get()

    override fun observe(observation: TelemetryObservation): Flow<TelemetrySnapshot> = snapshots
        .onStart {
            observations += observation
            activeObservations.incrementAndGet()
        }
        .onCompletion {
            activeObservations.decrementAndGet()
        }

    fun publish(snapshot: TelemetrySnapshot) {
        snapshots.value = snapshot
    }
}
