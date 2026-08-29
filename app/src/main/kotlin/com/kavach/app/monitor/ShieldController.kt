package com.kavach.app.monitor

import com.kavach.domain.IncidentRecorder
import com.kavach.domain.RiskAssessment
import com.kavach.domain.RiskBand
import com.kavach.domain.RiskEngine
import com.kavach.domain.Signal
import com.kavach.domain.TacticLexicon
import com.kavach.domain.TranscriptSource
import com.kavach.domain.Verdict
import com.kavach.domain.VerdictSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /**
     * Seeded with the lexicon's own shape so the home screen can state what the
     * engine is before any session has run — the counts on screen are read from
     * the same data the scorer uses, never duplicated as constants.
     */
    private val _state = MutableStateFlow(lexiconFacts(ShieldUiState()))
    val state: StateFlow<ShieldUiState> = _state.asStateFlow()

    /**
     * Bounded, DROP_OLDEST: if adjudication is slower than speech we skip
     * windows rather than lag (CLAUDE.md hard rule 7). A slow consumer must
     * degrade quality, never block the producer.
     *
     * Created per session and never shared across them. As a member it survived
     * [start], so up to two transcripts from the previous call were still queued
     * when the next one began — immediately after `engine.reset()` — and the new
     * conversation inherited the old one's evidence.
     */
    private var adjudicationQueue: Channel<String>? = null

    /**
     * The most recent Tier-2 verdict, held rather than emitted once.
     *
     * [RiskEngine.merge] is a pure function, so a merged assessment that is only
     * published lives exactly until the next tick republishes the Tier-1 score
     * over it — under a second. Keeping the verdict here means every subsequent
     * tick merges it again, which is the difference between the model affecting
     * what the user sees and the model being decorative.
     */
    private var llmVerdict: Verdict? = null

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
        llmVerdict = null
        source = transcriptSource
        sessionStartMs = clock()

        val queue = Channel<String>(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        adjudicationQueue = queue

        _state.value =
            ShieldUiState(
                monitoring = true,
                mode = mode,
                engineName = transcriptSource.engineName,
                degradedReason = if (adjudicator == null) DEGRADED_NO_MODEL else null,
            ).let(::lexiconFacts)

        sessionJob =
            scope.launch {
                val ticker = launch { tick() }
                val judge = launch { adjudicate(queue) }

                runCatching {
                    transcriptSource.start()
                    transcriptSource.transcripts().collect { window ->
                        val assessment = engine.onTranscript(window, elapsedMs())
                        logMatch(window, assessment)
                        recorder.onAssessment(assessment, clock())
                        // Settled windows only. ASR partials re-send the same
                        // sentence several times a second, and with a queue two
                        // deep every one of them evicted the last, so the model
                        // was handed a near-random fragment of a phrase still
                        // being spoken instead of a finished utterance.
                        if (!window.isPartial) queue.trySend(window.text)
                        publish(merged(assessment), window.text)
                    }
                    if (mode == MonitorMode.DEMO) {
                        // In DemoMode, hold the final warning screen indefinitely
                        // instead of closing and returning to the home screen. Stop the ticker
                        // so time decay doesn't degrade the final score while idle.
                        ticker.cancel()
                        judge.cancel()
                        awaitCancellation()
                    }
                }.onFailure { error ->
                    // Cancellation is how a session ends normally. Reporting it as a
                    // capture failure would put a false error on screen every time
                    // the user pressed Stop.
                    if (error is CancellationException) throw error
                    // Never crash the session on a source failure — degrade honestly.
                    _state.update { it.copy(monitoring = false, failureReason = error.message ?: "capture stopped") }
                }

                // The source ran out: a live session ended or error occurred.
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
        adjudicationQueue?.close()
        adjudicationQueue = null
        source = null
        _state.update { it.copy(monitoring = false, incidents = recorder.all()) }
        // The source's callbackFlow owns asynchronous recogniser teardown in
        // awaitClose. Cancelling the session job closes that flow; never use
        // runBlocking from a UI/service callback.
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
            publish(merged(engine.assess(elapsedMs())), _state.value.transcriptPreview)
        }
    }

    /**
     * Tier 2. Every failure path is silent by design: a timeout, malformed JSON
     * or a dead model must be invisible to the user, who keeps seeing the
     * Tier-1 score (docs/ARCHITECTURE.md 3).
     */
    private suspend fun adjudicate(queue: Channel<String>) {
        for (transcript in queue) {
            adjudicateOne(transcript)
        }
    }

    /**
     * One window. Any failure returns quietly; the user keeps seeing Tier 1.
     *
     * Quiet to the *user*, not to logcat. Silence in both directions is why the
     * model could be loaded, wired and answering nothing at all without anybody
     * being able to tell — so each window records whether a verdict arrived, and
     * what it changed about the number on screen. The transcript is never logged,
     * and neither is the model's sentence: both are derived from what was said.
     */
    private suspend fun adjudicateOne(transcript: String) {
        val engineRef = adjudicator ?: return
        val raw = runCatching { engineRef.adjudicate(transcript) }.getOrNull()
        val verdict = VerdictSchema.parseOrNull(raw, knownFamilies)
        if (verdict == null) {
            android.util.Log.i(
                TIER2_TAG,
                "no usable verdict (raw=${raw?.length ?: -1} chars) — showing Tier 1",
            )
            return
        }
        val tier1 = engine.assess(elapsedMs())
        llmVerdict = verdict
        val shown = merged(tier1)
        android.util.Log.i(
            TIER2_TAG,
            "verdict risk=${verdict.risk} tactics=${verdict.tactics} " +
                "action=${verdict.recommendedAction} " +
                "tier1=${tier1.score}/${tier1.band} shown=${shown.score}/${shown.band}",
        )
        publish(shown, _state.value.transcriptPreview)
    }

    /**
     * Enough to debug detection on a real device without ever logging the
     * conversation: which families fired, which marker spans caused them, and
     * the resulting score. The words the user actually said are not written
     * anywhere, including here.
     */
    private fun logMatch(
        window: com.kavach.domain.TranscriptWindow,
        assessment: RiskAssessment,
    ) {
        android.util.Log.d(
            "KavachMatch",
            "chars=${window.text.length} partial=${window.isPartial} " +
                "score=${assessment.score} band=${assessment.band} " +
                "families=${assessment.matchedFamilies} " +
                "spans=${assessment.evidence.take(EVIDENCE_LOG_LIMIT).map { it.evidenceSpan }}",
        )
    }

    /** Tier 1, with Tier 2 folded in if it has ever spoken this session. */
    private fun merged(assessment: RiskAssessment): RiskAssessment =
        llmVerdict?.let { engine.merge(assessment, it) } ?: assessment

    /**
     * "I'm fine." Silences the warning for the rest of this call without
     * silencing Kavach: capture continues, the score keeps moving, and the
     * report still records what was heard. Dismissing a warning is not the same
     * verb as stopping the app, and wiring them to one handler — as the session
     * footer did — quietly taught the user that neither could be trusted.
     */
    fun dismissAlert() {
        _state.update { it.copy(alertDismissed = true) }
    }

    private fun publish(
        assessment: RiskAssessment,
        transcript: String,
    ) {
        _state.update { current ->
            current.copy(
                assessment = assessment,
                tactics = assessment.matchedFamilies.mapNotNull { lexicon.displayName(it, hindi) },
                tacticEvidence = rank(assessment.matchedFamilies, assessment.evidence),
                elapsedMs = elapsedMs(),
                alertDismissed = current.alertDismissed && assessment.band == RiskBand.HIGH_RISK,
                transcriptPreview = transcript.takeLast(TRANSCRIPT_PREVIEW_CHARS),
                escalated = assessment.band == RiskBand.HIGH_RISK,
            )
        }
    }

    /**
     * The user asking to see the words Kavach matched on. Off by default and
     * never persisted: the preview lives in memory for the length of the
     * session and is written over, like the audio it came from.
     */
    fun setShowTranscript(show: Boolean) {
        _state.update { it.copy(showTranscript = show) }
    }

    /**
     * Turns an assessment into the numbered rows the alerting screens draw.
     *
     * [RiskAssessment.matchedFamilies] already arrives ranked strongest-first
     * and [RiskAssessment.evidence] newest-first, so this only has to pair them
     * up — the ordering the user sees is the engine's own, not a second opinion
     * invented in the UI.
     */
    private fun rank(
        families: List<String>,
        evidence: List<Signal>,
    ): List<TacticEvidence> =
        families.mapNotNull { family ->
            lexicon.displayName(family, hindi)?.let { name ->
                TacticEvidence(
                    displayName = name,
                    familyId = family,
                    lastSeenElapsedMs = evidence.firstOrNull { it.family == family }?.timestampMs ?: 0L,
                )
            }
        }

    private fun lexiconFacts(state: ShieldUiState) =
        state.copy(
            familiesTotal = lexicon.families.size,
            familiesForWarning = lexicon.scoring.minDistinctFamiliesForHighRisk,
            cautionThreshold = lexicon.scoring.cautionThreshold,
            highRiskThreshold = lexicon.scoring.highRiskThreshold,
            lexiconVersion = lexicon.version,
        )

    private fun elapsedMs() = clock() - sessionStartMs

    private companion object {
        const val TIER2_TAG = "KavachTier2"
        const val TICK_MS = 1_000L
        const val TRANSCRIPT_PREVIEW_CHARS = 240
        const val DEGRADED_NO_MODEL = "Advanced analysis unavailable"
        const val EVIDENCE_LOG_LIMIT = 6
    }
}
