package io.github.sumirenokai.vesqen.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibraryCatalogOperationGateTest {
    @Test
    fun `close waits for active operation and rejects all new operations`() = runBlocking {
        val gate = LibraryCatalogOperationGate()
        val operationEntered = CompletableDeferred<Unit>()
        val releaseOperation = CompletableDeferred<Unit>()
        val activeOperation = launch {
            gate.withOpenCatalog {
                operationEntered.complete(Unit)
                releaseOperation.await()
            }
        }
        operationEntered.await()

        assertTrue(gate.requestClose())
        var storeClosed = false
        val close = launch {
            gate.closeWhenIdle { storeClosed = true }
        }
        yield()
        assertFalse(storeClosed)
        try {
            gate.withOpenCatalog { fail("operation must not run after close is requested") }
            fail("closed catalog accepted an operation")
        } catch (_: IllegalStateException) {
            // Expected: the first open check fails without waiting behind the active scan.
        }

        releaseOperation.complete(Unit)
        activeOperation.join()
        close.join()
        assertTrue(storeClosed)
    }

    @Test
    fun `close request is idempotent`() {
        val gate = LibraryCatalogOperationGate()

        assertTrue(gate.requestClose())
        assertFalse(gate.requestClose())
    }
}
