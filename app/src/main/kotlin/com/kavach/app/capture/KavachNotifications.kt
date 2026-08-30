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
    const val CHANNEL_STATUS = "kavach_status_v3"
    const val CHANNEL_ALERT = "kavach_alerts_v3"
    const val CHANNEL_MESSAGE_GUARD = "kavach_message_guard_v2"

    /**
     * The same warning, posted quietly because the capsule is saying it instead.
     *
     * A second channel and not `setSilent(true)`, because heads-up is decided by
     * channel importance and nothing else. Verified on the iQOO test unit: with
     * `setSilent(true)` on the HIGH channel the heads-up still drew over the
     * capsule, so the user was warned twice and could read it once. On a LOW
     * channel the warning goes straight to the shade — still there, still on the
     * lock screen, just not shouting over the surface that is already shouting.
     */
    const val CHANNEL_MESSAGE_GUARD_QUIET = "kavach_message_guard_quiet_v1"

    /** Groups the message warnings under one summary so a burst collapses tidily. */
    const val GROUP_MESSAGE_GUARD = "kavach_message_guard"

    /**
     * Channel ids this app has used before, deleted on every start.
     *
     * A channel is immutable once the system has it: `createNotificationChannel`
     * on an existing id updates the name and description and silently ignores
     * importance, sound and vibration. Verified on the device — after switching
     * the alert channel to `enableVibration(false)`, dumpsys still reported
     * `mVibrationPattern=[0, 220, 160, 220]`, so HIGH_RISK kept buzzing twice on
     * every phone that had an earlier build installed. A fresh install would
     * have behaved correctly and the demo phone would not have, which is the
     * worst version of this bug.
     *
     * So changing a channel's behaviour means retiring its id. Anything listed
     * here is removed before the current pair is created.
     */
    private val RETIRED =
        listOf("kavach_monitoring", "kavach_status_v2", "kavach_alerts_v2", "kavach_message_guard_v1")

    const val ONGOING_ID = 1001
    const val VERDICT_ID = 1002
    const val RAISE_ID = 1003

    /**
     * The summary a burst of message warnings collapses into. The warnings
     * themselves own ids from 1100 up — see MessageGuardStore.
     */
    const val MESSAGE_SUMMARY_ID = 1004

    /**
     * Channel settings are immutable once created, so the old single channel is
     * deleted rather than edited: without this an upgrade would silently keep
     * IMPORTANCE_LOW and the warning would stay mute on the demo device.
     */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        RETIRED.forEach { runCatching { manager.deleteNotificationChannel(it) } }
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
                // Requires Notification Policy Access, which Kavach does not ask
                // for; the device reports mBypassDnd=false and this call is a
                // no-op. Kept because it costs nothing and starts working if the
                // user ever grants it. The alert does not depend on it: the
                // full-screen intent and CATEGORY_CALL are what carry a warning
                // through, and those need no extra grant.
                setBypassDnd(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGE_GUARD_QUIET,
                context.getString(com.kavach.app.R.string.message_channel_quiet_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(com.kavach.app.R.string.message_channel_quiet_description)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGE_GUARD,
                context.getString(com.kavach.app.R.string.message_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(com.kavach.app.R.string.message_channel_description)
                enableVibration(true)
                // PUBLIC, and it has to be. This notification is built to carry
                // no message text at all — only the conversation label the
                // messaging app is already showing, and Kavach's own words for
                // why it is worried. PRIVATE redacted exactly that, so the lock
                // screen showed "Contents hidden" and the user had to unlock and
                // open the app to learn anything, which is the failure this
                // whole surface exists to prevent. Nothing private is disclosed
                // by saying it out loud, because nothing private is in it.
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }
}
