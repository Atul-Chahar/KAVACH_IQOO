package com.kavach.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kavach.app.KavachApplication
import com.kavach.app.capture.KavachService
import com.kavach.domain.RiskBand

/**
 * The screen Kavach puts up for the length of a monitored call.
 *
 * It exists for two reasons at once, which is the whole trick of this design.
 * It is the warning surface — over the in-call UI, and over the lock screen. And
 * being visible is what places Kavach at `PROCESS_STATE_TOP`, which is the
 * condition Android's audio policy requires before it will let an accessibility
 * service hear anything during a call. If this activity goes away, the
 * microphone goes deaf. That is why it stays up for the whole call rather than
 * flashing and finishing like an ordinary launcher shim.
 *
 * Staying up must not mean getting in the way, and that is a statement about the
 * *window*, not about what we draw in it. A full-screen window that merely paints
 * a band at the top still covers the dialer, and Android then stamps every touch
 * bound for the app underneath with `FLAG_WINDOW_IS_OBSCURED`. Any view with
 * `filterTouchesWhenObscured` — which OEM in-call UIs and system dialogs set —
 * silently discards those. The phone looks alive and answers nothing, including
 * the button that picks up the call. `FLAG_NOT_TOUCHABLE` does not help: it stops
 * us *consuming* touches, not *obscuring* them.
 *
 * So the passive shield is physically a band: the window itself is
 * [BAND_HEIGHT_DP] tall, pinned to the top, and the rest of the screen has no
 * Kavach window over it at all. The instant the verdict reaches HIGH_RISK the
 * window grows to full screen and takes touch, because the user now has
 * decisions to make. It never covers the bottom of the screen while passive,
 * because the user must always be able to hang up without dismissing us first.
 */
class ShieldOverlayActivity : ComponentActivity() {
    private val app get() = application as KavachApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        if (intent?.action == ACTION_DISMISS) {
            finish()
            return
        }

        // We are visible, therefore foreground, therefore permitted to start a
        // microphone foreground service — the while-in-use restriction applies to
        // background starts, and this is not one.
        KavachService.start(this)

        setContent {
            KavachTheme {
                val state by app.controller.state.collectAsStateWithLifecycle()
                val capture by app.diagnostics.state.collectAsStateWithLifecycle()

                val alerting = state.band == RiskBand.HIGH_RISK && !state.alertDismissed

                // Passive: a band that neither takes nor obscures touch. Alerting:
                // full screen and touchable, because the user now has decisions to
                // make and buttons to press.
                LaunchedEffect(alerting) { applyShieldMode(alerting) }

                ShieldOverlay(
                    state = state,
                    capture = capture,
                    onCall1930 = ::dial1930,
                    onDismiss = { app.controller.dismissAlert() },
                    onStop = {
                        KavachService.stop(this@ShieldOverlayActivity)
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_DISMISS) finish()
    }

    /**
     * The user swiped us away or the call ended. Stop listening.
     *
     * Capture outliving the surface that says capture is happening would be a
     * silent microphone, which is the one thing docs/SAFETY.md forbids.
     */
    override fun onDestroy() {
        if (isFinishing) KavachService.stop(this)
        super.onDestroy()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyShieldMode(alerting = false)
    }

    /**
     * Sizes the window to what the shield currently has to say, and decides
     * whether it may take touch.
     *
     * Both halves must move together. A touchable window that is still only a
     * band cannot show the alert; a full-screen window that is passive is the
     * bug this method exists to prevent — see the class comment.
     *
     * The cutout mode is set here rather than in [showOverLockScreen] because
     * that version mutated the LayoutParams object in place and never called
     * `setAttributes`, so it was decorative: the shield was not laid out into
     * the punch-hole area it is drawn to align with.
     */
    private fun applyShieldMode(alerting: Boolean) {
        val params = window.attributes
        params.gravity = Gravity.TOP
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height =
            if (alerting) {
                WindowManager.LayoutParams.MATCH_PARENT
            } else {
                (BAND_HEIGHT_DP * resources.displayMetrics.density).toInt()
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = params
        setTouchable(alerting)
    }

    private fun setTouchable(touchable: Boolean) {
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (touchable) window.clearFlags(flags) else window.addFlags(flags)
    }

    /**
     * ACTION_DIAL, never ACTION_CALL: the number is pre-filled and the user
     * presses the green button. Kavach never places a call on anyone's behalf
     * (CLAUDE.md hard rule 5).
     */
    private fun dial1930() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    companion object {
        /**
         * How tall the passive shield is allowed to be, in dp.
         *
         * Generous enough for the pill and for the taller can't-hear card, and
         * far short of the dialer's answer and hang-up controls, which is the
         * point: everything below this line is untouched by Kavach and behaves
         * exactly as it would if we were not running.
         */
        private const val BAND_HEIGHT_DP = 280

        const val ACTION_DISMISS = "com.kavach.app.SHIELD_DISMISS"

        /**
         * Raise the shield from the background.
         *
         * Permitted because Kavach holds `SYSTEM_ALERT_WINDOW`, which is a
         * documented exemption from the background-activity-launch restriction.
         */
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, ShieldOverlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        fun dismiss(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, ShieldOverlayActivity::class.java)
                        .setAction(ACTION_DISMISS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
    }
}
