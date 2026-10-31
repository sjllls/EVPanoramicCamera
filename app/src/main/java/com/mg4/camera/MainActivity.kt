package com.mg4.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.mg4.camera.camera.CameraRecorder
import com.mg4.camera.camera.CameraScanner
import com.mg4.camera.camera.RecordingManager
import com.mg4.camera.service.RecordingService
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), RecordingManager.Listener, SettingsCallback {

    companion object {
        /**
         * 旋转校准量：标准公式为 (sensor - display)。
         * 车机实测（2026-10-30）画面需再顺时针旋转 90°，故校准量由 270 修正为 0。
         * 若个别机型仍偏转，可微调此值（+90 为顺时针 90°）。
         */
        const val ROTATION_CALIBRATION = 0
    }

    private lateinit var storage: StorageManager
    private lateinit var manager: RecordingManager

    private val previewViews = LinkedHashMap<String, TextureView>()
    private val recDots = LinkedHashMap<String, View>()
    private val labels = LinkedHashMap<String, TextView>()

    /** 每路摄像头的元数据（传感器方向 / 是否镜像），用于预览变换 */
    private val camMeta = HashMap<String, CameraScanner.Info>()

    private lateinit var btnRecord: ImageButton
    private lateinit var btnPause: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var tvStatus: TextView

    private val selectedIds: List<String> get() = Prefs.selectedCameras(this)

    // ---------------- 全屏预览 ----------------
    private lateinit var fsOverlay: View
    private lateinit var fsPreview: TextureView
    private lateinit var fsLabel: TextView
    private lateinit var fsRecDot: View
    private var fullscreenId: String? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                initIfNeeded()
            } else {
                Toast.makeText(this, R.string.need_camera_permission, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        storage = StorageManager(this)
        manager = RecordingManager(this, storage)
        manager.listener = this

        tvStatus = findViewById(R.id.tvStatus)
        tvStatus.text = ""
        btnRecord = findViewById(R.id.btnRecord)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                SettingsSheet().show(supportFragmentManager, "settings")
            } else {
                requestPermissions()
            }
        }

        findViewById<ImageButton>(R.id.btnPlayback).setOnClickListener {
            // 进入回放前先停止录制
            if (manager.isRecording) stopRecording()
            startActivity(Intent(this, com.mg4.camera.playback.PlaybackActivity::class.java))
        }

        btnRecord.setOnClickListener {
            if (!manager.isRecording) startRecording() else stopRecording()
        }
        btnPause.setOnClickListener {
            if (manager.isPaused) {
                manager.resumeAll()
                tvStatus.setText(R.string.recording)
            } else if (manager.isRecording) {
                manager.pauseAll()
                tvStatus.setText(R.string.paused)
            }
        }
        btnStop.setOnClickListener { stopRecording() }

        initFullscreenOverlay()
        requestPermissions()
    }

    // -------------------------------------------------- 标题旁实时时间

    private val clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockFormat = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", java.util.Locale.US)
    private val clockTick = object : Runnable {
        override fun run() {
            findViewById<android.widget.TextView>(R.id.tvClock)?.text =
                clockFormat.format(java.util.Date())
            clockHandler.postDelayed(this, 1000L)
        }
    }

    override fun onResume() {
        super.onResume()
        clockTick.run()
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockTick)
        super.onPause()
    }

    /** 车机无手势返回/返回键：隐藏状态栏与导航栏，实现全屏沉浸 */
    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** 初始化全屏预览层（点击某个预览格进入） */
    private fun initFullscreenOverlay() {
        fsOverlay = findViewById(R.id.fullscreenOverlay)
        fsPreview = findViewById(R.id.fsPreview)
        fsLabel = findViewById(R.id.fsLabel)
        fsRecDot = findViewById(R.id.fsRecDot)

        fsPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val id = fullscreenId ?: return
                val ps = manager.previewSizeOf(id)
                st.setDefaultBufferSize(ps.width, ps.height)
                applyTransformIfNeeded(fsPreview, id)
                manager.bindPreview(id, Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                fullscreenId?.let { applyTransformIfNeeded(fsPreview, it) }
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                fullscreenId?.let {
                    manager.unbindPreview(it)
                    appliedTransformFor.remove("${it}#${System.identityHashCode(fsPreview)}")
                }
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                fullscreenId?.let { applyTransformIfNeeded(fsPreview, it) }
            }
        }

        findViewById<ImageButton>(R.id.btnFsBack).setOnClickListener { exitFullscreen() }
    }

    /** 进入某路全屏预览 */
    private fun enterFullscreen(id: String) {
        if (fullscreenId == id && fsOverlay.visibility == View.VISIBLE) return
        // 若已在别的路全屏，先把旧路切回网格
        val prevId = fullscreenId
        fullscreenId = id
        if (prevId != null && prevId != id) rebindGridPreview(prevId)
        fsLabel.text = "CAM $id"
        fsRecDot.visibility = recDots[id]?.visibility ?: View.GONE
        fsOverlay.visibility = View.VISIBLE
        // 网格里该路的 TextureView 仍持有 Surface，但会话将切到全屏 Surface；
        // 全屏 TextureView 就绪后在 onSurfaceTextureAvailable 里绑定
        if (fsPreview.isAvailable) {
            val st = fsPreview.surfaceTexture ?: return
            val ps = manager.previewSizeOf(id)
            st.setDefaultBufferSize(ps.width, ps.height)
            applyPreviewTransform(fsPreview, id)
            manager.bindPreview(id, Surface(st))
        }
    }

    /** 退出全屏，把该路画面切回网格 TextureView */
    private fun exitFullscreen() {
        val id = fullscreenId ?: return
        fullscreenId = null
        fsOverlay.visibility = View.GONE
        rebindGridPreview(id)
    }

    /** 把某路画面重新绑定到网格里的 TextureView */
    private fun rebindGridPreview(id: String) {
        val tv = previewViews[id] ?: return
        val st = tv.surfaceTexture ?: return
        if (tv.isAvailable) {
            val ps = manager.previewSizeOf(id)
            st.setDefaultBufferSize(ps.width, ps.height)
            applyPreviewTransform(tv, id)
            manager.bindPreview(id, Surface(st))
        }
    }

    private fun requestPermissions() {
        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)

        if (needed.isEmpty()) {
            initIfNeeded()
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    private fun initIfNeeded() {
        // 采集每路摄像头的传感器方向 / 镜像信息
        camMeta.clear()
        CameraScanner.detect(this).forEach { camMeta[it.id] = it }
        buildPreviews()
        // 启动时不再强制设置存储位置；仅在开始录制时提示
        maybeAutoStart()
    }

    private fun buildPreviews() {
        manager.stopAll()
        manager.releaseAll()
        // 重建会重置全部会话，全屏层一并复位
        fullscreenId = null
        fsOverlay.visibility = View.GONE
        val grid = findViewById<GridLayout>(R.id.gridPreviews)
        grid.removeAllViews()
        previewViews.clear()
        recDots.clear()
        labels.clear()

        val ids = selectedIds
        if (ids.isEmpty()) {
            Toast.makeText(this, "请在右上角设置中选择摄像头", Toast.LENGTH_SHORT).show()
            updateButtons()
            return
        }
        val density = resources.displayMetrics.density
        for (id in ids) {
            val v = layoutInflater.inflate(R.layout.item_preview, grid, false)
            val lp = GridLayout.LayoutParams()
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.width = 0
            lp.height = 0
            val m = (3 * density).toInt()
            lp.setMargins(m, m, m, m)
            v.layoutParams = lp

            val tv = v.findViewById<TextureView>(R.id.preview)
            val dot = v.findViewById<View>(R.id.recDot)
            val label = v.findViewById<TextView>(R.id.label)
            label.text = "CAM $id"
            label.setTextColor(0xFFB0BEC5.toInt())
            grid.addView(v)
            previewViews[id] = tv
            recDots[id] = dot
            labels[id] = label

            // 点击预览格 -> 该路全屏预览
            v.setOnClickListener { enterFullscreen(id) }

            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    // 该路正处于全屏时，画面由全屏 TextureView 持有，网格不抢占
                    if (fullscreenId == id) return
                    val ps = manager.previewSizeOf(id)
                    st.setDefaultBufferSize(ps.width, ps.height)
                    applyTransformIfNeeded(tv, id)
                    manager.bindPreview(id, Surface(st))
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    if (fullscreenId == id) return
                    applyTransformIfNeeded(tv, id)
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    manager.unbindPreview(id)
                    appliedTransformFor.remove("${id}#${System.identityHashCode(tv)}")
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    // 布局与 Surface 就绪时序不确定，逐帧做尺寸守卫，保证变换最终收敛
                    if (fullscreenId != id) applyTransformIfNeeded(tv, id)
                }
            }

            // 视图尺寸变化（布局完成/格子重排）时立即重应用变换
            tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (fullscreenId != id) applyTransformIfNeeded(tv, id)
            }
        }
        // 布局完成后按 16:9 计算每格尺寸（数量多时自动缩放到一页内）。
        // doOnLayout 处理首次布局；grid.post 保证"应用设置"等重复构建时必然重算
        //（grid 已布局后 doOnLayout 不再触发）。
        grid.doOnLayout { layoutPreviews() }
        grid.post { layoutPreviews() }
        updateButtons()
    }

    /**
     * 预览变换：按摄像头传感器方向旋转 + 前置镜像 + 缩放。
     * 全屏/单路 contain 完整显示；双路/三路 cover 按档位裁掉鱼眼黑边。
     * @return false 表示缺少摄像头元数据，未能应用（调用方应稍后重试）
     */
    private fun applyPreviewTransform(tv: TextureView, id: String): Boolean {
        // 元数据兜底：camMeta 缺失时直接查 CameraManager（此前曾在部分多路组合下
        // 因元数据缺失导致变换从未生效、画面按原始缓冲拉伸、鱼眼黑边全部外露）
        val meta = camMeta.getOrPut(id) {
            com.mg4.camera.camera.CameraScanner.infoOf(this, id) ?: return false
        }
        val vw = tv.width.toFloat()
        val vh = tv.height.toFloat()
        if (vw <= 0f || vh <= 0f) return false

        val ps = manager.previewSizeOf(id)
        val bufferW = ps.width.toFloat()
        val bufferH = ps.height.toFloat()
        @Suppress("DEPRECATION")
        val displayRot = windowManager.defaultDisplay.rotation * 90
        // 画面相对当前显示方向需要旋转的角度。
        // 标准公式为 (sensor - display)，车机实测需顺时针修正 90°（校准量 0）；
        // 若个别机型仍偏转，可微调 ROTATION_CALIBRATION。
        val rot = ((meta.sensorOrientation - displayRot + ROTATION_CALIBRATION) % 360 + 360) % 360

        // 旋转后内容的有效宽高
        val swapped = rot == 90 || rot == 270
        val contentW = if (swapped) bufferH else bufferW
        val contentH = if (swapped) bufferW else bufferH

        // 缩放策略（分档倍率在设置中由用户独立调节，Prefs 默认：
        // 单路 1.0 / 全屏 1.0 / 双路 1.2 / 三路 1.8）：
        //   全屏/单路 → contain（完整显示缓冲）为基准；双路/三路 → cover（铺满）为基准
        val n = max(previewViews.size, 1)
        val contain = fullscreenId == id || n <= 1
        val zoom = when {
            fullscreenId == id -> Prefs.zoomFullscreen(this)
            n <= 1 -> Prefs.zoomSingle(this)
            n == 2 -> Prefs.zoomDual(this)
            else -> Prefs.zoomTriple(this)
        }
        android.util.Log.i("MG4Preview", "cam=$id sensor=${meta.sensorOrientation} display=$displayRot rot=$rot buf=${bufferW}x$bufferH view=${vw}x$vh n=$n contain=$contain zoom=$zoom")

        val matrix = android.graphics.Matrix()
        val cx = vw / 2f
        val cy = vh / 2f
        // contain（min）：完整显示整个缓冲，绝不裁切画面；
        // cover（max）：铺满格子，再按档位放大裁掉鱼眼黑边（绝不拉伸）
        val base = if (contain) min(vw / contentW, vh / contentH) else max(vw / contentW, vh / contentH)
        val scale = base * zoom
        matrix.setScale(scale, scale, cx, cy)
        matrix.postRotate(rot.toFloat(), cx, cy)
        if (meta.mirror) matrix.preScale(-1f, 1f, cx, cy) // 视图空间水平镜像（旋转后仍为左右翻转）
        tv.setTransform(matrix)
        return true
    }

    /** 已应用变换的视图尺寸标记（tag -> "宽x高"），避免每帧重复计算 */
    private val appliedTransformFor = HashMap<String, String>()

    /**
     * 仅在视图尺寸变化时应用变换。
     * 布局与 Surface 就绪的时序在多路摄像头下不确定（曾导致 3 路小窗变换未生效、
     * 黑边偏大），故在每一帧回调里做尺寸守卫重应用；仅当变换真正成功时才记录
     * 标记，失败（元数据缺失等）下一帧自动重试，保证最终收敛。
     */
    private fun applyTransformIfNeeded(tv: TextureView, id: String): Boolean {
        if (tv.width <= 0 || tv.height <= 0) return false
        val key = "${tv.width}x${tv.height}"
        val tag = "${id}#${System.identityHashCode(tv)}"
        if (appliedTransformFor[tag] == key) return true
        val ok = applyPreviewTransform(tv, id)
        if (ok) appliedTransformFor[tag] = key
        return ok
    }

    /**
     * 计算 16:9 预览格布局：
     * 每格严格 16:9，在可用区域内选择能容纳的最大格径向方案（列×行），
     * 格子在单元格内居中，保证全部显示在一页内且画面不变形。
     */
    private fun layoutPreviews() {
        val grid = findViewById<GridLayout>(R.id.gridPreviews) ?: return
        val w = grid.width
        val h = grid.height
        if (w == 0 || h == 0) return
        val n = previewViews.size
        if (n == 0) return

        val density = resources.displayMetrics.density
        val margin = (3 * density).toInt()

        var bestCols = 1
        var bestW = 0
        var bestH = 0
        var bestArea = -1f
        for (cols in 1..n) {
            val rows = ceil(n.toDouble() / cols).toInt()
            // 只考虑"无空洞"排布（整除或单行），避免出现
            // "最后一行只有 1 格、右侧大面积空白"的难看布局（如 3 路 2x2）
            if (rows != 1 && cols * rows != n) continue
            val maxW = (w - margin * 2 * cols).toFloat() / cols
            val maxH = (h - margin * 2 * rows).toFloat() / rows
            if (maxW <= 0f || maxH <= 0f) continue
            // 在 maxW × maxH 内取最大 16:9 格
            val ih = minOf(maxH, maxW * 9f / 16f)
            val iw = ih * 16f / 9f
            val area = iw * ih * n // n 个格子的总有效画面面积
            if (area > bestArea) {
                bestArea = area
                bestCols = cols
                bestH = ih.toInt()
                bestW = iw.toInt()
            }
        }
        if (bestW <= 0 || bestH <= 0) return

        // 关键：GridLayout 的列数必须与选中方案一致（XML 里写死 2 会导致多出的格子
        // 被挤到下一行、超出容器被裁剪成扁条）
        grid.columnCount = bestCols
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val lp = child.layoutParams as? GridLayout.LayoutParams ?: continue
            lp.width = bestW
            lp.height = bestH
            lp.setMargins(margin, margin, margin, margin)
            lp.setGravity(Gravity.CENTER)
            child.layoutParams = lp
        }
        grid.requestLayout()
        // 格子尺寸变化后重新应用画面变换（旋转/缩放依赖视图尺寸）。
        // requestLayout 是异步的，post 到下一轮布局完成后再计算；
        // 之后仍由 onSurfaceTextureUpdated 的守卫逐帧兜底收敛
        appliedTransformFor.clear()
        grid.post {
            previewViews.forEach { (id, tv) ->
                if (fullscreenId != id) applyTransformIfNeeded(tv, id)
            }
        }
    }

    // -------------------------------------------------- App 内存储位置选择

    /**
     * 存储位置选择：内建文件夹浏览器。
     * 顶层直接列出所有已挂载存储卷（自动识别外置硬盘，UUID 随机变化无影响），
     * 支持进入子目录、返回上级、当前目录下新建文件夹，并提供手动输入路径兜底。
     * 注意：部分系统目录（如 /storage）受限制无法 listFiles 枚举，
     * 故顶层用 StorageManager.listVolumes() 的实时枚举结果，不依赖 File.listFiles。
     * 选定后实际使用 <所选目录>/Carsight 作为录像根目录。
     */
    private fun showStorageDialog() {
        val ctx = this

        // 当前浏览目录；null = 顶层（列出所有挂载卷）
        var current: File? = null
        var navRows: List<Pair<String, File?>> = emptyList()

        val listView = android.widget.ListView(ctx).apply {
            // 显式深色背景：即使系统为浅色模式，对话框内列表也保持深色可读
            setBackgroundColor(0xFF1A1F26.toInt())
            divider = null
        }
        val adapter = android.widget.ArrayAdapter<String>(ctx, R.layout.item_dialog_row)
        listView.adapter = adapter

        val tvPath = TextView(ctx).apply {
            setPadding(64, 24, 64, 16)
            setTextIsSelectable(false)
            setTextColor(0xFFFFFFFF.toInt())
        }

        fun freeBytesOf(f: File): Long = try {
            android.os.StatFs(f.absolutePath).availableBytes
        } catch (_: Exception) {
            f.usableSpace
        }

        fun dirsOf(d: File): List<File> = try {
            d.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "self" }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        fun refresh() {
            adapter.clear()
            val rows = ArrayList<Pair<String, File?>>()
            val cur = current
            if (cur == null) {
                tvPath.text = "请选择存储设备"
                val vols = storage.listVolumes()
                if (vols.isEmpty()) {
                    rows.add("（未检测到存储设备，长按本行下方路径可手动输入）" to null)
                }
                vols.forEach { vol ->
                    rows.add("▸  ${vol.label}  ${vol.path}（可用 ${Formatter.formatFileSize(ctx, vol.freeBytes)}）"
                            to File(vol.path))
                }
            } else {
                tvPath.text = "当前：${cur.absolutePath}" +
                        "\n可用空间 ${Formatter.formatFileSize(ctx, freeBytesOf(cur))}"
                val parent = cur.parentFile
                if (parent != null && cur.absolutePath != "/") {
                    rows.add("←  返回上级" to null)
                }
                var dirs = dirsOf(cur)
                // 系统限制目录（/、/storage、/mnt）可能只能列出不完整内容：
                // 无条件并入所有挂载卷，确保外置硬盘一定可见
                if (cur.absolutePath in listOf("/", "/storage", "/mnt")) {
                    storage.listVolumes()
                        .filter { it.path.startsWith(cur.absolutePath) || cur.absolutePath == "/" }
                        .forEach { vol ->
                            if (dirs.none { it.absolutePath == File(vol.path).absolutePath }) {
                                dirs = dirs + File(vol.path)
                            }
                        }
                }
                dirs.forEach { rows.add("▸  ${it.name}" to it) }
                if (rows.size == if (parent != null && cur.absolutePath != "/") 1 else 0) {
                    rows.add("（无子文件夹）" to null)
                }
            }
            navRows = rows
            rows.forEach { adapter.add(it.first) }
            adapter.notifyDataSetChanged()
        }

        fun goUp() {
            val cur = current ?: return
            val p = cur.parentFile
            current = when {
                p == null -> null
                p.absolutePath == "/" -> null
                else -> p
            }
            refresh()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val row = navRows.getOrNull(position) ?: return@setOnItemClickListener
            if (row.first.startsWith("←")) {
                goUp()
            } else if (row.second != null) {
                current = row.second
                refresh()
            }
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("选择录像存储位置")
            .setView(ScrollView(ctx).apply {
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(tvPath)
                    addView(listView, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 600))
                })
            })
            .setPositiveButton("使用此文件夹") { _, _ ->
                val dir = current
                if (dir == null) {
                    Toast.makeText(ctx, "请先进入要使用的文件夹", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                storage.setSelectedPath(dir.absolutePath)
                if (storage.hasStorage()) {
                    Toast.makeText(ctx, "录像将保存到：${storage.selectedPath()}", Toast.LENGTH_LONG).show()
                    maybeAutoStart()
                } else {
                    Toast.makeText(ctx, "该路径不可写，请检查硬盘是否挂载", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("新建文件夹", null)
            .create()

        // 中性按钮默认会关闭对话框，这里拦截：新建后停留在浏览器内
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val dir = current
                if (dir == null) {
                    Toast.makeText(ctx, "请先进入一个存储设备再新建文件夹", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val input = EditText(ctx).apply {
                    hint = "新文件夹名称"
                    isSingleLine = true
                    setTextColor(0xFFFFFFFF.toInt())
                    setHintTextColor(0xFF90A4AE.toInt())
                }
                AlertDialog.Builder(ctx)
                    .setTitle("在 ${dir.name} 下新建文件夹")
                    .setView(input)
                    .setPositiveButton("创建") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) return@setPositiveButton
                        val nd = File(dir, name)
                        if (nd.isDirectory) {
                            Toast.makeText(ctx, "文件夹已存在", Toast.LENGTH_SHORT).show()
                            current = nd
                            refresh()
                        } else if (nd.mkdirs()) {
                            Toast.makeText(ctx, "已创建：${nd.absolutePath}", Toast.LENGTH_SHORT).show()
                            current = nd
                            refresh()
                        } else {
                            Toast.makeText(ctx, "创建失败：该位置不可写", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        // 手动输入路径兜底（长按路径文字进入，避免干扰新手用户）
        tvPath.setOnLongClickListener {
            val input = EditText(ctx).apply {
                hint = "例如 /storage/1F13-3D4D"
                isSingleLine = true
                setText(current?.absolutePath ?: "")
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF90A4AE.toInt())
            }
            AlertDialog.Builder(ctx)
                .setTitle("手动输入路径")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val p = input.text.toString().trim()
                    if (p.isNotEmpty()) {
                        val f = File(p)
                        if (f.isDirectory) {
                            current = f
                            refresh()
                        } else Toast.makeText(ctx, "目录不存在：$p", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        dialog.show()
        refresh()
    }

    // -------------------------------------------------- 录制控制

    private fun startRecording() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "请在设置中选择摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        if (!storage.hasStorage()) {
            Toast.makeText(this, "请先设置录像存储位置", Toast.LENGTH_SHORT).show()
            showStorageDialog()
            return
        }
        manager.startAll(selectedIds)
        RecordingService.start(this, true)
        tvStatus.setText(R.string.recording)
        updateButtons()
    }

    private fun stopRecording() {
        manager.stopAll()
        RecordingService.start(this, false)
        tvStatus.text = ""
        updateButtons()
    }

    private fun maybeAutoStart() {
        if (Prefs.autoStart(this) && !manager.isRecording && selectedIds.isNotEmpty() && storage.hasStorage()) {
            startRecording()
        }
    }

    private fun updateButtons() {
        val recording = manager.isRecording
        btnRecord.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_play_circle)
        btnPause.alpha = if (recording) 0.9f else 0.35f
        btnStop.alpha = if (recording) 0.9f else 0.35f
    }

    // ---------------- RecordingManager.Listener ----------------

    override fun onStateChanged(cameraId: String, state: CameraRecorder.State) {
        val vis = if (state == CameraRecorder.State.RECORDING) View.VISIBLE else View.GONE
        recDots[cameraId]?.visibility = vis
        // 全屏层同步录制指示灯
        if (cameraId == fullscreenId && ::fsRecDot.isInitialized) {
            fsRecDot.visibility = vis
        }
        updateButtons()
    }

    override fun onError(cameraId: String, message: String) {
        // 回调可能来自摄像头/编码线程，必须切回主线程才能更新 UI
        runOnUiThread {
            Toast.makeText(this, "CAM $cameraId: $message", Toast.LENGTH_SHORT).show()
            // 在对应预览格上标注错误状态，其它路不受影响
            labels[cameraId]?.let {
                it.text = "CAM $cameraId ⚠"
                it.setTextColor(0xFFFF5252.toInt())
            }
        }
    }

    // ---------------- SettingsCallback ----------------

    override fun onSettingsApplied() {
        buildPreviews()
        maybeAutoStart()
    }

    override fun onStorageChangeRequested() {
        showStorageDialog()
    }

    override fun onDestroy() {
        manager.releaseAll()
        RecordingService.start(this, false)
        super.onDestroy()
    }
}
