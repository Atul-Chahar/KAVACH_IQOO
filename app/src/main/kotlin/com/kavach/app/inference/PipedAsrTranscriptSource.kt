package com.kavach.app.inference

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import com.kavach.app.capture.CaptureDiagnostics
import com.kavach.app.capture.MicCapture
import com.kavach.app.capture.MicOwner
import com.kavach.domain.AudioRingBuffer
import com.kavach.domain.TranscriptSource
import com.kavach.domain.TranscriptWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

/**
 * The live pipeline: **Kavach opens the microphone, and every recogniser reads
 * what we give it.**
 *
 * Two separate reasons this cannot be `SpeechRecognizer` opening the mic itself.
 *
 * The first is the in-call exemption. `RecognizerIntent`'s own documentation says
 * that without [RecognizerIntent.EXTRA_AUDIO_SOURCE] "the recognizer will open
 * the mic", and it does so under its own UID. Android grants the in-call capture
 * exemption to *accessibility services*, matched on the UID that owns the
 * recording, and the recogniser's process is not one — so that path is silenced
 * mid-call while reporting no error at all.
 *
 * The second only became obvious on real speech. Indian scam calls are
 * code-switched: one sentence carries a Hindi verb, an English noun and a
 * romanised proper name. A single recogniser cannot cover that. `en-IN` emits
 * Latin script and matches the lexicon's 105 Latin markers, but an English
 * acoustic model turns spoken Hindi into noise. `hi-IN` emits Devanagari and
 * transcribes the Hindi properly, but only 16 markers are written in that script.
 * Either one alone misses most of a real call.
 *
 * Running both at once is the obvious answer, and the device refuses it: a second
 * concurrent recogniser returns `ERROR_RECOGNIZER_BUSY`, because the on-device
 * service holds one session at a time. So we alternate instead. The recogniser
 * ends its session at every utterance boundary anyway and the microphone behind
 * it never stops, so changing language between utterances costs nothing we were
 * not already paying. Over a conversation both scripts get heard, the engine
 * accumulates across the whole session, and its existing span dedup stops a
 * phrase heard twice from scoring twice.
 *
 * Fallbacks are written first (CLAUDE.md working style). A language the device
 * has not downloaded is retried and triggers on-demand background model downloads
 * via Android 13+ APIs.
 */
