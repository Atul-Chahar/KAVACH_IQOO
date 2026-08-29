package com.kavach.app.monitor

import com.kavach.domain.Incident
import com.kavach.domain.RiskAssessment
import com.kavach.domain.RiskBand

/**
 * One matched tactic family, ready to render.
 *
 * The alerting screens rank these strongest-first and number them, because a
 * bare score is unfalsifiable while "this caller claims to be police, 12 seconds
 * ago" can be checked against the conversation in one second. Everything needed
 * to draw that row is resolved here, so the UI never has to reach for the
 * lexicon.
 */
data class TacticEvidence(
    /** Plain language, already in the device locale. */
    val displayName: String,
    /** The family id, e.g. `AUTHORITY_IMPERSONATION`, shown as a small label. */
    val familyId: String,
    /** Session-elapsed milliseconds at the most recent hit for this family. */
    val lastSeenElapsedMs: Long,
) {
    /** `AUTHORITY_IMPERSONATION` reads as `AUTHORITY IMPERSONATION`. */
    val familyLabel: String get() = familyId.replace('_', ' ')
}

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
    /** The same matches, ranked strongest-first and carrying their evidence. */
    val tacticEvidence: List<TacticEvidence> = emptyList(),
    /** How many families the lexicon defines — the denominator in "2 of 5". */
    val familiesTotal: Int = 0,
    /** The lexicon version, shown on the home screen so the engine states its own provenance. */
    val lexiconVersion: String = "",
    /** How many distinct families a warning needs. The UI explains this rule. */
    val familiesForWarning: Int = 0,
    val elapsedMs: Long = 0,
    val transcriptPreview: String = "",
    /** Whether the user asked to see the words. Off by default, never persisted. */
    val showTranscript: Boolean = false,
    val engineName: String = "",
    /** Set when a model is missing. The app still works; the user is told plainly. */
    val degradedReason: String? = null,
    /** Set when capture itself failed. Honest message, never a pretence of listening. */
    val failureReason: String? = null,
    val escalated: Boolean = false,
    /**
     * The user pressed "I'm fine" on a warning. Suppresses the alert surface for
     * the rest of this call only — capture continues, and a fresh HIGH_RISK in a
     * later session warns again. Dismissing is not stopping.
     */
    val alertDismissed: Boolean = false,
    val incidents: List<Incident> = emptyList(),
) {
    val band: RiskBand get() = assessment.band
    val score: Int get() = assessment.score

    /** The numerator in "2 of 5 families". */
    val familiesSeen: Int get() = assessment.matchedFamilies.size
}
