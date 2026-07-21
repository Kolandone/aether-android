package com.aether.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var renewalJob: java.util.TimerTask? = null
    private val timer = java.util.Timer(true)

    companion object {
        private const val CHANNEL_ID = "aether_alive"
        private const val NOTIFICATION_ID = 1
        var isRunning = false
        var onStopRequested: (() -> Unit)? = null

        fun start(context: Context) {
            if (isRunning) return
            context.startForegroundService(Intent(context, KeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Aether running"))
        acquireWakeLock()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Aether",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keep Aether running"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, KeepAliveService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aether Proxy")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_power)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aether:keepalive")
        wakeLock?.acquire(60 * 60 * 1000L)
        // ponytail: single 1h acquire instead of 10min renewal loop. Android limits
        // total time anyway. If battery drain matters, add a periodic renewal timer.
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        renewalJob?.cancel()
        isRunning = false
        super.onDestroy()
    }
}
