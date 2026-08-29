package com.kavach.domain

import kotlinx.coroutines.flow.Flow

/**
 * A rolling window of recognised speech. [endMs] is relative to session start,
 * which is what the time-decay maths needs.
 */
data class TranscriptWindow(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val isPartial: Boolean = false,
)

/**
 * Where transcripts come from. The mic is exclusive — SpeechRecognizer and
 * AudioRecord cannot both hold it — so the abstraction lives at the transcript
 * level, not the audio level. That is what lets the app fall back from Whisper
 * to the system recogniser to a scripted fixture without anything above this
 * interface changing.
 */
interface TranscriptSource {
    /** Human-readable name of the active engine, for the diagnostics line. */
    val engineName: String

    fun transcripts(): Flow<TranscriptWindow>

    suspend fun start()

    suspend fun stop()
}
