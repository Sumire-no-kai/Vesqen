package io.github.sumirenokai.vesqen.reports

import io.github.sumirenokai.vesqen.chain.AppSegmentAssessment
import io.github.sumirenokai.vesqen.chain.AppSegmentCheck
import io.github.sumirenokai.vesqen.chain.AppSegmentCondition
import io.github.sumirenokai.vesqen.chain.AppSegmentStatus
import io.github.sumirenokai.vesqen.chain.RouteKind
import io.github.sumirenokai.vesqen.chain.SegmentEvidenceIssue
import io.github.sumirenokai.vesqen.chain.SegmentReason
import io.github.sumirenokai.vesqen.chain.SourceCompression
import io.github.sumirenokai.vesqen.chain.assessAppSegment
import io.github.sumirenokai.vesqen.telemetry.TelemetryMetricCatalog as Metrics
import io.github.sumirenokai.vesqen.telemetry.TelemetrySnapshot

/** Enum-only projection; original values, confidence and clocks stay in chainEvidence. */
internal fun deviceReportAppSegment(snapshot: TelemetrySnapshot?): Map<String, Any?> {
    val assessment = if (snapshot != null) {
        // Evaluate the captured instant, not the later preview/send time. No second observation.
        assessAppSegment(snapshot, nowElapsedRealtimeMs = snapshot.capturedAtElapsedRealtimeMs, maxSnapshotAgeMs = 0)
    } else {
        // Without a snapshot even the absence of a transition is unknown. Do not synthesize a
        // healthy empty snapshot; chainEvidence retains the specific capture-unavailable reason.
        AppSegmentAssessment(
            status = AppSegmentStatus.UNKNOWN,
            sourceCompression = SourceCompression.UNKNOWN,
            checks = AppSegmentCondition.entries.map {
                AppSegmentCheck(it, AppSegmentStatus.UNKNOWN, SegmentReason.MISSING)
            },
            route = RouteKind.UNKNOWN,
            bluetooth = null,
            routeEvidence = emptyMap(),
            routeIssues = listOf(SegmentEvidenceIssue(Metrics.ROUTE_SELECTED_SYSTEM_TYPE, SegmentReason.MISSING)),
        )
    }
    fun issues(values: List<SegmentEvidenceIssue>) = values.map {
        mapOf("metricId" to it.metricId.value, "reason" to it.reason.name)
    }
    return linkedMapOf(
        "status" to assessment.status.name,
        "sourceCompression" to assessment.sourceCompression.name,
        "sourceCompressionMetricId" to Metrics.DECODER_INPUT_MIME.value,
        "route" to assessment.route.name,
        "checks" to assessment.checks.map { check -> linkedMapOf(
            "condition" to check.condition.name,
            "status" to check.status.name,
            "reason" to check.reason?.name,
            "metricIds" to check.evidence.keys.map { it.value }.sorted(),
            "issues" to issues(check.issues),
        ) },
        "routeMetricIds" to assessment.routeEvidence.keys.map { it.value }.sorted(),
        "routeIssues" to issues(assessment.routeIssues),
        "bluetooth" to assessment.bluetooth?.let { mapOf("status" to it.status.name) },
    )
}
