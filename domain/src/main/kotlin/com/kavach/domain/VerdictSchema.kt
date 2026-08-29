package com.kavach.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Tier-2 model output, after validation. Raw model text never reaches the UI. */
@Serializable
data class Verdict(
    val risk: Int,
    val tactics: List<String> = emptyList(),
    @SerialName("one_line_reason") val oneLineReason: String = "",
    @SerialName("recommended_action") val recommendedAction: String = "",
)

/**
 * Parses and validates Tier-2 output. CLAUDE.md hard rule 4: all model output is
 * schema-validated before use, and on parse failure we fall back silently to the
 * Tier-1 score. An LLM is a text generator; treating its output as trusted
 * structured data is how you end up rendering a prompt injection to a
 * frightened person.
 */
object VerdictSchema {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private const val MAX_REASON_LENGTH = 160

    /**
     * Returns null on anything malformed, out of range, or otherwise unusable.
     *
     * The early returns are the validation: each one is a distinct way model
     * output can be untrustworthy, and collapsing them into one expression would
     * make the rejection reasons harder to read, not easier.
     */
    @Suppress("ReturnCount")
    fun parseOrNull(
        raw: String?,
        knownFamilies: Set<String>,
    ): Verdict? {
        val candidate = extractJsonObject(raw ?: return null) ?: return null
        val verdict = runCatching { json.decodeFromString(Verdict.serializer(), candidate) }.getOrNull() ?: return null

        if (verdict.risk !in 0..RiskAssessment.MAX_SCORE) return null

        // Drop invented tactic names. The UI names families in plain language,
        // so an unknown id would render as nothing useful at best.
        val tactics = verdict.tactics.filter { it in knownFamilies }

        val reason = verdict.oneLineReason.trim().take(MAX_REASON_LENGTH)
        if (reason.isEmpty()) return null

        return verdict.copy(tactics = tactics, oneLineReason = reason)
    }

    /**
     * Pulls the first balanced JSON object out of the response. Models wrap JSON
     * in prose and code fences no matter how firmly the prompt says not to.
     */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
