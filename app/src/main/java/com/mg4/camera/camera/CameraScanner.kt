package com.mg4.camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata

/**
 * 摄像头自动侦测：枚举系统所有 cameraId、朝向、传感器方向、是否需要镜像。
 */
object CameraScanner {

    data class Info(
        val id: String,
        val label: String,
        /** 传感器方向角（0/90/180/270），用于预览画面旋转校正 */
        val sensorOrientation: Int,
        /** 前置摄像头需要水平镜像 */
        val mirror: Boolean,
    )

    /** 查询单个摄像头的方向/镜像信息（预览变换的兜底来源） */
    fun infoOf(context: Context, id: String): Info? {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val c = mgr.getCameraCharacteristics(id)
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            val label = when (facing) {
                CameraMetadata.LENS_FACING_BACK -> "朝外(前视)"
                CameraMetadata.LENS_FACING_FRONT -> "朝内(舱内)"
                CameraMetadata.LENS_FACING_EXTERNAL -> "外置"
                else -> "未知"
            }
            Info(id, label, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                facing == CameraMetadata.LENS_FACING_FRONT)
        } catch (_: Exception) {
            null
        }
    }

    fun detect(context: Context): List<Info> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // 候选 ID：cameraIdList 为主，另逐个探测 0~7 兜底——
        // 部分车机 BSP HAL 不在 cameraIdList 中列全所有摄像头
        val candidates = LinkedHashSet<String>()
        try {
            mgr.cameraIdList.forEach { candidates.add(it) }
        } catch (_: Exception) {
        }
        for (i in 0..7) candidates.add(i.toString())

        val out = ArrayList<Info>()
        for (id in candidates) {
            val c = try {
                mgr.getCameraCharacteristics(id)
            } catch (_: Exception) {
                continue // 该 ID 不存在或不对第三方开放（如手机上隐藏的辅助摄像头）
            }
            try {
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                val label = when (facing) {
                    CameraMetadata.LENS_FACING_BACK -> "朝外(前视)"
                    CameraMetadata.LENS_FACING_FRONT -> "朝内(舱内)"
                    CameraMetadata.LENS_FACING_EXTERNAL -> "外置"
                    else -> "未知"
                }
                val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val mirror = facing == CameraMetadata.LENS_FACING_FRONT
                out.add(Info(id, label, sensorOrientation, mirror))
            } catch (_: Exception) {
                out.add(Info(id, "未知", 0, false))
            }
        }
        out.sortBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
        android.util.Log.i("MG4Scanner", "检测到摄像头: ${out.map { it.id }}")
        return out
    }
}
