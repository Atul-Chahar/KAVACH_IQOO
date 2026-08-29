package com.kavach.domain

/**
 * One marker hit in the transcript. Carries its own evidence so the UI can
 * explain itself — docs/SAFETY.md §4 makes explainability mandatory, and a
 * bare score is unfalsifiable.
 */
data class Signal(
    val family: String,
    val weight: Int,
    val evidenceSpan: String,
    val timestampMs: Long,
)

/** The three states in docs/PRD.md §5. */
enum class RiskBand { WATCHING, CAUTION, HIGH_RISK }

/**
 * The output of the engine. [matchedFamilies] is never empty for CAUTION or
 * HIGH_RISK — enforced in `init`, not by convention, per docs/SAFETY.md §4.
 */
data class RiskAssessment(
    val score: Int,
    val band: RiskBand,
    val matchedFamilies: List<String>,
    val evidence: List<Signal>,
    val tier2Reason: String? = null,
) {
    init {
        require(score in 0..MAX_SCORE) { "score out of range: $score" }
        require(band == RiskBand.WATCHING || matchedFamilies.isNotEmpty()) {
            "an alerting state must name the tactics that caused it (docs/SAFETY.md 4)"
        }
    }

    companion object {
        const val MAX_SCORE = 100

        val WATCHING = RiskAssessment(0, RiskBand.WATCHING, emptyList(), emptyList())
    }
}
