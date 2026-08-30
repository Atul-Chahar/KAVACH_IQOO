package com.kavach.app.ui

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kavach.app.KavachApplication
import com.kavach.app.MainActivity
import com.kavach.app.message.MessageDetection

/**
 * Hosts [MessageIsland] in a `TYPE_APPLICATION_OVERLAY` window.
 *
 * ## Why this is not an Activity
 *
 * It was one, and the device said no. An Activity — even a translucent one
 * sized to a band at the top of the screen — *pauses whatever is underneath
 * it*. Verified on the iQOO test unit: with the capsule up, `dumpsys` reported
 * `mLastPausedActivity: com.android.launcher3/.Launcher`, and taps below the
 * capsule reached nothing at all. The phone looked alive and answered nothing
 * for the nine seconds the capsule was on screen. A warning about a text message
 * has no business freezing the device it warns on.
 *
 * An overlay window creates no task and pauses nothing. With
 * `FLAG_NOT_TOUCH_MODAL` it consumes touches inside the capsule and lets every
 * other touch through to the app that owns the screen, which stays resumed the
 * whole time. This is what `SYSTEM_ALERT_WINDOW` is for, and Kavach already
 * holds it for the call shield.
 *
 * The call shield stays an Activity, and must: being a visible Activity is what
 * places Kavach at `PROCESS_STATE_TOP`, which is the audio policy's condition
 * for hearing anything during a call. Nothing here listens, so nothing here
 * needs that.
 */
object MessageIslandOverlay {
    /** The window currently on screen, if any. Main thread only. */
    private var current: View? = null
    private var currentOwner: OverlayOwner? = null

    /**
     * Whether the capsule can be drawn at all.
     *
     * The fallback is written before the risky path: when the overlay grant is
     * missing, the caller posts the ordinary heads-up warning instead. The user
     * is still warned; they just get it as a notification.
     */
    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Replaces any capsule already up. Must be called on the main thread. */
    @SuppressLint("InflateParams")
    fun show(
        context: Context,
        detection: MessageDetection,
    ) {
        val app = context.applicationContext as? KavachApplication ?: return
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return
        dismiss(context)

        val owner = OverlayOwner()
        val view =
            ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setContent {
                    KavachTheme {
                        MessageIsland(
                            detection = detection,
                            onOpenDetails = {
                                openGuard(context)
                                resolve(context, app, detection, trust = false)
                            },
                            onCall1930 = {
                                dial1930(context)
                                resolve(context, app, detection, trust = false)
                            },
                            onTrust = { resolve(context, app, detection, trust = true) },
                            onDismiss = { dismiss(context) },
                        )
                    }
                }
            }

        owner.start()
        val added =
            runCatching { windowManager.addView(view, layoutParams(context)) }.isSuccess
        if (!added) {
            owner.stop()
            return
        }
        current = view
        currentOwner = owner
    }

    fun dismiss(context: Context) {
        val view = current ?: return
        current = null
        val owner = currentOwner
        currentOwner = null
        runCatching { context.getSystemService(WindowManager::class.java)?.removeView(view) }
        owner?.stop()
    }

    private fun resolve(
        context: Context,
        app: KavachApplication,
        detection: MessageDetection,
        trust: Boolean,
    ) {
        if (trust) app.messageGuard.trust(detection.id)
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(detection.notificationId)
        }
        dismiss(context)
    }

    private fun openGuard(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_MESSAGE_GUARD
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
            )
        }
    }

    /**
     * ACTION_DIAL, never ACTION_CALL: the number is pre-filled and the user
     * presses the green button. Kavach places no call on anyone's behalf
     * (CLAUDE.md hard rule 5).
     */
    private fun dial1930(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * A window exactly as tall as the capsule, pinned below the status bar.
     *
     * `WRAP_CONTENT` height rather than a fixed band, because the window's own
     * bounds are what decide which touches Kavach takes: anything the window
     * does not cover is not ours to intercept. `FLAG_NOT_TOUCH_MODAL` is what
     * lets the rest of the screen keep working, and `FLAG_NOT_FOCUSABLE` keeps
     * the keyboard and the back button with the app underneath.
     */
    private fun layoutParams(context: Context): WindowManager.LayoutParams =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP
                y = statusBarHeight(context) + (TOP_MARGIN_DP * context.resources.displayMetrics.density).toInt()
            }

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private const val TOP_MARGIN_DP = 6

    /**
     * The minimum lifecycle a [ComposeView] needs to compose outside an
     * Activity. Created RESUMED and destroyed with the window; there is no
     * state here worth saving, because the capsule is a view onto a detection
     * the store already owns.
     */
    private class OverlayOwner :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
