package com.kavach.app.monitor

import com.kavach.domain.IncidentRecorder
import com.kavach.domain.RiskAssessment
import com.kavach.domain.RiskBand
import com.kavach.domain.RiskEngine
import com.kavach.domain.TacticLexicon
import com.kavach.domain.TranscriptSource
import com.kavach.domain.VerdictSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext

/** Which pipeline is feeding the engine. Both run through identical downstream code. */
enum class MonitorMode { LIVE, DEMO }

/**
 * Owns the running session: drives a [TranscriptSource] into the [RiskEngine]
 * and publishes one [ShieldUiState].
 *
 * Single source of truth for both the UI and the foreground service, so there is
 * no shared mutable state across threads — everything observes one StateFlow.
 */
class ShieldController(
    private val lexicon: TacticLexicon,
    private val hindi: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val engine = RiskEngine(lexicon)
    private val recorder = IncidentRecorder(lexicon)
    private val knownFamilies = lexicon.families.map { it.id }.toSet()

    private val _state = MutableStateFlow(ShieldUiState())
    val state: StateFlow<ShieldUiState> = _state.asStateFlow()

    /**
     * Bounded, DROP_OLDEST: if adjudication is slower than speech we skip
     * windows rather than lag (CLAUDE.md hard rule 7). A slow consumer must
     * degrade quality, never block the producer.
     */
    private val adjudicationQueue = Channel<String>(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var sessionJob: Job? = null
    private var sessionStartMs = 0L
    private var source: TranscriptSource? = null

    var adjudicator: LlmAdjudicator? = null

    fun start(
        scope: CoroutineScope,
        mode: MonitorMode,
        transcriptSource: TranscriptSource,
    ) {
        stop()
        engine.reset()
        recorder.clear()
        source = transcriptSource
        sessionStartMs = clock()

        _state.value =
            ShieldUiState(
                monitoring = true,
                mode = mode,
                engineName = transcriptSource.engineName,
                degradedReason = if (adjudicator == null) DEGRADED_NO_MODEL else null,
            )

        sessionJob =
            scope.launch {
                val ticker = launch { tick() }
                val judge = launch { adjudicate() }

                runCatching {
                    transcriptSource.start()
                    transcriptSource.transcripts().collect { window ->
                        val assessment = engine.onTranscript(window, elapsedMs())
                        recorder.onAssessment(assessment, clock())
                        adjudicationQueue.trySend(window.text)
                        publish(assessment, window.text)
                    }
                }.onFailure { error ->
                    // Never crash the session on a source failure — degrade honestly.
                    _state.update { it.copy(monitoring = false, failureReason = error.message ?: "capture stopped") }
                }

                // The source ran out: a DemoMode fixture reached its last line.
                // Close the session explicitly rather than leaving a frozen clock
                // on screen, and keep the final verdict visible.
                ticker.cancel()
                judge.cancel()
                finishSession()
            }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        finishSession()
    }

    /**
     * Ends the session exactly once, whether the user tapped Stop or the source
     * ran out. The final assessment is deliberately left on screen: the verdict
     * on the conversation that just happened is still the answer.
     */
    private fun finishSession() {
        if (!_state.value.monitoring) return
        recorder.endSession(clock())
        val finished = source
        source = null
        _state.update { it.copy(monitoring = false, incidents = recorder.all()) }
        // Best-effort: releasing the recogniser must not throw into the caller.
        runCatching { finished?.let { runBlocking { it.stop() } } }
    }

    fun report(formatTimestamp: (Long) -> String): String = recorder.renderReport(formatTimestamp)

    /**
     * Re-publishes once a second so the elapsed clock advances and decay is
     * reflected even while nobody is speaking.
     *
     * Uses its **own** coroutine's liveness, not the parent job's: a parent
     * whose body has finished but whose children are still running reports
     * `isActive == false`, which silently froze this loop the moment a fixture
     * reached its last line.
     */
    private suspend fun tick() {
        while (coroutineContext.isActive) {
            delay(TICK_MS)
            publish(engine.assess(elapsedMs()), _state.value.transcriptPreview)
        }
    }

    /**
     * Tier 2. Every failure path is silent by design: a timeout, malformed JSON
     * or a dead model must be invisible to the user, who keeps seeing the
     * Tier-1 score (docs/ARCHITECTURE.md 3).
     */
    private suspend fun adjudicate() {
        for (transcript in adjudicationQueue) {
            adjudicateOne(transcript)
        }
    }

    /** One window. Any failure returns quietly; the user keeps seeing Tier 1. */
    private suspend fun adjudicateOne(transcript: String) {
        val engineRef = adjudicator ?: return
        val raw = runCatching { engineRef.adjudicate(transcript) }.getOrNull()
        val verdict = VerdictSchema.parseOrNull(raw, knownFamilies) ?: return
        val merged = engine.merge(engine.assess(elapsedMs()), verdict)
        publish(merged, _state.value.transcriptPreview)
    }

    private fun publish(
        assessment: RiskAssessment,
        transcript: String,
    ) {
        _state.update { current ->
            current.copy(
                assessment = assessment,
                tactics = assessment.matchedFamilies.mapNotNull { lexicon.displayName(it, hindi) },
                elapsedMs = elapsedMs(),
                transcriptPreview = transcript.takeLast(TRANSCRIPT_PREVIEW_CHARS),
                escalated = assessment.band == RiskBand.HIGH_RISK,
            )
        }
    }

    private fun elapsedMs() = clock() - sessionStartMs

    private fun MutableStateFlow<ShieldUiState>.update(block: (ShieldUiState) -> ShieldUiState) {
        value = block(value)
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val TRANSCRIPT_PREVIEW_CHARS = 240
        const val DEGRADED_NO_MODEL = "Advanced analysis unavailable"
    }
}
