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
import com.kavach.app.capture.MicOwner
import com.kavach.domain.AudioRingBuffer
import com.kavach.domain.TranscriptSource
import com.kavach.domain.TranscriptWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The live pipeline: **Kavach opens the microphone, the recogniser reads what we
 * give it.**
 *
 * The obvious implementation — hand `SpeechRecognizer` the job and let it open
 * the mic — is the one that cannot work during a call. `RecognizerIntent`'s own
 * documentation says that without [RecognizerIntent.EXTRA_AUDIO_SOURCE] "the
 * recognizer will open the mic", and it does so under its own UID. Android's
 * audio policy grants the in-call capture exemption to *accessibility services*,
 * matched by the UID that owns the recording. The recogniser's process is not
 * one, so that path is silenced mid-call while reporting no error at all.
 *
 * Passing a pipe moves the recording into Kavach's UID, where the exemption can
 * apply. Two useful side effects fall out for free:
 *
 *  - the `AudioRecord` runs continuously across recogniser restarts, so the
 *    ~150 ms of speech previously lost at every utterance boundary is not lost;
 *  - the ring buffer becomes the real audio path again, which is what
 *    docs/SAFETY.md has always claimed.
 *
 * The fallback is written first (CLAUDE.md working style): if the device's
 * recogniser does not support the extra — its documentation explicitly allows
 * that — we detect the repeated failure, fall back to letting the recogniser own
 * the microphone, and mark the session degraded rather than pretending. That
 * mode still works everywhere except during a call, which is exactly where the
 * user is then told we cannot hear.
 */
