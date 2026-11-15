package dev.buyolitsez.b2player

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app in the foreground while the assistant answers. Without it, once the app is minimized and
 * nothing plays, Android takes the network away from it and the request fails.
 */
class AssistantService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName("Assistant").build()
        )
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Assistant is answering").setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, NOTIFICATION, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // A turn that ended before the service came up stops it here: stopping it before startForeground crashes the app.
        if (wanted) running = this else stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "assistant"
        private const val NOTIFICATION = 5
        private var wanted = false
        private var running: AssistantService? = null

        fun start(ctx: Context) = runCatching { wanted = true; ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java)) }
            .onFailure { AppLog.w("assistant", "Can't keep the request alive in the background", it) }

        fun stop() { wanted = false; running?.stopSelf(); running = null }
    }
}
