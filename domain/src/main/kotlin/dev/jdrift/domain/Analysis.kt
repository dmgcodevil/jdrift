package dev.jdrift.domain

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class RecordingContext(val application: String, val environment: String)
@Serializable
data class EventCoverage(val eventType: String, val recordedCount: Long)
@Serializable
data class Diagnostic(val detectorId: String, val severity: String, val summary: String?, val explanation: String?)
@Serializable
data class RecordingAnalysis(
    val sha256: String,
    val engine: String,
    val coverage: List<EventCoverage>,
    val diagnostics: List<Diagnostic>,
    val temporal: TemporalEvidence,
    val metadata: CaptureMetadata = CaptureMetadata(),
)

/** A detector's explicit evaluation; absence from a report is not a negative result. */
enum class Evaluation { PRESENT, ABSENT, UNAVAILABLE }
data class FindingEvaluation(val fingerprint: String, val evaluation: Evaluation)
data class HistoricalReport(
    val id: String,
    val capturedAt: Instant,
    val context: RecordingContext,
    val compatibilityKey: String,
    val findings: List<FindingEvaluation>,
)

enum class Transition { FIRST_OBSERVED, CONTINUING, NO_LONGER_OBSERVED, RECURRING, INSUFFICIENT_EVIDENCE }
data class FindingTransition(val reportId: String, val fingerprint: String, val transition: Transition)

/** Conservative foundation: resolution needs a detector-specific verification policy later. */
class HistoryAnalyzer {
    fun analyze(reports: List<HistoricalReport>): List<FindingTransition> {
        require(reports.map { it.id }.distinct().size == reports.size) { "Duplicate report IDs" }
        require(reports.map { it.context }.distinct().size <= 1) { "Mixed application/environment history" }
        val seen = mutableSetOf<String>()
        val previous = mutableMapOf<String, Evaluation>()
        val result = mutableListOf<FindingTransition>()
        var compatibilityKey: String? = null
        for (report in reports.sortedWith(compareBy({ it.capturedAt }, { it.id }))) {
            require(report.findings.map { it.fingerprint }.distinct().size == report.findings.size)
            val incompatible = compatibilityKey != null && compatibilityKey != report.compatibilityKey
            if (incompatible) previous.clear()
            val evaluations = report.findings.associate { it.fingerprint to it.evaluation }
            for (fingerprint in (seen + evaluations.keys).sorted()) {
                val current = evaluations[fingerprint] ?: Evaluation.UNAVAILABLE
                val prior = previous[fingerprint]
                val transition = when {
                    current == Evaluation.UNAVAILABLE || incompatible -> Transition.INSUFFICIENT_EVIDENCE
                    current == Evaluation.PRESENT && fingerprint !in seen -> Transition.FIRST_OBSERVED
                    current == Evaluation.PRESENT && prior == Evaluation.ABSENT -> Transition.RECURRING
                    current == Evaluation.PRESENT && prior == Evaluation.PRESENT -> Transition.CONTINUING
                    current == Evaluation.ABSENT && prior == Evaluation.PRESENT -> Transition.NO_LONGER_OBSERVED
                    else -> Transition.INSUFFICIENT_EVIDENCE
                }
                result += FindingTransition(report.id, fingerprint, transition)
                if (current == Evaluation.PRESENT) seen += fingerprint
                previous[fingerprint] = current
            }
            compatibilityKey = report.compatibilityKey
        }
        return result
    }
}