class PipedAsrTranscriptSource(
    private val context: Context,
    private val diagnostics: CaptureDiagnostics,
    private val languages: List<String> = defaultLanguages(),
) : TranscriptSource {
    private val main = Handler(Looper.getMainLooper())
    private var startedAtMs = 0L

    /** Languages still in rotation. Shrinks as the device tells us what it lacks. */
    private val live = mutableListOf<String>()

    /** Speech model manager for on-demand downloading of missing offline models. */
    private val modelManager = SpeechModelManager(context)

    /**
     * Whether we are still feeding the recogniser our own microphone.
     *
     * `RecognizerIntent` allows a recogniser not to support a supplied audio
     * source, and this device refuses one in a way that looks nothing like
     * "unsupported": it answers `ERROR_LANGUAGE_UNAVAILABLE` within a few
     * milliseconds, for a language it handles perfectly well when it opens the
     * microphone itself. Believing that answer retired every language and left
     * the app deaf.
     *
     * So the piped path is tried first, because it is the only one that can work
     * during a call, and if the device will not take it we hand the microphone
     * back. That mode works everywhere except during a call — which is exactly
     * where the UI then says, out loud, that it cannot hear.
     */
    private var piped = true

    /** Cancels our own capture when we stop being the microphone's owner. */
    private var micJob: Job? = null

    override val engineName: String
        get() {
            val owner = if (piped) "Kavach mic" else "recogniser mic"
            val tongues = if (live.isEmpty()) "" else " (${live.joinToString(" + ")})"
            return "$owner → on-device ASR$tongues"
        }

    override suspend fun start() {
        startedAtMs = System.currentTimeMillis()
    }

    override suspend fun stop() = Unit

    override fun transcripts(): Flow<TranscriptWindow> =
        callbackFlow {
            val audioManager =
                checkPrerequisites(context) ?: run {
                    close(IllegalStateException(SystemAsrTranscriptSource.UNAVAILABLE_MESSAGE))
                    return@callbackFlow
                }

            triggerMissingModelCheck(languages)

            val mic = MicCapture(audioManager, diagnostics)
            val ear = Ear(this)
            live.clear()
            live += languages

            micJob =
                launch(Dispatchers.IO) {
                    try {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            close(IllegalStateException("microphone permission was revoked mid-session"))
                            return@launch
                        }
                        mic.run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IllegalStateException) {
                        close(e)
                    }
                }

            val teeJob =
                launch(Dispatchers.IO) {
                    mic.frames().collect { frame ->
                        val bytes = frame.toLittleEndianBytes()
                        ear.write(bytes)
                    }
                }

            main.post {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ear.open(context)
                }
                publishLive()
            }

            awaitClose {
                teeJob.cancel()
                micJob?.cancel()
                micJob = null
                main.post { ear.close() }
            }
        }

    private fun checkPrerequisites(context: Context): AudioManager? {
        if (!SystemAsrTranscriptSource.isAvailable(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
        diagnostics.onAudioMode(audioManager?.mode ?: 0)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return audioManager
    }

    private fun triggerMissingModelCheck(targetLanguages: List<String>) {
        runCatching {
            modelManager.checkSupport { status ->
                val missing =
                    targetLanguages.filter { lang ->
                        status.installedLanguages.none { it.startsWith(lang.substringBefore("-"), ignoreCase = true) }
                    }
                if (missing.isNotEmpty()) {
                    Log.i(TAG, "Triggering background download for missing offline models: $missing")
                    modelManager.triggerDownload(missing)
                }
            }
        }
    }

    /** Republishes which languages are still in rotation, after any of them retires. */
    private fun publishLive() {
        diagnostics.onLanguage(live.joinToString(" + ").ifEmpty { null })
    }

    /**
     * One recogniser, listening in one language, fed from its own pipe.
     *
     * A recogniser ends its session at every utterance boundary, so each cycle
     * needs a fresh pipe — the old read end has been consumed. The microphone
     * behind all of them never stops, which is why the speech that used to fall
     * into the gap between restarts no longer does.
     */
    private inner class Ear(
        private val scope: ProducerScope<TranscriptWindow>,
    ) {
        private var recognizer: SpeechRecognizer? = null
        private var pipe: Pipe? = null
        private val lock = Any()
        private var stopped = false
        private var turn = 0

        /** Consecutive language failures, per language. */
        private val languageFailures = mutableMapOf<String, Int>()

        private var pipeRejections = 0
        private var heardAnything = false

        private fun handBackMicrophone() {
            if (!piped) return
            Log.w(TAG, "device will not read our audio pipe; handing the microphone to the recogniser")
            piped = false
            endCycle()
            micJob?.cancel()
            micJob = null
            diagnostics.onOwner(MicOwner.RECOGNISER)
            diagnostics.onCaptureError(PIPE_UNSUPPORTED_MESSAGE)
        }

        /** The language for this cycle. Advances one step per utterance. */
        private val language: String
            get() = live.getOrElse(turn % live.size.coerceAtLeast(1)) { FALLBACK_LANGUAGE }

        @RequiresApi(Build.VERSION_CODES.S)
        fun open(context: Context) {
            runCatching {
                recognizer =
                    createRecogniser(context).apply {
                        setRecognitionListener(listener)
                    }
            }.onFailure {
                Log.w(TAG, "recogniser unavailable", it)
                scope.close(IllegalStateException(SystemAsrTranscriptSource.UNAVAILABLE_MESSAGE))
                return
            }
            beginCycle()
        }

        fun write(bytes: ByteArray) {
            val target = synchronized(lock) { pipe } ?: return
            target.write(bytes)
        }

        fun close() {
            stopped = true
            endCycle()
            runCatching {
                recognizer?.stopListening()
                recognizer?.destroy()
            }
            recognizer = null
        }

        private fun endCycle() {
            synchronized(lock) {
                pipe?.close()
                pipe = null
            }
        }

        private fun beginCycle() {
            if (stopped) return
            if (live.isEmpty()) {
                // Ensure we never completely run out of fallback language
                live += FALLBACK_LANGUAGE
                publishLive()
            }
            endCycle()

            val listening = language
            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, listening)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }

            if (piped) {
                val fresh = runCatching { Pipe.open() }.getOrNull()
                if (fresh == null) {
                    Log.w(TAG, "could not open a pipe; handing the mic back")
                    handBackMicrophone()
                } else {
                    synchronized(lock) { pipe = fresh }
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, fresh.read)
                    intent.putExtra(
                        RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                        AudioRingBuffer.SAMPLE_RATE_HZ,
                    )
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    intent.putExtra(
                        RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                }
            }

            main.post {
                if (!stopped) {
                    runCatching { recognizer?.startListening(intent) }
                        .onFailure { Log.w(TAG, "[$listening] startListening failed", it) }
                }
            }
        }

        /**
         * Records a language failure, triggers background model downloads if missing,
         * and only retires the language after repeated failures while preserving fallbacks.
         */
        private fun noteLanguageFailure(error: Int) {
            if (piped && !heardAnything) {
                pipeRejections++
                Log.w(TAG, "[$language] error $error while piped ($pipeRejections/$MAX_PIPE_REJECTIONS)")
                if (pipeRejections >= MAX_PIPE_REJECTIONS) handBackMicrophone()
                return
            }

            val failing = language
            val count = (languageFailures[failing] ?: 0) + 1
            languageFailures[failing] = count
            Log.w(TAG, "[$failing] language error $error ($count/$MAX_LANGUAGE_FAILURES)")

            // On missing model error, trigger background download for the failing language
            if (error == ERROR_LANGUAGE_UNAVAILABLE || error == ERROR_LANGUAGE_NOT_SUPPORTED) {
                runCatching { modelManager.triggerDownload(listOf(failing)) }
            }

            if (count < MAX_LANGUAGE_FAILURES) return

            // If we have more than 1 language in rotation, retire this failing one temporarily
            if (live.size > 1) {
                Log.w(TAG, "[$failing] retiring temporarily after $count attempt(s)")
                live.remove(failing)
                publishLive()
            }
        }

        private val listener =
            object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    emit(results, partial = false)
                    turn++
                    beginCycle()
                }

                override fun onPartialResults(partialResults: Bundle?) = emit(partialResults, partial = true)

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        -> {
                            turn++
                            beginCycle()
                        }

                        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> {
                            noteLanguageFailure(error)
                            turn++
                            main.postDelayed({ if (!stopped) beginCycle() }, LANGUAGE_BACKOFF_MS)
                        }

                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            Log.w(TAG, "[$language] recogniser busy, backing off")
                            turn++
                            main.postDelayed({ if (!stopped) beginCycle() }, BUSY_BACKOFF_MS)
                        }

                        else -> {
                            Log.w(TAG, "[$language] recogniser error $error")
                            turn++
                            main.postDelayed({ if (!stopped) beginCycle() }, ERROR_BACKOFF_MS)
                        }
                    }
                }

                override fun onEndOfSpeech() = Unit

                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit

                private fun emit(
                    bundle: Bundle?,
                    partial: Boolean,
                ) {
                    val text =
                        bundle
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: return
                    heardAnything = true
                    languageFailures.remove(language)
                    diagnostics.onTranscript()
                    val now = System.currentTimeMillis() - startedAtMs
                    scope.trySend(TranscriptWindow(text, now - WINDOW_MS, now, isPartial = partial))
                }
            }
    }

    /**
     * Picks a recognition service, most capable first.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun createRecogniser(context: Context): SpeechRecognizer {
        val preferred = ComponentName(SYSTEM_INTELLIGENCE_PACKAGE, SYSTEM_INTELLIGENCE_SERVICE)
        if (isUsable(context, preferred)) {
            val explicit = runCatching { SpeechRecognizer.createSpeechRecognizer(context, preferred) }.getOrNull()
            if (explicit != null) {
                Log.i(TAG, "using $SYSTEM_INTELLIGENCE_PACKAGE")
                return explicit
            }
        }
        Log.i(TAG, "using the framework's default on-device recogniser")
        return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    }

    /** Installed, enabled, and actually declaring a RecognitionService. */
    private fun isUsable(
        context: Context,
        component: ComponentName,
    ): Boolean =
        runCatching {
            context.packageManager
                .queryIntentServices(Intent(RECOGNITION_SERVICE_ACTION), 0)
                .any { it.serviceInfo?.packageName == component.packageName }
        }.getOrDefault(false)

    /**
     * One recogniser session's worth of pipe.
     */
    private class Pipe(
        val read: ParcelFileDescriptor,
        write: ParcelFileDescriptor,
    ) {
        private val out = ParcelFileDescriptor.AutoCloseOutputStream(write)

        fun write(bytes: ByteArray) {
            try {
                out.write(bytes)
            } catch (e: IOException) {
                Log.d(TAG, "pipe closed: ${e.message}")
            }
        }

        fun close() {
            runCatching { out.close() }
            runCatching { read.close() }
        }

        companion object {
            fun open(): Pipe {
                val fds = ParcelFileDescriptor.createPipe()
                return Pipe(read = fds[0], write = fds[1])
            }
        }
    }

    companion object {
        private const val TAG = "KavachAsr"
        private const val ERROR_BACKOFF_MS = 400L
        private const val BUSY_BACKOFF_MS = 1_000L
        private const val LANGUAGE_BACKOFF_MS = 1_200L
        private const val MAX_LANGUAGE_FAILURES = 3
        private const val MAX_PIPE_REJECTIONS = 2

        private const val RECOGNITION_SERVICE_ACTION = "android.speech.RecognitionService"
        private const val SYSTEM_INTELLIGENCE_PACKAGE = "com.google.android.as"
        private const val SYSTEM_INTELLIGENCE_SERVICE =
            "com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService"

        const val PIPE_UNSUPPORTED_MESSAGE =
            "This device will not let Kavach feed its own microphone to the recogniser, " +
                "so listening during a call is unavailable."
        private const val WINDOW_MS = 8_000L

        /** SpeechRecognizer error codes (API 31+ constants). */
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /**
         * English and Hindi in rotation, with the active system locale and fallback
         * so single-language devices without downloaded Indic offline packs do not crash.
         */
        fun defaultLanguages(): List<String> {
            val system = Locale.getDefault().toLanguageTag()
            return listOf("en-IN", "hi-IN", system, "en-US")
                .filter { it.isNotBlank() }
                .distinct()
        }

        val DEFAULT_LANGUAGES = defaultLanguages()

        const val FALLBACK_LANGUAGE = "en-IN"

        const val NO_MIC_PERMISSION_MESSAGE = "Microphone permission was withdrawn."

        const val NO_LANGUAGE_MESSAGE =
            "Offline speech models are downloading. Keep internet active for a moment " +
                "while Google Speech Services installs Hindi and English models."
    }
}

private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8

/** PCM16 little-endian, which is what both AudioRecord and the recogniser expect. */
private fun ShortArray.toLittleEndianBytes(): ByteArray {
    val out = ByteArray(size * Short.SIZE_BYTES)
    var i = 0
    for (sample in this) {
        val value = sample.toInt()
        out[i++] = (value and BYTE_MASK).toByte()
        out[i++] = ((value shr BYTE_BITS) and BYTE_MASK).toByte()
    }
    return out
}
