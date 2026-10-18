package dev.jdrift.app

import dev.jdrift.jmc.JmcRecordingAnalyzer
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.jdrift.application.ImportRecording
import dev.jdrift.domain.*
import dev.jdrift.sqlite.SqliteReportRepository
import java.time.Instant

fun main(args: Array<String>) {
    if (args.firstOrNull() in setOf("import", "list", "show", "history", "compare")) {
        runHistoryCommand(args)
        return
    }
    require(args.size in 1..2) { "Usage: jdrift <recording.jfr> [temporal-evidence.json]" }
    val analysis = JmcRecordingAnalyzer().analyze(Path.of(args[0]))
    println("Recording: ${analysis.sha256}")
    println("Analyzer: ${analysis.engine}")
    analysis.coverage.forEach { println("${it.eventType}\t${it.recordedCount}") }
    analysis.diagnostics.forEach { println("${it.detectorId}\t${it.severity}\t${it.summary.orEmpty()}") }
    analysis.temporal.windows.forEach {
        println("window=${it.index} samples=${it.executionSamples} cpuFraction=${it.meanRecordedJvmCpuFraction}")
    }
    analysis.temporal.methods.take(20).forEach { println("${it.pattern}\t${it.samples}\t${it.method}") }
    if (args.size == 2) {
        Files.writeString(Path.of(args[1]), Json { prettyPrint = true; encodeDefaults = true }.encodeToString(analysis.temporal),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}

private val outputJson = Json { prettyPrint = true; encodeDefaults = true }

private fun runHistoryCommand(args: Array<String>) {
    require(args.size >= 3) { "Commands: import <db> <jfr> [application environment]; list <db> <application> <environment>; show <db> <id>; history <db> <application> <environment> <baseline-id> [--last N | --from ISO --until ISO]; compare <db> <baseline-id> <candidate-id>" }
    SqliteReportRepository(Path.of(args[1])).use { repository ->
        when (args[0]) {
            "import" -> {
                require(args.size == 3 || args.size == 5)
                val context = if (args.size == 5) RecordingContext(args[3], args[4]) else null
                val report = ImportRecording(JmcRecordingAnalyzer(), repository).execute(Path.of(args[2]), context)
                println(outputJson.encodeToString(mapOf("reportId" to report.id, "recordingHash" to report.analysis.sha256)))
            }
            "list" -> {
                require(args.size == 4)
                println(outputJson.encodeToString(repository.list(RecordingContext(args[2], args[3])).map {
                    mapOf("id" to it.id, "capturedAtEpochNanos" to it.capturedAtEpochNanos.toString(),
                        "build" to it.analysis.metadata.build.orEmpty(), "engine" to it.analysis.engine)
                }))
            }
            "show" -> {
                require(args.size == 3)
                println(outputJson.encodeToString(requireNotNull(repository.find(args[2])) { "Report not found" }))
            }
            "compare" -> {
                require(args.size == 4)
                val base = requireNotNull(repository.find(args[2])) { "Baseline not found" }
                val candidate = requireNotNull(repository.find(args[3])) { "Candidate not found" }
                require(base.context == candidate.context) { "Cannot compare different application/environment contexts" }
                val progress = PerformanceHistory.analyze(repository.list(base.context), base.id, HistorySelection())
                println(outputJson.encodeToString(progress.copy(points = progress.points.filter { it.reportId == candidate.id }.also {
                    require(it.isNotEmpty()) { "Candidate is superseded by a newer analysis" }
                })))
            }
            "history" -> {
                require(args.size >= 5)
                val options = args.drop(5)
                require(options.size % 2 == 0)
                val pairs = options.chunked(2)
                require(pairs.map { it[0] }.distinct().size == pairs.size)
                require(pairs.all { it[0] in setOf("--last", "--from", "--until") })
                val flags = pairs.associate { it[0] to it[1] }
                val selection = HistorySelection(flags["--last"]?.toInt(), flags["--from"]?.let(::epochNanos), flags["--until"]?.let(::epochNanos))
                println(outputJson.encodeToString(PerformanceHistory.analyze(
                    repository.list(RecordingContext(args[2], args[3])), args[4], selection)))
            }
        }
    }
}

private fun epochNanos(value: String): Long {
    val instant = Instant.parse(value)
    return Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000_000L), instant.nano.toLong())
}
