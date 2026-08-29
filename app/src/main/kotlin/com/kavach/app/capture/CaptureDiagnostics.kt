package com.kavach.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Why this exists at all.
 *
 * Android does not fail when it refuses an app the microphone during a call — it
 * hands over a stream of zeroes and says nothing. An app that does not measure
 * what it is receiving cannot tell "nobody is speaking" from "the platform has
 * muted us", and will happily show a calm green screen while a scam is running.
 *
 * So capture is instrumented rather than trusted. Everything here is observable
 * ground truth read back from the platform or computed from the samples
 * themselves, and it is rendered on screen unedited (see DiagnosticsPanel).
 * The honest failure state is worth more than a false all-clear.
 */
class CaptureDiagnostics {
    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    fun onAudioMode(mode: Int) = _state.update { it.copy(audioMode = mode) }

    fun onSilenced(silenced: Boolean) = _state.update { it.copy(silenced = silenced) }

    fun onOwner(owner: MicOwner) = _state.update { it.copy(owner = owner) }

    fun onFrame(rmsDb: Double) =
        _state.update {
            it.copy(
                rmsDb = rmsDb,
                framesRead = it.framesRead + 1,
                nonSilentFrames = if (rmsDb > SILENCE_FLOOR_DB) it.nonSilentFrames + 1 else it.nonSilentFrames,
            )
        }

    fun onTranscript() = _state.update { it.copy(transcripts = it.transcripts + 1) }

    fun onLanguage(tag: String?) = _state.update { it.copy(language = tag ?: "device default") }

    fun onCaptureError(message: String?) = _state.update { it.copy(captureError = message) }

    fun reset() = _state.update { CaptureState(audioMode = it.audioMode) }

    companion object {
        /** Digital silence sits far below this. Room tone does not. */
        const val SILENCE_FLOOR_DB = -70.0
    }
}

/** Who opened the AudioRecord. This is the whole ballgame — see docs/ARCHITECTURE.md 2. */
enum class MicOwner {
    /** Kavach opened it. The accessibility exemption is keyed to our UID, so only this can work in-call. */
    KAVACH,

    /** The system recogniser opened it under its own UID. Silenced during a call. */
    RECOGNISER,

    /** Nothing is recording. */
    NONE,
}

data class CaptureState(
    val audioMode: Int = 0,
    val silenced: Boolean = false,
    val owner: MicOwner = MicOwner.NONE,
    val rmsDb: Double = Double.NEGATIVE_INFINITY,
    val framesRead: Long = 0,
    val nonSilentFrames: Long = 0,
    val transcripts: Int = 0,
    /** Which language the recogniser actually accepted — not the one we asked for. */
    val language: String = "—",
    val captureError: String? = null,
) {
    /** True once we have read enough to be sure the stream is digital silence, not a quiet room. */
    val provenSilent: Boolean
        get() = framesRead > FRAMES_BEFORE_VERDICT && nonSilentFrames == 0L

    /** True when we are demonstrably receiving real audio. This is the number the demo hangs on. */
    val hearing: Boolean
        get() = nonSilentFrames > 0

    private companion object {
        /** 20 ms frames, so 150 frames is three seconds — long enough to be conclusive. */
        const val FRAMES_BEFORE_VERDICT = 150
    }
}
