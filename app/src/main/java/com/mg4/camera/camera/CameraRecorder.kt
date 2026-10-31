package com.mg4.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.media.Image
import android.media.ImageReader
import com.mg4.camera.Prefs
import com.mg4.camera.StorageManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs

/**
 * 单路摄像头录制引擎。
 * Camera2 采集 -> MediaCodec 硬件编码（Surface 零拷贝输入）-> MediaMuxer MP4 封装。
 * 支持：实时预览 / 开始 / 停止 / 暂停恢复 / 2 分钟自动分段 / 动态码率（1.2~3Mbps）。
 */
class CameraRecorder(
    private val context: Context,
    val cameraId: String,
    private val storage: StorageManager,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(cameraId: String, state: State)
        fun onError(cameraId: String, message: String)
    }

    enum class State { IDLE, PREVIEWING, RECORDING, PAUSED }

    companion object {
        private const val TAG = "MG4Recorder"
        private const val SEGMENT_US = 120_000_000L   // 单文件最大 2 分钟
        private const val FPS = 30
        private const val BITRATE_MIN = 1_200_000     // 动态码率下限
        private const val BITRATE_MAX = 3_000_000     // 码率上限 3Mbps
        private const val KEY_ROTATION = "rotation_degrees"
        private const val FRAME_ERROR_LIMIT = 15
    }

    enum class Rotation(val degrees: Int) { D0(0), D90(90), D180(180), D270(270) }

    /** 每路可单独设置录像方向，默认 0 */
    var rotation: Rotation = Rotation.D0

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    private var closing = false
    private var wantRecording = false

    // 断线自动重连：最多 3 次，指数退避；帧持续错误则彻底停用该路
    private var reconnectAttempts = 0
    private var consecutiveFrameErrors = 0
    private var disabled = false

    private var previewSurface: Surface? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var muxer: MuxerWrapper? = null
    private var drainThread: Thread? = null
    private var drainRunning = false

    private var imageReader: ImageReader? = null
    private var lastGray: IntArray? = null
    private var motion = 0f
    private var lastBitrateAdjustAt = 0L

    private var width = 1280
    private var height = 720

    var state: State = State.IDLE
        private set

    private fun bg(): Handler {
        if (bgHandler == null) {
            bgThread = HandlerThread("cam-$cameraId").also { it.start() }
            bgHandler = Handler(bgThread!!.looper)
        }
        return bgHandler!!
    }

    // ------------------------------------------------------------------ 预览

    /** TextureView 可用后绑定预览面（录制中也允许重绑，如全屏预览切换，编码流保持不变） */
    fun startPreview(surface: Surface) {
        if (closing) return
        previewSurface = surface
        if (camera == null) openCamera()
        else rebuildSession(wantRecording)
    }

    /** 解绑预览（画面销毁时），录制不受影响 */
    fun detachPreview() {
        previewSurface = null
    }

    // ------------------------------------------------------------------ 录制控制

    fun startRecording() {
        if (closing || wantRecording) return
        wantRecording = true
        if (camera == null) {
            openCamera()   // onOpened 后自动进入录制
        } else {
            if (encoder == null) startEncoder()
            rebuildSession(true)
        }
    }

    fun stopRecording() {
        wantRecording = false
        stopEncoder()
        if (camera != null) {
            rebuildSession(false)
        } else {
            setState(State.IDLE)
        }
    }

    fun pauseRecording() {
        if (state != State.RECORDING) return
        muxer?.pause()
        setState(State.PAUSED)
    }

    fun resumeRecording() {
        if (state != State.PAUSED) return
        muxer?.resume()
        setState(State.RECORDING)
    }

    fun release() {
        closing = true
        wantRecording = false
        stopEncoder()
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        reconnectAttempts = 0
        consecutiveFrameErrors = 0
        setState(State.IDLE)
    }

    // ------------------------------------------------------------------ 相机

    @SuppressLint("MissingPermission") // 权限由 MainActivity 统一申请
    private fun openCamera() {
        if (camera != null || opening || closing || disabled) return
        opening = true
        try {
            ensureAnalysisReader()
            cameraManager.openCamera(cameraId, deviceCallback, bg())
        } catch (e: Exception) {
            opening = false
            listener.onError(cameraId, "打开相机 $cameraId 失败: ${e.message}")
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            reconnectAttempts = 0
            this@CameraRecorder.camera = camera
            if (wantRecording) {
                if (encoder == null) startEncoder()
                rebuildSession(true)
            } else {
                rebuildSession(false)
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            opening = false
            try { camera.close() } catch (_: Exception) {}
            if (this@CameraRecorder.camera === camera) {
                this@CameraRecorder.camera = null
                stopEncoder()
                setState(State.IDLE)
                scheduleReconnect("断开")
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            opening = false
            try { camera.close() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            session = null
            if (this@CameraRecorder.camera === camera) {
                this@CameraRecorder.camera = null
                stopEncoder()
                setState(State.IDLE)
            }
            // 打开即错（如受限摄像头）：不重连，直接报错停用，避免错误循环
            listener.onError(cameraId, "相机 $cameraId 错误: $error")
        }
    }

    /** 断线自动重连：最多 3 次（1s/2s/4s 退避），超过则报错停用，防止错误循环拖垮其它路 */
    private fun scheduleReconnect(reason: String) {
        if (closing || disabled) return
        if (reconnectAttempts >= 3) {
            disabled = true
            android.util.Log.w(TAG, "cam=$cameraId $reason 重连超限，停用该路")
            listener.onError(cameraId, "CAM $cameraId 多次失败，已停用（可在设置中重新应用以重试）")
            return
        }
        reconnectAttempts++
        val delay = 1000L shl (reconnectAttempts - 1)
        android.util.Log.i(TAG, "cam=$cameraId $reason，${delay}ms 后第 $reconnectAttempts 次重连")
        mainHandler.postDelayed({ if (!closing && !disabled) openCamera() }, delay)
    }

    /**
     * 按当前状态重建捕获会话。
     * mode 降级策略（部分 V4L2 HAL 不支持多路 Surface 组合，配置失败时逐级精简）：
     *   mode 0：预览 + 分析流（ImageReader）+ 录制（全部）
     *   mode 1：预览 + 录制（去掉分析流）
     *   mode 2：仅录制流（放弃该路实时预览，保证录像可用）
     */
    private fun rebuildSession(record: Boolean, mode: Int = 0) {
        val cam = camera ?: return
        val h = bg()
        val surfaces = ArrayList<Surface>()
        val targets = ArrayList<Surface>()
        if (mode < 2) {
            previewSurface?.let { surfaces.add(it); targets.add(it) }
            if (mode == 0) imageReader?.surface?.let { surfaces.add(it); targets.add(it) }
        }
        if (record) encoderSurface?.let { surfaces.add(it); targets.add(it) }
        if (surfaces.isEmpty()) {
            listener.onError(cameraId, "无可用显示目标")
            return
        }
        android.util.Log.i(TAG, "cam=$cameraId rebuildSession record=$record mode=$mode surfaces=${surfaces.size}")
        try { session?.close() } catch (_: Exception) {}
        session = null
        try {
            cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera !== cam || closing) return
                    session = s
                    try {
                        val builder = cam.createCaptureRequest(
                            if (record) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                        )
                        targets.forEach { builder.addTarget(it) }
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        // 帧错误看门狗：连续 15 帧失败判定该路无有效画面（如受限摄像头只出黑帧），
                        // 彻底停用该路并关闭设备，避免拖垮其它路/引发底层异常
                        s.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: android.hardware.camera2.CaptureFailure,
                            ) {
                                consecutiveFrameErrors++
                                if (consecutiveFrameErrors == FRAME_ERROR_LIMIT) {
                                    android.util.Log.w(TAG, "cam=$cameraId 连续 $consecutiveFrameErrors 帧失败，停用该路")
                                    disabled = true
                                    try { session.device.close() } catch (_: Exception) {}
                                }
                            }

                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: android.hardware.camera2.TotalCaptureResult,
                            ) {
                                consecutiveFrameErrors = 0
                            }
                        }, h)
                        setState(if (record) State.RECORDING else State.PREVIEWING)
                    } catch (e: Exception) {
                        listener.onError(cameraId, "启动取流失败: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (camera !== cam || closing) return
                    val maxMode = if (record) 2 else 1
                    if (mode < maxMode) {
                        // 降级重试：去掉次要流再试，直到仅保留必要流
                        android.util.Log.w(TAG, "cam=$cameraId 会话配置失败(mode=$mode)，降级重试 mode=${mode + 1}")
                        rebuildSession(record, mode + 1)
                    } else {
                        listener.onError(cameraId, "会话配置失败：该摄像头不支持当前流组合")
                    }
                }
            }, h)
        } catch (e: Exception) {
            listener.onError(cameraId, "创建会话失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ 编码

    private fun targetSize(): Size {
        val target = if (Prefs.resolution(context) == Prefs.RES_480) Size(854, 480) else Size(1280, 720)
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaRecorder::class.java)
            if (sizes.isNullOrEmpty()) {
                target
            } else {
                // 画幅选择（设置中可切换）：16:9 适合 16:9 预览格铺满显示；
                // 4:3 用于部分鱼眼 HAL——完整圆画面只在 4:3 缓冲内，16:9 输出会把
                // 鱼眼圆上下裁掉（用户反馈"全屏看不全鱼眼"的根因）。
                val pool: List<Size> = if (Prefs.aspect(context) == Prefs.ASPECT_169) {
                    val wide = sizes.filter { s ->
                        val ar = s.width.toFloat() / s.height
                        ar > 1.70f && ar < 1.82f
                    }
                    if (wide.isNotEmpty()) wide else sizes.toList()
                } else {
                    sizes.toList()
                }
                pool.minByOrNull { abs(it.width * it.height - target.width * target.height) } ?: target
            }
        } catch (_: Exception) {
            target
        }
    }

    /** 预览缓冲应使用的尺寸（与编码尺寸一致，保证预览与录像画幅/方向统一） */
    fun previewSize(): Size = targetSize()

    /** 是否存在指定 MIME 的硬件视频编码器 */    private fun hasEncoderFor(mime: String, width: Int, height: Int): Boolean = try {
        val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(fmt) != null
    } catch (_: Exception) {
        false
    }

    private fun baseBitrate(): Int =
        if (Prefs.resolution(context) == Prefs.RES_480) 1_600_000 else 2_400_000

    private fun startEncoder() {
        if (encoder != null) return
        val size = targetSize()
        width = size.width
        height = size.height
        try {
            // 优先 HEVC 硬件编码（骁龙 8155/8Gen3 均支持），不可用时回退 H.264
            val mime = if (hasEncoderFor(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height))
                MediaFormat.MIMETYPE_VIDEO_HEVC
            else
                MediaFormat.MIMETYPE_VIDEO_AVC
            val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, baseBitrate())
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            val codec = MediaCodec.createEncoderByType(mime)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
            encoder = codec
            android.util.Log.i(TAG, "encoder started: mime=$mime ${width}x$height")
            muxer = MuxerWrapper(
                opener = {
                    // 循环写入：开新分段前检查剩余容量，不足 1G 自动删除各目录最早录像
                    storage.enforceFreeSpace()
                    val f = storage.createSegmentFile(cameraId)
                        ?: throw IOException("无法创建录像文件（请检查存储位置设置）")
                    // 写入 .part 临时文件，分段正常结束（muxer stop 成功）后才改名为 .mp4：
                    // 断电/死机/强杀只残留 .part（启动时清理），回放列表永远是完整可播放文件
                    val part = File(f.parentFile, f.name + StorageManager.PART_SUFFIX)
                    val fos = FileOutputStream(part)
                    val m = android.media.MediaMuxer(
                        fos.fd,
                        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    )
                    if (rotation != Rotation.D0) m.setOrientationHint(rotation.degrees)
                    MuxerWrapper.MuxerHandle(m, f.absolutePath, fos, part.absolutePath)
                },
                maxSegmentUs = SEGMENT_US
            )
            startDrainThread()
        } catch (e: Exception) {
            stopEncoder()
            listener.onError(cameraId, "编码器启动失败: ${e.message}")
        }
    }

    private fun stopEncoder() {
        drainRunning = false
        encoder?.let { c ->
            try { c.stop() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
        }
        encoder = null
        drainThread?.join(1000)
        drainThread = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
        muxer?.stop()
        muxer = null
        lastGray = null
        motion = 0f
    }

    private fun startDrainThread() {
        drainRunning = true
        drainThread = Thread {
            val info = MediaCodec.BufferInfo()
            while (drainRunning) {
                val c = encoder ?: break
                val idx = try {
                    c.dequeueOutputBuffer(info, 10_000)
                } catch (e: Exception) {
                    // 停止录制时 codec 被关闭会打断 pending dequeue，属正常现象
                    android.util.Log.d(TAG, "drain: dequeue 结束（${e.message}）")
                    break
                }
                // 编码输出/Muxer 写入异常只报废当前路，绝不让线程异常杀死整个进程
                try {
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            muxer?.onFormat(c.outputFormat)

                        idx >= 0 -> {
                            val buf = c.getOutputBuffer(idx)
                            if (buf != null && info.size > 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    muxer?.write(info, buf)
                                }
                            }
                            try { c.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "cam=$cameraId 编码输出处理失败: ${e.message}")
                    listener.onError(cameraId, "录像写入异常: ${e.message}")
                    break
                }
            }
        }.also { it.start() }
    }

    // ------------------------------------------------------------------ 动态码率（运动检测）

    private fun ensureAnalysisReader() {
        if (imageReader != null) return
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            val s = if (sizes.isNullOrEmpty()) Size(320, 240)
            else sizes.minByOrNull { abs(it.width * it.height - 320 * 240) } ?: Size(320, 240)
            imageReader = ImageReader.newInstance(s.width, s.height, ImageFormat.YUV_420_888, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                analyzeImage(img)
            }, bg())
        } catch (_: Exception) {
        }
    }

    /** 低分辨率 Y 平面帧间差分 -> 运动强度 -> 动态调整编码码率 */
    private fun analyzeImage(image: Image) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val gw = w / 8
            val gh = h / 8
            if (gw > 0 && gh > 0) {
                val gray = IntArray(gw * gh)
                for (gy in 0 until gh) {
                    val rowBase = gy * 8 * rowStride
                    for (gx in 0 until gw) {
                        gray[gy * gw + gx] = buf.get(rowBase + gx * 8 * pixelStride).toInt() and 0xFF
                    }
                }
                val last = lastGray
                if (last != null && last.size == gray.size) {
                    var sum = 0L
                    for (i in gray.indices) sum += abs(gray[i] - last[i])
                    motion = (sum.toFloat() / gray.size / 255f).coerceIn(0f, 1f)
                }
                lastGray = gray
            }
        } catch (_: Exception) {
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
        val now = System.currentTimeMillis()
        if (state == State.RECORDING && now - lastBitrateAdjustAt > 2000) {
            lastBitrateAdjustAt = now
            adjustBitrate()
        }
    }

    /** 码率 = 1.2Mbps + 运动强度 × 1.8Mbps，上限 3Mbps */
    private fun adjustBitrate() {
        val c = encoder ?: return
        val bps = (BITRATE_MIN + motion * (BITRATE_MAX - BITRATE_MIN)).toInt().coerceIn(BITRATE_MIN, BITRATE_MAX)
        try {
            val p = Bundle()
            p.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            c.setParameters(p)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------

    private fun setState(s: State) {
        if (state == s) return
        state = s
        mainHandler.post { listener.onStateChanged(cameraId, s) }
    }
}
