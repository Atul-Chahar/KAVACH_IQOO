package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The regression test that governs every threshold change.
 *
 * CLAUDE.md: never tune detection thresholds by intuition — tune against
 * `fixtures/` and report the false-positive rate on `fixtures/negative/` every
 * time. This test prints both rates on every run so that reporting is automatic
 * rather than remembered at 4am.
 *
 * Targets are from `fixtures/README.md`.
 */
class FixtureCorpusTest {
    private val scoring = TestFixtures.lexicon.scoring

    @Test
    fun `corpus meets the acceptance targets`() {
        val results = TestFixtures.fixtures().map { it to TestFixtures.replay(it) }

        val positives = results.filter { it.first.isPositive }
        val negatives = results.filterNot { it.first.isPositive }

        println()
        println("=== Kavach fixture corpus ===")
        results.forEach { (fixture, assessment) ->
            val set = if (fixture.isPositive) "POS" else "NEG"
            println(
                "  [$set] %-34s score=%3d  band=%-9s  families=%s".format(
                    fixture.name,
                    assessment.score,
                    assessment.band,
                    assessment.matchedFamilies.joinToString(",").ifEmpty { "-" },
                ),
            )
        }

        val positiveCaution = positives.count { it.second.score >= scoring.cautionThreshold }
        val positiveHigh = positives.count { it.second.band == RiskBand.HIGH_RISK }
        val falsePositives = negatives.count { it.second.score >= scoring.highRiskThreshold }
        val negativeCaution = negatives.count { it.second.score >= scoring.cautionThreshold }

        val fpRate = falsePositives * PERCENT / negatives.size
        val cautionRate = negativeCaution * PERCENT / negatives.size

        println()
        println("  positives reaching CAUTION : $positiveCaution/${positives.size}")
        println("  positives reaching HIGH_RISK: $positiveHigh/${positives.size}")
        println("  FALSE POSITIVE RATE (>=70)  : $fpRate%  ($falsePositives/${negatives.size})")
        println("  negatives reaching CAUTION  : $cautionRate%  ($negativeCaution/${negatives.size})")
        println()

        assertTrue(positives.isNotEmpty() && negatives.isNotEmpty(), "corpus must contain both sets")

        assertTrue(
            positiveCaution == positives.size,
            "every positive must reach CAUTION (>=${scoring.cautionThreshold}); " +
                "$positiveCaution/${positives.size} did",
        )
        assertTrue(
            positiveHigh * PERCENT / positives.size >= HIGH_RISK_TARGET_PERCENT,
            "at least $HIGH_RISK_TARGET_PERCENT% of positives must reach HIGH_RISK; " +
                "got ${positiveHigh * PERCENT / positives.size}%",
        )
        assertTrue(
            falsePositives == 0,
            "NO negative may reach HIGH_RISK. A detector that cries wolf gets uninstalled. " +
                "Offenders: ${negatives.filter { it.second.score >= scoring.highRiskThreshold }.map { it.first.name }}",
        )
        assertTrue(
            cautionRate <= CAUTION_TOLERANCE_PERCENT,
            "at most $CAUTION_TOLERANCE_PERCENT% of negatives may reach CAUTION; got $cautionRate%",
        )
    }

    /**
     * How long a positive takes to escalate, not merely whether it does.
     *
     * A verdict that arrives on the last line of the script is a verdict that
     * arrives after the money has moved. This asserts a budget in seconds of
     * speech, so a lexicon change that makes detection slower fails here rather
     * than being discovered on stage.
     */
    @Test
    fun `positives escalate early enough to matter`() {
        println()
        println("=== Escalation latency (seconds of speech) ===")

        val late = mutableListOf<String>()
        TestFixtures.fixtures().filter { it.isPositive }.forEach { fixture ->
            val trace = TestFixtures.trace(fixture)
            val caution = trace.indexOfFirst { it.score >= scoring.cautionThreshold }
            val high = trace.indexOfFirst { it.band == RiskBand.HIGH_RISK }

            println(
                "  %-34s caution=%s  high_risk=%s  (of %d lines)".format(
                    fixture.name,
                    seconds(caution),
                    seconds(high),
                    trace.size,
                ),
            )

            if (caution < 0 || caution * TestFixtures.SECONDS_PER_LINE > CAUTION_BUDGET_SECONDS) {
                late += "${fixture.name} reached CAUTION at ${seconds(caution)}"
            }
            if (high < 0 || high * TestFixtures.SECONDS_PER_LINE > HIGH_RISK_BUDGET_SECONDS) {
                late += "${fixture.name} reached HIGH_RISK at ${seconds(high)}"
            }
        }
        println()
        println("  budget: CAUTION <= ${CAUTION_BUDGET_SECONDS}s, HIGH_RISK <= ${HIGH_RISK_BUDGET_SECONDS}s")
        println()

        assertTrue(late.isEmpty(), "escalation too slow to be useful: $late")
    }

    /** Line index to a readable elapsed time, or "never". */
    private fun seconds(lineIndex: Int): String =
        if (lineIndex < 0) "never" else "${lineIndex * TestFixtures.SECONDS_PER_LINE}s"

    @Test
    fun `high risk always names at least three distinct families`() {
        TestFixtures
            .fixtures()
            .map { TestFixtures.replay(it) }
            .filter { it.band == RiskBand.HIGH_RISK }
            .forEach {
                assertTrue(
                    it.matchedFamilies.size >= scoring.minDistinctFamiliesForHighRisk,
                    "HIGH_RISK with only ${it.matchedFamilies.size} families: ${it.matchedFamilies}",
                )
            }
    }

    private companion object {
        const val PERCENT = 100
        const val HIGH_RISK_TARGET_PERCENT = 80
        const val CAUTION_TOLERANCE_PERCENT = 30

        /**
         * Escalation budgets, in seconds of speech.
         *
         * These are the honest floor for this corpus, not an aspiration. The
         * binding case is `otp-phish-01`, which cannot legitimately go amber
         * before 36 s: until the caller asks for the OTP it is saying the same
         * things, in the same order, as the *legitimate* bank desk in
         * `real-bank-desk-devanagari-01` — both plateau at ~35. Escalating
         * earlier would mean alarming on a real bank call, so the engine is
         * right to wait, and a budget that forced it lower would be a bug
         * dressed as a target.
         */
        const val CAUTION_BUDGET_SECONDS = 36L
        const val HIGH_RISK_BUDGET_SECONDS = 42L
    }
}
