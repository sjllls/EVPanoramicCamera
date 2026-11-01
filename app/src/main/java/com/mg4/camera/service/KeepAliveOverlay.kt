package com.mg4.camera.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 后台录制保活悬浮窗：录制中切到后台时，在屏幕右上角添加一个
 * 1×1 像素、完全透明、不可触摸不可聚焦的悬浮窗。
 *
 * 原理：进程持有可见（TYPE_APPLICATION_OVERLAY）窗口后，
 * 系统将其 oom_adj 提升至"可见进程"级别，比仅靠前台服务更难被杀。
 * 部分车机 ROM 对前台服务仍会查杀，此悬浮窗是有效的兜底手段。
 *
 * 需要"显示在其他应用上层"权限（Settings.canDrawOverlays），
 * 未授权时静默跳过（不影响功能，仅保活降级为前台服务）。
 */
object KeepAliveOverlay {

    private const val TAG = "MG4KeepAlive"

    private var view: View? = null

    fun granted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    @Synchronized
    fun show(ctx: Context) {
        if (view != null) return
        if (!granted(ctx)) {
            android.util.Log.w(TAG, "overlay permission not granted, keep-alive degraded")
            return
        }
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(ctx)
            v.setBackgroundColor(Color.TRANSPARENT)
            val lp = WindowManager.LayoutParams(
                1, 1, // 1 像素，肉眼不可见
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.END // 右上角
            lp.x = 0
            lp.y = 0
            wm.addView(v, lp)
            view = v
            android.util.Log.i(TAG, "keep-alive overlay shown")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "show overlay failed: ${e.message}")
            view = null
        }
    }

    @Synchronized
    fun hide(ctx: Context) {
        val v = view ?: return
        view = null
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
            android.util.Log.i(TAG, "keep-alive overlay removed")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "hide overlay failed: ${e.message}")
        }
    }
}
