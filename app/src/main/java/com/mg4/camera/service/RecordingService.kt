package com.mg4.camera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mg4.camera.MainActivity
import com.mg4.camera.R

/**
 * 录制前台服务：录制期间保持常驻通知，保证多路采集不被系统杀死。
 */
class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val ACTION_START = "com.mg4.camera.action.START"
        private const val ACTION_STOP = "com.mg4.camera.action.STOP"

        fun start(context: Context, recording: Boolean) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(if (recording) ACTION_START else ACTION_STOP)
            if (recording) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                try { context.startService(intent) } catch (_: Exception) {}
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "录制状态", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // intent 为 null 说明服务被系统杀死后重建（START_STICKY），
            // 必须立即回到前台状态，否则会触发 ForegroundServiceDidNotStartInTime
            null -> startForeground(NOTIF_ID, buildNotification())
            else -> startForeground(NOTIF_ID, buildNotification())
        }
        // START_STICKY：录制期间被系统杀死后自动拉起，尽量保证后台采集不中断
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
