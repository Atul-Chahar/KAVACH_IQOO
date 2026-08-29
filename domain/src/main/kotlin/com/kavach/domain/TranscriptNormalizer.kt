package com.kavach.domain

/**
 * Normalises transcript text before matching. The lexicon's own notes require
 * case-insensitive matching over normalised text, and ASR output is messy:
 * inconsistent punctuation, stray capitalisation, doubled spaces.
 *
 * Devanagari is left intact — the lexicon carries markers in both scripts
 * because the ASR may emit either, depending on the recogniser's language — but
 * its digits are mapped to ASCII, so "२ घंटे" and "2 ghante" meet the same
 * deadline markers.
 */
object TranscriptNormalizer {
    /**
     * Dropped outright rather than replaced with a space, so that an ASR that
     * emits "O.T.P." or "F.I.R." still matches the markers "otp" and "fir".
     * Apostrophes go the same way, so "don't" survives as one token.
     */
    private val elided = Regex("[.'`\u2019]+")

    /**
     * Everything else becomes a word break. En and em dashes are in there
     * deliberately: recognisers substitute them for hyphens, and
     * "digital—arrest" must meet the "digital arrest" marker just the way
     * "digital-arrest" would.
     */
    private val separators =
        Regex("[,!?;:\"()\\[\\]{}<>*_/\\\\|~^\u2013\u2014\u2212\u0964\u0965-]+")

    /** `\s` misses NBSP and friends, which some recognisers emit between words. */
    private val whitespace = Regex("[\\s\u00A0\u2007\u202F]+")

    private val devanagariDigits = Regex("[\u0966-\u096F]")

    fun normalize(raw: String): String =
        raw
            .lowercase()
            .replace(devanagariDigits) { ('0' + (it.value[0] - '\u0966')).toString() }
            .replace(elided, "")
            .replace(separators, " ")
            .replace(whitespace, " ")
            .trim()
}
