package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.StudyHomeActivity
import me.rerere.rikkahub.data.study.StudyDesktopManager
import me.rerere.rikkahub.data.study.StudyDesktopSystemPolicyReason

class StudyKeepAliveService : Service() {
    private val manager by lazy { StudyDesktopManager(applicationContext) }
    private val policyHandler = Handler(Looper.getMainLooper())
    private val policyWatchdog = object : Runnable {
        override fun run() {
            if (!manager.reconcileForegroundGuard()) {
                stopSelf()
                return
            }
            policyHandler.postDelayed(this, HOME_RECONCILE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!manager.reconcileForegroundGuard()) {
            stopSelf()
            return START_NOT_STICKY
        }
        manager.publishSystemPolicy(
            StudyDesktopSystemPolicyReason.SERVICE_START,
            force = true,
        )
        policyHandler.removeCallbacks(policyWatchdog)
        policyHandler.postDelayed(policyWatchdog, HOME_RECONCILE_INTERVAL_MS)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        policyHandler.removeCallbacks(policyWatchdog)
        manager.publishSystemPolicy(
            StudyDesktopSystemPolicyReason.SERVICE_STOP,
            force = true,
        )
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.study_desktop_notification_title))
        .setContentText(getString(R.string.study_desktop_notification_text))
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, StudyHomeActivity::class.java).apply {
                    putExtra(StudyDesktopManager.EXTRA_OPEN_STUDY_DESKTOP, true)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.study_desktop_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.START_STUDY_KEEP_ALIVE"
        private const val HOME_RECONCILE_INTERVAL_MS = 2_000L
        private const val CHANNEL_ID = "study_desktop_guard"
        private const val NOTIFICATION_ID = 8301
    }
}
