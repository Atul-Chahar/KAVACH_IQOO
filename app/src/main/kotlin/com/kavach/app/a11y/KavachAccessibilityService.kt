package com.kavach.app.a11y

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.kavach.app.KavachApplication
import com.kavach.app.R
import com.kavach.app.capture.CallWatcher
import com.kavach.app.capture.KavachNotifications
import com.kavach.app.ui.ShieldOverlayActivity

/**
 * The narrowest accessibility service we could write, and the reason it exists.
 *
 * **It reads nothing.** [onAccessibilityEvent] is empty, `canRetrieveWindowContent`
 * is false, and no window, view, text or key event is ever inspected. If this
 * class ever grows a line that looks at another app's screen, that is a bug and a
 * broken promise — see docs/SAFETY.md.
 *
 * It is registered for one reason. Android's audio policy silences every ordinary
 * app's microphone during a call, and the single exemption a non-privileged app
 * can reach is documented on developer.android.com: an accessibility service
 * whose UI is on top continues to receive audio input. Registering here places
 * Kavach's UID on the list the audio policy consults. Nothing else about the
 * accessibility API is used.
 *
 * The service is also the only part of Kavach that is alive before a call starts,
 * so it carries the [CallWatcher] — one integer of audio state, no notifications
 * read, no numbers read. When a call begins it brings the shield on screen, which
 * is what satisfies the "UI on top" half of the same exemption.
 */
class KavachAccessibilityService : AccessibilityService() {
    private var watcher: CallWatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected; Kavach UID is now on the accessibility list")

        val app = application as? KavachApplication ?: return
        watcher =
            CallWatcher(
                context = this,
                diagnostics = app.diagnostics,
                onCallStarted = ::onCallStarted,
                onCallEnded = ::onCallEnded,
            ).also { it.start() }
    }

    /**
     * Deliberately empty, and it must stay that way.
     *
     * The manifest asks for the minimum event type Android will accept for a
     * registered service. We discard every one of them.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        watcher?.stop()
        watcher = null
        return super.onUnbind(intent)
    }

    /**
     * A call started (or is ringing). Bring the shield up.
     *
     * Starting an activity from the background is normally forbidden, but holding
     * `SYSTEM_ALERT_WINDOW` is a documented exemption — the same one Truecaller-style
     * caller cards rely on. Once the activity is visible Kavach is a foreground app,
     * which legalises the microphone foreground service *and* satisfies the
     * accessibility exemption's "UI on top" condition. The two constraints solve
     * each other; neither is worked around.
     *
     * [CallWatcher] fires this at RINGING *and* again at IN_CALL: the in-call UI
     * takes the foreground when the user answers and covers the shield, so the
     * second callback re-raises it. [ShieldOverlayActivity] is singleTask, so a
     * re-launch delivers onNewIntent instead of stacking a second instance.
     */
    private fun onCallStarted() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "call started but overlay permission is missing; cannot raise the shield")
            notifyRaiseBlocked()
            return
        }
        runCatching { ShieldOverlayActivity.launch(this) }
            .onFailure { Log.w(TAG, "could not raise the shield", it) }
    }

    private fun onCallEnded() {
        ShieldOverlayActivity.dismiss(this)
    }

    /**
     * The one case where auto-activation is impossible and the user would never
     * know: no overlay grant, so the shield cannot legally appear and the call
     * proceeds unmonitored. A silent no would be the worst outcome — the user
     * believes they are protected. One heads-up notification, tap to fix.
     */
    private fun notifyRaiseBlocked() {
        KavachNotifications.ensureChannels(this)
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(this, KavachNotifications.CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.raise_blocked_title))
                .setContentText(getString(R.string.raise_blocked_body))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.raise_blocked_body)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        getSystemService(NotificationManager::class.java)
            ?.notify(KavachNotifications.RAISE_ID, notification)
    }

    companion object {
        private const val TAG = "KavachA11y"

        /** Whether the user has switched us on in Settings. Nothing works in-call until they have. */
        fun isEnabled(context: Context): Boolean {
            val enabled =
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ) ?: return false
            val id = "${context.packageName}/${KavachAccessibilityService::class.java.name}"
            val shortId = "${context.packageName}/.a11y.KavachAccessibilityService"
            return enabled.split(':').any { it.equals(id, true) || it.equals(shortId, true) }
        }
    }
}
