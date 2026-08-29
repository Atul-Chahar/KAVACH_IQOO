package com.kavach.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.app.KavachApplication
import com.kavach.app.capture.CaptureState
import com.kavach.app.capture.KavachService
import com.kavach.app.monitor.MonitorMode
import com.kavach.app.monitor.ShieldUiState
import com.kavach.domain.ModelCatalog
import com.kavach.domain.ModelSpec
import com.kavach.domain.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thin: it starts and stops sessions and exposes the controller's single
 * StateFlow. All the logic worth testing lives in `:domain`, on the JVM.
 */
class ShieldViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as KavachApplication

    val state: StateFlow<ShieldUiState> = app.controller.state

    /**
     * Ground truth about the microphone, so the session screen can animate from
     * the real signal instead of pretending. A waveform that moves while the
     * platform is feeding us silence would be the false all-clear docs/SAFETY.md
     * forbids, so the same flow that drives the diagnostics panel drives the art.
     */
    val capture: StateFlow<CaptureState> = app.diagnostics.state

    val fixtures: List<String> = app.demoFixtures()

    val modelSpec: ModelSpec = ModelCatalog.default

    val modelState: StateFlow<ModelState> = app.models.state

    val speechModelStatus: StateFlow<com.kavach.app.inference.SpeechModelStatus> = app.speechModels.status

    fun refreshSpeechModels() {
        app.speechModels.checkSupport()
    }

    fun downloadSpeechModels() {
        app.speechModels.triggerDownload()
    }

    fun freeSpaceBytes(): Long = app.models.usableSpaceBytes()

    fun refreshModel() {
        viewModelScope.launch(Dispatchers.IO) { app.models.refresh(modelSpec) }
    }

    /**
     * The app cannot fetch this itself — it has no INTERNET permission. It hands
     * the official URL to the browser and the user brings the file back.
     */
    fun downloadIntent(): Intent = Intent(Intent.ACTION_VIEW, modelSpec.fileUrl.toUri())

    fun importModel(uri: Uri) {
        viewModelScope.launch { app.models.import(uri, modelSpec) }
    }

    fun deleteModel() {
        viewModelScope.launch(Dispatchers.IO) { app.models.delete(modelSpec) }
    }

    fun liveCaptureAvailable(): Boolean = app.isLiveCaptureAvailable()

    /**
     * Resolves a family id to plain language in the device locale, so the report
     * can print "Caller claims to be law enforcement" rather than an enum name.
     * Falls back to the id with its underscores opened out, which is still
     * readable if the lexicon ever drops a family the log remembers.
     */
    fun tacticName(familyId: String): String =
        app.lexicon.displayName(familyId, Locale.getDefault().language == "hi")
            ?: familyId.replace('_', ' ')

    /** Reveals the matched words in-place. Nothing is written anywhere. */
    fun setShowTranscript(show: Boolean) = app.controller.setShowTranscript(show)

    /**
     * "I'm fine." Silences the warning for the rest of this call and nothing
     * more: capture continues, the score keeps moving, the report still records
     * what was heard.
     *
     * The home screen's "Not a scam" button was wired to [stop], so telling
     * Kavach it had misread the call switched the whole thing off — the exact
     * conflation [com.kavach.app.monitor.ShieldController.dismissAlert] was
     * written to end. That fix reached the overlay and never reached here.
     */
    fun dismissAlert() = app.controller.dismissAlert()

    /** Live monitoring runs in the foreground service, so it survives the screen going off. */
    fun startLive() = KavachService.start(getApplication())

    fun stopLive() = KavachService.stop(getApplication())

    /**
     * DemoMode runs in-process, with no foreground service and no microphone —
     * which is exactly why it still works in airplane mode with the mic denied.
     */
    fun startDemo(asset: String) {
        app.controller.start(viewModelScope, MonitorMode.DEMO, app.createDemoTranscriptSource(asset))
    }

    fun stop() {
        if (state.value.mode == MonitorMode.LIVE) stopLive() else app.controller.stop()
    }

    fun report(): String {
        val format = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault())
        return app.controller.report { format.format(Date(it)) }
    }
}
