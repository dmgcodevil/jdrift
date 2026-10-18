package dev.jdrift.domain

import kotlinx.serialization.Serializable

@Serializable
data class WorkObservation(val startEpochNanos: Long, val endEpochNanos: Long, val operations: Long,
    val threadCpuNanos: Long?, val workload: String, val subject: String, val loadProfile: String? = null)

@Serializable
data class CaptureMetadata(
    val application: String? = null,
    val environment: String? = null,
    val build: String? = null,
    val jvmVersion: String? = null,
    val executionSampleSettings: List<String> = emptyList(),
    val work: List<WorkObservation> = emptyList(),
    val limitations: List<String> = emptyList(),
)

@Serializable
data class StoredReport(val id: String, val context: RecordingContext, val importedAtEpochMillis: Long,
    val analysis: RecordingAnalysis, val schemaVersion: Int = 1) {
    val capturedAtEpochNanos: Long get() = analysis.temporal.windows.first().startEpochNanos
}

data class HistorySelection(val last: Int? = null, val fromEpochNanos: Long? = null, val untilEpochNanos: Long? = null) {
    init {
        require(last == null || last > 0)
        require(last == null || (fromEpochNanos == null && untilEpochNanos == null))
        require(fromEpochNanos == null || untilEpochNanos == null || fromEpochNanos < untilEpochNanos)
    }
}

@Serializable
data class MetricPoint(val key: String, val value: Double?, val unit: String, val basis: String)
@Serializable
data class MetricChange(val key: String, val baseline: Double?, val candidate: Double?,
    val absoluteChange: Double?, val relativeChangePercent: Double?, val assessment: String, val reason: String)
@Serializable
data class ActivityChange(val method: String, val status: String, val stackIds: List<String>)
@Serializable
data class HistoryPoint(val reportId: String, val capturedAtEpochNanos: Long, val build: String?,
    val metrics: List<MetricPoint>, val changes: List<MetricChange>, val activities: List<ActivityChange>)
@Serializable
data class ProgressReport(val baselineReportId: String, val points: List<HistoryPoint>,
    val policyVersion: String = "history-1", val limitations: List<String>)

object PerformanceHistory {
    fun metrics(report: StoredReport): List<MetricPoint> {
        val windows = report.analysis.temporal.windows
        val duration = (windows.last().endEpochNanosExclusive - windows.first().startEpochNanos) / 1e9
        val cpuCount = windows.sumOf { it.cpuObservationCount }
        val cpu = if (cpuCount > 0) windows.sumOf {
            (it.meanRecordedJvmCpuFraction ?: 0.0) * it.cpuObservationCount
        } / cpuCount else null
        val work = report.analysis.metadata.work
        val operations = work.sumOf { it.operations }
        val workValid = work.isNotEmpty() && work.all {
            it.threadCpuNanos != null && it.threadCpuNanos >= 0 && it.operations >= 0 && it.endEpochNanos > it.startEpochNanos
        } && work.sortedBy { it.startEpochNanos }.zipWithNext().all { (left, right) -> left.endEpochNanos <= right.startEpochNanos }
        val cost = if (workValid && operations > 0) work.sumOf { it.threadCpuNanos!! }.toDouble() / operations else null
        return listOf(
            MetricPoint("jvm.cpu.observationMean", cpu, "fraction", "Recorded observations; not time weighted"),
            MetricPoint("execution.samples.rate", windows.sumOf { it.executionSamples } / duration, "samples/s", "Observed event envelope; not CPU time"),
            MetricPoint("work.threadCpu.perOperation", cost, "ns/operation", "Instrumented worker-thread CPU divided by completed work batches"),
        )
    }

