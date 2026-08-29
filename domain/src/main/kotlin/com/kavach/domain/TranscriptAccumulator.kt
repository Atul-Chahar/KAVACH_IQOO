package com.kavach.domain

/**
 * Builds the rolling transcript Tier 2 is asked about.
 *
 * ASR windows do not arrive as tidy sentences. A recogniser running a continuous
 * session emits the same utterance over and over as it grows — "main", "main
 * sub", "main sub inspector" — then abandons it and starts a fresh one.
 * Concatenating every window hands the model twenty copies of one half-sentence;
 * keeping only the newest hands it a fragment with no context. Neither is a
 * conversation, and the model's answer is only as good as the excerpt.
 *
 * So a window that extends or revises the previous one replaces it, and a window
 * that does not is appended as a new utterance. The result is bounded to
 * [maxChars], oldest dropped first.
 *
 * Nothing here is persisted. This is the same in-memory text the preview shows,
 * and it dies with the session.
 */
class TranscriptAccumulator(
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    private val utterances = ArrayDeque<String>()

    /** Folds one window in and returns the rolling transcript. */
    fun add(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return snapshot()

        val last = utterances.lastOrNull()
        if (last != null && continues(last, trimmed)) {
            utterances.removeLast()
        }
        utterances.addLast(trimmed)

        trimToBudget()
        return snapshot()
    }

    /** The rolling transcript, oldest utterance first. */
    fun snapshot(): String = utterances.joinToString(" ")

    fun clear() = utterances.clear()

    /**
     * Whether [next] is the same utterance as [previous], still being
     * transcribed. Growth is the common case; recognisers also revise downward
     * mid-utterance, so a shorter prefix counts too and the newer text wins —
     * it is the recogniser's current best guess, not a truncation.
     */
    private fun continues(
        previous: String,
        next: String,
    ): Boolean = next.startsWith(previous) || previous.startsWith(next)

    /**
     * Drops whole utterances from the front until the budget is met. Never
     * splits one: half a sentence is worse evidence than no sentence, and the
     * model is being asked to judge meaning.
     */
    private fun trimToBudget() {
        var total = utterances.sumOf { it.length + 1 }
        while (total > maxChars && utterances.size > 1) {
            total -= utterances.removeFirst().length + 1
        }
    }

    private companion object {
        /** Roughly a minute of speech, well inside the adjudicator's prompt budget. */
        const val DEFAULT_MAX_CHARS = 1_800
    }
}
