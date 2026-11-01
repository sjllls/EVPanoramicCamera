package com.mg4.camera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mg4.camera.MainActivity
import com.mg4.camera.R

/**
 * 录制前台服务：录制期间保持常驻通知 + WakeLock，保证多路采集不被系统杀死。
 *
 * 保活策略（多层兜底）：
 * 1. 前台服务（camera 类型）+ START_STICKY（被杀后系统自动拉起）
 * 2. PARTIAL_WAKE_LOCK：录制期间阻止 CPU 休眠断流
 * 3. MainActivity 在录制中切后台时叠加 1 像素透明悬浮窗（KeepAliveOverlay，
 *    将进程优先级提升到"可见"级，部分 ROM 对前台服务仍会查杀）
 * 4. 进程被杀 → 服务粘性重启（intent 为 null）→ 自动拉起 MainActivity
 *    并携带 EXTRA_RESUME_RECORDING，App 恢复后延时自动重新开录
 */
class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val ACTION_START = "com.mg4.camera.action.START"
        private const val ACTION_STOP = "com.mg4.camera.action.STOP"

        /** 红点呼吸闪烁间隔（毫秒）：亮/暗两态交替 */
        private const val BLINK_INTERVAL_MS = 800L

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

    private var wakeLock: PowerManager.WakeLock? = null

    // ---------------- 状态栏图标红点呼吸闪烁 ----------------

    private val blinkHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var blinkOn = true
    private var recStartTime = 0L

    private val blinkTask = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            notifyRecording()
            blinkHandler.postDelayed(this, BLINK_INTERVAL_MS)
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
                releaseWakeLock()
                blinkHandler.removeCallbacks(blinkTask)
                isForeground = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // intent 为 null：进程被系统杀死后服务粘性重启，
            // 立即回到前台状态（5 秒超时限制），并尝试拉起主界面恢复录制
            null -> {
                startRecordingNotification()
                acquireWakeLock()
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(MainActivity.EXTRA_RESUME_RECORDING, true)
                    )
                } catch (e: Exception) {
                    android.util.Log.w("MG4KeepAlive", "resume launch failed: ${e.message}")
                }
            }
            else -> {
                startRecordingNotification()
                acquireWakeLock()
            }
        }
        // START_STICKY：录制期间被系统杀死后自动拉起，恢复录制
        return START_STICKY
    }

    /** 启动前台通知并开始红点呼吸闪烁 */
    private fun startRecordingNotification() {
        recStartTime = System.currentTimeMillis()
        blinkOn = true
        notifyRecording()
        blinkHandler.removeCallbacks(blinkTask)
        blinkHandler.postDelayed(blinkTask, BLINK_INTERVAL_MS)
    }

    /**
     * 构建录制中通知：
     * - 状态栏小图标为白色半透明圆形摄像头，红点随 notify 交替亮/暗形成呼吸闪烁
     * - 通知栏显示持久性通知（ongoing + 计时器显示录制时长），维持前台服务保活
     */
    private fun notifyRecording() {
        val icon = if (blinkOn) R.drawable.ic_rec_on else R.drawable.ic_rec_off
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setColor(0xFFFF5252.toInt())
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.recording))
            // 通知右侧实时显示录制时长（00:12 递增）
            .setUsesChronometer(true)
            .setWhen(recStartTime)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (!isForeground) {
            // 首次必须走 startForeground（前台服务 5 秒超时限制），后续静默更新
            startForeground(NOTIF_ID, notification)
            isForeground = true
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notification)
        }
    }

    private var isForeground = false

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MG4Cam:recording")
            .apply {
                setReferenceCounted(false)
                // 24 小时上限防泄漏；行车录像单次使用远短于此
                acquire(24L * 60 * 60 * 1000)
            }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        blinkHandler.removeCallbacks(blinkTask)
        releaseWakeLock()
        isForeground = false
        super.onDestroy()
    }
}
