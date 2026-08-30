package com.kavach.app.message

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat
import com.kavach.app.KavachApplication
import com.kavach.app.MainActivity
import com.kavach.app.R
import com.kavach.app.capture.KavachNotifications
import com.kavach.app.ui.MessageIslandOverlay
import com.kavach.domain.SmsMessageAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads incoming message notifications, scores them on this device, and warns
 * on the lock screen without the user opening anything.
 *
 * ## Why nothing heavy happens in [onNotificationPosted]
 *
 * Android delivers listener callbacks on the process's **main thread**. The
 * original version ran the whole Tier-1 matcher there — 220 markers across up
 * to ten thousand characters — and, on the very first message, also triggered
 * the lazy load that parses the lexicon asset and builds the matcher. That is a
 * UI stall on every message the phone receives, and the worst case is during a
 * call: [com.kavach.app.ui.ShieldOverlayActivity] shares this main thread, and
 * being visible is the condition the audio policy requires before it will let
 * us hear anything. Blocking it is how the shield freezes and the microphone
 * goes deaf.
 *
 * So the callback does exactly two cheap things — filter by package, and copy
 * the strings out of the notification bundle — and hands the rest to
 * [scope]. The copy has to happen here: the platform is free to recycle the
 * [StatusBarNotification] once the callback returns.
 */
class MessageNotificationListener : NotificationListenerService() {
    private val app get() = application as KavachApplication

    /**
     * Analysis runs here, never on the caller's thread. [Dispatchers.Default]
     * rather than IO: this is CPU work, and the single-thread-at-a-time
     * behaviour of the store's lock keeps it from piling up under a burst.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        super.onListenerConnected()
        app.messageGuard.setConnected(true)
        // Pay for the lexicon parse and the matcher build now, off the main
        // thread, so the first real message is not the one that waits for them.
        scope.launch { runCatching { app.messageGuard } }
    }

    /**
     * Android unbinds notification listeners on its own — on low memory, on app
     * update, on OEM cleanup — and does not reliably come back. Without the
     * rebind request Message Guard stops working silently while the app still
     * reports notification access as granted, which is a false all-clear.
     */
    override fun onListenerDisconnected() {
        app.messageGuard.setConnected(false)
        runCatching { requestRebind(ComponentName(this, MessageNotificationListener::class.java)) }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        app.messageGuard.setConnected(false)
        // The capsule is our window; it must not outlive the thing that raised it.
        runCatching { MessageIslandOverlay.dismiss(this) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in messagingPackages()) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extracted = extract(sbn) ?: return
        scope.launch { inspect(extracted) }
    }

    /**
     * One finding, one alert.
     *
     * The capsule and a heads-up notification are two ways of saying the same
     * sentence, and on device they arrived together — the heads-up drew over
     * the capsule, so the user got the warning twice and could see it once.
     * Whichever surface is going to speak, the other stays quiet: when the
     * capsule can be raised it does the alerting and the notification is posted
     * silently, still in the shade and still on the lock screen for later.
     */
    private suspend fun inspect(extracted: Extracted) {
        val detection =
            runCatching {
                app.messageGuard.inspect(extracted.sourceKey, extracted.conversation, extracted.text)
            }.getOrNull() ?: return
        val island = detection.result.severity == SmsMessageAnalyzer.Severity.HIGH_RISK && canRaiseIsland()
        postWarning(detection, silent = island)
        if (island) raiseIsland(detection)
    }

    /** Windows are added on the main thread; analysis is not. */
    private suspend fun raiseIsland(detection: MessageDetection) {
        withContext(Dispatchers.Main) {
            runCatching { MessageIslandOverlay.show(this@MessageNotificationListener, detection) }
        }
    }

    /** What we keep from a notification: never the body, once analysis is done. */
    private data class Extracted(
        val sourceKey: String,
        val conversation: String?,
        val text: String,
    )

    private fun extract(sbn: StatusBarNotification): Extracted? {
        val extras = sbn.notification.extras ?: return null
        val text = newestMessageText(extras) ?: return null
        val conversation =
            sequenceOf(
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
                extras.getCharSequence(Notification.EXTRA_TITLE),
            ).filterNotNull().map { it.toString().trim() }.firstOrNull { it.isNotEmpty() }
        return Extracted(sbn.key, conversation, text)
    }

    private fun newestMessageText(extras: android.os.Bundle): String? {
        val messagingText =
            runCatching {
                Notification.MessagingStyle.Message
                    .getMessagesFromBundleArray(
                        BundleCompat.getParcelableArray(
                            extras,
                            Notification.EXTRA_MESSAGES,
                            android.os.Parcelable::class.java,
                        ),
                    ).lastOrNull { !it.text.isNullOrBlank() }
                    ?.text
            }.getOrNull()
        return sequenceOf(
            messagingText,
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull { !it.isNullOrBlank() },
        ).filterNotNull().map { it.toString().trim() }.firstOrNull { it.isNotEmpty() }
    }

