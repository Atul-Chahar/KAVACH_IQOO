package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TacticMatcherTest {
    private val matcher = TacticMatcher(TestFixtures.lexicon)

    private fun familiesIn(text: String): Set<String> =
        matcher
            .match(TranscriptWindow(text, 0, 1000))
            .filterNot { it.family == TacticMatcher.NEGATIVE_GUARD }
            .map { it.family }
            .toSet()

    private fun guardsIn(text: String): List<Signal> =
        matcher.match(TranscriptWindow(text, 0, 1000)).filter { it.family == TacticMatcher.NEGATIVE_GUARD }

    // --- one positive and one negative case per family, per docs/TASKS.md S1.1 ---

    @Test
    fun `authority impersonation fires on a fake CBI claim`() {
        assertTrue("AUTHORITY_IMPERSONATION" in familiesIn("Main CBI se bol raha hoon, arrest warrant hai"))
    }

    @Test
    fun `authority impersonation stays quiet on an ordinary call`() {
        assertTrue(familiesIn("Main aapka parcel lekar aaya hoon, neeche khada hoon").isEmpty())
    }

    @Test
    fun `isolation fires on digital arrest framing`() {
        assertTrue("ISOLATION_AND_SECRECY" in familiesIn("Aap digital arrest par hain, kisi ko mat bataiye"))
    }

    @Test
    fun `isolation stays quiet on a normal request for privacy`() {
        assertTrue("ISOLATION_AND_SECRECY" !in familiesIn("Please hold on, I am checking your file"))
    }

    @Test
    fun `urgency fires on an arrest threat`() {
        assertTrue("URGENCY_AND_THREAT" in familiesIn("Aap giraftar ho jayenge, do ghante ke andar"))
    }

    @Test
    fun `urgency stays quiet on ordinary haste`() {
        assertTrue("URGENCY_AND_THREAT" !in familiesIn("Please come quickly, I am waiting downstairs"))
    }

    @Test
    fun `credential extraction fires on an OTP request`() {
        assertTrue("CREDENTIAL_EXTRACTION" in familiesIn("Aap OTP bataiye aur UPI PIN confirm kijiye"))
    }

    @Test
    fun `credential extraction stays quiet when a bank refuses to ask`() {
        assertTrue("CREDENTIAL_EXTRACTION" !in familiesIn("Hum kabhi phone par paise ya OTP nahi maangte"))
    }

    @Test
    fun `remote access fires on AnyDesk`() {
        assertTrue("REMOTE_ACCESS_AND_TRANSFER" in familiesIn("AnyDesk download kijiye aur screen share kijiye"))
    }

    @Test
    fun `remote access stays quiet on an ordinary app suggestion`() {
        assertTrue("REMOTE_ACCESS_AND_TRANSFER" !in familiesIn("You can check the balance in your banking app"))
    }

    // --- matching mechanics ---

    @Test
    fun `matching is case insensitive and survives punctuation`() {
        assertTrue("CREDENTIAL_EXTRACTION" in familiesIn("Sir... the O.T.P?! please"))
    }

    @Test
    fun `word boundaries stop fir from matching inside firm`() {
        assertTrue("AUTHORITY_IMPERSONATION" !in familiesIn("I work at a law firm in Bengaluru"))
        assertTrue("AUTHORITY_IMPERSONATION" in familiesIn("An FIR has been registered"))
    }

    @Test
    fun `devanagari markers match`() {
        assertTrue("ISOLATION_AND_SECRECY" in familiesIn("आप डिजिटल अरेस्ट पर हैं"))
    }

    @Test
    fun `a longer marker consumes the span so weights are not double counted`() {
        val signals =
            matcher
                .match(TranscriptWindow("aap otp bataiye", 0, 1000))
                .filterNot { it.family == TacticMatcher.NEGATIVE_GUARD }
        assertEquals(1, signals.size, "expected only the specific marker, got ${signals.map { it.evidenceSpan }}")
        assertEquals("otp bataiye", signals.single().evidenceSpan)
    }

    @Test
    fun `a guard outranks the scoring marker nested inside it`() {
        // The whole point: "we never ask for your OTP" must not score as "OTP".
        val signals = matcher.match(TranscriptWindow("hum kabhi otp nahi maangte sir", 0, 1000))
        assertTrue(signals.all { it.family == TacticMatcher.NEGATIVE_GUARD }, "got ${signals.map { it.family }}")
    }

    @Test
    fun `guards carry negative weight`() {
        assertTrue(guardsIn("we will never ask for your otp").single().weight < 0)
    }

    @Test
    fun `empty transcript yields nothing`() {
        assertTrue(matcher.match(TranscriptWindow("   ", 0, 1000)).isEmpty())
    }
}
