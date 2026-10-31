package com.mg4.camera

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.mg4.camera.camera.CameraScanner

interface SettingsCallback {
    fun onSettingsApplied()
    fun onStorageChangeRequested()
}

/**
 * 设置面板：勾选录制相机 ID、选择分辨率、存储位置、启动自动录制开关。
 *
 * 使用标准居中 DialogFragment（而非 BottomSheet）——
 * BottomSheet 在车机/手机横屏 + 厂商 ROM 下会把内容布局到屏幕外导致无法点击，
 * 标准 AlertDialog 在任意方向和 ROM 下均可靠（与存储选择对话框一致）。
 */
class SettingsSheet : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_settings, null)
        val list = v.findViewById<android.widget.LinearLayout>(R.id.cameraList)

        // 存储位置显示与更改入口
        val tvStorage = v.findViewById<TextView>(R.id.tvStoragePath)
        tvStorage.text = Prefs.storagePath(ctx) ?: "未设置（开始录制时会提示）"
        v.findViewById<Button>(R.id.btnStorage).setOnClickListener {
            (activity as? SettingsCallback)?.onStorageChangeRequested()
            dismiss()
        }

        val detected = CameraScanner.detect(ctx)
        val selected = Prefs.selectedCameras(ctx).toHashSet()
        val boxes = LinkedHashMap<String, CheckBox>()

        if (detected.isEmpty()) {
            val tv = TextView(ctx)
            tv.text = "未检测到任何摄像头"
            tv.setTextColor(Color.WHITE)
            list.addView(tv)
        }
        for (info in detected) {
            val cb = CheckBox(ctx).apply {
                text = "CAM ${info.id}（${info.label}）"
                isChecked = info.id in selected
                setTextColor(Color.WHITE)
            }
            boxes[info.id] = cb
            list.addView(cb)
        }

        val rb720 = v.findViewById<RadioButton>(R.id.rb720)
        val rb480 = v.findViewById<RadioButton>(R.id.rb480)
        if (Prefs.resolution(ctx) == Prefs.RES_480) rb480.isChecked = true else rb720.isChecked = true

        // 预览画幅：16:9 / 4:3（完整鱼眼）
        val rbAspect169 = v.findViewById<RadioButton>(R.id.rbAspect169)
        val rbAspect43 = v.findViewById<RadioButton>(R.id.rbAspect43)
        if (Prefs.aspect(ctx) == Prefs.ASPECT_43) rbAspect43.isChecked = true else rbAspect169.isChecked = true

        // 画面缩放滑块（0.5~3.0，步进 0.1；部分车机无输入法，故不用文本框）
        val sbSingle = v.findViewById<SeekBar>(R.id.sbZoomSingle)
        val sbFs = v.findViewById<SeekBar>(R.id.sbZoomFullscreen)
        val sbDual = v.findViewById<SeekBar>(R.id.sbZoomDual)
        val sbTriple = v.findViewById<SeekBar>(R.id.sbZoomTriple)
        val tvSingle = v.findViewById<TextView>(R.id.tvZoomSingle)
        val tvFs = v.findViewById<TextView>(R.id.tvZoomFullscreen)
        val tvDual = v.findViewById<TextView>(R.id.tvZoomDual)
        val tvTriple = v.findViewById<TextView>(R.id.tvZoomTriple)

        fun zoomToProgress(f: Float): Int = Math.round((f - 0.5f) / 0.1f).coerceIn(0, 25)
        fun progressToZoom(p: Int): Float = (0.5f + p * 0.1f).coerceIn(0.5f, 3.0f)
        fun fmt(f: Float): String = String.format(java.util.Locale.US, "%.1f", f)

        fun bindZoom(sb: SeekBar, tv: TextView, value: Float) {
            sb.progress = zoomToProgress(value)
            tv.text = fmt(value)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    tv.text = fmt(progressToZoom(progress))
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        bindZoom(sbSingle, tvSingle, Prefs.zoomSingle(ctx))
        bindZoom(sbFs, tvFs, Prefs.zoomFullscreen(ctx))
        bindZoom(sbDual, tvDual, Prefs.zoomDual(ctx))
        bindZoom(sbTriple, tvTriple, Prefs.zoomTriple(ctx))

        // 循环录制阈值：固定容量（GB）或总容量百分比（无输入法车机，滑块调节）
        val rbLoopFixed = v.findViewById<RadioButton>(R.id.rbLoopFixed)
        val rbLoopPercent = v.findViewById<RadioButton>(R.id.rbLoopPercent)
        val sbLoop = v.findViewById<SeekBar>(R.id.sbLoop)
        val tvLoop = v.findViewById<TextView>(R.id.tvLoop)

        if (Prefs.loopMode(ctx) == Prefs.LOOP_PERCENT) rbLoopPercent.isChecked = true else rbLoopFixed.isChecked = true

        val fixedToProgress: (Int) -> Int = { it - 1 }   // 1..50 GB -> 0..49
        val progressToFixed: (Int) -> Int = { it + 1 }   // 0..49 -> 1..50 GB
        val pctToProgress: (Int) -> Int = { it - 5 }     // 5..50 %  -> 0..45
        val progressToPct: (Int) -> Int = { it + 5 }     // 0..45 -> 5..50 %

        fun refreshLoop() {
            if (rbLoopPercent.isChecked) {
                sbLoop.max = 45
                sbLoop.progress = pctToProgress(Prefs.loopPercent(ctx))
                tvLoop.text = Prefs.loopPercent(ctx).toString() + " %"
            } else {
                sbLoop.max = 49
                sbLoop.progress = fixedToProgress(Prefs.loopFixedGb(ctx))
                tvLoop.text = Prefs.loopFixedGb(ctx).toString() + " GB"
            }
        }
        refreshLoop()
        rbLoopFixed.setOnClickListener { if (!rbLoopFixed.isChecked) rbLoopFixed.isChecked = true; refreshLoop() }
        rbLoopPercent.setOnClickListener { if (!rbLoopPercent.isChecked) rbLoopPercent.isChecked = true; refreshLoop() }
        sbLoop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (rbLoopPercent.isChecked) {
                    tvLoop.text = progressToPct(progress).toString() + " %"
                } else {
                    tvLoop.text = progressToFixed(progress).toString() + " GB"
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        v.findViewById<Button>(R.id.btnZoomReset).setOnClickListener {
            bindZoom(sbSingle, tvSingle, 1.0f)
            bindZoom(sbFs, tvFs, 1.0f)
            bindZoom(sbDual, tvDual, Prefs.DEFAULT_ZOOM_DUAL)
            bindZoom(sbTriple, tvTriple, Prefs.DEFAULT_ZOOM_TRIPLE)
            rbAspect169.isChecked = true
            rbLoopFixed.isChecked = true
            refreshLoop()
        }

        val swAuto = v.findViewById<Switch>(R.id.swAutoStart)
        swAuto.isChecked = Prefs.autoStart(ctx)

        v.findViewById<Button>(R.id.btnApply).setOnClickListener {
            // 相机勾选为空（或未检测到摄像头）时保留原选择，其余设置照常保存
            val chosen = boxes.filter { it.value.isChecked }.keys
            val toSave: Collection<String> = if (chosen.isEmpty()) {
                Prefs.selectedCameras(ctx)
            } else {
                chosen
            }
            val okCameras = Prefs.setSelectedCameras(ctx, toSave)
            val okRes = Prefs.setResolution(ctx, if (rb480.isChecked) Prefs.RES_480 else Prefs.RES_720)
            val okAuto = Prefs.setAutoStart(ctx, swAuto.isChecked)

            // 画面缩放：直接取滑块值（0.5~3.0，步进 0.1）
            val okZoom = Prefs.setZoomSingle(ctx, progressToZoom(sbSingle.progress)) &&
                    Prefs.setZoomFullscreen(ctx, progressToZoom(sbFs.progress)) &&
                    Prefs.setZoomDual(ctx, progressToZoom(sbDual.progress)) &&
                    Prefs.setZoomTriple(ctx, progressToZoom(sbTriple.progress))
            val okAspect = Prefs.setAspect(
                ctx,
                if (rbAspect43.isChecked) Prefs.ASPECT_43 else Prefs.ASPECT_169
            )
            val loopMode = if (rbLoopPercent.isChecked) Prefs.LOOP_PERCENT else Prefs.LOOP_FIXED
            val okLoop = Prefs.setLoopMode(ctx, loopMode) &&
                    (if (rbLoopPercent.isChecked) Prefs.setLoopPercent(ctx, progressToPct(sbLoop.progress))
                     else Prefs.setLoopFixedGb(ctx, progressToFixed(sbLoop.progress)))

            if (okCameras && okRes && okAuto && okZoom && okAspect && okLoop) {
                Toast.makeText(ctx, "设置已保存", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "设置保存失败，请重试", Toast.LENGTH_LONG).show()
            }
            (activity as? SettingsCallback)?.onSettingsApplied()
            dismiss()
        }

        // 包一层 ScrollView，横屏高度不足时可滚动
        val scroll = ScrollView(ctx)
        scroll.addView(v)

        return AlertDialog.Builder(ctx)
            .setTitle("设置")
            .setView(scroll)
            .create()
    }
}
