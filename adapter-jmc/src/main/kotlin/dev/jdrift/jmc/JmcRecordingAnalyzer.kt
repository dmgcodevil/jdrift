package dev.jdrift.jmc

import dev.jdrift.application.AnalysisException
import dev.jdrift.application.RecordingAnalyzer
import dev.jdrift.domain.EventCoverage
import dev.jdrift.domain.Diagnostic
import dev.jdrift.domain.RecordingAnalysis
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit
import org.openjdk.jmc.flightrecorder.rules.RuleRegistry
import org.openjdk.jmc.flightrecorder.rules.util.RulesToolkit
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class JmcRecordingAnalyzer : RecordingAnalyzer {
    override fun analyze(input: Path): RecordingAnalysis {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(input).use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val events = JfrLoaderToolkit.loadEvents(input.toFile())
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val temporal = TemporalExtractor().extract(events, hash)
            val counts = mutableMapOf<String, Long>()
            for (items in events) {
                var count = 0L
                for (ignored in items) count++
                counts.merge(items.type.identifier, count, Long::plus)
            }
            val diagnostics = RulesToolkit.evaluateParallel(RuleRegistry.getRules(), events, null, 1)
                .entries.sortedBy { it.key.id }.map { (rule, future) ->
                    val result = future.get()
                    Diagnostic(rule.id, result.severity.name, result.summary, result.explanation)
                }
            return RecordingAnalysis(
                hash,
                "jmc-9.1.2/extractor-0.4",
                counts.toSortedMap().map { (type, count) -> EventCoverage(type, count) },
                diagnostics,
                temporal,
                CaptureMetadataExtractor.extract(input),
            )
        } catch (exception: Exception) {
            throw AnalysisException("Could not analyze recording", exception)
        }
    }
}
