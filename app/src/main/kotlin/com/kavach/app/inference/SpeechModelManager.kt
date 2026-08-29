package com.kavach.app.inference

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages on-device speech recognition models for offline transcription.
 *
 * Modern Android (API 33+) deprecated user-managed offline settings menus and
 * moved to dynamic API-driven model management via [SpeechRecognizer.checkRecognitionSupport]
 * and [SpeechRecognizer.triggerModelDownload].
 */
class SpeechModelManager(
    private val context: Context,
) {
    private val _status = MutableStateFlow(SpeechModelStatus())
    val status: StateFlow<SpeechModelStatus> = _status.asStateFlow()

    /**
     * Checks installed and supported on-device languages.
     */
    fun checkSupport(onComplete: ((SpeechModelStatus) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            _status.update {
                it.copy(
                    isSupported = SystemAsrTranscriptSource.isAvailable(context),
                    checked = true,
                )
            }
            onComplete?.invoke(_status.value)
            return
        }

        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        if (recognizer == null) {
            _status.update { it.copy(isSupported = false, checked = true) }
            onComplete?.invoke(_status.value)
            return
        }

        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }

        val executor = ContextCompat.getMainExecutor(context)
        recognizer.checkRecognitionSupport(
            intent,
            executor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    val installed = recognitionSupport.installedOnDeviceLanguages.toList()
                    val supported = recognitionSupport.supportedOnDeviceLanguages.toList()
                    val online = recognitionSupport.onlineLanguages.toList()

                    Log.i(TAG, "ASR Support: installed=$installed, supported=$supported, online=$online")

                    _status.update {
                        it.copy(
                            isSupported = true,
                            installedLanguages = installed,
                            supportedLanguages = supported,
                            checked = true,
                        )
                    }
                    recognizer.destroy()
                    onComplete?.invoke(_status.value)
                }

                override fun onError(errorCode: Int) {
                    Log.w(TAG, "checkRecognitionSupport failed with error: $errorCode")
                    _status.update {
                        it.copy(
                            isSupported = SystemAsrTranscriptSource.isAvailable(context),
                            checked = true,
                            lastError = "Support check error: $errorCode",
                        )
                    }
                    recognizer.destroy()
                    onComplete?.invoke(_status.value)
                }
            },
        )
    }

    /**
     * Triggers downloading offline speech recognition models for the target languages.
     */
    fun triggerDownload(
        languages: List<String> = TARGET_LANGUAGES,
        onProgress: ((String, Int) -> Unit)? = null,
        onSuccess: ((String) -> Unit)? = null,
        onError: ((String, Int) -> Unit)? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.w(TAG, "triggerModelDownload requires Android 14+ (API 34)")
            return
        }

        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        if (recognizer == null) {
            Log.w(TAG, "No on-device SpeechRecognizer available to download models")
            return
        }

        val executor = ContextCompat.getMainExecutor(context)

        for (lang in languages) {
            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                }

            _status.update { current ->
                current.copy(downloadingLanguages = current.downloadingLanguages + lang)
            }

            Log.i(TAG, "Triggering model download for: $lang")
            recognizer.triggerModelDownload(
                intent,
                executor,
                object : ModelDownloadListener {
                    override fun onProgress(progress: Int) {
                        Log.d(TAG, "[$lang] model download progress: $progress%")
                        onProgress?.invoke(lang, progress)
                    }

                    override fun onSuccess() {
                        Log.i(TAG, "[$lang] model downloaded successfully")
                        _status.update { current ->
                            current.copy(
                                downloadingLanguages = current.downloadingLanguages - lang,
                                installedLanguages = (current.installedLanguages + lang).distinct(),
                            )
                        }
                        onSuccess?.invoke(lang)
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "[$lang] model download failed with error $error")
                        _status.update { current ->
                            current.copy(
                                downloadingLanguages = current.downloadingLanguages - lang,
                                lastError = "Download error for $lang: $error",
                            )
                        }
                        onError?.invoke(lang, error)
                    }

                    override fun onScheduled() {
                        Log.i(TAG, "[$lang] model download scheduled")
                    }
                },
            )
        }
    }

    companion object {
        private const val TAG = "SpeechModelManager"

        /** Primary Indian languages for scam detection. */
        val TARGET_LANGUAGES = listOf("en-IN", "hi-IN", "en-US")
    }
}

data class SpeechModelStatus(
    val checked: Boolean = false,
    val isSupported: Boolean = false,
    val installedLanguages: List<String> = emptyList(),
    val supportedLanguages: List<String> = emptyList(),
    val downloadingLanguages: Set<String> = emptySet(),
    val lastError: String? = null,
) {
    val hasHindi: Boolean
        get() = installedLanguages.any { it.startsWith("hi", ignoreCase = true) }

    val hasIndianEnglish: Boolean
        get() =
            installedLanguages.any {
                it.equals("en-IN", ignoreCase = true) || it.startsWith("en", ignoreCase = true)
            }

    val isDownloading: Boolean
        get() = downloadingLanguages.isNotEmpty()
}
