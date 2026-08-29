package com.kavach.demo

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.kavach.domain.TranscriptSource
import com.kavach.domain.TranscriptWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray

/**
 * DemoMode: replays a scripted fixture through the **identical** pipeline,
 * playing studio-grade pre-rendered neural voice audio via [MediaPlayer] while
 * emitting [TranscriptWindow]s in frame-accurate real-time synchrony with the
 * spoken dialogue.
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
    private var mediaPlayer: MediaPlayer? = null

    private data class TimedUtterance(
        val speaker: String,
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    override suspend fun start() {
        startedAtMs = System.currentTimeMillis()
        val audioAsset = fixtureAsset.removeSuffix(TXT_EXTENSION) + MP3_EXTENSION
        runCatching {
            val afd = context.assets.openFd(audioAsset)
            val player =
                MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    prepare()
                    start()
                }
            mediaPlayer = player
        }.onFailure {
            Log.w(TAG, "Audio asset $audioAsset not available or failed to play: ${it.message}")
        }
    }

    override suspend fun stop() {
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
    }

    override fun transcripts(): Flow<TranscriptWindow> =
        flow {
            val utterances = readTimedUtterances()
            if (utterances.isEmpty()) return@flow

            var lastEndMs = 0L

            try {
                for (utt in utterances) {
                    val delayNeeded = (utt.endMs - lastEndMs).coerceAtLeast(0L)
                    delay(delayNeeded)
                    lastEndMs = utt.endMs
                    emit(TranscriptWindow(utt.text, utt.startMs, utt.endMs))
                }
            } finally {
                mediaPlayer?.runCatching {
                    if (isPlaying) stop()
                }
            }
        }

    /**
     * Reads timestamped utterances from the companion .json file. If missing,
     * falls back to linearly spaced lines from the .txt fixture.
     */
    private fun readTimedUtterances(): List<TimedUtterance> {
        val jsonAsset = fixtureAsset.removeSuffix(TXT_EXTENSION) + JSON_EXTENSION
        val jsonContent =
            runCatching {
                context.assets
                    .open(jsonAsset)
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrNull()

        if (jsonContent != null) {
            val parsed =
                runCatching {
                    val array = JSONArray(jsonContent)
                    (0 until array.length()).map { i ->
                        val obj = array.getJSONObject(i)
                        TimedUtterance(
                            speaker = obj.optString(FIELD_SPEAKER, DEFAULT_SPEAKER),
                            text = obj.getString(FIELD_TEXT),
                            startMs = obj.getLong(FIELD_START_MS),
                            endMs = obj.getLong(FIELD_END_MS),
                        )
                    }
                }.getOrNull()

            if (!parsed.isNullOrEmpty()) {
                return parsed
            }
        }

        return readFixtureLinesFallback()
    }

    private fun readFixtureLinesFallback(): List<TimedUtterance> {
        val rawLines =
            runCatching {
                context.assets.open(fixtureAsset).bufferedReader().use { reader ->
                    reader
                        .readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                }
            }.getOrDefault(emptyList())

        val stepMs = secondsPerLine * MILLIS_PER_SECOND
        return rawLines.mapIndexed { index, line ->
            val speaker = if (line.contains(':')) line.substringBefore(':').trim() else DEFAULT_SPEAKER
            val text = line.substringAfter(':', line).trim()
            val startMs = index * stepMs
            val endMs = (index + 1) * stepMs
            TimedUtterance(speaker, text, startMs, endMs)
        }
    }

    companion object {
        private const val TAG = "KavachDemo"
        const val DEFAULT_SECONDS_PER_LINE = 4L
        private const val MILLIS_PER_SECOND = 1000L

        private const val TXT_EXTENSION = ".txt"
        private const val MP3_EXTENSION = ".mp3"
        private const val JSON_EXTENSION = ".json"

        private const val FIELD_SPEAKER = "speaker"
        private const val FIELD_TEXT = "text"
        private const val FIELD_START_MS = "startMs"
        private const val FIELD_END_MS = "endMs"
        private const val DEFAULT_SPEAKER = "CALLER"

        const val ASSET_DIR = "fixtures"

        /** Fixture scripts shipped in assets, positives first. */
        fun available(context: Context): List<String> =
            listOf("positive", "negative").flatMap { kind ->
                context.assets
                    .list("$ASSET_DIR/$kind")
                    .orEmpty()
                    .filter { it.endsWith(TXT_EXTENSION) }
                    .sorted()
                    .map { "$ASSET_DIR/$kind/$it" }
            }

        fun isPositive(asset: String): Boolean = asset.contains("/positive/")
    }
}
