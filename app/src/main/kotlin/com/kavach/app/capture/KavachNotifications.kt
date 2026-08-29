package com.kavach.app.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Notification channel ids and setup, shared by the two independent posters:
 * [KavachService] (listening status and end-of-call verdict) and the
 * accessibility service (the full-screen raise fallback). Channel creation is
 * idempotent, so both may call [ensureChannels] in any order.
 */
object KavachNotifications {
    const val CHANNEL_STATUS = "kavach_status_v2"
    const val CHANNEL_ALERT = "kavach_alerts_v2"

    const val ONGOING_ID = 1001
    const val VERDICT_ID = 1002
    const val RAISE_ID = 1003

    /**
     * Channel settings are immutable once created, so the old single channel is
     * deleted rather than edited: without this an upgrade would silently keep
     * IMPORTANCE_LOW and the warning would stay mute on the demo device.
     */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(com.kavach.app.R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(com.kavach.app.R.string.notification_channel_description)
                setShowBadge(false)
                setSound(null, null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                context.getString(com.kavach.app.R.string.notification_channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(com.kavach.app.R.string.notification_channel_alert_description)
                // Vibration is deliberately NOT set on the channel. KavachService
                // already buzzes explicitly on every band change, so a channel
                // pattern meant HIGH_RISK fired both and landed as a four-pulse
                // rattle — the siren docs/PRD.md 5 rules out. The explicit path
                // is the one that is testable and the one that survives an OEM
                // ignoring channel vibration, so the channel defers to it.
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            },
        )
    }
}
