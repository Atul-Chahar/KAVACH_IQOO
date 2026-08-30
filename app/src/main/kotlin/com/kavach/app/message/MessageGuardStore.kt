package com.kavach.app.message

import com.kavach.domain.SmsMessageAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MessageDetection(
    val id: String,
    val detectedAtMillis: Long,
    /**
     * The conversation the notification came from, as the messaging app already
     * displayed it — a contact name or a sender id. Never message text.
     *
     * It is here because a warning that cannot say *which* message it is about
     * is not actionable from the lock screen, and because "this sender is fine"
     * needs something to remember.
     */
    val conversation: String?,
    val result: SmsMessageAnalyzer.Result,
    /** The system notification slot this detection owns, so warnings coexist. */
    val notificationId: Int,
)

/**
 * Bounded, process-memory-only message findings. Raw notification text is never
 * retained — only the conversation label, the time, and the warning categories.
 *
 * Every method is [Synchronized] because the notification listener inspects on a
 * background dispatcher while the UI collects on the main thread.
 */
class MessageGuardStore(
    private val analyzer: SmsMessageAnalyzer,
) {
    private val fingerprints = LinkedHashSet<String>()
    private val trusted = LinkedHashSet<String>()
    private var nextSlot = 0

    private val mutableDetections = MutableStateFlow<List<MessageDetection>>(emptyList())

    /**
     * Warnings only.
     *
     * Clear messages are deliberately not kept. They were, and it made the list
     * a log of every message the phone received: twelve slots filled by ordinary
     * chat inside a minute, evicting the one HIGH_RISK detection the user opened
     * the screen to read. A record of what Kavach *didn't* flag is also a record
     * of who is messaging you, which is not a thing this app should hold.
     */
    val detections: StateFlow<List<MessageDetection>> = mutableDetections.asStateFlow()

    private val mutableUnreviewed = MutableStateFlow(0)

    /** Warnings raised since the user last opened Message Guard. Drives the home badge. */
    val unreviewed: StateFlow<Int> = mutableUnreviewed.asStateFlow()

    private val mutableConnected = MutableStateFlow(false)

    /**
     * Whether the notification listener is actually bound right now.
     *
     * Distinct from "the user granted notification access": Android unbinds
     * listeners on its own and does not always come back. Reporting the grant as
     * if it were the capability is how the screen ends up claiming Message Guard
     * is on while nothing is being inspected — the message-side version of the
     * calm shield over a call we cannot hear.
     */
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    fun setConnected(value: Boolean) {
        mutableConnected.value = value
    }

    /**
     * Analyses [text] and records it if it is a new warning.
     *
     * Returns the detection only when there is something to warn about, so the
     * caller posts exactly one notification per finding. Runs the full matcher,
     * so callers must be off the main thread.
     */
    @Synchronized
    fun inspect(
        sourceKey: String,
        conversation: String?,
        text: String,
        detectedAtMillis: Long = System.currentTimeMillis(),
    ): MessageDetection? {
        if (conversation != null && conversation in trusted) return null

        // Messaging apps repost a conversation's notification on every update,
        // so the same text arrives many times. Fingerprint before analysing:
        // the matcher is the expensive half and a repost cannot change it.
        val fingerprint = "$sourceKey:${text.hashCode()}"
        if (!fingerprints.add(fingerprint)) return null
        while (fingerprints.size > MAX_FINGERPRINTS) fingerprints.remove(fingerprints.first())

        val result = analyzer.analyze(text)
        if (result.severity == SmsMessageAnalyzer.Severity.CLEAR) return null

        val detection =
            MessageDetection(
                id = fingerprint,
                detectedAtMillis = detectedAtMillis,
                conversation = conversation,
                result = result,
                notificationId = FIRST_NOTIFICATION_ID + nextSlot,
            )
        nextSlot = (nextSlot + 1) % NOTIFICATION_SLOTS
        mutableDetections.value = (listOf(detection) + mutableDetections.value).take(MAX_DETECTIONS)
        mutableUnreviewed.value = mutableUnreviewed.value + 1
        return detection
    }

    /** The user opened Message Guard, so the badge has served its purpose. */
    @Synchronized
    fun markReviewed() {
        mutableUnreviewed.value = 0
    }

    /**
     * "This sender is fine." Drops the finding and stops warning about that
     * conversation until Kavach's process ends.
     *
     * Session-scoped on purpose: a permanent allow-list is a thing an attacker
     * who borrows the phone for a minute can poison, and it would have to live
     * on disk to be permanent.
     */
    @Synchronized
    fun trust(id: String): MessageDetection? {
        val detection = mutableDetections.value.firstOrNull { it.id == id } ?: return null
        detection.conversation?.let {
            trusted += it
            while (trusted.size > MAX_TRUSTED) trusted.remove(trusted.first())
        }
        mutableDetections.value = mutableDetections.value.filterNot { it.id == id }
        mutableUnreviewed.value = mutableUnreviewed.value.minus(1).coerceAtLeast(0)
        return detection
    }

    @Synchronized
    fun find(id: String): MessageDetection? = mutableDetections.value.firstOrNull { it.id == id }

    private companion object {
        const val MAX_FINGERPRINTS = 40
        const val MAX_DETECTIONS = 12
        const val MAX_TRUSTED = 20

        /**
         * Warnings rotate through their own id block instead of sharing one.
         *
         * A single fixed id meant the second scam message silently replaced the
         * first, so a burst — which is how these arrive — showed as one warning.
         * The block is small and wraps: twelve stale warnings on the shade is
         * worse than losing the thirteenth.
         */
        const val FIRST_NOTIFICATION_ID = 1100
        const val NOTIFICATION_SLOTS = 8
    }
}
