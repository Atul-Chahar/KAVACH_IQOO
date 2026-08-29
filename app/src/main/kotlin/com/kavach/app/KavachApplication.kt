package com.kavach.app

import android.app.Application
import android.util.Log
import com.kavach.app.capture.CaptureDiagnostics
import com.kavach.app.capture.KavachNotifications
import com.kavach.app.inference.GemmaLlmAdjudicator
import com.kavach.app.inference.PipedAsrTranscriptSource
import com.kavach.app.inference.SystemAsrTranscriptSource
import com.kavach.app.model.ModelRepository
import com.kavach.app.monitor.ShieldController
import com.kavach.demo.FixtureTranscriptSource
import com.kavach.domain.ModelCatalog
import com.kavach.domain.TacticLexicon
import com.kavach.domain.TranscriptSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Application entry point and the whole object graph.
 *
 * Manual constructor injection, no Hilt/Dagger (CLAUDE.md Stack): at this scale
 * the annotation processor costs more build time than it saves, and there is
 * nothing here a reader cannot follow in one screen.
 */
class KavachApplication : Application() {
    /** Parsed once. A failure here is a packaging bug, and it should be loud. */
    val lexicon: TacticLexicon by lazy {
        assets.open(LEXICON_ASSET).bufferedReader().use { TacticLexicon.parse(it.readText()) }
    }

    val models: ModelRepository by lazy {
        ModelRepository(this).also { it.refresh() }
    }

    val speechModels: com.kavach.app.inference.SpeechModelManager by lazy {
        com.kavach.app.inference
            .SpeechModelManager(this)
            .also { it.checkSupport() }
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Ground truth about the microphone, shared by the accessibility service,
     * the capture path and every screen that reports on them.
     *
     * Application-scoped because the accessibility service starts observing the
     * audio mode long before any session exists, and the shield needs to read
     * the same values the moment it appears.
     */
    val diagnostics = CaptureDiagnostics()

    val controller: ShieldController by lazy {
        ShieldController(lexicon, hindi = Locale.getDefault().language == "hi")
    }

    private var activeAdjudicator: GemmaLlmAdjudicator? = null

    override fun onCreate() {
        super.onCreate()
        // Here rather than in KavachService.onCreate, because channel creation
        // is also channel *migration* — retired ids are deleted in the same
        // call — and the service only starts once a session does. A user who
        // installs an update and does not immediately start monitoring would
        // otherwise keep the previous build's channel settings indefinitely.
        KavachNotifications.ensureChannels(this)

        if (!TIER2_ENABLED) {
            Log.w(TAG, "Tier 2 is disabled: it crashes the process on this runtime pairing")
            return
        }

        applicationScope.launch {
            models.state.collectLatest { state ->
                val ready = state as? com.kavach.domain.ModelState.Ready
                val replacement =
                    ready?.let { modelReady ->
                        ModelCatalog.byId(modelReady.specId)?.let { spec ->
                            fileFor(spec).takeIf { it.isFile && it.length() == spec.sizeBytes }?.let { file ->
                                GemmaLlmAdjudicator(file, cacheDir, lexicon.families.map { it.id })
                            }
                        }
                    }
                activeAdjudicator?.let { prev -> applicationScope.launch { prev.closeAsync() } }
                activeAdjudicator = replacement
                controller.adjudicator = replacement
            }
        }
    }

    override fun onTerminate() {
        // best-effort synchronous detach; the ordered path (closeAsync) runs in
        // the collector above whenever replacement happens during normal runtime.
        activeAdjudicator?.close()
        applicationScope.cancel()
        super.onTerminate()
    }

    private fun fileFor(spec: com.kavach.domain.ModelSpec): File = models.fileFor(spec)

    /**
     * The live pipeline.
     *
     * Kavach opens the microphone and feeds the on-device recogniser through a
     * pipe, rather than letting the recogniser open the microphone itself. That
     * is not a stylistic choice: the in-call capture exemption is granted to the
     * UID that owns the recording, so the obvious implementation is silenced
     * during exactly the calls this app exists for. See PipedAsrTranscriptSource.
     *
     * When the Whisper/QNN path lands it is swapped here and nothing else changes.
     */
    fun createLiveTranscriptSource(): TranscriptSource = PipedAsrTranscriptSource(this, diagnostics)

    fun createDemoTranscriptSource(asset: String): TranscriptSource = FixtureTranscriptSource(this, asset)

    fun demoFixtures(): List<String> =
        runCatching { FixtureTranscriptSource.available(this) }
            .onFailure { Log.w(TAG, "no demo fixtures packaged", it) }
            .getOrDefault(emptyList())

    /** True when live capture can work at all. Drives the honest degraded message. */
    fun isLiveCaptureAvailable(): Boolean = SystemAsrTranscriptSource.isAvailable(this)

    private companion object {
        const val TAG = "Kavach"
        const val LEXICON_ASSET = "tactic_lexicon.json"

        /**
         * Tier 2 is off, and this is not a preference — it is a crash.
         *
         * LiteRT-LM 0.16.1 calls SendChannel.close$default(...) as a static on the
         * interface. Every kotlinx-coroutines in the 1.9-1.10 line puts that bridge
         * in SendChannel$DefaultImpls instead, so the first inference that completes
         * throws NoSuchMethodError. Verified on the iQOO, twice, about six seconds
         * after the engine reports ready:
         *
         *     FATAL EXCEPTION: Thread-82
         *     java.lang.NoSuchMethodError: No static method close$default(...)
         *       at com.google.ai.edge.litertlm.Conversation.sendMessageAsync
         *
         * It lands on LiteRT-LM's own JNI callback thread, not inside our coroutine,
         * so no catch of ours can reach it and the process dies mid-call. An
         * adjudicator that kills the app is strictly worse than no adjudicator:
         * Tier 1 alone is required to carry the whole demo (docs/ARCHITECTURE.md 3),
         * and it does.
         *
         * The fix is a version alignment that cannot be made safely right now.
         * litertlm 0.17.0-alpha1 needs Kotlin 2.4 metadata and this project is
         * pinned to 2.2; coroutines 1.10.2 was tried and crashes identically.
         * Flip this back to true once the toolchain can move, and re-run the
         * DemoMode fixture before trusting it.
         */
        const val TIER2_ENABLED = false
    }
}
