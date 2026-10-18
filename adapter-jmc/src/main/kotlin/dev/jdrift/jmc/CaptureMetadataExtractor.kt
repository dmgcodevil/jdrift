package dev.jdrift.jmc

import dev.jdrift.domain.CaptureMetadata
import dev.jdrift.domain.WorkObservation
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Path
import java.time.Instant

/** A bounded second pass for known metadata; no JDK consumer objects escape the adapter. */
internal object CaptureMetadataExtractor {
    fun extract(path: Path): CaptureMetadata {
        val services = mutableSetOf<String>()
        val environments = mutableSetOf<String>()
        val builds = mutableSetOf<String>()
        val jvms = mutableSetOf<String>()
        val settings = sortedSetOf<String>()
        val work = mutableListOf<WorkObservation>()
        RecordingFile(path).use { file ->
            val executionTypeIds = file.readEventTypes().filter { it.name == "jdk.ExecutionSample" }.map { it.id }.toSet()
            while (file.hasMoreEvents()) {
                val event = file.readEvent()
                when (event.eventType.name) {
                    "jdk.JVMInformation" -> if (event.hasField("jvmVersion")) jvms += event.getString("jvmVersion")
                    "jdk.ActiveSetting" -> if (event.getLong("id") in executionTypeIds) {
                        settings += "${event.getString("name")}=${event.getString("value")}"
                    }
                    "dev.jdrift.ScenarioPhase" -> {
                        if (event.hasField("service")) services += event.getString("service")
                        if (event.hasField("environment")) environments += event.getString("environment")
                        if (event.hasField("scenario")) builds += event.getString("scenario")
                        if (event.hasField("workload") && event.hasField("threadCpuNanos")) {
                            val cpu = event.getLong("threadCpuNanos").takeIf { it >= 0 }
                            work += WorkObservation(nanos(event.startTime), nanos(event.endTime), event.getLong("operations"),
                                cpu, event.getString("workload"), "synthetic-worker",
                                if (event.hasField("loadProfile")) event.getString("loadProfile") else null)
                            require(work.size <= 100_000) { "Too many work observations" }
                        }
                    }
                }
            }
        }
        return CaptureMetadata(services.singleOrNull(), environments.singleOrNull(), builds.singleOrNull(),
            jvms.singleOrNull(), settings.toList(), work.sortedBy { it.startEpochNanos }, listOf(
                "Only the known dev.jdrift.ScenarioPhase schema supplies workload context; arbitrary application metadata is not mapped.",
                "Distinct observed sampling settings do not prove continuous event coverage.",
                "Missing or conflicting metadata is left unavailable. Scenario name is used as synthetic build identity.",
            ))
    }
    private fun nanos(time: Instant) = Math.addExact(Math.multiplyExact(time.epochSecond, 1_000_000_000L), time.nano.toLong())
}
