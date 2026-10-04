package io.github.sumirenokai.vesqen.reports

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DefaultDeviceReporterTest {
    @Test fun `preview system share email and uploader receive the same immutable bytes with one capture`() = runBlocking {
        val delivered = mutableListOf<Pair<Boolean, ByteArray>>()
        val uploader = FakeDeviceReportUploader()
        var captures = 0
        val reporter = DefaultDeviceReporter(CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            capture = { captures++; reportData() },
            sharer = DeviceReportSharer { artifact, email -> delivered += email to artifact.copyBytes(); null }, uploader = uploader)
        reporter.generate()
        val preview = (reporter.snapshot.value.state as DeviceReportState.Preview).report
        val expected = preview.previewText.toByteArray(Charsets.UTF_8)
        preview.copyBytes().fill(0)
        reporter.send(DeviceReportDelivery.SHARE)
        assertEquals(DeviceReportState.Sent(preview, DeviceReportDelivery.SHARE), reporter.snapshot.value.state)
        reporter.send(DeviceReportDelivery.EMAIL)
        reporter.send(DeviceReportDelivery.UPLOAD)
        assertEquals(DeviceReportState.Sent(preview, DeviceReportDelivery.UPLOAD, uploader.reportId), reporter.snapshot.value.state)
        assertEquals(1, captures)
        assertEquals(listOf(false, true), delivered.map { it.first })
        delivered.forEach { assertArrayEquals(expected, it.second) }
        assertArrayEquals(expected, uploader.uploads.single())
        assertSame(preview, (reporter.snapshot.value.state as DeviceReportState.Sent).report)
        uploader.uploads.single().fill(0)
        assertArrayEquals(expected, uploader.uploads.single())
    }

    @Test fun `artifact does not retain mutable input or expose writable storage`() {
        val input = "你好\n".toByteArray()
        val artifact = DeviceReportArtifact(input)
        input.fill(0)
        artifact.copyBytes().fill(0)
        assertEquals("你好\n", artifact.previewText)
        assertArrayEquals("你好\n".toByteArray(), artifact.copyBytes())
        assertEquals(DeviceReportArtifact("你好\n".toByteArray()).sha256, artifact.sha256)
    }

    @Test fun `invalid utf8 cannot create a preview that differs from the sent payload`() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { DeviceReportArtifact(byteArrayOf(0xc3.toByte())) }
    }

    @Test fun `selection change cancels pending generation and invalidates previous preview`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var captures = 0
        var sends = 0
        val reporter = DefaultDeviceReporter(CoroutineScope(coroutineContext + Dispatchers.Unconfined), {
            captures++
            release.await()
            reportData()
        }, DeviceReportSharer { _, _ -> sends++; null })
        reporter.generate()
        reporter.generate()
        assertEquals(DeviceReportState.Generating, reporter.snapshot.value.state)
        assertEquals(1, captures)
        reporter.setOptions(DeviceReportOptions(includeFileNames = true))
        release.complete(Unit)
        yield()
        assertEquals(DeviceReportState.Editing, reporter.snapshot.value.state)
        reporter.send(DeviceReportDelivery.SHARE)
        assertEquals(0, sends)
        reporter.generate()
        assertTrue(reporter.snapshot.value.state is DeviceReportState.Preview)
        reporter.setOptions(DeviceReportOptions())
        assertEquals(DeviceReportState.Editing, reporter.snapshot.value.state)
    }

    @Test fun `delivery failure retains preview and retry never recaptures`() = runBlocking {
        var captures = 0
        var fail = true
        val reporter = DefaultDeviceReporter(CoroutineScope(coroutineContext + Dispatchers.Unconfined), { captures++; reportData() },
            DeviceReportSharer { _, _ -> if (fail) throw IOException("private-path") else null })
        reporter.generate()
        val preview = (reporter.snapshot.value.state as DeviceReportState.Preview).report
        reporter.send(DeviceReportDelivery.EMAIL)
        val failed = reporter.snapshot.value.state as DeviceReportState.Failed
        assertEquals(DeviceReportFailure.SHARE_FAILED, failed.reason)
        assertSame(preview, failed.report)
        fail = false
        reporter.send(DeviceReportDelivery.EMAIL)
        assertSame(preview, (reporter.snapshot.value.state as DeviceReportState.Sent).report)
        assertEquals(1, captures)
        reporter.send(DeviceReportDelivery.UPLOAD)
        assertEquals(DeviceReportFailure.UPLOAD_NOT_CONFIGURED, (reporter.snapshot.value.state as DeviceReportState.Failed).reason)
        reporter.discard()
        assertEquals(DeviceReportState.Editing, reporter.snapshot.value.state)
    }

    @Test fun `duplicate sends are ignored while sending and generation failures use codes only`() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        val reporter = DefaultDeviceReporter(CoroutineScope(coroutineContext + Dispatchers.Unconfined), { reportData() }, DeviceReportSharer { _, _ ->
            calls++; pending.await(); null
        })
        reporter.generate()
        reporter.send(DeviceReportDelivery.SHARE)
        reporter.send(DeviceReportDelivery.EMAIL)
        assertTrue(reporter.snapshot.value.state is DeviceReportState.Sending)
        assertEquals(1, calls)
        reporter.discard()
        pending.complete(Unit)
        yield()
        assertEquals(DeviceReportState.Editing, reporter.snapshot.value.state)
        val failing = DefaultDeviceReporter(this, { throw IOException("/private/track.flac") }, DeviceReportSharer { _, _ -> null })
        failing.generate()
        yield()
        assertEquals(DeviceReportState.Failed(DeviceReportFailure.GENERATION_FAILED), failing.snapshot.value.state)
    }

    @Test fun `debug fake lets UI drive preview and errors without IO`() {
        val fake = FakeDeviceReporter()
        fake.setOptions(DeviceReportOptions(recentErrors = true))
        fake.generate()
        assertEquals(DeviceReportState.Generating, fake.snapshot.value.state)
        val report = DeviceReportGenerator.generate(reportData(), fake.snapshot.value.options)
        fake.emit(DeviceReportState.Preview(report))
        fake.send(DeviceReportDelivery.EMAIL)
        assertEquals(DeviceReportState.Sending(report, DeviceReportDelivery.EMAIL), fake.snapshot.value.state)
        fake.emit(DeviceReportState.Failed(DeviceReportFailure.NO_SHARE_APPLICATION, report))
        fake.discard()
        assertEquals(DeviceReportState.Editing, fake.snapshot.value.state)
    }
}
