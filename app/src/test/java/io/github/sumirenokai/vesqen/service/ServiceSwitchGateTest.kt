package io.github.sumirenokai.vesqen.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceSwitchGateTest {
    private val enabled = ServiceStatus(ServiceState.ENABLED, ServiceState.ENABLED)

    @Test fun `each service follows its own entry and the reading is remembered for the UI`() = runBlocking {
        val store = MemoryStore()
        val gate = ServiceSwitchGate(Source(ServiceStatus(ServiceState.PAUSED, ServiceState.ENABLED)), store, 12)
        assertEquals(ServiceState.PAUSED, gate.check(ReportService.USAGE_PINGS))
        assertEquals(ServiceState.ENABLED, gate.check(ReportService.REPORT_UPLOADS))
        assertEquals(KnownServiceStatus(ServiceState.PAUSED, ServiceState.ENABLED), gate.known.value)
        // A restart shows the last reading before anything is read again.
        assertEquals(gate.known.value, ServiceSwitchGate(Source(enabled), store, 12).known.value)
    }

    @Test fun `an unreadable file sends nothing and leaves the UI as it was`() = runBlocking {
        val gate = ServiceSwitchGate(Source(null), MemoryStore(), 12)
        assertNull(gate.check(ReportService.USAGE_PINGS))
        assertEquals(KnownServiceStatus(), gate.known.value)
    }

    @Test fun `one reading serves ten minutes and a pause lifts on the next reading`() = runBlocking {
        var now = 1_000L
        val source = Source(ServiceStatus(ServiceState.PAUSED, ServiceState.PAUSED))
        val gate = ServiceSwitchGate(source, MemoryStore(), 12) { now }
        gate.check(ReportService.REPORT_UPLOADS)
        now += ServiceSwitchGate.ReuseMs - 1
        gate.check(ReportService.REPORT_UPLOADS)
        assertEquals(1, source.reads)
        source.status = enabled
        now += 1
        assertEquals(ServiceState.ENABLED, gate.check(ReportService.REPORT_UPLOADS))
        assertEquals(2, source.reads)
        assertEquals(KnownServiceStatus(ServiceState.ENABLED, ServiceState.ENABLED), gate.known.value)
    }

    @Test fun `a retired service is not read again by this version, a newer one reads it`() = runBlocking {
        var now = 0L
        val store = MemoryStore()
        val source = Source(ServiceStatus(ServiceState.RETIRED, ServiceState.ENABLED))
        val gate = ServiceSwitchGate(source, store, 12) { now }
        assertEquals(ServiceState.RETIRED, gate.check(ReportService.USAGE_PINGS))
        now += ServiceSwitchGate.ReuseMs
        source.status = enabled
        assertEquals(ServiceState.RETIRED, gate.check(ReportService.USAGE_PINGS))
        assertEquals(1, source.reads)
        // The same version after a restart still treats it as retired, without reading.
        val restarted = ServiceSwitchGate(source, store, 12) { now }
        assertEquals(ServiceState.RETIRED, restarted.check(ReportService.USAGE_PINGS))
        assertEquals(1, source.reads)
        // An update forgets the old reading and asks again.
        val updated = ServiceSwitchGate(source, store, 13) { now }
        assertEquals(KnownServiceStatus(), updated.known.value)
        assertEquals(ServiceState.ENABLED, updated.check(ReportService.USAGE_PINGS))
        assertEquals(2, source.reads)
    }

    private class Source(var status: ServiceStatus?) : ServiceSwitchSource {
        var reads = 0
        override suspend fun read(): ServiceStatus? { reads++; return status }
    }

    private class MemoryStore : ServiceSwitchStore {
        private var value: StoredServiceSwitch? = null
        override fun read() = value
        override fun write(value: StoredServiceSwitch) { this.value = value }
    }
}