class PipedAsrTranscriptSource(
    private val context: Context,
    private val diagnostics: CaptureDiagnostics,
    private val languageTag: String = DEFAULT_LANGUAGE,
) : TranscriptSource {
    override val engineName: String
        get() = if (pipeSupported) "Kavach mic → on-device ASR ($languageTag)" else "System ASR ($languageTag)"

    private val main = Handler(Looper.getMainLooper())
    private var startedAtMs = 0L

    /** Flipped off permanently for the session once the device proves it cannot take a piped source. */
    private var pipeSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override suspend fun start() {
        startedAtMs = System.currentTimeMillis()
    }

    override suspend fun stop() = Unit

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun transcripts(): Flow<TranscriptWindow> =
        callbackFlow {
            if (!SystemAsrTranscriptSource.isAvailable(context)) {
                close(IllegalStateException(SystemAsrTranscriptSource.UNAVAILABLE_MESSAGE))
                return@callbackFlow
            }

            val audioManager = context.getSystemService(AudioManager::class.java)
            diagnostics.onAudioMode(audioManager?.mode ?: 0)

            val mic = audioManager?.let { MicCapture(it, diagnostics) }
            var recognizer: SpeechRecognizer? = null
            var micJob: Job? = null
            var writerJob: Job? = null
            var cycle: Pipe? = null
            var consecutiveFailures = 0
            var everProducedText = false
            var stopped = false

            /** Tears the current recogniser cycle down without touching the microphone. */
            fun endCycle() {
                writerJob?.cancel()
                writerJob = null
                cycle?.close()
                cycle = null
            }

            fun beginCycle() {
                if (stopped) return
                endCycle()

                val intent =
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }

                if (pipeSupported && mic != null) {
                    val pipe = runCatching { Pipe.open() }.getOrNull()
                    if (pipe == null) {
                        pipeSupported = false
                    } else {
                        cycle = pipe
                        // Formats are already the recogniser's defaults; passing them
                        // explicitly means a future change to the ring buffer breaks
                        // loudly here instead of producing quiet garbage.
                        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe.read)
                        intent.putExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                            AudioRingBuffer.SAMPLE_RATE_HZ,
                        )
                        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        intent.putExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        writerJob = launch(Dispatchers.IO) { pipe.drain(mic.frames()) }
                    }
                }

                if (!pipeSupported) diagnostics.onOwner(MicOwner.RECOGNISER)
                main.post {
                    if (!stopped) {
                        runCatching { recognizer?.startListening(intent) }
                            .onFailure { Log.w(TAG, "startListening failed", it) }
                    }
                }
            }

            /**
             * A device that cannot take a piped source usually says so instantly and
             * repeatedly. Latch to the degraded path rather than spin.
             */
            fun noteFailure() {
                if (everProducedText || !pipeSupported) return
                consecutiveFailures++
                if (consecutiveFailures >= MAX_PIPE_FAILURES) {
                    Log.w(TAG, "recogniser rejected EXTRA_AUDIO_SOURCE $consecutiveFailures times; degrading")
                    pipeSupported = false
                    micJob?.cancel()
                    micJob = null
                    diagnostics.onCaptureError(PIPE_UNSUPPORTED_MESSAGE)
                }
            }

            val listener =
                object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        emit(results, partial = false)
                        beginCycle()
                    }

                    override fun onPartialResults(partialResults: Bundle?) = emit(partialResults, partial = true)

                    override fun onError(error: Int) {
                        // NO_MATCH and SPEECH_TIMEOUT are normal in a quiet room —
                        // they are not evidence that the piped source was refused.
                        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ) {
                            beginCycle()
                            return
                        }
                        Log.w(TAG, "recogniser error $error")
                        noteFailure()
                        main.postDelayed({ if (!stopped) beginCycle() }, ERROR_BACKOFF_MS)
                    }

                    override fun onEndOfSpeech() = Unit

                    override fun onReadyForSpeech(params: Bundle?) {
                        consecutiveFailures = 0
                    }

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
                        everProducedText = true
                        consecutiveFailures = 0
                        diagnostics.onTranscript()
                        val now = System.currentTimeMillis() - startedAtMs
                        trySend(TranscriptWindow(text, now - WINDOW_MS, now, isPartial = partial))
                    }
                }

            if (pipeSupported && mic != null) {
                micJob =
                    launch(Dispatchers.IO) {
                        try {
                            mic.run()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IllegalStateException) {
                            // The microphone itself is gone. That is fatal to the session
                            // and the user is told, rather than shown a calm empty screen.
                            close(e)
                        }
                    }
            } else {
                diagnostics.onOwner(MicOwner.RECOGNISER)
            }

            main.post {
                runCatching {
                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    recognizer?.setRecognitionListener(listener)
                }.onFailure { close(it) }
                    .onSuccess { beginCycle() }
            }

            awaitClose {
                stopped = true
                micJob?.cancel()
                endCycle()
                main.post {
                    runCatching {
                        recognizer?.stopListening()
                        recognizer?.destroy()
                    }
                    recognizer = null
                }
            }
        }

    /**
     * One recogniser session's worth of pipe.
     *
     * The read end is handed to the recogniser and deliberately kept open on our
     * side until teardown: closing it during the binder handover is a race, and
     * holding it does not stop the recogniser seeing EOF, which is signalled by
     * the write end closing.
     */
    private class Pipe(
        val read: ParcelFileDescriptor,
        private val write: ParcelFileDescriptor,
    ) {
        private val out = ParcelFileDescriptor.AutoCloseOutputStream(write)

        /**
         * Copies microphone frames into the pipe.
         *
         * Runs on its own coroutine because a full pipe blocks the writer, and
         * the one thing that must never block is the microphone thread. The
         * DROP_OLDEST channel upstream absorbs that: a stalled recogniser costs
         * audio quality, never memory and never the mic.
         */
        suspend fun drain(frames: Flow<ShortArray>) {
            try {
                frames.collect { frame -> out.write(frame.toLittleEndianBytes()) }
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
        private const val MAX_PIPE_FAILURES = 3

        const val DEFAULT_LANGUAGE = "en-IN"

        const val PIPE_UNSUPPORTED_MESSAGE =
            "This device's recogniser will not read our microphone. In-call listening is unavailable."
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
