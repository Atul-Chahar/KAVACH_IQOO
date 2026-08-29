package com.kavach.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
 * Staying up must not mean getting in the way, so the window is translucent and,
 * while there is nothing to say, untouchable: the call underneath is fully
 * visible and fully usable, and Kavach is a band across the top. The instant the
 * verdict reaches HIGH_RISK the window takes touch and becomes the full warning.
 * It never covers the bottom of the screen, because the user must always be able
 * to hang up without dismissing us first.
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

                // Passive: touches fall through to the call. Alerting: we take them,
                // because the user now has decisions to make and buttons to press.
                LaunchedEffect(alerting) { setTouchable(alerting) }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTouchable(false)
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
