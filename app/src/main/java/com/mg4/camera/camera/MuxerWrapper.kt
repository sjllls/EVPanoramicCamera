package com.mg4.camera.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * MP4 分段封装器。
 * - 单个文件最大时长 2 分钟（分段切换不重启编码器，无缝换文件）；
 * - 支持暂停（结束当前分段文件）与恢复（自动开新分段）；
 * - 依赖编码器输出的 MediaFormat（含 csd-0/csd-1 SPS/PPS）以在新分段直接 addTrack。
 * - 断电安全：分段写入 <路径>.part 临时文件，每 2 秒强制 sync 落盘；
 *   分段正常收尾（muxer stop 成功）才改名为最终 .mp4，异常中断只残留
 *   .part 文件（启动时清理），回放列表中出现的永远都是完整可播放的文件。
 */
class MuxerWrapper(
    private val opener: () -> MuxerHandle,
    private val maxSegmentUs: Long,
) {
    companion object {
        private const val TAG = "MG4Muxer"

        /** 每隔该时长对写入中的文件执行一次 fd.sync()，缩小断电丢数据窗口 */
        private const val SYNC_INTERVAL_US = 2_000_000L
    }

    /**
     * @param muxer    MediaMuxer 实例
     * @param path     最终对外文件名（.mp4）
     * @param out      底层文件输出流（用于周期性 sync 落盘；路径直连模式非空）
     * @param diskPath 实际写盘路径（.part 临时文件）；与 path 相同表示无改名机制
     */
    class MuxerHandle(
        val muxer: MediaMuxer,
        val path: String,
        val out: FileOutputStream? = null,
        val diskPath: String = path,
    )

    private var handle: MuxerHandle? = null
    private var track = -1
    private var format: MediaFormat? = null
    private var base = -1L
    private var lastSyncUs = -1L
    private var rotatePending = false
    private var paused = false

    var segments = 0
        private set
    var lastPath: String? = null
        private set

    /** 编码器输出格式变化（携带 SPS/PPS）时调用 */
    @Synchronized
    fun onFormat(f: MediaFormat) {
        format = f
    }

    @Synchronized
    fun write(info: MediaCodec.BufferInfo, buf: ByteBuffer) {
        if (paused) return
        if (rotatePending) {
            closeSegment()
            rotatePending = false
        }
        if (handle == null && !openSegment()) return
        if (base < 0) base = info.presentationTimeUs
        info.presentationTimeUs = info.presentationTimeUs - base
        val h = handle ?: return
        try {
            h.muxer.writeSampleData(track, buf, info)
        } catch (_: Exception) {
            return
        }
        // 周期性强制落盘：断电/死机时最多丢失最近约 2 秒的数据
        if (h.out != null && info.presentationTimeUs - lastSyncUs >= SYNC_INTERVAL_US) {
            lastSyncUs = info.presentationTimeUs
            try { h.out.fd.sync() } catch (_: Exception) {}
        }
        // 达到 2 分钟上限，下一样本切换到新文件
        if (info.presentationTimeUs >= maxSegmentUs) rotatePending = true
    }

    private fun openSegment(): Boolean {
        val f = format
        if (f == null) {
            android.util.Log.w(TAG, "openSegment: 尚未收到编码器输出格式（等待 INFO_OUTPUT_FORMAT_CHANGED）")
            return false
        }
        val h = try {
            opener()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "openSegment: 创建文件失败", e)
            return false
        }
        return try {
            track = h.muxer.addTrack(f)
            h.muxer.start()
            handle = h
            base = -1L
            lastSyncUs = -1L
            segments++
            lastPath = h.path
            android.util.Log.i(TAG, "segment opened: ${h.diskPath}")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "openSegment: addTrack/start 失败", e)
            releaseHandle(h)
            handle = null
            false
        }
    }

    private fun closeSegment() {
        handle?.let { releaseHandle(it) }
        handle = null
        track = -1
        base = -1L
    }

    private fun releaseHandle(h: MuxerHandle) {
        var ok = true
        try { h.muxer.stop() } catch (_: Exception) { ok = false }
        try { h.muxer.release() } catch (_: Exception) {}
        // 收尾前最后一次强制落盘并关闭文件句柄
        h.out?.let { o ->
            try { o.fd.sync() } catch (_: Exception) {}
            try { o.close() } catch (_: Exception) {}
        }
        if (h.diskPath != h.path) {
            val disk = File(h.diskPath)
            if (ok && disk.exists()) {
                // 正常收尾：.part 改名为最终 .mp4（同一文件系统内改名近原子）
                if (!disk.renameTo(File(h.path))) {
                    android.util.Log.w(TAG, "改名失败：${h.diskPath} -> ${h.path}")
                    disk.delete()
                }
            } else {
                // stop 失败（文件已损坏）或文件不存在：删除残留，回放列表只保留完整文件
                disk.delete()
            }
        }
    }

    /** 暂停：结束当前分段文件 */
    @Synchronized
    fun pause() {
        paused = true
        closeSegment()
    }

    /** 恢复：下一次采样自动开新分段 */
    @Synchronized
    fun resume() {
        paused = false
    }

    @Synchronized
    fun stop() {
        paused = false
        closeSegment()
    }
}
