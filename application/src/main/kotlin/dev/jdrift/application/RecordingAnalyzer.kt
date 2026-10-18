package dev.jdrift.application

import dev.jdrift.domain.RecordingAnalysis
import java.nio.file.Path

interface RecordingAnalyzer {
    fun analyze(input: Path): RecordingAnalysis
}

class AnalysisException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
