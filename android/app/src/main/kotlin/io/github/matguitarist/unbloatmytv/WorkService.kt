package io.github.matguitarist.unbloatmytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Runs while the TV is being changed (clean-up, undo, a restart, an install), so Android keeps the app,
 * the processor and the Wi-Fi awake when the phone locks or the person switches apps. Shows a
 * notification with the progress; tapping it opens the app.
 */
class WorkService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ID, build(this), type)
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "unbloatmytv:work")
            .apply { setReferenceCounted(false); acquire(30 * 60_000L) }
        if (wifi == null) wifi = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(@Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF, "unbloatmytv:work")
            ?.apply { setReferenceCounted(false); acquire() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "work"

        @Volatile private var title = ""
        @Volatile private var text = ""
        /** 0 to 1, or null for "busy" without a figure. */
        @Volatile private var progress: Float? = null
        @Volatile private var running = false

        /** Starts holding the connection. Call it from a tap: Android only allows this while the app shows. */
        fun start(ctx: Context, what: String) {
            title = what
            text = ""
            progress = null
            running = true
            runCatching { ctx.startForegroundService(Intent(ctx, WorkService::class.java)) }
        }

        fun update(ctx: Context, step: String, fraction: Float?) {
            if (!running || (step == text && fraction == progress)) return
            text = step
            progress = fraction
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx)) }
        }

        fun stop(ctx: Context) {
            if (!running) return
            running = false
            ctx.stopService(Intent(ctx, WorkService::class.java))
        }

        private fun build(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, str(R.string.Android_WorkChannel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                ctx, 0, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE,
            )
            val p = progress
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text.ifEmpty { str(R.string.Android_WorkText) })
                .setProgress(100, ((p ?: 0f) * 100).toInt(), p == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
