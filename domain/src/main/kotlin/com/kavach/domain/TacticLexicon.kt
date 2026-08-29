package com.kavach.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed view of `data/tactic_lexicon.json` — the detection patterns, and the
 * core IP of this app. Parsed once at startup and held immutable.
 */
@Serializable
data class TacticLexicon(
    val version: String,
    val scoring: Scoring,
    val families: List<Family>,
    val negativeGuards: NegativeGuards,
) {
    @Serializable
    data class Scoring(
        val highRiskThreshold: Int,
        val cautionThreshold: Int,
        val decayHalfLifeSeconds: Int,
        val minDistinctFamiliesForHighRisk: Int,
        val familyDiversityBonus: Int,
        val maxScorePerFamily: Int,
    )

    @Serializable
    data class Family(
        val id: String,
        val displayEn: String,
        val displayHi: String,
        val baseWeight: Int,
        val markers: List<Marker>,
    )

    @Serializable
    data class NegativeGuards(
        val markers: List<Marker>,
    )

    /** A single phrase. [t]ext and [w]eight, named as in the JSON. */
    @Serializable
    data class Marker(
        @SerialName("t") val text: String,
        @SerialName("w") val weight: Int,
    )

    /** Plain-language label for a family id, for the UI. Explainability is mandatory. */
    fun displayName(
        familyId: String,
        hindi: Boolean,
    ): String? = families.firstOrNull { it.id == familyId }?.let { if (hindi) it.displayHi else it.displayEn }

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        /**
         * Parses the lexicon. Throws on malformed input — this is a build asset,
         * not model output, so a failure here is a packaging bug we want loudly.
         */
        fun parse(raw: String): TacticLexicon = json.decodeFromString(serializer(), raw)
    }
}
