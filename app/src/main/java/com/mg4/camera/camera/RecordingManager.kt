package com.mg4.camera.camera

import android.content.Context
import android.view.Surface
import com.mg4.camera.StorageManager

/**
 * 多路录制管理器：按 cameraId 管理各自的录制引擎。
 */
class RecordingManager(
    private val context: Context,
    private val storage: StorageManager,
) {
    interface Listener {
        fun onStateChanged(cameraId: String, state: CameraRecorder.State)
        fun onError(cameraId: String, message: String)
    }

    var listener: Listener? = null

    /** 所有回调统一切换到主线程：摄像头/编码线程直接改 UI 会抛 CalledFromWrongThreadException */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val recorders = LinkedHashMap<String, CameraRecorder>()

    val isRecording: Boolean
        get() = recorders.values.any { it.state == CameraRecorder.State.RECORDING || it.state == CameraRecorder.State.PAUSED }

    val isPaused: Boolean
        get() = recorders.values.any { it.state == CameraRecorder.State.PAUSED }

    private fun recorder(id: String): CameraRecorder =
        recorders.getOrPut(id) {
            CameraRecorder(context, id, storage, object : CameraRecorder.Listener {
                override fun onStateChanged(cameraId: String, state: CameraRecorder.State) {
                    mainHandler.post { listener?.onStateChanged(cameraId, state) }
                }

                override fun onError(cameraId: String, message: String) {
                    mainHandler.post { listener?.onError(cameraId, message) }
                }
            })
        }

    fun bindPreview(cameraId: String, surface: Surface) {
        try { recorder(cameraId).startPreview(surface) } catch (_: Exception) {}
    }

    /** 该路预览/编码缓冲尺寸（随分辨率设置变化） */
    fun previewSizeOf(cameraId: String): android.util.Size = try {
        recorder(cameraId).previewSize()
    } catch (_: Exception) {
        android.util.Size(1280, 720)
    }

    fun unbindPreview(cameraId: String) {
        recorders[cameraId]?.detachPreview()
    }

    fun startAll(ids: Collection<String>): Boolean {
        var any = false
        // 多路错开 300ms 依次打开：同时 open 多个摄像头在部分 HAL 上会冲突甚至崩溃
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        ids.forEachIndexed { i, id ->
            h.postDelayed({
                try { recorder(id).startRecording() } catch (e: Exception) {
                    android.util.Log.w("MG4Manager", "cam=$id start failed: ${e.message}")
                }
            }, i * 300L)
        }
        return ids.isNotEmpty()
    }

    fun stopAll() {
        recorders.values.forEach { try { it.stopRecording() } catch (_: Exception) {} }
    }

    fun pauseAll() {
        recorders.values.forEach { try { it.pauseRecording() } catch (_: Exception) {} }
    }

    fun resumeAll() {
        recorders.values.forEach { try { it.resumeRecording() } catch (_: Exception) {} }
    }

    fun releaseAll() {
        recorders.values.forEach { try { it.release() } catch (_: Exception) {} }
        recorders.clear()
    }
}
