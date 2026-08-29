package com.kavach.domain

/**
 * One recorded incident. **Metadata only** — timestamp, tactic ids, score,
 * duration. Never audio, never transcript (docs/SAFETY.md 6).
 */
data class Incident(
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val peakScore: Int,
    val band: RiskBand,
    val tactics: List<String>,
)

/**
 * Accumulates incidents for the session and renders the one-page report that
 * gets moved to the laptop over Office Kit file transfer.
 *
 * Records only when the session reaches CAUTION or above, and keeps one record
 * per session rather than one per window — a log that fires on every partial is
 * noise, and noise is what makes people stop reading logs.
 */
class IncidentRecorder(
    private val lexicon: TacticLexicon,
) {
    private val incidents = mutableListOf<Incident>()

    private var openStartMs: Long? = null
    private var openPeak: RiskAssessment = RiskAssessment.WATCHING

    fun onAssessment(
        assessment: RiskAssessment,
        epochMs: Long,
    ) {
        if (assessment.band == RiskBand.WATCHING) return
        if (openStartMs == null) openStartMs = epochMs
        if (assessment.score >= openPeak.score) openPeak = assessment
    }

    /** Closes the current incident, if any. Called when monitoring stops. */
    fun endSession(epochMs: Long) {
        val start = openStartMs ?: return
        incidents +=
            Incident(
                startedAtEpochMs = start,
                durationMs = (epochMs - start).coerceAtLeast(0),
                peakScore = openPeak.score,
                band = openPeak.band,
                tactics = openPeak.matchedFamilies,
            )
        openStartMs = null
        openPeak = RiskAssessment.WATCHING
    }

    fun all(): List<Incident> = incidents.toList()

    fun clear() {
        incidents.clear()
        openStartMs = null
        openPeak = RiskAssessment.WATCHING
    }

    /**
     * Renders the exportable report. Plain text on purpose: it opens on any
     * laptop with no viewer, no fonts and no app.
     */
    fun renderReport(formatTimestamp: (Long) -> String): String =
        buildString {
            appendLine("KAVACH — INCIDENT REPORT")
            appendLine("=".repeat(HEADING_RULE))
            appendLine()
            appendLine("Generated on-device. Nothing in this report left the phone.")
            appendLine("Contains metadata only: no audio and no transcript were recorded.")
            appendLine()
            appendLine("Lexicon version: ${lexicon.version}")
            appendLine("Incidents: ${incidents.size}")
            appendLine()

            if (incidents.isEmpty()) {
                appendLine("No incidents recorded in this session.")
            }

            incidents.forEachIndexed { index, incident ->
                appendLine("${index + 1}. ${formatTimestamp(incident.startedAtEpochMs)}")
                appendLine("   Assessment : ${incident.band} (score ${incident.peakScore}/100)")
                appendLine("   Duration   : ${incident.durationMs / MILLIS_PER_SECOND}s")
                appendLine("   Patterns matched:")
                incident.tactics.forEach { id ->
                    appendLine("     - ${lexicon.displayName(id, hindi = false) ?: id}")
                }
                appendLine()
            }

            appendLine("-".repeat(HEADING_RULE))
            appendLine("Kavach matches conversations against known scam patterns.")
            appendLine("It does not determine that a crime occurred.")
            appendLine("No alert is not a guarantee of safety.")
            appendLine("Report suspected fraud in India: cybercrime helpline 1930.")
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1000
        const val HEADING_RULE = 56
    }
}
