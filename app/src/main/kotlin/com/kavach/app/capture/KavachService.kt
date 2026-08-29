package com.kavach.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kavach.app.KavachApplication
import com.kavach.app.MainActivity
import com.kavach.app.R
import com.kavach.app.monitor.MonitorMode
import com.kavach.domain.RiskBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    private val scope = CoroutineScope(SupervisorJob())

    private val app get() = application as KavachApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            else -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        startAsForeground(notification(RiskBand.WATCHING, getString(R.string.notification_watching)))

        val source = app.createLiveTranscriptSource()
        app.controller.start(scope, MonitorMode.LIVE, source)

        scope.launch {
            app.controller.state
                .map { Triple(it.monitoring, it.band, it.tactics) }
                .distinctUntilChanged()
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
        app.controller.stop()
        scope.cancel()
        ServiceCompat.stopForegroundAndRemoveNotification(this)
        stopSelf()
    }

    override fun onDestroy() {
        app.controller.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
        manager?.notify(NOTIFICATION_ID, notification(band, text))
    }

    private fun notification(
        band: RiskBand,
        text: String,
    ): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, KavachService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(
                when (band) {
                    RiskBand.HIGH_RISK -> getString(R.string.state_high_risk_title)
                    RiskBand.CAUTION -> getString(R.string.state_caution_title)
                    RiskBand.WATCHING -> getString(R.string.app_name)
                },
            ).setContentText(text)
            // docs/SAFETY.md 2 requires this sentence in the notification itself.
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n${getString(R.string.safety_no_guarantee)}"))
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setSilent(band == RiskBand.WATCHING)
            .setPriority(
                if (band ==
                    RiskBand.HIGH_RISK
                ) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_LOW
                },
            ).build()
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

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
        private const val CHANNEL_ID = "kavach_monitoring"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.kavach.app.STOP"

        /** One gentle buzz for CAUTION, two for HIGH_RISK. Never a siren. */
        private val CAUTION_PATTERN = longArrayOf(0, 180)
        private val HIGH_RISK_PATTERN = longArrayOf(0, 220, 160, 220)
        private const val NO_REPEAT = -1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, KavachService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, KavachService::class.java).setAction(ACTION_STOP))
        }
    }
}
