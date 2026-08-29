package com.kavach.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns accumulated [Signal]s into a score and a band.
 *
 * Two mechanisms matter more than the individual weights
 * (docs/ARCHITECTURE.md 3):
 *
 *  1. **Time decay** — a marker from 8 minutes ago counts less than one from
 *     8 seconds ago. Half-life comes from the lexicon (120 s).
 *  2. **Family diversity** — three *different* families firing is far more
 *     diagnostic than one family firing three times. A real bank fraud desk
 *     legitimately trips AUTHORITY and mild URGENCY, but never asks for an OTP
 *     or for AnyDesk.
 *
 * Scoring, in full:
 *
 *     familyScore(f) = min(cap(f), sum of decayed marker weights in f)
 *         where cap(f) = min(family.baseWeight, scoring.maxScorePerFamily)
 *     bonus          = familyDiversityBonus * max(0, distinctFamilies - 2)
 *     guards         = sum of decayed negative-guard weights (negative)
 *     score          = clamp(sum(familyScore) + bonus + guards, 0, 100)
 *
 * The diversity bonus starts at the *third* family on purpose: two families is
 * an ordinary conversation about money, three is a script.
 *
 * `baseWeight` is used as the family's ceiling rather than a flat contribution,
 * so a family the lexicon considers weakly diagnostic (URGENCY, 15) can never
 * dominate one it considers strongly diagnostic (CREDENTIAL_EXTRACTION, 30).
 */
class SignalAggregator(
    private val lexicon: TacticLexicon,
) {
    private val scoring = lexicon.scoring

    private val familyCaps: Map<String, Int> =
        lexicon.families.associate { it.id to min(it.baseWeight, scoring.maxScorePerFamily) }

    fun assess(
        signals: List<Signal>,
        nowMs: Long,
    ): RiskAssessment {
        if (signals.isEmpty()) return RiskAssessment.WATCHING

        val (guards, families) = signals.partition { it.family == TacticMatcher.NEGATIVE_GUARD }

        val perFamily =
            families
                .groupBy { it.family }
                .mapValues { (family, hits) ->
                    val raw = hits.sumOf { decayed(it, nowMs) }
                    min(raw, (familyCaps[family] ?: scoring.maxScorePerFamily).toDouble())
                }.filterValues { it > 0.0 }

        val distinctFamilies = perFamily.keys.size
        val bonus = scoring.familyDiversityBonus * max(0, distinctFamilies - DIVERSITY_BONUS_FLOOR)
        val guardTotal = guards.sumOf { decayed(it, nowMs) }

        val rawScore = perFamily.values.sum() + bonus + guardTotal
        val score = rawScore.coerceIn(0.0, RiskAssessment.MAX_SCORE.toDouble()).toInt()

        val band = band(score, distinctFamilies)

        // The band, not the raw score, decides what we are allowed to claim.
        val cappedScore =
            if (score >= scoring.highRiskThreshold && band != RiskBand.HIGH_RISK) {
                scoring.highRiskThreshold - 1
            } else {
                score
            }

        val matched =
            perFamily.entries
                .sortedByDescending { it.value }
                .map { it.key }

        return RiskAssessment(
            score = cappedScore,
            band = band,
            matchedFamilies = if (band == RiskBand.WATCHING) emptyList() else matched,
            evidence = families.sortedByDescending { it.timestampMs },
        )
    }

    /**
     * HIGH_RISK requires at least [TacticLexicon.Scoring.minDistinctFamiliesForHighRisk]
     * distinct families. A single family, however loud, caps at CAUTION — this is
     * the primary false-positive defence and it is a hard requirement, not a
     * heuristic.
     */
    private fun band(
        score: Int,
        distinctFamilies: Int,
    ): RiskBand =
        when {
            score >= scoring.highRiskThreshold && distinctFamilies >= scoring.minDistinctFamiliesForHighRisk ->
                RiskBand.HIGH_RISK
            score >= scoring.cautionThreshold -> RiskBand.CAUTION
            else -> RiskBand.WATCHING
        }

    private fun decayed(
        signal: Signal,
        nowMs: Long,
    ): Double {
        val ageSeconds = max(0L, nowMs - signal.timestampMs) / MILLIS_PER_SECOND
        val halfLives = ageSeconds / scoring.decayHalfLifeSeconds.toDouble()
        return signal.weight * DECAY_BASE.pow(halfLives)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        const val DECAY_BASE = 0.5
        const val DIVERSITY_BONUS_FLOOR = 2
    }
}
