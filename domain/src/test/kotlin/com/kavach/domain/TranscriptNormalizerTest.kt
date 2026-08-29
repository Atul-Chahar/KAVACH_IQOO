package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptNormalizerTest {
    @Test
    fun `lowercases and strips punctuation`() {
        assertEquals("aap otp bataiye", TranscriptNormalizer.normalize("Aap, O.T.P. bataiye!!"))
    }

    @Test
    fun `collapses whitespace and newlines`() {
        assertEquals("main cbi se", TranscriptNormalizer.normalize("  Main\n\tCBI   se  "))
    }

    @Test
    fun `leaves devanagari intact`() {
        assertEquals("डिजिटल अरेस्ट", TranscriptNormalizer.normalize("डिजिटल अरेस्ट।"))
    }

    @Test
    fun `handles empty input`() {
        assertEquals("", TranscriptNormalizer.normalize("   "))
    }
}
