package dev.jdrift.sqlite

import dev.jdrift.application.ReportRepository
import dev.jdrift.domain.RecordingContext
import dev.jdrift.domain.StoredReport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Stores only detached analysis data. Raw recording bytes and source paths are never persisted. */
class SqliteReportRepository(path: Path) : ReportRepository, AutoCloseable {
    private val json = Json { encodeDefaults = true }
    private val connection: Connection
    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute("PRAGMA foreign_keys=ON")
            val version = statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            require(version in 0..1) { "Unsupported database schema $version" }
            if (version == 0) {
                connection.autoCommit = false
                try {
                    statement.execute("""CREATE TABLE reports (
                        id TEXT PRIMARY KEY, application TEXT NOT NULL, environment TEXT NOT NULL,
                        captured_ns INTEGER NOT NULL, imported_ms INTEGER NOT NULL,
                        recording_hash TEXT NOT NULL, engine TEXT NOT NULL, payload TEXT NOT NULL,
                        UNIQUE(application, environment, recording_hash, engine))""")
                    statement.execute("CREATE INDEX reports_context_time ON reports(application, environment, captured_ns)")
                    statement.execute("PRAGMA user_version=1")
                    connection.commit()
                } catch (e: Exception) { connection.rollback(); throw e }
                finally { connection.autoCommit = true }
            }
        }
    }

    @Synchronized override fun save(report: StoredReport): StoredReport {
        connection.prepareStatement("INSERT INTO reports VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING").use {
            it.setString(1, report.id)
            it.setString(2, report.context.application)
            it.setString(3, report.context.environment)
            it.setLong(4, report.capturedAtEpochNanos)
            it.setLong(5, report.importedAtEpochMillis)
            it.setString(6, report.analysis.sha256)
            it.setString(7, report.analysis.engine)
            it.setString(8, json.encodeToString(report))
            it.executeUpdate()
        }
        return requireNotNull(find(report.id)) { "Conflicting recording identity" }
    }

    @Synchronized override fun find(id: String): StoredReport? =
        connection.prepareStatement("SELECT payload FROM reports WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rows -> if (rows.next()) json.decodeFromString<StoredReport>(rows.getString(1)) else null }
        }

    @Synchronized override fun list(context: RecordingContext): List<StoredReport> =
        connection.prepareStatement("SELECT payload FROM reports WHERE application=? AND environment=? ORDER BY captured_ns,id").use {
            it.setString(1, context.application)
            it.setString(2, context.environment)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) add(json.decodeFromString<StoredReport>(rows.getString(1)))
            } }
        }

    override fun close() = connection.close()
}
