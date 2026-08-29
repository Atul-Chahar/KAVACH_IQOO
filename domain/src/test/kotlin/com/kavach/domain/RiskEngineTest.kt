package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiskEngineTest {
    private fun engine() = RiskEngine(TestFixtures.lexicon)

    @Test
    fun `score rises as a scam script unfolds`() {
        val engine = engine()
        val first = engine.onTranscript(TranscriptWindow("Main CBI se bol raha hoon", 0, 5_000))
        val second = engine.onTranscript(TranscriptWindow("Kisi ko mat bataiye, digital arrest", 5_000, 10_000))
        val third = engine.onTranscript(TranscriptWindow("AnyDesk download kijiye aur OTP bataiye", 10_000, 15_000))
        assertTrue(first.score < second.score, "${first.score} -> ${second.score}")
        assertTrue(second.score < third.score, "${second.score} -> ${third.score}")
        assertEquals(RiskBand.HIGH_RISK, third.band)
    }

    @Test
    fun `a repeated phrase in overlapping partials is only counted once`() {
        // ASR partials re-send text. Without dedup the score would climb purely
        // as a function of how chatty the recogniser is.
        val engine = engine()
        val once = engine.onTranscript(TranscriptWindow("aap otp bataiye", 0, 5_000))
        val twice = engine.onTranscript(TranscriptWindow("aap otp bataiye", 0, 5_000))
        assertEquals(once.score, twice.score)
    }

    @Test
    fun `reset clears accumulated state`() {
        val engine = engine()
        engine.onTranscript(TranscriptWindow("Main CBI se bol raha hoon, OTP bataiye", 0, 5_000))
        engine.reset()
        assertEquals(RiskBand.WATCHING, engine.assess(5_000).band)
    }

    @Test
    fun `a valid tier 2 verdict can raise the score`() {
        val engine = engine()
        val tier1 = engine.onTranscript(TranscriptWindow("Main CBI se bol raha hoon, OTP bataiye, anydesk", 0, 5_000))
        val merged = engine.merge(tier1, Verdict(risk = 95, oneLineReason = "Police impersonation plus OTP request."))
        assertTrue(merged.score >= tier1.score)
        assertEquals("Police impersonation plus OTP request.", merged.tier2Reason)
    }

    @Test
    fun `tier 2 never lowers the tier 1 score`() {
        val engine = engine()
        val tier1 = engine.onTranscript(TranscriptWindow("Main CBI se bol raha hoon, OTP bataiye, anydesk", 0, 5_000))
        val merged = engine.merge(tier1, Verdict(risk = 5, oneLineReason = "Looks fine to me."))
        assertEquals(tier1.score, merged.score)
    }

    @Test
    fun `a null verdict changes nothing - model failure is invisible`() {
        val engine = engine()
        val tier1 = engine.onTranscript(TranscriptWindow("Main CBI se bol raha hoon", 0, 5_000))
        assertEquals(tier1, engine.merge(tier1, null))
    }

    @Test
    fun `tier 2 can raise a caution the lexicon had no word for`() {
        val engine = engine()
        val tier1 = engine.onTranscript(TranscriptWindow("Nice weather today", 0, 5_000))
        assertEquals(RiskBand.WATCHING, tier1.band)

        val merged =
            engine.merge(
                tier1,
                Verdict(
                    risk = 90,
                    tactics = listOf("CREDENTIAL_EXTRACTION"),
                    oneLineReason = "Caller is walking the listener through a bank transfer.",
                ),
            )

        assertEquals(RiskBand.CAUTION, merged.band, "the model must be able to speak first")
        assertEquals(listOf("CREDENTIAL_EXTRACTION"), merged.matchedFamilies)
        assertTrue(
            merged.score < TestFixtures.lexicon.scoring.highRiskThreshold,
            "an escalation the engine cannot corroborate must stay under HIGH_RISK",
        )
    }

    @Test
    fun `tier 2 alone can never reach high risk`() {
        val engine = engine()
        val tier1 = engine.onTranscript(TranscriptWindow("Nice weather today", 0, 5_000))
        val merged =
            engine.merge(
                tier1,
                Verdict(risk = 100, tactics = listOf("AUTHORITY_IMPERSONATION"), oneLineReason = "Certain scam."),
            )
        assertEquals(RiskBand.CAUTION, merged.band, "HIGH_RISK still needs the family-diversity rule")
    }

    @Test
    fun `a quiet tier 2 verdict leaves a silent conversation alone`() {
        val engine = engine()
        val tier1 = engine.onTranscript(TranscriptWindow("Nice weather today", 0, 5_000))
        val merged =
            engine.merge(tier1, Verdict(risk = 10, tactics = listOf("URGENCY_AND_THREAT"), oneLineReason = "Fine."))
        assertEquals(RiskBand.WATCHING, merged.band)
        assertNull(merged.tier2Reason)
    }

    @Test
    fun `tier 2 cannot manufacture an alert the deterministic engine cannot justify`() {
        val engine = engine()
        val tier1 = engine.onTranscript(TranscriptWindow("Nice weather today", 0, 5_000))
        assertEquals(RiskBand.WATCHING, tier1.band)
        val merged = engine.merge(tier1, Verdict(risk = 99, oneLineReason = "Trust me."))
        assertEquals(RiskBand.WATCHING, merged.band)
        assertNull(merged.tier2Reason)
    }

    @Test
    fun `tier 2 cannot display a score its band cannot justify`() {
        val engine = engine()
        // Two families fire → CAUTION; HIGH_RISK needs three.
        val tier1 =
            engine.onTranscript(
                TranscriptWindow("Main CBI se bol raha hoon, kisi ko mat bataiye", 0, 5_000),
            )
        assertEquals(RiskBand.CAUTION, tier1.band)

        val merged = engine.merge(tier1, Verdict(risk = 100, oneLineReason = "Definitely a scam."))
        assertEquals(RiskBand.CAUTION, merged.band, "the band must not widen")
        assertTrue(
            merged.score < TestFixtures.lexicon.scoring.highRiskThreshold,
            "score ${merged.score} must stay under the threshold only HIGH_RISK may display",
        )
        assertEquals("Definitely a scam.", merged.tier2Reason)
    }

    @Test
    fun `tier 2 can raise the score inside a high risk band`() {
        val engine = engine()
        val tier1 =
            engine.onTranscript(
                TranscriptWindow(
                    "Main CBI se bol raha hoon, kisi ko mat bataiye, anydesk download kijiye, OTP bataiye",
                    0,
                    5_000,
                ),
            )
        assertEquals(RiskBand.HIGH_RISK, tier1.band)

        val merged = engine.merge(tier1, Verdict(risk = 100, oneLineReason = "Full fraud script."))
        assertEquals(100, merged.score)
        assertEquals(RiskBand.HIGH_RISK, merged.band)
    }

    @Test
    fun `a script repeated after the decay horizon scores again`() {
        val engine = engine()
        val script = "Main CBI se bol raha hoon, OTP bataiye, anydesk download kijiye"
        val first = engine.onTranscript(TranscriptWindow(script, 0, 5_000))
        assertTrue(first.score > 0)

        // Half-life 120 s × 8 half-lives = 16 minutes. Past that, the signals
        // have decayed out entirely.
        val muchLater = 20 * 60 * 1000L
        val quiet = engine.onTranscript(TranscriptWindow("aap kaise ho", muchLater, muchLater + 5_000))
        assertEquals(0, quiet.score)

        // A repeat is a new utterance, not an ASR partial, so it must re-score.
        val repeat =
            engine.onTranscript(TranscriptWindow(script, muchLater + 5_000, muchLater + 10_000))
        assertEquals(first.score, repeat.score, "a repeated script must not score zero")
    }
}
