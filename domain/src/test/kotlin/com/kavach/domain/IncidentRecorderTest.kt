package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncidentRecorderTest {
    private val lexicon = TestFixtures.lexicon

    private fun alerting(score: Int) =
        RiskAssessment(
            score = score,
            band = if (score >= 70) RiskBand.HIGH_RISK else RiskBand.CAUTION,
            matchedFamilies = listOf("AUTHORITY_IMPERSONATION", "CREDENTIAL_EXTRACTION", "URGENCY_AND_THREAT"),
            evidence = emptyList(),
        )

    @Test
    fun `watching states are not recorded`() {
        val recorder = IncidentRecorder(lexicon)
        recorder.onAssessment(RiskAssessment.WATCHING, 0)
        recorder.endSession(1_000)
        assertTrue(recorder.all().isEmpty())
    }

    @Test
    fun `an alerting session records one incident at its peak`() {
        val recorder = IncidentRecorder(lexicon)
        recorder.onAssessment(alerting(45), 1_000)
        recorder.onAssessment(alerting(88), 5_000)
        recorder.onAssessment(alerting(60), 9_000)
        recorder.endSession(11_000)

        val incident = recorder.all().single()
        assertEquals(88, incident.peakScore)
        assertEquals(RiskBand.HIGH_RISK, incident.band)
        assertEquals(10_000, incident.durationMs)
    }

    @Test
    fun `the report contains metadata and never a transcript`() {
        val recorder = IncidentRecorder(lexicon)
        recorder.onAssessment(alerting(88), 1_000)
        recorder.endSession(5_000)

        val report = recorder.renderReport { "2026-08-30 11:04" }
        assertTrue(report.contains("KAVACH"))
        assertTrue(report.contains("88"))
        assertTrue(report.contains("1930"), "the report must tell people where to report fraud")
        assertTrue(report.contains("No alert is not a guarantee of safety."))
        assertTrue(report.contains("metadata only"))
        assertTrue(
            report.contains(lexicon.displayName("AUTHORITY_IMPERSONATION", hindi = false)!!),
            "tactics must be named in plain language, not as raw ids",
        )
    }

    @Test
    fun `an empty session still renders a valid report`() {
        val report = IncidentRecorder(lexicon).renderReport { "t" }
        assertTrue(report.contains("No incidents recorded"))
        assertFalse(report.isBlank())
    }
}
