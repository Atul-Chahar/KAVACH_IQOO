package com.kavach.domain

import com.kavach.domain.SmsMessageAnalyzer.Evidence
import com.kavach.domain.SmsMessageAnalyzer.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmsMessageAnalyzerTest {
    private val analyzer = SmsMessageAnalyzer(TestFixtures.lexicon)

    @Test
    fun `kyc expiry lure with link is flagged`() {
        val result = analyzer.analyze("Your account expires today. Update KYC now: http://sbi-verify.example/login")

        assertEquals(Severity.HIGH_RISK, result.severity)
        assertTrue(Evidence.CREDENTIAL_REQUEST in result.evidence)
        assertTrue(Evidence.SUSPICIOUS_LINK in result.evidence)
    }

    @Test
    fun `otp request with shortened link is flagged`() {
        val result = analyzer.analyze("Verify immediately at https://bit.ly/check and enter your OTP")

        assertEquals(Severity.HIGH_RISK, result.severity)
        assertTrue(Evidence.CREDENTIAL_REQUEST in result.evidence)
    }

    @Test
    fun `fake refund collect request is flagged`() {
        val result = analyzer.analyze("Claim refund now: upi://collect?pa=thief@upi&pn=Refund&mode=collect")

        assertEquals(Severity.HIGH_RISK, result.severity)
        assertTrue(Evidence.PAYMENT_OR_REMOTE_ACCESS in result.evidence)
    }

    @Test
    fun `normal bank otp with warning stays clear`() {
        val result = analyzer.analyze("Your OTP is 483921 for card purchase. Do not share your OTP with anyone.")

        assertEquals(Severity.CLEAR, result.severity)
        assertTrue(Evidence.CREDENTIAL_REQUEST !in result.evidence)
    }

    @Test
    fun `delivery otp stays clear`() {
        val result = analyzer.analyze("Delivery OTP 4821. Share only with the courier at your door.")

        assertEquals(Severity.CLEAR, result.severity)
    }

    @Test
    fun `ordinary promotion stays clear`() {
        assertEquals(Severity.CLEAR, analyzer.analyze("Weekend sale: 20 percent off groceries in our stores.").severity)
    }

    @Test
    fun `hinglish credential theft is flagged`() {
        val result = analyzer.analyze("Account abhi block hoga. https://tinyurl.com/kyc par OTP bataiye")

        assertEquals(Severity.HIGH_RISK, result.severity)
    }

    @Test
    fun `long malformed input is bounded and does not fail`() {
        val result = analyzer.analyze("x".repeat(20_000) + " https://")

        assertEquals(Severity.CLEAR, result.severity)
    }

    /**
     * A real bank's own words, which are the exact opposite of a scammer's.
     * The regex fallback used to overrule the lexicon guard that catches this,
     * so the message path called a genuine fraud desk credential theft while
     * the call engine cleared it — see `hasCredentialRequest`.
     */
    @Test
    fun `a warning about OTP is not a request for one`() {
        val result =
            analyzer.analyze(
                "हम कभी ओटीपी नहीं मांगते। ओटीपी किसी को मत बताइए, हमें भी नहीं।",
            )

        assertTrue(Evidence.CREDENTIAL_REQUEST !in result.evidence)
    }

    @Test
    fun `reciting the warning and then asking anyway is still a request`() {
        val result = analyzer.analyze("We never ask for your OTP. Now reply with your OTP to confirm.")

        assertTrue(Evidence.CREDENTIAL_REQUEST in result.evidence)
    }

    @Test
    fun `ranking leads with the loss, not the pressure`() {
        val result = analyzer.analyze("Account blocked immediately. Reply with your OTP to restore it.")

        assertTrue(Evidence.URGENCY_OR_THREAT in result.evidence)
        assertEquals(Evidence.CREDENTIAL_REQUEST, result.ranked().first())
    }

    @Test
    fun `ranking keeps a linked action last`() {
        val result = analyzer.analyze("Your account expires today. Update KYC now: http://sbi-verify.example/login")

        assertTrue(Evidence.LINKED_ACTION in result.evidence)
        assertEquals(Evidence.LINKED_ACTION, result.ranked().last())
    }

    @Test
    fun `ranking honours the limit and never invents evidence`() {
        val result = analyzer.analyze("Your account expires today. Update KYC now: http://sbi-verify.example/login")

        assertEquals(2, result.ranked(2).size)
        assertTrue(result.ranked(2).all { it in result.evidence })
    }

    @Test
    fun `ranking of nothing is nothing`() {
        assertEquals(emptyList(), analyzer.analyze("See you at six.").ranked(2))
    }
}
