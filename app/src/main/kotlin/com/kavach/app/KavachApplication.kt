package com.kavach.app

import android.app.Application
import android.util.Log
import com.kavach.app.capture.CaptureDiagnostics
import com.kavach.app.capture.KavachNotifications
import com.kavach.app.inference.GemmaLlmAdjudicator
import com.kavach.app.inference.PipedAsrTranscriptSource
import com.kavach.app.inference.SystemAsrTranscriptSource
import com.kavach.app.message.MessageGuardStore
import com.kavach.app.model.ModelRepository
import com.kavach.app.monitor.ShieldController
import com.kavach.demo.FixtureTranscriptSource
import com.kavach.domain.ModelCatalog
import com.kavach.domain.SmsMessageAnalyzer
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

    val messageGuard: MessageGuardStore by lazy {
        MessageGuardStore(SmsMessageAnalyzer(lexicon))
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
    }
}
