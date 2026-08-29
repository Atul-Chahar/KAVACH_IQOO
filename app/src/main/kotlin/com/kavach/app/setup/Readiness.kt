package com.kavach.app.setup

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.kavach.app.a11y.KavachAccessibilityService

/**
 * One rung of the permission ladder.
 *
 * Kavach is deliberately usable at every rung and says which one it is on, so a
 * half-granted install degrades loudly instead of appearing to work. [blocks]
 * names, in the user's terms, what is lost by leaving this rung ungranted —
 * that sentence is the whole argument for granting it.
 */
data class Rung(
    val id: String,
    val title: String,
    val why: String,
    val blocks: String,
    val granted: Boolean,
    val settingsIntent: Intent?,
)

/**
 * What Kavach can actually do on this device right now.
 *
 * Read fresh on every resume rather than cached: the user leaves for Settings
 * and comes back, and a stale checklist is worse than none.
 */
object Readiness {
    const val TIER_NOTHING = "Not ready"
    const val TIER_MANUAL = "Manual sessions only"
    const val TIER_AUTOMATIC = "Starts on its own, but not during calls"
    const val TIER_IN_CALL = "Full — listens during calls"

    fun rungs(context: Context): List<Rung> =
        listOf(
            Rung(
                id = "mic",
                title = "Microphone",
                why = "So Kavach can hear the conversation. Audio is never recorded or saved.",
                blocks = "Kavach cannot do anything at all.",
                granted = hasPermission(context, Manifest.permission.RECORD_AUDIO),
                settingsIntent = appSettings(context),
            ),
            Rung(
                id = "notifications",
                title = "Notifications",
                why = "So Kavach can warn you, and so you always know when it is listening.",
                blocks = "You will not see warnings when the screen is off.",
                granted = hasNotificationPermission(context),
                settingsIntent = appSettings(context),
            ),
            Rung(
                id = "overlay",
                title = "Display over other apps",
                why = "So the warning appears on top of your call, and so Kavach can raise itself when a call starts.",
                blocks = "Kavach cannot start by itself and cannot show a warning during a call.",
                granted = Settings.canDrawOverlays(context),
                settingsIntent =
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
            ),
            Rung(
                id = "a11y",
                title = "Accessibility service",
                why =
                    "Android mutes every ordinary app's microphone during a call. An accessibility service " +
                        "is the one documented exception. Kavach's reads nothing — no screen, no text, no keys.",
                blocks = "Kavach hears silence during calls. It will tell you so rather than pretend.",
                granted = KavachAccessibilityService.isEnabled(context),
                settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            ),
            Rung(
                id = "fullscreen",
                title = "Full-screen alerts",
                why = "So a warning can reach you on the lock screen.",
                blocks = "Warnings appear as a banner instead of taking over the screen.",
                granted = canUseFullScreenIntent(context),
                settingsIntent = fullScreenIntentSettings(context),
            ),
        )

    /** The one honest sentence about what this install can do. Shown on the home screen. */
    fun tier(context: Context): String {
        val granted = rungs(context).filter { it.granted }.map { it.id }.toSet()
        return when {
            "mic" !in granted -> TIER_NOTHING
            "overlay" !in granted -> TIER_MANUAL
            "a11y" !in granted -> TIER_AUTOMATIC
            else -> TIER_IN_CALL
        }
    }

    /**
     * Android 13+ greys out the accessibility toggle for apps installed outside
     * an app store, and does not say why on the toggle itself. The user must
     * unblock it once. Surfacing this is the difference between a two-minute
     * setup and twenty minutes of confusion.
     */
    fun needsRestrictedSettingsHint(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !KavachAccessibilityService.isEnabled(context)

    private fun hasPermission(
        context: Context,
        permission: String,
    ): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)

    private fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false
    }

    private fun fullScreenIntentSettings(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            null
        }

    private fun appSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
}
