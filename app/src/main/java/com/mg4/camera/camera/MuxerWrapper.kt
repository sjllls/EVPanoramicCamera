package com.mg4.camera.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * MP4 分段封装器。
 * - 单个文件最大时长 2 分钟（分段切换不重启编码器，无缝换文件）；
 * - 支持暂停（结束当前分段文件）与恢复（自动开新分段）；
 * - 依赖编码器输出的 MediaFormat（含 csd-0/csd-1 SPS/PPS）以在新分段直接 addTrack。
 */
class MuxerWrapper(
    private val opener: () -> MuxerHandle,
    private val maxSegmentUs: Long,
) {
    companion object {
        private const val TAG = "MG4Muxer"
    }
    class MuxerHandle(val muxer: MediaMuxer, val path: String)

    private var handle: MuxerHandle? = null
    private var track = -1
    private var format: MediaFormat? = null
    private var base = -1L
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
            segments++
            lastPath = h.path
            android.util.Log.i(TAG, "segment opened: ${h.path}")
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
        try { h.muxer.stop() } catch (_: Exception) {}
        try { h.muxer.release() } catch (_: Exception) {}
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
