package com.mg4.camera

import android.content.Context

/** 全局配置存取 */
object Prefs {
    private const val NAME = "mg4_camera"
    private const val KEY_CAMERAS = "selected_cameras"
    private const val KEY_RES = "resolution"
    private const val KEY_AUTO = "auto_start"
    private const val KEY_KEEP_ALIVE = "keep_alive_overlay"
    private const val KEY_STORAGE = "storage_path"
    private const val KEY_ZOOM_SINGLE = "zoom_single"
    private const val KEY_ZOOM_FULLSCREEN = "zoom_fullscreen"
    private const val KEY_ZOOM_DUAL = "zoom_dual"
    private const val KEY_ZOOM_TRIPLE = "zoom_triple"
    private const val KEY_ASPECT = "preview_aspect"
    private const val KEY_LOOP_MODE = "loop_mode"
    private const val KEY_LOOP_GB = "loop_fixed_gb"
    private const val KEY_LOOP_PCT = "loop_percent"

    const val RES_720 = 0
    const val RES_480 = 1

    /** 预览/录像画幅：16:9（默认）/ 4:3（部分鱼眼 HAL 的完整圆画面在 4:3 缓冲内） */
    const val ASPECT_169 = 0
    const val ASPECT_43 = 1

    /** 循环录制模式：固定剩余容量（GB）/ 总容量百分比 */
    const val LOOP_FIXED = "fixed"
    const val LOOP_PERCENT = "percent"

    /** 单路/全屏/双路/三路缩放默认值（实测标定） */
    const val DEFAULT_ZOOM_SINGLE = 0.8f
    const val DEFAULT_ZOOM_FULLSCREEN = 0.7f
    const val DEFAULT_ZOOM_DUAL = 1.3f
    const val DEFAULT_ZOOM_TRIPLE = 2.0f

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun selectedCameras(ctx: Context): List<String> =
        sp(ctx).getStringSet(KEY_CAMERAS, null)?.filterNotNull()?.sorted() ?: emptyList()

    fun setSelectedCameras(ctx: Context, ids: Collection<String>): Boolean =
        sp(ctx).edit().putStringSet(KEY_CAMERAS, HashSet(ids)).commit()

    fun resolution(ctx: Context): Int = sp(ctx).getInt(KEY_RES, RES_720)

    fun setResolution(ctx: Context, res: Int): Boolean =
        sp(ctx).edit().putInt(KEY_RES, res).commit()

    fun autoStart(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO, false)

    fun setAutoStart(ctx: Context, value: Boolean): Boolean =
        sp(ctx).edit().putBoolean(KEY_AUTO, value).commit()

    /** 后台录制保活：录制中切后台时叠加 1 像素透明悬浮窗（默认开启） */
    fun keepAliveOverlay(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_KEEP_ALIVE, true)

    fun setKeepAliveOverlay(ctx: Context, value: Boolean): Boolean =
        sp(ctx).edit().putBoolean(KEY_KEEP_ALIVE, value).commit()

    /** 录像存储根目录（直连路径，如 /storage/1F13-3D4D） */
    fun storagePath(ctx: Context): String? = sp(ctx).getString(KEY_STORAGE, null)

    fun setStoragePath(ctx: Context, path: String): Boolean =
        sp(ctx).edit().putString(KEY_STORAGE, path).commit()

    // ---------------- 预览缩放（单路/全屏/双路/三路独立设置，用户可调） ----------------

    private fun zoom(ctx: Context, key: String, def: Float): Float =
        try {
            sp(ctx).getFloat(key, def).coerceIn(0.5f, 3.0f)
        } catch (_: Exception) {
            def
        }

    private fun setZoom(ctx: Context, key: String, v: Float): Boolean =
        sp(ctx).edit().putFloat(key, v.coerceIn(0.5f, 3.0f)).commit()

    /** 单路预览缩放 */
    fun zoomSingle(ctx: Context): Float = zoom(ctx, KEY_ZOOM_SINGLE, DEFAULT_ZOOM_SINGLE)

    fun setZoomSingle(ctx: Context, v: Float): Boolean = setZoom(ctx, KEY_ZOOM_SINGLE, v)

    /** 全屏预览缩放 */
    fun zoomFullscreen(ctx: Context): Float = zoom(ctx, KEY_ZOOM_FULLSCREEN, DEFAULT_ZOOM_FULLSCREEN)

    fun setZoomFullscreen(ctx: Context, v: Float): Boolean = setZoom(ctx, KEY_ZOOM_FULLSCREEN, v)

    /** 双路预览缩放 */
    fun zoomDual(ctx: Context): Float = zoom(ctx, KEY_ZOOM_DUAL, DEFAULT_ZOOM_DUAL)

    fun setZoomDual(ctx: Context, v: Float): Boolean = setZoom(ctx, KEY_ZOOM_DUAL, v)

    /** 三路及以上预览缩放 */
    fun zoomTriple(ctx: Context): Float = zoom(ctx, KEY_ZOOM_TRIPLE, DEFAULT_ZOOM_TRIPLE)

    fun setZoomTriple(ctx: Context, v: Float): Boolean = setZoom(ctx, KEY_ZOOM_TRIPLE, v)

    // ---------------- 预览画幅 ----------------

    fun aspect(ctx: Context): Int = sp(ctx).getInt(KEY_ASPECT, ASPECT_169)

    fun setAspect(ctx: Context, value: Int): Boolean =
        sp(ctx).edit().putInt(KEY_ASPECT, value).commit()

    // ---------------- 循环录制阈值（固定剩余容量 / 总容量百分比） ----------------

    /** 循环录制模式，默认固定容量 1GB（与旧版行为一致） */
    fun loopMode(ctx: Context): String =
        sp(ctx).getString(KEY_LOOP_MODE, LOOP_FIXED) ?: LOOP_FIXED

    fun setLoopMode(ctx: Context, mode: String): Boolean =
        sp(ctx).edit().putString(KEY_LOOP_MODE, mode).commit()

    /** 固定容量阈值（GB），范围 1~50 */
    fun loopFixedGb(ctx: Context): Int =
        sp(ctx).getInt(KEY_LOOP_GB, 1).coerceIn(1, 50)

    fun setLoopFixedGb(ctx: Context, gb: Int): Boolean =
        sp(ctx).edit().putInt(KEY_LOOP_GB, gb.coerceIn(1, 50)).commit()

    /** 百分比阈值（录像卷总容量的百分比），范围 5~50 */
    fun loopPercent(ctx: Context): Int =
        sp(ctx).getInt(KEY_LOOP_PCT, 10).coerceIn(5, 50)

    fun setLoopPercent(ctx: Context, pct: Int): Boolean =
        sp(ctx).edit().putInt(KEY_LOOP_PCT, pct.coerceIn(5, 50)).commit()
}
