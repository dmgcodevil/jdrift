package dev.jdrift.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class HistoryAnalyzerTest {
    private fun report(day: Int, evaluation: Evaluation?, key: String = "v1") = HistoricalReport(
        "report-$day", Instant.parse("2026-01-01T00:00:00Z").plusSeconds(day * 86400L),
        RecordingContext("service", "prod"), key,
        evaluation?.let { listOf(FindingEvaluation("cpu:hot", it)) } ?: emptyList(),
    )

    @Test fun `capture order recognizes recurrence without claiming a fix`() {
        val reports = listOf(report(3, Evaluation.PRESENT), report(0, Evaluation.PRESENT),
            report(2, Evaluation.ABSENT), report(1, Evaluation.PRESENT))
        assertEquals(listOf(Transition.FIRST_OBSERVED, Transition.CONTINUING,
            Transition.NO_LONGER_OBSERVED, Transition.RECURRING),
            HistoryAnalyzer().analyze(reports).map { it.transition })
    }

    @Test fun `missing evaluation cannot establish absence`() {
        assertEquals(Transition.INSUFFICIENT_EVIDENCE,
            HistoryAnalyzer().analyze(listOf(report(0, Evaluation.PRESENT), report(1, null))).last().transition)
    }

    @Test fun `incompatible analysis cannot establish improvement`() {
        assertEquals(Transition.INSUFFICIENT_EVIDENCE,
            HistoryAnalyzer().analyze(listOf(report(0, Evaluation.PRESENT),
                report(1, Evaluation.ABSENT, "v2"))).last().transition)
    }

    @Test fun `unrelated services cannot share history`() {
        assertThrows(IllegalArgumentException::class.java) {
            HistoryAnalyzer().analyze(listOf(report(0, Evaluation.PRESENT),
                report(1, Evaluation.PRESENT).copy(context = RecordingContext("other", "prod"))))
        }
    }
}
