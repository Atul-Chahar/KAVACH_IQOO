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
    private val seenSpans = mutableSetOf<String>()

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
            if (seenSpans.add(key)) signals += signal
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
     * The band is still governed by the Tier-1 family-diversity rule: the model
     * may raise the number, but it may not manufacture a HIGH_RISK state that
     * the deterministic engine cannot justify. That keeps every alert
     * explainable by something we can point at.
     */
    fun merge(
        tier1: RiskAssessment,
        verdict: Verdict?,
    ): RiskAssessment {
        if (verdict == null || verdict.risk <= tier1.score) return tier1
        if (tier1.band == RiskBand.WATCHING) return tier1
        return tier1.copy(
            score = maxOf(tier1.score, verdict.risk),
            tier2Reason = verdict.oneLineReason,
        )
    }

    fun reset() {
        signals.clear()
        seenSpans.clear()
    }

    /** Drops signals decayed into irrelevance, so a long session cannot grow without bound. */
    private fun prune(nowMs: Long) {
        val horizonMs = lexicon.scoring.decayHalfLifeSeconds * MILLIS_PER_SECOND * DECAY_HORIZON_HALF_LIVES
        signals.removeAll { nowMs - it.timestampMs > horizonMs }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val DECAY_HORIZON_HALF_LIVES = 8L
    }
}
