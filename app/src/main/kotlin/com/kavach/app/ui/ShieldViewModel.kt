package com.kavach.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.app.KavachApplication
import com.kavach.app.capture.KavachService
import com.kavach.app.monitor.MonitorMode
import com.kavach.app.monitor.ShieldUiState
import com.kavach.domain.ModelCatalog
import com.kavach.domain.ModelSpec
import com.kavach.domain.ModelState
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

    val fixtures: List<String> = app.demoFixtures()

    val modelSpec: ModelSpec = ModelCatalog.default

    val modelState: StateFlow<ModelState> = app.models.state

    fun freeSpaceBytes(): Long = app.models.usableSpaceBytes()

    fun refreshModel() = app.models.refresh(modelSpec)

    /**
     * The app cannot fetch this itself — it has no INTERNET permission. It hands
     * the official URL to the browser and the user brings the file back.
     */
    fun downloadIntent(): Intent = Intent(Intent.ACTION_VIEW, modelSpec.fileUrl.toUri())

    fun importModel(uri: Uri) {
        viewModelScope.launch { app.models.import(uri, modelSpec) }
    }

    fun deleteModel() = app.models.delete(modelSpec)

    fun liveCaptureAvailable(): Boolean = app.isLiveCaptureAvailable()

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
