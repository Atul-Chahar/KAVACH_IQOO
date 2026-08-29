package com.kavach.app.message

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.kavach.app.KavachApplication
import com.kavach.app.MainActivity
import com.kavach.app.R
import com.kavach.app.capture.KavachNotifications
import com.kavach.domain.SmsMessageAnalyzer

class MessageNotificationListener : NotificationListenerService() {
    private val app get() = application as KavachApplication

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in SUPPORTED_MESSAGE_PACKAGES || sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val text = newestMessageText(sbn.notification) ?: return
        val detection = app.messageGuard.inspect(sbn.key, text) ?: return
        if (detection.result.severity != SmsMessageAnalyzer.Severity.CLEAR) postWarning(detection.result)
    }

    private fun newestMessageText(notification: Notification): String? {
        val extras = notification.extras ?: return null
        val messagingText =
            runCatching {
                Notification.MessagingStyle.Message
                    .getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
                    .lastOrNull { !it.text.isNullOrBlank() }
                    ?.text
            }.getOrNull()
        return sequenceOf(
            messagingText,
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull { !it.isNullOrBlank() },
        ).filterNotNull().map { it.toString().trim() }.firstOrNull { it.isNotEmpty() }
    }

    private fun postWarning(result: SmsMessageAnalyzer.Result) {
        val highRisk = result.severity == SmsMessageAnalyzer.Severity.HIGH_RISK
        val title = getString(if (highRisk) R.string.message_warning_high else R.string.message_warning_caution)
        val text = getString(R.string.message_warning_body)
        val open =
            PendingIntent.getActivity(
                this,
                KavachNotifications.MESSAGE_WARNING_ID,
                Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_MESSAGE_GUARD
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(this, KavachNotifications.CHANNEL_MESSAGE_GUARD)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(if (highRisk) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build()
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(
                KavachNotifications.MESSAGE_WARNING_ID,
                notification,
            )
        }
    }

    private companion object {
        val SUPPORTED_MESSAGE_PACKAGES = setOf("com.google.android.apps.messaging")
    }
}
