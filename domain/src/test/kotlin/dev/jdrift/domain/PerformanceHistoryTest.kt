package dev.jdrift.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PerformanceHistoryTest {
    private fun report(day: Int, cost: Long = 100, operations: Long = 1000, method: String? = "hot", jvm: String? = "21"): StoredReport {
        val start = day * 86_400_000_000_000L
        val temporal = TemporalEvidence("hash-$day", windowNanos = 1_000_000_000,
            windows = listOf(MeasurementWindow(0, start, start + 10_000_000_000, operations, 0, emptyList(), 1, operations / 10000.0)),
            stacks = emptyList(), methods = method?.let { listOf(MethodActivity(it, 100, 10, 10, SamplePattern.SUSTAINED_SAMPLES, emptyList())) } ?: emptyList(), limitations = emptyList())
        return StoredReport("r$day", RecordingContext("service", "prod"), (100 - day).toLong(),
            RecordingAnalysis("hash-$day", "test-v1", emptyList(), emptyList(), temporal,
                CaptureMetadata(jvmVersion = jvm, work = listOf(WorkObservation(start, start + 10_000_000_000,
                    operations, operations * cost, "work-v1", "worker", "saturated")))))
    }

    @Test fun `history tracks improvement regression and new activity in capture order`() {
        val results = PerformanceHistory.analyze(listOf(report(3, 130, method = "new"), report(1), report(2, 60)), "r1", HistorySelection())
        assertEquals(listOf("r1", "r2", "r3"), results.points.map { it.reportId })
        assertEquals(listOf("BASELINE", "MEASURED_IMPROVEMENT", "POTENTIAL_REGRESSION"),
            results.points.map { it.changes.last().assessment })
        assertEquals("FIRST_OBSERVED_IN_RETAINED_HISTORY", results.points.last().activities.first { it.method == "new" }.status)
    }

    @Test fun `lower load alone is not an efficiency improvement`() {
        val result = PerformanceHistory.analyze(listOf(report(1), report(2, operations = 200)), "r1", HistorySelection(last = 1)).points.single()
        assertEquals("NO_MATERIAL_CHANGE", result.changes.last().assessment)
        assertTrue(result.changes.first().absoluteChange!! < 0)
        assertEquals("INSUFFICIENT_EVIDENCE", result.changes.first().assessment)
    }

    @Test fun `novelty remembers reports outside selected range`() {
        val result = PerformanceHistory.analyze(listOf(report(1), report(2, method = null), report(3)), "r1",
            HistorySelection(fromEpochNanos = 3 * 86_400_000_000_000L, untilEpochNanos = 4 * 86_400_000_000_000L))
        assertEquals("REOBSERVED_AFTER_GAP", result.points.single().activities.single().status)
    }

    @Test fun `missing context prevents efficiency claims and zero baseline has no relative percentage`() {
        val missing = PerformanceHistory.analyze(listOf(report(1), report(2, cost = 20, jvm = null)), "r1", HistorySelection(last = 1))
        assertEquals("INSUFFICIENT_EVIDENCE", missing.points.single().changes.last().assessment)
        val zero = PerformanceHistory.analyze(listOf(report(1, cost = 0), report(2)), "r1", HistorySelection(last = 1))
        assertNull(zero.points.single().changes.last().relativeChangePercent)
    }

    @Test fun `reanalyzing a recording does not add a history point`() {
        val original = report(1)
        val rerun = original.copy(id = "rerun", importedAtEpochMillis = 1000)
        val result = PerformanceHistory.analyze(listOf(original, rerun, report(2)), "rerun", HistorySelection())
        assertEquals(2, result.points.size)
        assertEquals("rerun", result.points.first().reportId)
    }

    @Test fun `overlapping work cannot be counted twice`() {
        val report = report(1)
        val metadata = report.analysis.metadata.copy(work = report.analysis.metadata.work + report.analysis.metadata.work)
        assertNull(PerformanceHistory.metrics(report.copy(analysis = report.analysis.copy(metadata = metadata))).last().value)
    }

    @Test fun `changed load profile prevents regression attribution`() {
        val candidate = report(2, cost = 1000, operations = 200)
        val metadata = candidate.analysis.metadata.copy(work = candidate.analysis.metadata.work.map { it.copy(loadProfile = "throttled") })
        val result = PerformanceHistory.analyze(listOf(report(1), candidate.copy(analysis = candidate.analysis.copy(metadata = metadata))),
            "r1", HistorySelection(last = 1))
        assertEquals("INSUFFICIENT_EVIDENCE", result.points.single().changes.last().assessment)
        assertTrue(result.points.single().changes.last().reason.contains("Load profiles differ"))
    }
}
