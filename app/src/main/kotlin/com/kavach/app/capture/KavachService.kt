package com.kavach.app.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kavach.app.KavachApplication
import com.kavach.app.MainActivity
import com.kavach.app.R
import com.kavach.app.monitor.MonitorMode
import com.kavach.app.monitor.ShieldUiState
import com.kavach.app.ui.KavachTokens
import com.kavach.app.ui.ShieldOverlayActivity
import com.kavach.domain.RiskBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that hosts a monitoring session.
 *
 * It does not own the microphone itself — it hosts whichever
 * [com.kavach.domain.TranscriptSource] is active, keeps the process alive, and
 * carries the persistent notification Android mandates for a `microphone`
 * foreground service. That notification is a feature, not a nuisance
 * (docs/SAFETY.md 7): it is how the user knows we are listening, and how they
 * stop us in one tap.
 */
class KavachService : Service() {
    private var scope = CoroutineScope(SupervisorJob())
    private val started = AtomicBoolean(false)

    private val app get() = application as KavachApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    /**
     * Only ever starts on an explicit, user-reachable action, and only with the
     * microphone permission still in hand.
     *
     * The previous `START_STICKY` with an unconditional start meant Android could
     * restart this service after a process kill and switch the microphone back on
     * with no tap, no permission recheck and nothing on screen. That is precisely
     * the behaviour our own consent model forbids, so the service now refuses to
     * exist without a reason and declines to be resurrected.
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMonitoring()
            ACTION_START -> start()
            else -> {
                Log.w(TAG, "refusing to start: no explicit action (was this a sticky restart?)")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "refusing to start: RECORD_AUDIO not granted")
            stopSelf()
            return
        }

        // Written before the risky path: if Android refuses the foreground start —
        // which it will if we somehow got here from the background — say so and
        // leave, rather than dying with an opaque crash on stage.
        val promoted =
            runCatching {
                startAsForeground(notification(RiskBand.WATCHING, getString(R.string.notification_watching)))
            }.onFailure {
                Log.w(TAG, "startForeground refused", it)
                stopSelf()
            }.isSuccess
        if (!promoted) return

        if (!started.compareAndSet(false, true)) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob())
        startMonitoring()
    }

    private fun startMonitoring() {
        app.diagnostics.reset()
        val source = app.createLiveTranscriptSource()
        app.controller.start(scope, MonitorMode.LIVE, source)

        scope.launch {
            app.controller.state
                // Vibration is edge-triggered by band, not by every newly found
                // tactic; repeated ASR partials must not buzz the user repeatedly.
                .map { Triple(it.monitoring, it.band, it.tactics) }
                .distinctUntilChangedBy { it.first to it.second }
                .collect { (monitoring, band, tactics) ->
                    // If the session ended — the user stopped it, or capture
                    // failed — tear the service down. A persistent notification
                    // that says "Listening" while nothing is listening is a lie,
                    // and this notification is the user's evidence that we are
                    // honest about when the microphone is live.
                    if (!monitoring) {
                        stopMonitoring()
                        return@collect
                    }
                    updateNotification(band, tactics)
                    vibrateFor(band)
                }
        }
    }

    private fun stopMonitoring() {
        // Capture the final verdict before teardown: [ShieldController.stop]
        // flips monitoring to false and clears the live assessment we report on.
        val finalState = app.controller.state.value
        started.set(false)
        app.controller.stop()
        scope.cancel()
        postCallVerdict(finalState)
        ServiceCompat.stopForegroundAndRemoveNotification(this)
        stopSelf()
    }

    /**
     * The end-of-call report card. While the call runs, the verdict is a
     * transient notification the user can miss — they are busy talking. Posting
     * it again after hangup, on the loud channel, is the answer to the question
     * the user actually has: "was that call safe?" It names the band, the top
     * tactic when there is one, and never reuses the listening notification's
     * id, which is about to be removed.
     */
    private fun postCallVerdict(state: ShieldUiState) {
        val band = state.band
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val title =
            when (band) {
                RiskBand.HIGH_RISK -> getString(R.string.verdict_high_risk_title)
                RiskBand.CAUTION -> getString(R.string.verdict_caution_title)
                RiskBand.WATCHING -> getString(R.string.verdict_safe_title)
            }
        val text =
            when (band) {
                RiskBand.HIGH_RISK -> getString(R.string.verdict_high_risk_body, state.score)
                RiskBand.CAUTION ->
                    state.tactics.firstOrNull()
                        ?: getString(R.string.verdict_caution_body, state.score)
                RiskBand.WATCHING -> getString(R.string.verdict_safe_body, state.score)
            }

        val open =
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        // Only a scam verdict earns the loud channel. Every call ended on
        // CHANNEL_ALERT — which bypasses Do Not Disturb — so hanging up on your
        // mother produced a DND-piercing buzz to announce that the call was
        // safe. Alerting on good news is how an alert channel loses its meaning
        // by the third day, and the user turns it off before the call that
        // matters.
        val loud = band == RiskBand.HIGH_RISK
        val notification =
            NotificationCompat
                .Builder(
                    this,
                    if (loud) KavachNotifications.CHANNEL_ALERT else KavachNotifications.CHANNEL_STATUS,
                ).setSmallIcon(R.drawable.ic_shield)
                .setColor(bandColor(band))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setSilent(!loud)
                .setCategory(if (loud) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        manager.notify(KavachNotifications.VERDICT_ID, notification)
    }

    override fun onDestroy() {
        app.controller.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                KavachNotifications.ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(KavachNotifications.ONGOING_ID, notification)
        }
    }

