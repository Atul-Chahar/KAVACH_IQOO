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
 * One thing Kavach either can or cannot do, named as the user would name it.
 *
 * The rung list is a *permission* view — "Accessibility service", "Display over
 * other apps" — which is the vocabulary of the Settings app, not of someone
 * wondering whether they are protected. A capability is the same information
 * asked the other way round: "Starts on its own — needs the accessibility
 * service". [fix] is the rung id that grants it, so a tap can go straight there.
 */
data class Capability(
    val name: String,
    val ready: Boolean,
    val missing: String?,
    val fix: String?,
)

/**
 * What Kavach can actually do on this device right now.
 *
 * Read fresh on every resume rather than cached: the user leaves for Settings
 * and comes back, and a stale checklist is worse than none.
 */
object Readiness {
    const val TIER_NOTHING = "Not ready"
    const val TIER_MANUAL = "Manual sessions only — will not start by itself"
    const val TIER_NO_SHIELD = "Notices calls, but cannot raise the shield"
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
                blocks = "Kavach still notices your calls, but cannot put the warning on top of one.",
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
                blocks =
                    "Kavach will not notice your calls at all, and hears silence during the ones you " +
                        "start by hand. It will tell you so rather than pretend.",
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

    /**
     * The one honest sentence about what this install can do. Shown on the home
     * screen.
     *
     * The ladder is ordered by what each rung actually gates, which is not the
     * order the rungs are listed in. The accessibility service is checked before
     * the overlay because it is the more load-bearing of the two: `CallWatcher`
     * — the only thing in Kavach that notices a call — is constructed inside
     * `KavachAccessibilityService.onServiceConnected`, so with that toggle off
     * nothing observes the audio mode and no call is ever detected.
     *
     * This previously read "Starts on its own, but not during calls" in exactly
     * that state, which was the opposite of the truth: without the accessibility
     * service Kavach starts on its own for no call at all. Telling someone they
     * have automatic protection when they have none is the worst sentence this
     * screen could print, so the ladder now follows the wiring.
     */
    fun tier(context: Context): String {
        val granted = rungs(context).filter { it.granted }.map { it.id }.toSet()
        return when {
            "mic" !in granted -> TIER_NOTHING
            "a11y" !in granted -> TIER_MANUAL
            "overlay" !in granted -> TIER_NO_SHIELD
            else -> TIER_IN_CALL
        }
    }

    /**
     * The home screen's status strip: four things, each either working or not.
     *
     * This exists because [tier] is shown on the setup gate, and the setup gate
     * is dismissed once and then never seen again. After that the home screen
     * said nothing at all about whether the app was actually armed — so a user
     * who never granted accessibility saw the same calm home screen as one who
     * had, and would only discover the difference by being scammed.
     *
     * Deriving from [rungs] rather than re-reading the platform keeps one source
     * of truth for what is granted; this only regroups it around outcomes.
     */
    fun capabilities(context: Context): List<Capability> {
        val granted = rungs(context).filter { it.granted }.map { it.id }.toSet()

        fun cap(
            name: String,
            requires: List<String>,
        ): Capability {
            val absent = requires.firstOrNull { it !in granted }
            return Capability(
                name = name,
                ready = absent == null,
                missing = absent?.let { NEEDS[it] },
                fix = absent,
            )
        }
        return listOf(
            cap("Notices your calls", listOf("a11y")),
            cap("Hears during a call", listOf("mic", "a11y")),
            cap("Warns on top of the call", listOf("overlay")),
            cap("Reaches you on the lock screen", listOf("notifications", "fullscreen")),
        )
    }

    /** What the user must turn on, phrased as the Settings screen phrases it. */
    private val NEEDS =
        mapOf(
            "mic" to "Needs the microphone",
            "notifications" to "Needs notifications",
            "overlay" to "Needs display-over-apps",
            "a11y" to "Needs the accessibility service",
            "fullscreen" to "Needs full-screen alerts",
        )

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
