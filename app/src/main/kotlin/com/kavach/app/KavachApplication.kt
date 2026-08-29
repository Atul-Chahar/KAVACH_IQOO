package com.kavach.app

import android.app.Application
import android.util.Log
import com.kavach.app.inference.SystemAsrTranscriptSource
import com.kavach.app.monitor.ShieldController
import com.kavach.demo.FixtureTranscriptSource
import com.kavach.domain.TacticLexicon
import com.kavach.domain.TranscriptSource
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

    val controller: ShieldController by lazy {
        ShieldController(lexicon, hindi = Locale.getDefault().language == "hi")
    }

    /**
     * The live pipeline. Today this is the on-device system recogniser; when the
     * Whisper/QNN path lands it is swapped here and nothing else changes.
     */
    fun createLiveTranscriptSource(): TranscriptSource = SystemAsrTranscriptSource(this)

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
