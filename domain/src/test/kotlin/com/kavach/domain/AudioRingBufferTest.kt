package com.kavach.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioRingBufferTest {
    @Test
    fun `holds what fits`() {
        val buffer = AudioRingBuffer(5)
        buffer.write(shortArrayOf(1, 2, 3))
        assertEquals(3, buffer.size)
        assertContentEquals(shortArrayOf(1, 2, 3), buffer.snapshot())
        assertFalse(buffer.isFull)
    }

    @Test
    fun `overwrites oldest samples once full`() {
        val buffer = AudioRingBuffer(4)
        buffer.write(shortArrayOf(1, 2, 3, 4))
        buffer.write(shortArrayOf(5, 6))
        assertTrue(buffer.isFull)
        assertContentEquals(shortArrayOf(3, 4, 5, 6), buffer.snapshot())
    }

    @Test
    fun `a write larger than the buffer keeps only its tail`() {
        val buffer = AudioRingBuffer(3)
        buffer.write(shortArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertContentEquals(shortArrayOf(5, 6, 7), buffer.snapshot())
    }

    @Test
    fun `memory does not grow across a long session`() {
        // 30 minutes of 20 ms frames — the S0.3 acceptance criterion, in miniature.
        val buffer = AudioRingBuffer.tenSeconds()
        val frame = ShortArray(AudioRingBuffer.SAMPLES_PER_FRAME)
        repeat(30 * 60 * 50) { buffer.write(frame) }
        assertEquals(buffer.capacitySamples, buffer.size)
        assertEquals(30L * 60 * 50 * AudioRingBuffer.SAMPLES_PER_FRAME, buffer.totalWritten)
    }

    @Test
    fun `clear leaves nothing behind`() {
        val buffer = AudioRingBuffer(4)
        buffer.write(shortArrayOf(1, 2, 3, 4))
        buffer.clear()
        assertEquals(0, buffer.size)
        assertContentEquals(ShortArray(0), buffer.snapshot())
    }

    @Test
    fun `partial writes respect the count argument`() {
        val buffer = AudioRingBuffer(5)
        buffer.write(shortArrayOf(1, 2, 3, 4, 5), count = 2)
        assertContentEquals(shortArrayOf(1, 2), buffer.snapshot())
    }

    @Test
    fun `ten second buffer is sized as documented`() {
        assertEquals(160_000, AudioRingBuffer.tenSeconds().capacitySamples)
        assertEquals(320, AudioRingBuffer.SAMPLES_PER_FRAME)
    }
}
