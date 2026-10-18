package dev.jdrift.application

import dev.jdrift.domain.*
import java.nio.file.Path
import java.security.MessageDigest

interface ReportRepository {
    fun save(report: StoredReport): StoredReport
    fun list(context: RecordingContext): List<StoredReport>
    fun find(id: String): StoredReport?
}

class ImportRecording(private val analyzer: RecordingAnalyzer, private val repository: ReportRepository) {
    fun execute(path: Path, overrideContext: RecordingContext? = null): StoredReport {
        val analysis = analyzer.analyze(path)
        require(analysis.temporal.windows.isNotEmpty()) { "No timestamped data in recording" }
        val context = overrideContext ?: RecordingContext(
            requireNotNull(analysis.metadata.application) { "Specify application: embedded metadata unavailable" },
            requireNotNull(analysis.metadata.environment) { "Specify environment: embedded metadata unavailable" })
        require(context.application.isNotBlank() && context.environment.isNotBlank())
        val identity = listOf(analysis.sha256, analysis.engine, context.application, context.environment)
            .joinToString("") { "${it.length}:$it" }
        val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return repository.save(StoredReport(id, context, System.currentTimeMillis(), analysis))
    }
}