    /**
     * The warning, written so it can be read and acted on from the lock screen
     * and never opened.
     *
     * It names the conversation, says in plain words what Kavach objected to,
     * and offers the only two answers that matter: report it, or say it is
     * fine. What it never contains is the message text — see
     * [KavachNotifications] on why the channel can then be PUBLIC.
     */
    private fun postWarning(
        detection: MessageDetection,
        silent: Boolean,
    ) {
        val highRisk = detection.result.severity == SmsMessageAnalyzer.Severity.HIGH_RISK
        val title = warningTitle(detection.conversation, highRisk)
        val reasons = reasonLines(detection.result)
        val body = (reasons + getString(R.string.message_warning_advice)).joinToString("\n")
        val notification =
            NotificationCompat
                .Builder(this, channelFor(silent))
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(reasons.firstOrNull() ?: getString(R.string.message_warning_advice))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(openGuard(detection))
                .setAutoCancel(true)
                .setWhen(detection.detectedAtMillis)
                .setShowWhen(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setGroup(KavachNotifications.GROUP_MESSAGE_GUARD)
                .setSilent(silent)
                .setPriority(if (highRisk) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .addAction(0, getString(R.string.action_dial_1930), MessageGuardActions.dial(this, detection))
                .addAction(0, getString(R.string.message_action_trust), MessageGuardActions.trust(this, detection))
                .build()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(detection.notificationId, notification)
            manager.notify(KavachNotifications.MESSAGE_SUMMARY_ID, summary(silent))
        }
    }

    private fun warningTitle(
        conversation: String?,
        highRisk: Boolean,
    ): String =
        when {
            conversation != null && highRisk -> getString(R.string.message_warning_high_from, conversation)
            conversation != null -> getString(R.string.message_warning_caution_from, conversation)
            highRisk -> getString(R.string.message_warning_high)
            else -> getString(R.string.message_warning_caution)
        }

    /** The two worst reasons, in Kavach's own words. Ranking lives in the domain. */
    private fun reasonLines(result: SmsMessageAnalyzer.Result): List<String> =
        result.ranked(MAX_REASON_LINES).map { getString(MessageGuardStrings.evidence(it)) }

    /** HIGH when this notification is the alert, LOW when the capsule is. */
    private fun channelFor(silent: Boolean): String =
        if (silent) {
            KavachNotifications.CHANNEL_MESSAGE_GUARD_QUIET
        } else {
            KavachNotifications.CHANNEL_MESSAGE_GUARD
        }

    private fun summary(silent: Boolean): android.app.Notification =
        NotificationCompat
            .Builder(this, channelFor(silent))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.message_summary_title))
            .setContentIntent(openGuard(null))
            .setGroup(KavachNotifications.GROUP_MESSAGE_GUARD)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

    private fun openGuard(detection: MessageDetection?): PendingIntent =
        PendingIntent.getActivity(
            this,
            detection?.notificationId ?: KavachNotifications.MESSAGE_SUMMARY_ID,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_MESSAGE_GUARD
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Whether the capsule is the right surface right now.
     *
     * Only while the screen is on and unlocked. On a locked phone the
     * notification is already better — it is on the lock screen, it keeps its
     * actions, and it does not demand attention by taking the screen.
     * Launching an activity at someone's pocket would be both useless and
     * alarming.
     */
    private fun canRaiseIsland(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        return keyguard?.isKeyguardLocked == false &&
            power?.isInteractive == true &&
            MessageIslandOverlay.canShow(this)
    }

    /**
     * Which apps to read.
     *
     * The static set is the floor, and it is what actually carries the demo:
     * the device's default SMS app is often unset — on the iQOO test unit
     * `sms_default_application` reads null — so runtime resolution alone would
     * watch nothing at all. The resolved default is unioned on top for phones
     * whose stock messaging app is not on the list.
     */
    private fun messagingPackages(): Set<String> {
        val default = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        return if (default.isNullOrEmpty()) KNOWN_MESSAGE_PACKAGES else KNOWN_MESSAGE_PACKAGES + default
    }

    private companion object {
        const val MAX_REASON_LINES = 2

        val KNOWN_MESSAGE_PACKAGES =
            setOf(
                "com.google.android.apps.messaging",
                "com.android.mms",
                "com.android.messaging",
                "com.samsung.android.messaging",
                "com.vivo.messaging",
                "com.bbk.mms",
                "com.miui.smsextra",
                "com.android.mms.service",
            )
    }
}

/** Shared evidence-to-copy mapping, so every message surface says the same thing. */
internal object MessageGuardStrings {
    fun evidence(evidence: SmsMessageAnalyzer.Evidence): Int =
        when (evidence) {
            SmsMessageAnalyzer.Evidence.SUSPICIOUS_LINK -> R.string.sms_evidence_suspicious_link
            SmsMessageAnalyzer.Evidence.LINKED_ACTION -> R.string.sms_evidence_linked_action
            SmsMessageAnalyzer.Evidence.CREDENTIAL_REQUEST -> R.string.sms_evidence_credentials
            SmsMessageAnalyzer.Evidence.PAYMENT_OR_REMOTE_ACCESS -> R.string.sms_evidence_payment
            SmsMessageAnalyzer.Evidence.URGENCY_OR_THREAT -> R.string.sms_evidence_urgency
            SmsMessageAnalyzer.Evidence.IMPERSONATION -> R.string.sms_evidence_impersonation
            SmsMessageAnalyzer.Evidence.SECRECY -> R.string.sms_evidence_secrecy
        }
}
