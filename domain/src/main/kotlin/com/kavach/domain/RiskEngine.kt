package com.kavach.domain

/**
 * Accumulates signals across transcript windows and produces the current
 * assessment. Stateful but pure — no clock of its own, no Android, no I/O.
 * The caller supplies `nowMs`, which is what makes the decay maths testable.
 */
class RiskEngine(
    private val lexicon: TacticLexicon,
    private val matcher: TacticMatcher = TacticMatcher(lexicon),
    private val aggregator: SignalAggregator = SignalAggregator(lexicon),
) {
    private val signals = mutableListOf<Signal>()

    /** Span key → timestamp of the signal it dedupes. Expires with the decay horizon. */
    private val seenSpans = mutableMapOf<String, Long>()

    /**
     * Feeds one window and returns the updated assessment.
     *
     * Partial windows re-send text we have already scored, so a marker is only
     * counted once per span+family. Without this, a phrase sitting in the
     * rolling window would re-score on every ASR partial and inflate the score
     * purely as a function of how chatty the recogniser is.
     */
    fun onTranscript(
        window: TranscriptWindow,
        nowMs: Long = window.endMs,
    ): RiskAssessment {
        matcher.match(window).forEach { signal ->
            val key = "${signal.family}|${signal.evidenceSpan}"
            if (seenSpans.putIfAbsent(key, signal.timestampMs) == null) signals += signal
        }
        prune(nowMs)
        return aggregator.assess(signals, nowMs)
    }

    /** Current assessment without feeding new text — for the UI's ticking clock. */
    fun assess(nowMs: Long): RiskAssessment = aggregator.assess(signals, nowMs)

    /**
     * Merges a Tier-2 verdict. The displayed score is `max(tier1, tier2)` when
     * Tier 2 is valid and `tier1` otherwise (docs/ARCHITECTURE.md 3).
     *
     * Two invariants survive the merge, both inherited from the deterministic
     * engine. The band never widens: the model may raise the number, but it may
     * not manufacture a HIGH_RISK state the family-diversity rule cannot
     * justify. And the number never outruns the band: a merged score is capped
     * at the threshold its band can justify, because "CAUTION (100/100)" is a
     * contradiction printed on the card. Every alert stays explainable by
     * something we can point at.
     */
    fun merge(
        tier1: RiskAssessment,
        verdict: Verdict?,
    ): RiskAssessment {
        if (verdict == null || verdict.risk <= tier1.score) return tier1
        if (tier1.band == RiskBand.WATCHING) return tier1
        val ceiling =
            if (tier1.band == RiskBand.HIGH_RISK) {
                RiskAssessment.MAX_SCORE
            } else {
                lexicon.scoring.highRiskThreshold - 1
            }
        return tier1.copy(
            score = minOf(maxOf(tier1.score, verdict.risk), ceiling),
            tier2Reason = verdict.oneLineReason,
        )
    }

    fun reset() {
        signals.clear()
        seenSpans.clear()
    }

    /**
     * Drops signals decayed into irrelevance, so a long session cannot grow
     * without bound. The dedup map expires in lockstep: ASR partials re-send a
     * span within seconds and stay deduped, but a scammer repeating the script
     * after the horizon is making a new utterance, and it must score again.
     * Keys that outlived their signals made a repeated script score zero.
     */
    private fun prune(nowMs: Long) {
        val horizonMs = lexicon.scoring.decayHalfLifeSeconds * MILLIS_PER_SECOND * DECAY_HORIZON_HALF_LIVES
        signals.removeAll { nowMs - it.timestampMs > horizonMs }
        seenSpans.entries.removeAll { nowMs - it.value > horizonMs }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val DECAY_HORIZON_HALF_LIVES = 8L
    }
}
