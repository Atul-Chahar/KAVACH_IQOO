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
    fun `tier 2 cannot manufacture an alert the deterministic engine cannot justify`() {
        val engine = engine()
        val tier1 = engine.onTranscript(TranscriptWindow("Nice weather today", 0, 5_000))
        assertEquals(RiskBand.WATCHING, tier1.band)
        val merged = engine.merge(tier1, Verdict(risk = 99, oneLineReason = "Trust me."))
        assertEquals(RiskBand.WATCHING, merged.band)
        assertNull(merged.tier2Reason)
    }
}