    fun analyze(history: List<StoredReport>, baselineId: String, selection: HistorySelection): ProgressReport {
        require(history.isNotEmpty()) { "No reports for this context" }
        require(history.map { it.context }.distinct().size == 1) { "Mixed contexts" }
        require(history.all { it.analysis.temporal.windows.isNotEmpty() })
        // Latest imported analysis per original recording; old analyzer versions do not add observations.
        val ordered = history.groupBy { it.analysis.sha256 }.values.map { versions ->
            versions.maxWith(compareBy({ it.importedAtEpochMillis }, { it.id }))
        }.sortedWith(compareBy({ it.capturedAtEpochNanos }, { it.id }))
        val baseline = ordered.singleOrNull { it.id == baselineId }
            ?: error("Baseline must identify the selected analysis of a recording in this context")
        val selected = if (selection.last != null) ordered.takeLast(selection.last) else ordered.filter {
            (selection.fromEpochNanos == null || it.capturedAtEpochNanos >= selection.fromEpochNanos) &&
                (selection.untilEpochNanos == null || it.capturedAtEpochNanos < selection.untilEpochNanos)
        }
        require(selected.isNotEmpty()) { "No reports in selected history" }
        val seen = mutableSetOf<String>()
        var previous = emptySet<String>()
        val activityHistory = mutableMapOf<String, List<ActivityChange>>()
        for (report in ordered) {
            val candidates = report.analysis.temporal.methods.filter { it.pattern != SamplePattern.INSUFFICIENT_SAMPLES }
            val current = candidates.map { it.method }.toSet()
            activityHistory[report.id] = candidates.map {
                val status = when {
                    it.method !in seen -> "FIRST_OBSERVED_IN_RETAINED_HISTORY"
                    it.method !in previous -> "REOBSERVED_AFTER_GAP"
                    else -> "CONTINUING"
                }
                ActivityChange(it.method, status, it.stackIds)
            } + (previous - current).sorted().map { ActivityChange(it, "NOT_OBSERVED_IN_THIS_REPORT", emptyList()) }
            seen += current
            previous = current
        }
        val baseMetrics = metrics(baseline).associateBy { it.key }
        return ProgressReport(baseline.id, selected.map { report ->
            val values = metrics(report)
            HistoryPoint(report.id, report.capturedAtEpochNanos, report.analysis.metadata.build, values,
                values.map { compare(baseline, report, baseMetrics.getValue(it.key), it) }, activityHistory.getValue(report.id))
        }, limitations = listOf(
            "Sampled method activity is a candidate for investigation, not a diagnosed performance problem.",
            "No longer observed does not mean fixed; reobserved after a gap does not prove a verified recurrence.",
            "Resource efficiency assessment currently requires matching instrumented workload and JVM, and at least 100 operations in each recording.",
            "A 10% effect threshold flags measured changes, not statistical significance or causal attribution. Customer impact remains unverified.",
            "Novelty uses all retained earlier history in this context, including reports outside the selected display window.",
        ))
    }

    private fun compare(base: StoredReport, candidate: StoredReport, left: MetricPoint, right: MetricPoint): MetricChange {
        val delta = if (left.value != null && right.value != null) right.value - left.value else null
        val relative = if (delta != null && left.value != 0.0) 100 * delta / left.value!! else null
        val baseMeta = base.analysis.metadata
        val candidateMeta = candidate.analysis.metadata
        val reason = when {
            left.value == null || right.value == null -> "Measurement unavailable"
            base.analysis.engine != candidate.analysis.engine -> "Analyzer versions differ"
            baseMeta.jvmVersion == null || baseMeta.jvmVersion != candidateMeta.jvmVersion -> "JVM context missing or different"
            left.key != "work.threadCpu.perOperation" -> "Descriptive change only; CPU load and sample rate are not workload-normalized efficiency"
            baseMeta.work.map { it.workload }.distinct().singleOrNull() == null -> "Baseline workload is missing or mixed"
            baseMeta.work.any { it.workload.isBlank() } -> "Workload identity is blank"
            baseMeta.work.map { it.workload }.toSet() != candidateMeta.work.map { it.workload }.toSet() -> "Workloads differ"
            baseMeta.work.map { it.loadProfile }.distinct().singleOrNull().isNullOrBlank() -> "Baseline load profile missing or mixed"
            baseMeta.work.map { it.loadProfile }.toSet() != candidateMeta.work.map { it.loadProfile }.toSet() -> "Load profiles differ or candidate profile missing"
            baseMeta.work.map { it.subject }.toSet() != candidateMeta.work.map { it.subject }.toSet() -> "Measured worker scopes differ"
            baseMeta.work.sumOf { it.operations } < 100 || candidateMeta.work.sumOf { it.operations } < 100 -> "Insufficient completed work"
            else -> null
        }
        val assessment = when {
            reason != null -> "INSUFFICIENT_EVIDENCE"
            base.id == candidate.id -> "BASELINE"
            relative == null -> "INSUFFICIENT_EVIDENCE"
            relative <= -10 -> "MEASURED_IMPROVEMENT"
            relative >= 10 -> "POTENTIAL_REGRESSION"
            else -> "NO_MATERIAL_CHANGE"
        }
        return MetricChange(left.key, left.value, right.value, delta, relative, assessment,
            reason ?: "Comparable instrumented CPU cost per work batch; validate over repeated runs")
    }
}
