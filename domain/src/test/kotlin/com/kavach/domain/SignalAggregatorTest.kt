package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SignalAggregatorTest {
    private val lexicon = TestFixtures.lexicon
    private val aggregator = SignalAggregator(lexicon)

    private fun signal(
        family: String,
        weight: Int,
        atMs: Long,
    ) = Signal(family, weight, "test", atMs)

    @Test
    fun `no signals means watching`() {
        assertEquals(RiskBand.WATCHING, aggregator.assess(emptyList(), 0).band)
    }

    @Test
    fun `one loud family cannot reach high risk`() {
        // Five maximum-weight credential hits: as loud as one family can possibly be.
        val signals = List(5) { signal("CREDENTIAL_EXTRACTION", 32, 0) }
        val result = aggregator.assess(signals, 0)
        assertTrue(
            result.band != RiskBand.HIGH_RISK,
            "a single family reached ${result.band} at ${result.score} — the diversity rule is not holding",
        )
        assertTrue(result.score < lexicon.scoring.highRiskThreshold)
    }

    @Test
    fun `three distinct families can reach high risk`() {
        val signals =
            listOf(
                signal("AUTHORITY_IMPERSONATION", 25, 0),
                signal("ISOLATION_AND_SECRECY", 30, 0),
                signal("CREDENTIAL_EXTRACTION", 32, 0),
                signal("REMOTE_ACCESS_AND_TRANSFER", 32, 0),
            )
        val result = aggregator.assess(signals, 0)
        assertEquals(RiskBand.HIGH_RISK, result.band, "score was ${result.score}")
    }

    @Test
    fun `score decays with age`() {
        val signals =
            listOf(
                signal("AUTHORITY_IMPERSONATION", 20, 0),
                signal("ISOLATION_AND_SECRECY", 25, 0),
                signal("CREDENTIAL_EXTRACTION", 30, 0),
            )
        val fresh = aggregator.assess(signals, 0).score
        val oneHalfLife = aggregator.assess(signals, 120_000).score
        val fourHalfLives = aggregator.assess(signals, 480_000).score

        assertTrue(oneHalfLife < fresh, "expected decay: $oneHalfLife should be below $fresh")
        assertTrue(fourHalfLives < oneHalfLife)
    }

    @Test
    fun `half life is roughly 120 seconds`() {
        val one = listOf(signal("CREDENTIAL_EXTRACTION", 20, 0))
        val fresh = aggregator.assess(one, 0).score
        val decayed = aggregator.assess(one, 120_000).score
        // 20 -> ~10. Integer truncation makes an exact assert brittle, so bound it.
        assertTrue(decayed in (fresh / 2 - 1)..(fresh / 2 + 1), "expected ~${fresh / 2}, got $decayed")
    }

    @Test
    fun `negative guards subtract`() {
        val withoutGuard =
            listOf(
                signal("AUTHORITY_IMPERSONATION", 20, 0),
                signal("CREDENTIAL_EXTRACTION", 30, 0),
            )
        val withGuard = withoutGuard + signal(TacticMatcher.NEGATIVE_GUARD, -35, 0)
        assertTrue(aggregator.assess(withGuard, 0).score < aggregator.assess(withoutGuard, 0).score)
    }

    @Test
    fun `guards cannot push the score below zero`() {
        val signals =
            listOf(
                signal("CREDENTIAL_EXTRACTION", 10, 0),
                signal(TacticMatcher.NEGATIVE_GUARD, -200, 0),
            )
        assertEquals(0, aggregator.assess(signals, 0).score)
    }

    @Test
    fun `the diversity bonus starts at the third family`() {
        val two =
            listOf(
                signal("AUTHORITY_IMPERSONATION", 18, 0),
                signal("URGENCY_AND_THREAT", 15, 0),
            )
        val three = two + signal("CREDENTIAL_EXTRACTION", 30, 0)
        val twoScore = aggregator.assess(two, 0).score
        val threeScore = aggregator.assess(three, 0).score
        // Third family adds its own weight AND unlocks the bonus.
        assertTrue(
            threeScore - twoScore > lexicon.scoring.familyDiversityBonus,
            "two=$twoScore three=$threeScore",
        )
    }

    @Test
    fun `a score above the threshold is capped when too few families fired`() {
        // With the shipped weights two families cannot reach 70 (caps are 30 and
        // 28), so the cap branch is unreachable in production today. It exists as
        // defence-in-depth for future lexicon edits, and this test raises the caps
        // to prove it actually holds rather than assuming it.
        val permissive =
            lexicon.copy(
                scoring = lexicon.scoring.copy(maxScorePerFamily = 60),
                families = lexicon.families.map { it.copy(baseWeight = 60) },
            )
        val signals =
            List(10) { signal("CREDENTIAL_EXTRACTION", 32, 0) } +
                List(10) { signal("REMOTE_ACCESS_AND_TRANSFER", 32, 0) }

        val result = SignalAggregator(permissive).assess(signals, 0)

        assertEquals(RiskBand.CAUTION, result.band, "two families must never reach HIGH_RISK")
        assertEquals(permissive.scoring.highRiskThreshold - 1, result.score)
    }

    @Test
    fun `an alerting assessment always names its families`() {
        val result = aggregator.assess(listOf(signal("CREDENTIAL_EXTRACTION", 30, 0)), 0)
        if (result.band != RiskBand.WATCHING) {
            assertTrue(result.matchedFamilies.isNotEmpty())
        }
    }

    @Test
    fun `an alerting state cannot be constructed without tactics`() {
        // docs/SAFETY.md 4 — a constructor requirement, not a convention.
        assertFailsWith<IllegalArgumentException> {
            RiskAssessment(80, RiskBand.HIGH_RISK, emptyList(), emptyList())
        }
    }
}