    private fun updateNotification(
        band: RiskBand,
        tactics: List<String>,
    ) {
        val text =
            when (band) {
                RiskBand.WATCHING -> getString(R.string.notification_watching)
                RiskBand.CAUTION -> tactics.firstOrNull() ?: getString(R.string.state_caution_title)
                RiskBand.HIGH_RISK -> getString(R.string.state_high_risk_title)
            }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(KavachNotifications.ONGOING_ID, notification(band, text))
    }

    /**
     * The ongoing notification lives on the quiet channel; a warning is posted on
     * the loud one.
     *
     * Channel importance, not `setPriority`, decides whether a notification makes
     * a sound or a heads-up banner on API 26 and above. With a single
     * `IMPORTANCE_LOW` channel the HIGH_RISK warning could never be more than a
     * silent line in the shade — the alert was, in practice, unreachable.
     */
    private fun notification(
        band: RiskBand,
        text: String,
    ): Notification {
        val alerting = band == RiskBand.HIGH_RISK
        val intents = buildPendingIntents()
        val accentColor = bandColor(band)
        val channelId = if (alerting) KavachNotifications.CHANNEL_ALERT else KavachNotifications.CHANNEL_STATUS

        return NotificationCompat
            .Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_shield)
            .setColor(accentColor)
            .setColorized(true)
            .setSubText(getString(R.string.notification_subtext))
            .setCategory(if (alerting) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_STATUS)
            .setContentTitle(notificationTitle(band))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n${getString(R.string.safety_no_guarantee)}"))
            .setContentIntent(if (alerting) intents.shield else intents.open)
            .apply {
                if (alerting) {
                    setCategory(NotificationCompat.CATEGORY_CALL)
                    setFullScreenIntent(intents.shield, true)
                    addAction(0, getString(R.string.action_dial_1930), intents.dial)
                }
            }.addAction(0, getString(R.string.action_stop), intents.stop)
            .setOngoing(true)
            .setSilent(!alerting)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun notificationTitle(band: RiskBand): String =
        when (band) {
            RiskBand.HIGH_RISK -> getString(R.string.state_high_risk_title)
            RiskBand.CAUTION -> getString(R.string.state_caution_title)
            RiskBand.WATCHING -> getString(R.string.app_name)
        }

    /**
     * The notification accent, read from the design tokens rather than restated.
     *
     * These were three raw hex literals that happened to be Tailwind's defaults,
     * so the shade in the status bar did not match the shade on the card it
     * opens. WATCHING is deliberately the neutral ink rather than a green: a
     * colourised "all clear" is a claim, and Kavach only ever reports what it
     * has heard so far.
     */
    private fun bandColor(band: RiskBand): Int =
        when (band) {
            RiskBand.HIGH_RISK -> KavachTokens.PressRed.toArgb()
            RiskBand.CAUTION -> KavachTokens.Amber.toArgb()
            RiskBand.WATCHING -> KavachTokens.Ink.toArgb()
        }

    private data class NotificationIntents(
        val open: PendingIntent,
        val shield: PendingIntent,
        val stop: PendingIntent,
        val dial: PendingIntent,
    )

    private fun buildPendingIntents(): NotificationIntents {
        val open =
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val shield =
            PendingIntent.getActivity(
                this,
                REQUEST_SHIELD,
                Intent(this, ShieldOverlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, KavachService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val dial =
            PendingIntent.getActivity(
                this,
                REQUEST_DIAL,
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationIntents(open, shield, stop, dial)
    }

    /**
     * A single gentle buzz for CAUTION, a double for HIGH_RISK (docs/PRD.md 5).
     * Never a siren: the person seeing this is often already frightened.
     */
    private fun vibrateFor(band: RiskBand) {
        val pattern =
            when (band) {
                RiskBand.CAUTION -> CAUTION_PATTERN
                RiskBand.HIGH_RISK -> HIGH_RISK_PATTERN
                RiskBand.WATCHING -> return
            }
        vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, NO_REPEAT))
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    private fun createChannels() {
        runCatching { getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(LEGACY_CHANNEL) }
        KavachNotifications.ensureChannels(this)
    }

    /** Minimal shim so the deprecated stopForeground overload is isolated in one place. */
    private object ServiceCompat {
        fun stopForegroundAndRemoveNotification(service: Service) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                service.stopForeground(true)
            }
        }
    }

    companion object {
        private const val TAG = "KavachService"
        private const val LEGACY_CHANNEL = "kavach_monitoring"

        /** Distinct request codes, so one PendingIntent never overwrites another. */
        private const val REQUEST_OPEN = 0
        private const val REQUEST_STOP = 1
        private const val REQUEST_SHIELD = 2
        private const val REQUEST_DIAL = 3

        const val ACTION_START = "com.kavach.app.START"
        const val ACTION_STOP = "com.kavach.app.STOP"

        /** One gentle buzz for CAUTION, two for HIGH_RISK. Never a siren. */
        val CAUTION_PATTERN = longArrayOf(0, 180)
        val HIGH_RISK_PATTERN = longArrayOf(0, 220, 160, 220)
        private const val NO_REPEAT = -1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KavachService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, KavachService::class.java).setAction(ACTION_STOP))
        }
    }
}
