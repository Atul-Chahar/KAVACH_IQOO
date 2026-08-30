package com.kavach.app.message

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kavach.app.KavachApplication

/**
 * The two answers a message warning can be given, both reachable from the lock
 * screen so the user never has to open Kavach to resolve one.
 *
 * "Report" is [Intent.ACTION_DIAL] and never `ACTION_CALL`: the cybercrime
 * number is pre-filled and the user presses the green button themselves. Kavach
 * places no call on anyone's behalf (CLAUDE.md hard rule 5).
 */
object MessageGuardActions {
    const val ACTION_TRUST = "com.kavach.app.action.MESSAGE_TRUST"
    const val EXTRA_DETECTION_ID = "detection_id"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    private const val CYBERCRIME_NUMBER = "tel:1930"

    fun dial(
        context: Context,
        detection: MessageDetection,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(detection.notificationId, DIAL_OFFSET),
            Intent(Intent.ACTION_DIAL, Uri.parse(CYBERCRIME_NUMBER)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun trust(
        context: Context,
        detection: MessageDetection,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(detection.notificationId, TRUST_OFFSET),
            Intent(context, Receiver::class.java).apply {
                action = ACTION_TRUST
                setPackage(context.packageName)
                putExtra(EXTRA_DETECTION_ID, detection.id)
                putExtra(EXTRA_NOTIFICATION_ID, detection.notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Distinct per notification and per action, so no PendingIntent overwrites another. */
    private fun requestCode(
        notificationId: Int,
        offset: Int,
    ) = notificationId * ACTIONS_PER_NOTIFICATION + offset

    private const val ACTIONS_PER_NOTIFICATION = 4
    private const val DIAL_OFFSET = 1
    private const val TRUST_OFFSET = 2

    /**
     * Handles "this is fine". Deliberately trivial: drop the finding, silence
     * that conversation for the session, take the notification down. No
     * analysis, no disk, nothing that could keep a broadcast running long.
     */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            if (intent.action != ACTION_TRUST) return
            val app = context.applicationContext as? KavachApplication ?: return
            val id = intent.getStringExtra(EXTRA_DETECTION_ID) ?: return
            app.messageGuard.trust(id)

            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            if (notificationId >= 0) runCatching { manager.cancel(notificationId) }
        }
    }
}
