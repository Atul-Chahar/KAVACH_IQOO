package com.kavach.app.inference

import android.content.Context
import android.content.Intent
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
import com.kavach.app.capture.CaptureDiagnostics
import com.kavach.app.capture.MicCapture
import com.kavach.domain.AudioRingBuffer
import com.kavach.domain.TranscriptSource
import com.kavach.domain.TranscriptWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.IOException

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
 * Owning the microphone makes the fix cheap: the PCM is ours, so we tee it into
 * one pipe per language and run the recognisers side by side, merging both
 * transcript streams into the same engine. Latin markers match the English
 * stream, Devanagari markers match the Hindi stream, and the engine's existing
 * span dedup stops a phrase both of them heard from scoring twice.
 *
 * Fallbacks are written first (CLAUDE.md working style). A language the device
 * has not downloaded is dropped from the set rather than retried forever; if
 * every language is missing, or the recogniser will not take a piped source at
 * all, the session degrades to a stated reason instead of a silent nothing.
 */
class PipedAsrTranscriptSource(
    private val context: Context,
    private val diagnostics: CaptureDiagnostics,
    private val languages: List<String> = DEFAULT_LANGUAGES,
) : TranscriptSource {
    private val main = Handler(Looper.getMainLooper())
    private var startedAtMs = 0L
    private val live = mutableListOf<String>()
    private var ears: List<Ear> = emptyList()

    override val engineName: String
        get() =
            if (live.isEmpty()) {
                "Kavach mic → on-device ASR"
            } else {
                "Kavach mic → on-device ASR (${live.joinToString(" + ")})"
            }

    override suspend fun start() {
        startedAtMs = System.currentTimeMillis()
    }

    override suspend fun stop() = Unit

    override fun transcripts(): Flow<TranscriptWindow> =
        callbackFlow {
            if (!SystemAsrTranscriptSource.isAvailable(context)) {
                close(IllegalStateException(SystemAsrTranscriptSource.UNAVAILABLE_MESSAGE))
                return@callbackFlow
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                close(IllegalStateException(SystemAsrTranscriptSource.UNAVAILABLE_MESSAGE))
                return@callbackFlow
            }

            val audioManager = context.getSystemService(AudioManager::class.java)
            diagnostics.onAudioMode(audioManager?.mode ?: 0)
            if (audioManager == null) {
                close(IllegalStateException("no audio service"))
                return@callbackFlow
            }

            val mic = MicCapture(audioManager, diagnostics)
            val hearing = languages.map { Ear(it, this) }
            ears = hearing
            live.clear()
            live += languages

            val micJob =
                launch(Dispatchers.IO) {
                    try {
                        mic.run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IllegalStateException) {
                        close(e)
                    }
                }

            // One reader, many writers. The microphone thread is never the thing
            // that blocks: MicCapture's channel is DROP_OLDEST, so a recogniser
            // that stalls costs audio quality and nothing else.
            val teeJob =
                launch(Dispatchers.IO) {
                    mic.frames().collect { frame ->
                        val bytes = frame.toLittleEndianBytes()
                        hearing.forEach { it.write(bytes) }
                    }
                }

            main.post {
                hearing.forEach { it.open(context) }
                publishLive()
            }

            awaitClose {
                teeJob.cancel()
                micJob.cancel()
                main.post { hearing.forEach { it.close() } }
            }
        }

    /** Republishes which languages are still listening, after any of them retires. */
    private fun publishLive() {
        live.clear()
        live += ears.filter { it.alive }.map { it.language }
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
        val language: String,
        private val scope: ProducerScope<TranscriptWindow>,
    ) {
        var alive: Boolean = true
            private set

        private var recognizer: SpeechRecognizer? = null
        private var pipe: Pipe? = null
        private val lock = Any()
        private var stopped = false

        fun open(context: Context) {
            runCatching {
                recognizer =
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                        setRecognitionListener(listener)
                    }
            }.onFailure {
                Log.w(TAG, "[$language] recogniser unavailable", it)
                alive = false
            }
            if (alive) beginCycle()
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
            if (stopped || !alive) return
            endCycle()

            val fresh = runCatching { Pipe.open() }.getOrNull()
            if (fresh == null) {
                Log.w(TAG, "[$language] could not open a pipe")
                alive = false
                return
            }
            synchronized(lock) { pipe = fresh }

            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, fresh.read)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioRingBuffer.SAMPLE_RATE_HZ)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }

            main.post {
                if (!stopped) {
                    runCatching { recognizer?.startListening(intent) }
                        .onFailure { Log.w(TAG, "[$language] startListening failed", it) }
                }
            }
        }

        /** This language is not downloaded. Drop it and let the others carry on. */
        private fun retire(reason: String) {
            Log.w(TAG, "[$language] retiring: $reason")
            alive = false
            endCycle()
            runCatching { recognizer?.destroy() }
            recognizer = null
        }

        private val listener =
            object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    emit(results, partial = false)
                    beginCycle()
                }

                override fun onPartialResults(partialResults: Bundle?) = emit(partialResults, partial = true)

                override fun onError(error: Int) {
                    when (error) {
                        // Normal in a quiet room. Not evidence of anything wrong.
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        -> beginCycle()

                        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> {
                            retire("language pack not installed (error $error)")
                            publishLive()
                        }

                        else -> {
                            Log.w(TAG, "[$language] recogniser error $error")
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
                    diagnostics.onTranscript()
                    val now = System.currentTimeMillis() - startedAtMs
                    scope.trySend(TranscriptWindow(text, now - WINDOW_MS, now, isPartial = partial))
                }
            }
    }

    /**
     * One recogniser session's worth of pipe.
     *
     * The read end is handed over and deliberately kept open on our side until
     * teardown: closing it during the binder handover is a race, and holding it
     * does not stop the recogniser seeing EOF, which the write end signals.
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
                // The recogniser closed its end. Normal at every cycle boundary.
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
        private const val WINDOW_MS = 8_000L

        /** SpeechRecognizer error codes, absent from the constants available at minSdk 30. */
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /**
         * English first because the lexicon is 105 Latin markers to 16 Devanagari,
         * including every negative guard. Hindi is what makes the Hindi half of a
         * code-switched sentence legible at all.
         */
        val DEFAULT_LANGUAGES = listOf("en-IN", "hi-IN")
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
