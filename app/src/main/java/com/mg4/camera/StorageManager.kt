package com.mg4.camera

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 存储管理：不使用 SAF 原生选择器（Android 11 车机不稳定），
 * 全部通过 java.io.File 直连路径读写。
 *
 * - 存储位置在 App 内选择（自动侦测挂载卷 + 支持手动输入自定义路径）
 * - 兼容外置硬盘 UUID 随机变化：每次启动重新枚举 /storage 下的挂载卷
 * - 每路摄像头影像存于对应 cameraID 名称的文件夹
 * - 文件命名：纯数字 "yyyyMMdd-cameraId-序号.mp4"，序号从 1 开始
 */
class StorageManager(private val context: Context) {

    data class Volume(
        val path: String,
        val label: String,
        val writable: Boolean,
        val freeBytes: Long,
    )

    // ------------------------------------------------------------ 路径选择

    companion object {
        /** 默认录像子目录：选定存储位置后，实际使用 <所选路径>/Carsight */
        const val CARSIGHT_DIR = "Carsight"
    }

    fun selectedPath(): String? = Prefs.storagePath(context)

    /**
     * 设置存储位置。默认在所选路径下新建 Carsight 子目录作为录像根目录
     * （已有同名文件夹则直接使用，不重复创建）；若所选路径本身已叫 Carsight 则原样使用。
     */
    fun setSelectedPath(path: String) {
        val base = File(path.trim().trimEnd('/'))
        val target = if (base.name == CARSIGHT_DIR) base else File(base, CARSIGHT_DIR)
        if (!target.exists()) target.mkdirs()
        Prefs.setStoragePath(context, target.absolutePath.trimEnd('/'))
    }

    fun hasStorage(): Boolean {
        val p = selectedPath() ?: return false
        val f = File(p)
        return f.isDirectory && f.canWrite()
    }

    /** 检测某路径当前是否可写（用于新建文件夹/手动输入路径的即时校验） */
    fun isPathWritable(path: String): Boolean = testWritable(File(path))

    /** 构造指定路径的卷信息（用于"新建文件夹"后加入选择列表） */
    fun volumeFor(path: String, label: String): Volume {
        val f = File(path)
        return Volume(f.absolutePath.trimEnd('/'), label, testWritable(f), usableSpace(f))
    }

    fun root(): File? {
        val p = selectedPath() ?: return null
        val f = File(p)
        return if (f.isDirectory) f else null
    }

    /**
     * 侦测当前所有挂载的存储卷（内部存储 + 外置硬盘/USB）。
     * 兼容硬盘 UUID 随机变化：每次调用实时枚举，不做任何缓存。
     */
    fun listVolumes(): List<Volume> {
        val out = LinkedHashMap<String, Volume>()

        fun addPath(path: String, label: String) {
            if (path.isBlank()) return
            val f = File(path)
            if (!f.isDirectory) return
            val w = testWritable(f)
            val old = out[f.absolutePath.trimEnd('/')]
            // 优先保留可写的那条记录
            if (old == null || (w && !old.writable)) {
                out[f.absolutePath.trimEnd('/')] = Volume(f.absolutePath.trimEnd('/'), label, w, usableSpace(f))
            }
        }

        // 1) 通过 getExternalFilesDirs 枚举所有已挂载卷（免权限、最可靠）
        try {
            context.getExternalFilesDirs(null)?.forEach { d ->
                if (d == null) return@forEach
                val p = d.absolutePath
                val idx = p.indexOf("/Android/")
                val volPath = if (idx > 0) p.substring(0, idx) else p
                val label = if (volPath.contains("/emulated") || volPath.endsWith("/sdcard0")) {
                    "内部存储"
                } else {
                    "外置存储 ${File(volPath).name}"
                }
                addPath(volPath, label)
            }
        } catch (_: Exception) {
        }

        // 2) 兜底：扫描 /storage 下所有子目录（覆盖部分 ROM 不返回外置卷的情况）
        try {
            File("/storage").listFiles()?.forEach { d ->
                if (d.isDirectory && d.name != "self" && !d.name.startsWith("emulated")) {
                    addPath(d.absolutePath, "外置存储 ${d.name}")
                }
            }
        } catch (_: Exception) {
        }

        // 3) 主存储兜底
        try {
            Environment.getExternalStorageDirectory()?.absolutePath?.let {
                addPath(it, "内部存储")
            }
        } catch (_: Exception) {
        }

        // 4) 解析 /proc/mounts：车机 ROM 上 getExternalFilesDirs 常不返回外置盘、
        //    /storage 又受系统限制无法 listFiles，挂载表是最可靠的来源
        //    （实测本车机外置盘挂载点 /storage/1F13-3D4D，vold public:8,1）
        try {
            val fsTypes = setOf(
                "vfat", "exfat", "ntfs", "fuseblk", "texfat", "ufsd", "sdcardfs", "fuse"
            )
            File("/proc/mounts").readLines().forEach { line ->
                val parts = line.split(" ")
                if (parts.size < 3) return@forEach
                val mp = parts[1]
                val fs = parts[2]
                if (fs in fsTypes &&
                    mp.startsWith("/storage/") &&
                    !mp.contains("emulated") && !mp.contains("self")
                ) {
                    addPath(mp, "外置存储 ${mp.substringAfterLast('/')}")
                }
            }
        } catch (_: Exception) {
        }

        // 5) /mnt/media_rw 子目录兜底（部分 ROM 将外置盘真实挂载在此）
        try {
            File("/mnt/media_rw").listFiles()?.forEach { d ->
                if (d.isDirectory && !d.name.startsWith("emulated")) {
                    // 优先用 /storage/<名> 的用户可见视图
                    val visible = File("/storage", d.name)
                    addPath(
                        if (visible.isDirectory) visible.absolutePath else d.absolutePath,
                        "外置存储 ${d.name}"
                    )
                }
            }
        } catch (_: Exception) {
        }

        return out.values.toList()
    }

    private fun usableSpace(f: File): Long = try {
        StatFs(f.absolutePath).availableBytes
    } catch (_: Exception) {
        f.usableSpace
    }

    private fun testWritable(dir: File): Boolean = try {
        val t = File(dir, ".mg4_write_test")
        val ok = t.createNewFile()
        if (ok) t.delete()
        ok
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------ 录像文件

    /** 获取（或创建）某路摄像头的存储文件夹，文件夹名为 cameraId */
    fun cameraDir(cameraId: String): File? {
        val r = root() ?: return null
        val d = File(r, cameraId)
        if (!d.exists() && !d.mkdirs()) return null
        return if (d.isDirectory) d else null
    }

    /**
     * 创建下一个分段录像文件。
     * 命名：CAM<cameraId>-录制起始时间.mp4，时间精确到秒（纯数字 年-月-日-时-分-秒），
     * 如 CAM1-2026-10-30-22-55-03.mp4，文件名即实现时间追溯；
     * 定宽零填充保证按名称排序即为时间排序。同秒重名时追加 -1、-2 序号兜底。
     */
    fun createSegmentFile(cameraId: String): File? {
        val dir = cameraDir(cameraId) ?: return null
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        var f = File(dir, "CAM$cameraId-$stamp.mp4")
        var seq = 1
        while (f.exists()) {
            f = File(dir, "CAM$cameraId-$stamp-${seq++}.mp4")
        }
        return f
    }

    /** 回放：列出所有摄像头文件夹 */
    fun listCameraDirs(): List<File> =
        root()?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

    /** 回放：列出某文件夹下所有视频，按文件名（即时间）排序 */
    fun listVideos(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp4") }
            ?.sortedBy { it.name }
            ?: emptyList()
}
