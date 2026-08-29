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

    @Test
    fun `em and en dashes become word breaks`() {
        assertEquals("digital arrest", TranscriptNormalizer.normalize("digital—arrest"))
        assertEquals("digital arrest", TranscriptNormalizer.normalize("digital–arrest"))
    }

    @Test
    fun `devanagari digits map to ascii`() {
        assertEquals("2 घंटे के अंदर", TranscriptNormalizer.normalize("२ घंटे के अंदर"))
    }

    @Test
    fun `non breaking spaces collapse like normal spaces`() {
        assertEquals("digital arrest", TranscriptNormalizer.normalize("digital arrest"))
    }
}
