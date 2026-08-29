package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpiLinkAnalyzerTest {
    @Test
    fun `flags a collect request disguised as a payment`() {
        val result = UpiLinkAnalyzer.analyze("upi://collect?pa=refund@okaxis&pn=Refund%20Desk&am=45000")
        assertTrue(UpiLinkAnalyzer.Flag.COLLECT_REQUEST_DISGUISED in result.flags)
        assertTrue(result.isSuspicious)
    }

    @Test
    fun `flags a mode collect parameter`() {
        val result = UpiLinkAnalyzer.analyze("upi://pay?pa=x@ybl&pn=X&mode=collect")
        assertTrue(UpiLinkAnalyzer.Flag.COLLECT_REQUEST_DISGUISED in result.flags)
    }

    @Test
    fun `flags a payee name that disagrees with the handle`() {
        val result = UpiLinkAnalyzer.analyze("upi://pay?pa=rakesh9832@okhdfcbank&pn=Apollo%20Hospitals")
        assertTrue(UpiLinkAnalyzer.Flag.PAYEE_NAME_MISMATCH in result.flags)
    }

    @Test
    fun `accepts a handle that matches the displayed name`() {
        val result = UpiLinkAnalyzer.analyze("upi://pay?pa=apollohospitals@okicici&pn=Apollo%20Hospitals")
        assertTrue(UpiLinkAnalyzer.Flag.PAYEE_NAME_MISMATCH !in result.flags)
    }

    @Test
    fun `flags a shortened link`() {
        val result = UpiLinkAnalyzer.analyze("https://bit.ly/3xKpay")
        assertTrue(UpiLinkAnalyzer.Flag.SHORTENER_OR_REDIRECT in result.flags)
    }

    @Test
    fun `flags a homoglyph lookalike domain`() {
        val result = UpiLinkAnalyzer.analyze("https://hdfc-1ogin.example.com/verify")
        assertTrue(UpiLinkAnalyzer.Flag.LOOKALIKE_DOMAIN in result.flags)
    }

    @Test
    fun `flags punycode`() {
        val result = UpiLinkAnalyzer.analyze("https://xn--pynepe-8za.com/pay")
        assertTrue(UpiLinkAnalyzer.Flag.LOOKALIKE_DOMAIN in result.flags)
    }

    @Test
    fun `passes an ordinary payment intent`() {
        val result = UpiLinkAnalyzer.analyze("upi://pay?pa=kirana.store@okaxis&pn=Kirana%20Store&am=250")
        assertTrue(result.flags.isEmpty(), "unexpected flags: ${result.flags}")
        assertEquals("kirana.store@okaxis", result.payeeVpa)
    }

    @Test
    fun `passes a real bank domain`() {
        assertTrue(UpiLinkAnalyzer.analyze("https://www.hdfcbank.com/personal").flags.isEmpty())
    }

    @Test
    fun `every flag comes with a plain language explanation`() {
        val result = UpiLinkAnalyzer.analyze("upi://collect?pa=refund@okaxis&pn=Refund%20Desk")
        assertEquals(result.flags.size, result.explanation.size)
    }
}
