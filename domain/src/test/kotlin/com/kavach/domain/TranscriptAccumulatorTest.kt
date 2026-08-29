package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptAccumulatorTest {
    @Test
    fun `a growing partial replaces itself rather than repeating`() {
        val accumulator = TranscriptAccumulator()
        accumulator.add("main")
        accumulator.add("main sub")
        val result = accumulator.add("main sub inspector")

        assertEquals("main sub inspector", result)
    }

    @Test
    fun `a revised shorter partial keeps the recogniser's newest guess`() {
        val accumulator = TranscriptAccumulator()
        accumulator.add("main sub inspector")
        val result = accumulator.add("main sub")

        assertEquals("main sub", result)
    }

    @Test
    fun `a new utterance is appended, not merged`() {
        val accumulator = TranscriptAccumulator()
        accumulator.add("main sub inspector bol raha hoon")
        val result = accumulator.add("aapka aadhaar linked hai")

        assertEquals("main sub inspector bol raha hoon aapka aadhaar linked hai", result)
    }

    @Test
    fun `blank windows change nothing`() {
        val accumulator = TranscriptAccumulator()
        accumulator.add("hello")
        val result = accumulator.add("   ")

        assertEquals("hello", result)
    }

    @Test
    fun `the transcript stays inside its budget, dropping oldest first`() {
        val accumulator = TranscriptAccumulator(maxChars = 30)
        accumulator.add("first utterance here")
        accumulator.add("second utterance here")
        val result = accumulator.add("third utterance here")

        assertTrue(result.length <= 30, "expected <= 30 chars, got ${result.length}: $result")
        assertTrue(result.endsWith("third utterance here"), "newest must survive: $result")
        assertTrue(!result.contains("first"), "oldest must be dropped first: $result")
    }

    @Test
    fun `a single oversized utterance is kept whole rather than split`() {
        val accumulator = TranscriptAccumulator(maxChars = 10)
        val long = "an utterance far longer than the budget"
        assertEquals(long, accumulator.add(long))
    }

    @Test
    fun `clear empties the transcript`() {
        val accumulator = TranscriptAccumulator()
        accumulator.add("something")
        accumulator.clear()

        assertEquals("", accumulator.snapshot())
    }
}
