package dev.jdrift.sqlite

import dev.jdrift.application.ImportRecording
import dev.jdrift.application.RecordingAnalyzer
import dev.jdrift.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files

class SqliteReportRepositoryTest {
    @TempDir lateinit var directory: Path

    @Test fun `derived evidence survives source removal reopen and duplicate import`() {
        val source = directory.resolve("source.jfr")
        Files.writeString(source, "stand-in source owned by the user")
        val temporal = TemporalEvidence("hash", windowNanos = 1_000_000_000,
            windows = listOf(MeasurementWindow(0, 100, 200, 0, 0, emptyList(), 0, null)),
            stacks = listOf(StackEvidence("stack", listOf(StackFrame("Example", "run", "()V", 1, 0)), false)),
            methods = emptyList(), limitations = listOf("Test evidence"))
        val analysis = RecordingAnalysis("hash", "test-engine", emptyList(), emptyList(), temporal,
            CaptureMetadata(application = "service", environment = "test"))
        val analyzer = object : RecordingAnalyzer { override fun analyze(input: Path) = analysis }
        val path = directory.resolve("history.db")
        val saved = SqliteReportRepository(path).use { repository ->
            val importer = ImportRecording(analyzer, repository)
            val first = importer.execute(source)
            assertEquals(first, importer.execute(source))
            assertEquals(1, repository.list(first.context).size)
            first
        }
        Files.delete(source)
        SqliteReportRepository(path).use {
            assertEquals(saved, it.find(saved.id))
            assertEquals("Example", it.find(saved.id)!!.analysis.temporal.stacks.single().frames.single().className)
            assertTrue(it.list(RecordingContext("other", "test")).isEmpty())
        }
    }
}
