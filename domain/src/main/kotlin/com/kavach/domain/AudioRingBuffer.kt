package com.kavach.domain

/**
 * Fixed-size circular buffer of 16 kHz mono PCM16 samples.
 *
 * CLAUDE.md hard rule 3: audio never touches disk. This is the only place
 * captured audio lives — preallocated once, overwritten in place, discarded
 * when the session ends. It cannot grow, so a slow consumer degrades audio
 * quality rather than leaking memory or blocking the mic thread.
 *
 * Pure Kotlin so the wrap-around arithmetic is unit-tested rather than trusted;
 * the `AudioRecord` loop that feeds it lives in `app/capture/`.
 */
class AudioRingBuffer(
    val capacitySamples: Int,
) {
    init {
        require(capacitySamples > 0) { "capacity must be positive" }
    }

    private val buffer = ShortArray(capacitySamples)
    private var writeIndex = 0
    private var filled = 0

    /** Samples currently held, up to [capacitySamples]. */
    val size: Int get() = filled

    val isFull: Boolean get() = filled == capacitySamples

    /** Total samples ever written, including those since overwritten. */
    var totalWritten: Long = 0
        private set

    @Synchronized
    fun write(
        samples: ShortArray,
        count: Int = samples.size,
    ) {
        require(count <= samples.size) { "count exceeds input size" }
        var offset = 0
        var remaining = count

        // A chunk larger than the buffer can only leave its tail behind.
        if (remaining > capacitySamples) {
            offset = remaining - capacitySamples
            remaining = capacitySamples
        }

        while (remaining > 0) {
            val chunk = minOf(remaining, capacitySamples - writeIndex)
            samples.copyInto(buffer, writeIndex, offset, offset + chunk)
            writeIndex = (writeIndex + chunk) % capacitySamples
            offset += chunk
            remaining -= chunk
        }

        filled = minOf(capacitySamples, filled + count)
        totalWritten += count
    }

    /** Snapshot in chronological order, oldest first. Allocates; call sparingly. */
    @Synchronized
    fun snapshot(): ShortArray {
        if (filled == 0) return ShortArray(0)
        val out = ShortArray(filled)
        val start = if (filled < capacitySamples) 0 else writeIndex
        for (i in 0 until filled) {
            out[i] = buffer[(start + i) % capacitySamples]
        }
        return out
    }

    /** Zeroes the contents. Called when monitoring stops, so nothing lingers in RAM. */
    @Synchronized
    fun clear() {
        buffer.fill(0)
        writeIndex = 0
        filled = 0
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000
        private const val RING_BUFFER_SECONDS = 10

        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_MS = 20
        const val SAMPLES_PER_FRAME = SAMPLE_RATE_HZ / MILLIS_PER_SECOND * FRAME_MS

        /** The 10-second window from docs/ARCHITECTURE.md 5. */
        fun tenSeconds() = AudioRingBuffer(SAMPLE_RATE_HZ * RING_BUFFER_SECONDS)
    }
}
