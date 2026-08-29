package com.kavach.app.inference

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.kavach.domain.TranscriptSource
import com.kavach.domain.TranscriptWindow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Transcription through Android's **on-device** recogniser.
 *
 * This is the fallback rung, written before the risky path (CLAUDE.md working
 * style). It needs no model file, no NPU delegate and no network — and, being
 * on-device, it keeps working with the phone in airplane mode, which is the
 * claim we make out loud in the pitch.
 *
 * Deliberately NOT the networked recogniser: the app has no INTERNET permission,
 * so a networked fallback would fail confusingly rather than degrade honestly.
 * If on-device recognition is unavailable we say so and offer DemoMode.
 *
 * `SpeechRecognizer` is main-thread-only, hence the [Handler] hop on every call.
 */
class SystemAsrTranscriptSource(
    private val context: Context,
    private val languageTag: String = DEFAULT_LANGUAGE,
) : TranscriptSource {
    override val engineName: String = "System on-device ASR ($languageTag)"

    private val main = Handler(Looper.getMainLooper())
    private var startedAtMs = 0L

    override suspend fun start() {
        startedAtMs = System.currentTimeMillis()
    }

    override suspend fun stop() = Unit

    override fun transcripts(): Flow<TranscriptWindow> =
        callbackFlow {
            if (!isAvailable(context)) {
                close(IllegalStateException(UNAVAILABLE_MESSAGE))
                return@callbackFlow
            }

            var recognizer: SpeechRecognizer? = null
            var stopped = false

            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }

            val listener =
                object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        emit(results, partial = false)
                        restart()
                    }

                    override fun onPartialResults(partialResults: Bundle?) = emit(partialResults, partial = true)

                    override fun onError(error: Int) {
                        // NO_MATCH and SPEECH_TIMEOUT are normal in a quiet room: restart
                        // and keep listening rather than treating silence as a failure.
                        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ) {
                            restart()
                            return
                        }
                        Log.w(TAG, "recogniser error $error")
                        restart(delayMs = ERROR_BACKOFF_MS)
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
                        val now = System.currentTimeMillis() - startedAtMs
                        trySend(TranscriptWindow(text, now - WINDOW_MS, now, isPartial = partial))
                    }

                    private fun restart(delayMs: Long = RESTART_DELAY_MS) {
                        if (stopped) return
                        // Continuous recognition is not a first-class mode on Android:
                        // the recogniser stops at every utterance boundary, so we drive
                        // it round again ourselves.
                        main.postDelayed({
                            if (!stopped) {
                                runCatching { recognizer?.startListening(intent) }
                                    .onFailure { Log.w(TAG, "restart failed", it) }
                            }
                        }, delayMs)
                    }
                }

            main.post {
                runCatching {
                    recognizer =
                        createRecognizer(context).apply {
                            setRecognitionListener(listener)
                            startListening(intent)
                        }
                }.onFailure { close(it) }
            }

            awaitClose {
                stopped = true
                main.post {
                    runCatching {
                        recognizer?.stopListening()
                        recognizer?.destroy()
                    }
                    recognizer = null
                }
            }
        }

    private fun createRecognizer(context: Context): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            error(UNAVAILABLE_MESSAGE)
        }

    companion object {
        private const val TAG = "KavachAsr"
        private const val RESTART_DELAY_MS = 150L
        private const val ERROR_BACKOFF_MS = 1_000L
        private const val WINDOW_MS = 8_000L

        /**
         * en-IN emits Latin script, which is what the lexicon's romanised
         * Hinglish markers are written in. hi-IN emits Devanagari, which the
         * lexicon also carries — switch here and re-run the corpus to compare.
         */
        const val DEFAULT_LANGUAGE = "en-IN"

        const val UNAVAILABLE_MESSAGE =
            "On-device speech recognition is not available on this device."

        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
}
