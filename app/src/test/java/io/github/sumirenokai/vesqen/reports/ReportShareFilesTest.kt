package io.github.sumirenokai.vesqen.reports

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReportShareFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `shared file contains precisely previewed bytes with no input filenames`() {
        val directory = temporary.newFolder()
        val report = DeviceReportGenerator.generate(reportData(), DeviceReportOptions(recentErrors = true))
        val file = ReportShareFiles(directory).prepare(report, System.currentTimeMillis())
        assertEquals("device-report-${report.sha256}.json", file.name)
        assertArrayEquals(report.previewText.toByteArray(Charsets.UTF_8), file.readBytes())
        assertEquals(listOf(file), directory.listFiles()!!.toList())
        assertEquals(file, ReportShareFiles(directory).prepare(report, System.currentTimeMillis()))
    }

    @Test fun `share cache bounds file count and removes old artifacts on next share`() {
        val directory = temporary.newFolder()
        val files = ReportShareFiles(directory)
        repeat(8) { files.prepare(DeviceReportArtifact("report-$it".toByteArray()), System.currentTimeMillis()) }
        assertEquals(3, directory.listFiles()!!.size)
        directory.listFiles()!!.forEach { assertTrue(it.setLastModified(100)) }
        val final = files.prepare(DeviceReportArtifact("last".toByteArray()), ReportShareFiles.MAX_AGE_MS + 101)
        assertEquals(listOf(final), directory.listFiles()!!.toList())
    }
}
