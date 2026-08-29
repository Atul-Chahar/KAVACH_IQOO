package com.kavach.app.capture

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

/**
 * Notices that a call has started, without being told and without reading
 * anything private.
 *
 * The obvious approaches all cost more than they are worth here. `PHONE_STATE`
 * sees only cellular calls, so it is blind on a device with no SIM and blind to
 * WhatsApp, Meet and Telegram everywhere. A notification listener would work but
 * demands access to the text of every notification on the phone, which is a
 * far larger permission than the job needs and one we would then have to defend.
 * Reading the foreground window through the accessibility service means reading
 * other apps' screens, which contradicts the narrow service we promised.
 *
 * The audio mode says the same thing with none of that: when any call connects,
 * cellular or VoIP, the mode moves to `MODE_IN_CALL` or `MODE_IN_COMMUNICATION`.
 * It is a single integer, it belongs to no app in particular, it reveals nothing
 * about who is calling or what is said, and it is exactly the condition that
 * governs whether Kavach can hear anything at all.
 */
class CallWatcher(
    context: Context,
    private val diagnostics: CaptureDiagnostics,
    private val onCallStarted: () -> Unit,
    private val onCallEnded: () -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> handler.post(command) }

    private var inCall = false
    private var listener: AudioManager.OnModeChangedListener? = null
    private var polling: Runnable? = null

    fun start() {
        val manager = audioManager ?: return
        onMode(manager.mode)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val modeListener = AudioManager.OnModeChangedListener { mode -> onMode(mode) }
            listener = modeListener
            runCatching { manager.addOnModeChangedListener(executor, modeListener) }
                .onFailure {
                    Log.w(TAG, "mode listener unavailable, polling instead", it)
                    listener = null
                    poll()
                }
        } else {
            poll()
        }
    }

    fun stop() {
        listener?.let { runCatching { audioManager?.removeOnModeChangedListener(it) } }
        listener = null
        polling?.let { handler.removeCallbacks(it) }
        polling = null
    }

    /** Pre-S fallback. One integer read every two seconds costs nothing measurable. */
    private fun poll() {
        val task =
            object : Runnable {
                override fun run() {
                    audioManager?.let { onMode(it.mode) }
                    handler.postDelayed(this, POLL_MS)
                }
            }
        polling = task
        handler.postDelayed(task, POLL_MS)
    }

    private fun onMode(mode: Int) {
        diagnostics.onAudioMode(mode)
        val nowInCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        if (nowInCall == inCall) return
        inCall = nowInCall
        Log.i(TAG, "call ${if (nowInCall) "started" else "ended"} (mode=$mode)")
        if (nowInCall) onCallStarted() else onCallEnded()
    }

    private companion object {
        const val TAG = "KavachCallWatcher"
        const val POLL_MS = 2_000L
    }
}

/** Human-readable audio mode, for the diagnostics panel and the demo video. */
fun audioModeName(mode: Int): String =
    when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "MODE_$mode"
    }
