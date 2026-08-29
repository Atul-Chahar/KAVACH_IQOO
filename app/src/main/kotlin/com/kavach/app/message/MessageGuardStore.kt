package com.kavach.app.message

import com.kavach.domain.SmsMessageAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MessageDetection(
    val id: String,
    val detectedAtMillis: Long,
    val result: SmsMessageAnalyzer.Result,
)

/** Bounded, process-memory-only message findings. Raw notification text is never retained. */
class MessageGuardStore(
    private val analyzer: SmsMessageAnalyzer,
) {
    private val fingerprints = LinkedHashSet<String>()
    private val mutableDetections = MutableStateFlow<List<MessageDetection>>(emptyList())
    val detections: StateFlow<List<MessageDetection>> = mutableDetections.asStateFlow()

    @Synchronized
    fun inspect(
        sourceKey: String,
        text: String,
        detectedAtMillis: Long = System.currentTimeMillis(),
    ): MessageDetection? {
        val fingerprint = "$sourceKey:${text.hashCode()}"
        if (!fingerprints.add(fingerprint)) return null
        while (fingerprints.size > MAX_FINGERPRINTS) fingerprints.remove(fingerprints.first())

        val detection = MessageDetection(fingerprint, detectedAtMillis, analyzer.analyze(text))
        mutableDetections.value = (listOf(detection) + mutableDetections.value).take(MAX_DETECTIONS)
        return detection
    }

    private companion object {
        const val MAX_FINGERPRINTS = 40
        const val MAX_DETECTIONS = 12
    }
}
