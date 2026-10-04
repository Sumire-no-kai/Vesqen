package io.github.sumirenokai.vesqen.reports

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class ReportShareFiles(private val directory: File) {
    @Synchronized
    fun prepare(report: DeviceReportArtifact, nowEpochMs: Long): File {
        Files.createDirectories(directory.toPath())
        val target = File(directory, "device-report-${report.sha256}.json")
        val pending = File(directory, "pending")
        pending.writeBytes(report.copyBytes())
        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val files = directory.listFiles() ?: throw IOException("Report share cache is unavailable")
        files.filter { it != target && it.isFile }.sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
            if (index >= 2 || nowEpochMs - file.lastModified() > MAX_AGE_MS) Files.delete(file.toPath())
        }
        return target
    }

    companion object { const val MAX_AGE_MS = 24L * 60 * 60 * 1_000 }
}
