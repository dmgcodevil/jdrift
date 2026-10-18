package dev.jdrift.jmc

import dev.jdrift.domain.*
import org.openjdk.jmc.common.item.IItemCollection
import org.openjdk.jmc.common.unit.UnitLookup
import org.openjdk.jmc.flightrecorder.JfrAttributes
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs
import java.security.MessageDigest

/** JMC-specific extraction stays in this adapter; output contains only detached domain values. */
internal class TemporalExtractor(private val windowNanos: Long = 1_000_000_000L) {
    private class Bucket {
        var samples = 0L
        var missingStacks = 0L
        val stacks = mutableMapOf<String, Long>()
        var cpuCount = 0L
        var cpuSum = 0.0
    }

    fun extract(events: IItemCollection, hash: String): TemporalEvidence {
        var start = Long.MAX_VALUE
        var end = Long.MIN_VALUE
        for (items in events) {
            val starts = JfrAttributes.START_TIME.getAccessor(items.type) ?: continue
            val ends = JfrAttributes.END_TIME.getAccessor(items.type)
            for (item in items) {
                val time = starts.getMember(item)?.longValueIn(UnitLookup.EPOCH_NS) ?: continue
                start = minOf(start, time)
                end = maxOf(end, ends?.getMember(item)?.longValueIn(UnitLookup.EPOCH_NS) ?: time)
            }
        }
        val limitations = listOf(
            "Bounds use the observed event envelope, not guaranteed full recording coverage.",
            "Zero samples does not prove idle execution or enabled sampling; event settings and gaps are not yet verified.",
            "Execution samples are inclusive stack observations, not method CPU time. Native samples are excluded.",
            "CPU means average observations within a window; they are not time-weighted and missing windows remain null.",
            "Patterns are provisional descriptions: >=3 samples per full window, >=10 total, >=5 full windows; sustained >=70%, localized <=30%.",
        )
        if (start == Long.MAX_VALUE) return TemporalEvidence(hash, windowNanos = windowNanos,
            windows = emptyList(), stacks = emptyList(), methods = emptyList(), limitations = limitations + "No timestamped events.")
        require(windowNanos > 0)
        // Add one nanosecond so the final point event is included in a half-open interval.
        val exclusiveEnd = Math.addExact(end, 1)
        val span = Math.subtractExact(exclusiveEnd, start)
        val count = (span - 1) / windowNanos + 1
        require(count <= 100_000) { "Recording exceeds 100000 temporal windows" }
        val buckets = List(count.toInt()) { Bucket() }
        val stacks = mutableMapOf<String, StackEvidence>()
        for (items in events) {
            val execution = items.type.identifier == JdkTypeIDs.EXECUTION_SAMPLE
            val cpu = items.type.identifier == JdkTypeIDs.CPU_LOAD
            if (!execution && !cpu) continue
            val times = JfrAttributes.START_TIME.getAccessor(items.type) ?: continue
            val traces = JfrAttributes.EVENT_STACKTRACE.getAccessor(items.type)
            val loads = if (cpu) JdkAttributes.JVM_TOTAL.getAccessor(items.type) else null
            for (item in items) {
                val time = times.getMember(item)?.longValueIn(UnitLookup.EPOCH_NS) ?: continue
                val bucket = buckets[((time - start) / windowNanos).toInt()]
                if (cpu) {
                    val value = loads?.getMember(item)?.doubleValueIn(UnitLookup.PERCENT_UNITY)
                    if (value != null && value.isFinite() && value in 0.0..1.0) {
                        bucket.cpuCount++
                        bucket.cpuSum += value
                    }
                } else {
                    bucket.samples++
                    val trace = traces?.getMember(item)
                    if (trace == null || trace.frames.isEmpty()) { bucket.missingStacks++; continue }
                    val frames = trace.frames.map {
                        StackFrame(it.method.type.fullName, it.method.methodName, it.method.formalDescriptor ?: "",
                            it.frameLineNumber, it.bci)
                    }
                    val truncated = when (trace.truncationState.name) {
                        "TRUNCATED" -> true
                        "NOT_TRUNCATED" -> false
                        else -> null
                    }
                    val canonical = frames.joinToString("\n") {
                        "${it.methodKey}\t${it.line}\t${it.bytecodeIndex}"
                    } + "\ntruncated=$truncated"
                    val id = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    stacks.putIfAbsent(id, StackEvidence(id, frames, truncated))
                    require(stacks.size <= 100_000) { "Recording exceeds 100000 unique sampled stacks" }
                    bucket.stacks.merge(id, 1, Long::plus)
                }
            }
        }
        val windows = buckets.mapIndexed { index, bucket ->
            val from = start + index * windowNanos
            MeasurementWindow(index, from, minOf(from + windowNanos, exclusiveEnd), bucket.samples,
                bucket.missingStacks, bucket.stacks.toSortedMap().map { StackCount(it.key, it.value) },
                bucket.cpuCount, if (bucket.cpuCount > 0) bucket.cpuSum / bucket.cpuCount else null)
        }
        val orderedStacks = stacks.values.sortedBy { it.id }
        return TemporalEvidence(hash, windowNanos = windowNanos, windows = windows, stacks = orderedStacks,
            methods = SamplePatternAnalyzer.analyze(windows, orderedStacks, windowNanos), limitations = limitations)
    }
}
