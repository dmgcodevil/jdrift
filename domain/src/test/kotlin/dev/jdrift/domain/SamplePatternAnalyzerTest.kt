package dev.jdrift.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SamplePatternAnalyzerTest {
    private val frame = StackFrame("example.Work", "run", "()V", 12, 0)
    private val stacks = listOf(StackEvidence("stack", listOf(frame, frame), false))
    private fun windows(counts: List<Long>) = counts.mapIndexed { index, count ->
        MeasurementWindow(index, index * 1_000_000_000L, (index + 1) * 1_000_000_000L,
            count, 0, if (count > 0) listOf(StackCount("stack", count)) else emptyList(), 0, null)
    }

    @Test fun `same total samples can be sustained or localized`() {
        val sustained = SamplePatternAnalyzer.analyze(windows(List(10) { 10L }), stacks, 1_000_000_000).single()
        val burst = SamplePatternAnalyzer.analyze(windows(List(10) { if (it == 5) 100L else 0L }), stacks, 1_000_000_000).single()
        assertEquals(100L, sustained.samples)
        assertEquals(sustained.samples, burst.samples)
        assertEquals(SamplePattern.SUSTAINED_SAMPLES, sustained.pattern)
        assertEquals(SamplePattern.LOCALIZED_SAMPLES, burst.pattern)
    }

    @Test fun `low sample counts cannot establish pattern`() {
        val activity = SamplePatternAnalyzer.analyze(windows(List(10) { if (it == 0) 3L else 0L }), stacks, 1_000_000_000).single()
        assertEquals(SamplePattern.INSUFFICIENT_SAMPLES, activity.pattern)
    }

    @Test fun `partial final window does not dilute occupancy`() {
        val values = windows(List(5) { 10L }) + MeasurementWindow(5, 5_000_000_000,
            5_000_000_001, 0, 0, emptyList(), 0, null)
        val activity = SamplePatternAnalyzer.analyze(values, stacks, 1_000_000_000).single()
        assertEquals(5, activity.consideredWindowCount)
        assertEquals(SamplePattern.SUSTAINED_SAMPLES, activity.pattern)
    }
}
