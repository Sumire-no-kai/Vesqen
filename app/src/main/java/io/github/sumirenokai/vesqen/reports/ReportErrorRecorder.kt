package io.github.sumirenokai.vesqen.reports

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Bounded, event-driven IO queue. Player callbacks never wait for file IO or start sampling. */
internal class ReportErrorRecorder(
    scope: CoroutineScope,
    private val store: ErrorHistoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    exits: () -> Pair<ExitHistoryAvailability, List<ReportErrorEvent>>,
) {
    private sealed interface Command {
        data class Append(val event: ReportErrorEvent, val name: (() -> String?)?) : Command
        data class Read(val result: CompletableDeferred<ErrorHistorySnapshot>) : Command
    }
    private val queue = Channel<Command>(32)
    private val availability = AtomicReference(ErrorHistoryAvailability.AVAILABLE)
    private var exitAvailability = ExitHistoryAvailability.AVAILABLE

    init {
        scope.launch(Dispatchers.IO) {
            val (status, events) = exits()
            exitAvailability = status
            storage {
                store.read(clock())
                events.forEach { store.append(it, clock()) }
            }
            for (command in queue) {
                when (command) {
                    is Command.Append -> storage {
                        store.append(command.event.copy(fileName = command.name?.invoke() ?: command.event.fileName), clock())
                    }
                    is Command.Read -> {
                        val retained = storage { store.read(clock()) } ?: emptyList()
                        command.result.complete(ErrorHistorySnapshot(retained, availability.get(), exitAvailability))
                    }
                }
            }
        }
    }

    private inline fun <T> storage(block: () -> T): T? = try {
        block().also {
            availability.compareAndSet(ErrorHistoryAvailability.STORAGE_UNAVAILABLE, ErrorHistoryAvailability.AVAILABLE)
            if (store.discardedUnreadableJournal) {
                availability.compareAndSet(ErrorHistoryAvailability.AVAILABLE, ErrorHistoryAvailability.JOURNAL_RESET)
            }
        }
    } catch (_: IOException) {
        availability.set(ErrorHistoryAvailability.STORAGE_UNAVAILABLE)
        null
    }

    fun record(event: ReportErrorEvent, resolveFileName: (() -> String?)? = null) {
        if (queue.trySend(Command.Append(event, resolveFileName)).isFailure) {
            availability.compareAndSet(ErrorHistoryAvailability.AVAILABLE, ErrorHistoryAvailability.QUEUE_OVERFLOW)
        }
    }

    suspend fun snapshot(): ErrorHistorySnapshot {
        val result = CompletableDeferred<ErrorHistorySnapshot>()
        queue.send(Command.Read(result))
        return result.await()
    }
}
