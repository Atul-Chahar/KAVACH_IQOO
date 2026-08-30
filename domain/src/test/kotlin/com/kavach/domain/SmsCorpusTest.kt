package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The message path's false-positive report, printed on every run.
 *
 * CLAUDE.md: never tune detection by intuition — measure against `fixtures/`
 * and report the false-positive rate on `fixtures/negative/` every time. That
 * rule had a gap: [FixtureCorpusTest] measures the call engine, and nothing
 * measured [SmsMessageAnalyzer]. A message finding now raises a lock-screen
 * warning and a capsule with no call in progress to explain it, so a false one
 * is more visible than it has ever been, not less.
 *
 * The corpus is call transcripts rather than SMS, and that is deliberately
 * unkind: `genuine-delivery-otp-en-01` is a real courier asking for a real OTP,
 * and `genuine-bank-kyc-en-01` is a real bank asking for a real KYC update.
 * Those sentences arrive by SMS too. If the analyzer calls them credential
 * theft, it is wrong about the message case as well.
 */
class SmsCorpusTest {
    private val analyzer = SmsMessageAnalyzer(TestFixtures.lexicon)

    @Test
    fun `no legitimate script is called a scam message`() {
        val results = TestFixtures.fixtures().map { it to analyzer.analyze(it.text) }
        val positives = results.filter { it.first.isPositive }
        val negatives = results.filterNot { it.first.isPositive }

        println()
        println("=== Kavach message corpus (SmsMessageAnalyzer) ===")
        results.forEach { (fixture, result) ->
            println(
                "  [%s] %-34s %-9s %s".format(
                    if (fixture.isPositive) "POS" else "NEG",
                    fixture.name,
                    result.severity,
                    result.ranked().joinToString(",").ifEmpty { "-" },
                ),
            )
        }

        val falsePositives = negatives.filter { it.second.severity == SmsMessageAnalyzer.Severity.HIGH_RISK }
        val negativeCaution = negatives.count { it.second.severity != SmsMessageAnalyzer.Severity.CLEAR }
        val positiveFlagged = positives.count { it.second.severity != SmsMessageAnalyzer.Severity.CLEAR }

        println()
        println("  positives flagged at all      : $positiveFlagged/${positives.size}")
        println(
            "  MESSAGE FALSE POSITIVE RATE   : " +
                "${falsePositives.size * PERCENT / negatives.size}% (${falsePositives.size}/${negatives.size})",
        )
        println("  negatives reaching CAUTION    : $negativeCaution/${negatives.size}")
        println()

        assertTrue(positives.isNotEmpty() && negatives.isNotEmpty(), "corpus must contain both sets")
        assertTrue(
            falsePositives.isEmpty(),
            "no legitimate script may be called HIGH_RISK by the message analyzer. " +
                "Offenders: ${falsePositives.map { it.first.name }}",
        )
    }

    private companion object {
        const val PERCENT = 100
    }
}
