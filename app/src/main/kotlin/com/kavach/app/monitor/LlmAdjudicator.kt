package com.kavach.app.monitor

/**
 * Tier 2. Implemented by the LiteRT-LM/Gemma engine once the model file is on
 * the device; null until then, and the app is fully functional without it.
 *
 * The contract is deliberately weak — it returns raw text, because that is all
 * a language model can honestly promise. Validation happens in
 * `VerdictSchema.parseOrNull`, and raw text never reaches the UI.
 */
interface LlmAdjudicator {
    /** Returns the model's raw response, or null on timeout or failure. */
    suspend fun adjudicate(transcript: String): String?

    fun close()
}
