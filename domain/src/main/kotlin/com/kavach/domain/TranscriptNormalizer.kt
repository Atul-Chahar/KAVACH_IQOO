package com.kavach.domain

/**
 * Normalises transcript text before matching. The lexicon's own notes require
 * case-insensitive matching over normalised text, and ASR output is messy:
 * inconsistent punctuation, stray capitalisation, doubled spaces.
 *
 * Devanagari is left intact — the lexicon carries markers in both scripts
 * because the ASR may emit either, depending on the recogniser's language.
 */
object TranscriptNormalizer {
    /**
     * Dropped outright rather than replaced with a space, so that an ASR that
     * emits "O.T.P." or "F.I.R." still matches the markers "otp" and "fir".
     * Apostrophes go the same way, so "don't" survives as one token.
     */
    private val elided = Regex("[.'`\u2019]+")

    /** Everything else becomes a word break. */
    private val separators = Regex("[,!?;:\"()\\[\\]{}<>*_/\\\\|~^\u0964\u0965-]+")

    private val whitespace = Regex("\\s+")

    fun normalize(raw: String): String =
        raw
            .lowercase()
            .replace(elided, "")
            .replace(separators, " ")
            .replace(whitespace, " ")
            .trim()
}
