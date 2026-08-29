package com.kavach.demo

import android.content.Context
import com.kavach.domain.TranscriptSource
import com.kavach.domain.TranscriptWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * DemoMode: replays a scripted fixture through the **identical** pipeline.
 *
 * Nothing downstream of this class can tell the difference between a fixture and
 * the microphone — same [TranscriptWindow]s, same engine, same UI. That is what
 * makes it an honest fallback rather than a fake: it exercises the real
 * detection code, and it works in airplane mode with no mic and no model.
 *
 * ⚠ FREEZE RULE (CLAUDE.md hard rule 6): once `demo/DEMO_FROZEN` exists, this
 * module is read-only. Do not edit it, for any reason, at any hour.
 */
class FixtureTranscriptSource(
    private val context: Context,
    private val fixtureAsset: String,
    private val secondsPerLine: Long = DEFAULT_SECONDS_PER_LINE,
) : TranscriptSource {
    override val engineName: String = "DemoMode (${fixtureAsset.substringAfterLast('/')})"

    private var startedAtMs = 0L

    override suspend fun start() {
        startedAtMs = System.currentTimeMillis()
    }

    override suspend fun stop() = Unit

    override fun transcripts(): Flow<TranscriptWindow> =
        flow {
            val lines = readFixture()
            lines.forEachIndexed { index, line ->
                delay(secondsPerLine * MILLIS_PER_SECOND)
                val end = (index + 1) * secondsPerLine * MILLIS_PER_SECOND
                emit(TranscriptWindow(line, end - secondsPerLine * MILLIS_PER_SECOND, end))
            }
        }

    /**
     * Strips the `# expected:` header and `SPEAKER:` prefixes, leaving only what
     * a transcriber would actually produce.
     */
    private fun readFixture(): List<String> =
        context.assets.open(fixtureAsset).bufferedReader().use { reader ->
            reader
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.substringAfter(':', it).trim() }
        }

    companion object {
        const val DEFAULT_SECONDS_PER_LINE = 4L
        private const val MILLIS_PER_SECOND = 1000L

        const val ASSET_DIR = "fixtures"

        /** Fixture scripts shipped in assets, positives first. */
        fun available(context: Context): List<String> =
            listOf("positive", "negative").flatMap { kind ->
                context.assets
                    .list("$ASSET_DIR/$kind")
                    .orEmpty()
                    .filter { it.endsWith(".txt") }
                    .sorted()
                    .map { "$ASSET_DIR/$kind/$it" }
            }

        fun isPositive(asset: String): Boolean = asset.contains("/positive/")
    }
}
