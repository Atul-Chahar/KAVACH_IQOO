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

    /** Bidi controls can make rendered text appear reordered or deceptively reversed. */
    private val bidiControls = Regex("[\u202A-\u202E\u2066-\u2069\u200E\u200F]")

    /** Model markup has no business on an alert card. */
    private val markupTags = Regex("<[^>]*>")

    private val whitespace = Regex("\\s+")

    /**
     * Returns null on anything malformed, out of range, or otherwise unusable.
     * Every balanced object is tried because prose before the verdict may itself
     * contain braces ("I think {this} looks bad").
     */
    fun parseOrNull(
        raw: String?,
        knownFamilies: Set<String>,
    ): Verdict? {
        val text = raw ?: return null
        return extractJsonObjects(text)
            .asSequence()
            .mapNotNull { decode(it) }
            .mapNotNull { validate(it, knownFamilies) }
            .firstOrNull()
    }

    private fun decode(candidate: String): Verdict? =
        runCatching { json.decodeFromString(Verdict.serializer(), candidate) }.getOrNull()

    private fun validate(
        verdict: Verdict,
        knownFamilies: Set<String>,
    ): Verdict? {
        if (verdict.risk !in 0..RiskAssessment.MAX_SCORE) return null

        val reason = sanitize(verdict.oneLineReason)
        if (reason.isEmpty()) return null

        return verdict.copy(
            tactics = verdict.tactics.filter { it in knownFamilies },
            oneLineReason = reason,
            // This value is retained only as sanitised data; the UI must not
            // render a model-selected action. User actions remain deterministic.
            recommendedAction = sanitize(verdict.recommendedAction),
        )
    }

    /** One clean, single line of plain text for safe diagnostic display. */
    private fun sanitize(raw: String): String =
        raw
            .replace(markupTags, " ")
            .replace(bidiControls, "")
            .replace(whitespace, " ")
            .trim()
            .take(MAX_REASON_LENGTH)

    /** Every balanced top-level `{...}` object in [raw], in appearance order. */
    private fun extractJsonObjects(raw: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false

        raw.forEachIndexed { index, character ->
            val state = JsonScanState(character, depth, start, inString, escaped)
            if (state.isEscaped) {
                escaped = false
            } else if (state.isStringEscape) {
                escaped = true
            } else if (state.isQuote) {
                inString = !inString
            } else if (!inString) {
                when (character) {
                    '{' -> {
                        if (depth == 0) start = index
                        depth++
                    }
                    '}' -> {
                        if (depth > 0) {
                            depth--
                            if (depth == 0 && start >= 0) {
                                objects += raw.substring(start, index + 1)
                                start = -1
                            }
                        }
                    }
                }
            }
        }
        return objects
    }

    private data class JsonScanState(
        val character: Char,
        val depth: Int,
        val start: Int,
        val inString: Boolean,
        val escaped: Boolean,
    ) {
        val isEscaped: Boolean get() = escaped
        val isStringEscape: Boolean get() = character == '\\' && inString
        val isQuote: Boolean get() = character == '"'
    }
}
