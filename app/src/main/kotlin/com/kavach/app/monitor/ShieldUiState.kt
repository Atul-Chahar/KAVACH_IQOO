package com.kavach.app.monitor

import com.kavach.domain.Incident
import com.kavach.domain.RiskAssessment
import com.kavach.domain.RiskBand

/**
 * Everything the UI renders, in one immutable value. Unidirectional data flow:
 * the screen observes this and nothing else.
 */
data class ShieldUiState(
    val monitoring: Boolean = false,
    val mode: MonitorMode = MonitorMode.LIVE,
    val assessment: RiskAssessment = RiskAssessment.WATCHING,
    /** Matched tactics already resolved to plain language in the device locale. */
    val tactics: List<String> = emptyList(),
    val elapsedMs: Long = 0,
    val transcriptPreview: String = "",
    val engineName: String = "",
    /** Set when a model is missing. The app still works; the user is told plainly. */
    val degradedReason: String? = null,
    /** Set when capture itself failed. Honest message, never a pretence of listening. */
    val failureReason: String? = null,
    val escalated: Boolean = false,
    val incidents: List<Incident> = emptyList(),
) {
    val band: RiskBand get() = assessment.band
    val score: Int get() = assessment.score
}
