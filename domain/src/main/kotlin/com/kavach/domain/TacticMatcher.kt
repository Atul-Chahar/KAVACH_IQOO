package com.kavach.domain

/**
 * Tier 1: deterministic marker matching. No ML, fully unit-tested. This is the
 * backbone — docs/ARCHITECTURE.md 3 requires that Tier 1 alone can carry the
 * entire demo if every model fails.
 */
class TacticMatcher(
    private val lexicon: TacticLexicon,
) {
    /**
     * Every marker — scoring and guard alike — in one list, longest first.
     *
     * The single list matters. A guard like "otp nahi maangte" ("we never ask
     * for your OTP") must beat the bare "otp" marker sitting inside it, or a
     * police officer warning someone about OTP fraud scores the same as a
     * scammer demanding one. Longest-and-most-specific wins, regardless of
     * whether it adds or subtracts.
     */
    private val markers: List<Triple<String, String, Int>> =
        (
            lexicon.families.flatMap { family ->
                family.markers.map { Triple(family.id, normalizedMarker(it.text), it.weight) }
            } +
                lexicon.negativeGuards.markers.map {
                    Triple(NEGATIVE_GUARD, normalizedMarker(it.text), it.weight)
                }
        ).sortedByDescending { it.second.length }

    private fun normalizedMarker(text: String) = TranscriptNormalizer.normalize(text)

    /**
     * Matches [window] and emits one [Signal] per occurrence. Overlapping hits
     * are suppressed: a longer marker consumes its span so the same words are
     * not counted twice by a shorter, weaker marker.
     */
    fun match(window: TranscriptWindow): List<Signal> {
        val text = TranscriptNormalizer.normalize(window.text)
        if (text.isEmpty()) return emptyList()

        val claimed = BooleanArray(text.length)
        val signals = mutableListOf<Signal>()

        for ((family, marker, weight) in markers) {
            repeat(collectOccurrences(text, marker, claimed).size) {
                signals +=
                    Signal(
                        family = family,
                        weight = weight,
                        evidenceSpan = marker,
                        timestampMs = window.endMs,
                    )
            }
        }

        return signals
    }

    /** Finds every unclaimed occurrence of [marker], marking its span as claimed. */
    private fun collectOccurrences(
        text: String,
        marker: String,
        claimed: BooleanArray,
    ): List<Int> {
        if (marker.isEmpty()) return emptyList()
        val found = mutableListOf<Int>()
        var from = 0
        while (true) {
            val index = text.indexOf(marker, from)
            if (index < 0) break
            val end = index + marker.length
            val overlaps = (index until end).any { claimed[it] }
            if (!overlaps && isWordBoundary(text, index, end)) {
                for (i in index until end) claimed[i] = true
                found += index
            }
            from = index + 1
        }
        return found
    }

    /**
     * Keeps "fir" from matching inside "firm" and "otp" from matching inside
     * "otpx". Cheap, and it removes a whole class of false positive.
     */
    private fun isWordBoundary(
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        val before = if (start == 0) ' ' else text[start - 1]
        val after = if (end >= text.length) ' ' else text[end]
        return !before.isLetterOrDigit() && !after.isLetterOrDigit()
    }

    companion object {
        /** Pseudo-family for negative guards, which subtract rather than add. */
        const val NEGATIVE_GUARD = "NEGATIVE_GUARD"
    }
}
