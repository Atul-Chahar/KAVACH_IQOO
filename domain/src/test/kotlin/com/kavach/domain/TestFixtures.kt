package com.kavach.domain

import java.io.File

/**
 * Loads the real `data/` and `fixtures/` files so the tests exercise the
 * shipped lexicon rather than a hand-written stand-in. If these drift apart,
 * the tests are worthless.
 */
object TestFixtures {
    /** Walks up from the module directory to find the repository root. */
    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "data/tactic_lexicon.json").exists() }
            ?: error("could not locate repo root from ${File(".").absolutePath}")
    }

    val lexicon: TacticLexicon by lazy {
        TacticLexicon.parse(File(repoRoot, "data/tactic_lexicon.json").readText())
    }

    data class Fixture(
        val name: String,
        val isPositive: Boolean,
        val lines: List<String>,
    ) {
        /** Full script as one string, for a whole-conversation score. */
        val text: String get() = lines.joinToString(" ")
    }

    fun fixtures(): List<Fixture> =
        listOf(true to "positive", false to "negative").flatMap { (positive, dir) ->
            File(repoRoot, "fixtures/$dir")
                .listFiles { f -> f.extension == "txt" }
                .orEmpty()
                .sortedBy { it.name }
                .map { file -> Fixture(file.name, positive, parse(file.readText())) }
        }

    /**
     * Strips the `# expected:` header comments and `SPEAKER:` prefixes, leaving
     * only what a transcriber would actually produce.
     */
    private fun parse(raw: String): List<String> =
        raw
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.substringAfter(':', it).trim() }
            .toList()

    /**
     * Replays a fixture through the engine the way the app does — one line at a
     * time, with plausible spacing — so decay and diversity are actually
     * exercised rather than bypassed by a single bulk match.
     */
    fun replay(
        fixture: Fixture,
        secondsPerLine: Long = 6,
    ): RiskAssessment {
        val engine = RiskEngine(lexicon)
        var assessment = RiskAssessment.WATCHING
        var peak = RiskAssessment.WATCHING
        fixture.lines.forEachIndexed { index, line ->
            val endMs = (index + 1) * secondsPerLine * 1000L
            assessment = engine.onTranscript(TranscriptWindow(line, endMs - secondsPerLine * 1000L, endMs))
            if (assessment.score >= peak.score) peak = assessment
        }
        return peak
    }
}
