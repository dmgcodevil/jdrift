package dev.jdrift.domain

import kotlinx.serialization.Serializable

@Serializable
data class StackFrame(val className: String, val methodName: String, val descriptor: String,
    val line: Int?, val bytecodeIndex: Int?) {
    val methodKey: String get() = "$className#$methodName$descriptor"
}

@Serializable
data class StackEvidence(val id: String, val frames: List<StackFrame>, val truncated: Boolean?)

@Serializable
data class StackCount(val stackId: String, val samples: Long)

@Serializable
data class MeasurementWindow(
    val index: Int,
    val startEpochNanos: Long,
    val endEpochNanosExclusive: Long,
    val executionSamples: Long,
    val samplesWithoutStack: Long,
    val stacks: List<StackCount>,
    val cpuObservationCount: Long,
    /** Arithmetic mean of recorded JVM user+system fractions, NOT time-weighted CPU utilization. */
    val meanRecordedJvmCpuFraction: Double?,
)

@Serializable
enum class SamplePattern { SUSTAINED_SAMPLES, LOCALIZED_SAMPLES, INTERMITTENT_SAMPLES, INSUFFICIENT_SAMPLES }

@Serializable
data class MethodActivity(
    val method: String,
    val samples: Long,
    val activeWindowCount: Int,
    val consideredWindowCount: Int,
    val pattern: SamplePattern,
    val stackIds: List<String>,
)

@Serializable
data class TemporalEvidence(
    val recordingSha256: String,
    val schemaVersion: String = "temporal-1",
    val policyVersion: String = "sample-pattern-1",
    val windowNanos: Long,
    val boundsSource: String = "observed-event-envelope",
    val windows: List<MeasurementWindow>,
    val stacks: List<StackEvidence>,
    val methods: List<MethodActivity>,
    val limitations: List<String>,
)

/** Describes sample distribution only; it does not diagnose CPU pressure or prove idle periods. */
object SamplePatternAnalyzer {
    fun analyze(windows: List<MeasurementWindow>, stacks: List<StackEvidence>, windowNanos: Long): List<MethodActivity> {
        val byId = stacks.associateBy { it.id }
        val fullWindows = windows.filter { it.endEpochNanosExclusive - it.startEpochNanos >= windowNanos }
        val totals = mutableMapOf<String, Long>()
        val active = mutableMapOf<String, Int>()
        val evidence = mutableMapOf<String, MutableSet<String>>()
        for (window in windows) {
            val counts = mutableMapOf<String, Long>()
            for (entry in window.stacks) {
                // Inclusive attribution, with recursion counted once per sampled stack.
                for (method in byId.getValue(entry.stackId).frames.map { it.methodKey }.distinct()) {
                    totals.merge(method, entry.samples, Long::plus)
                    counts.merge(method, entry.samples, Long::plus)
                    evidence.getOrPut(method) { sortedSetOf() }.add(entry.stackId)
                }
            }
            if (window.endEpochNanosExclusive - window.startEpochNanos >= windowNanos) counts.filterValues { it >= 3 }.keys.forEach {
                active.merge(it, 1, Int::plus)
            }
        }
        return totals.map { (method, total) ->
            val occupied = active[method] ?: 0
            val fraction = occupied.toDouble() / fullWindows.size.coerceAtLeast(1)
            val pattern = when {
                total < 10 || fullWindows.size < 5 || occupied == 0 -> SamplePattern.INSUFFICIENT_SAMPLES
                fraction >= 0.7 -> SamplePattern.SUSTAINED_SAMPLES
                fraction <= 0.3 -> SamplePattern.LOCALIZED_SAMPLES
                else -> SamplePattern.INTERMITTENT_SAMPLES
            }
            MethodActivity(method, total, occupied, fullWindows.size, pattern, evidence.getValue(method).toList())
        }.sortedWith(compareByDescending<MethodActivity> { it.samples }.thenBy { it.method })
    }
}
