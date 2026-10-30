package com.mg4.camera.playback

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mg4.camera.R
import com.mg4.camera.StorageManager
import java.io.File

/**
 * 回放：按 cameraID 文件夹分类 -> 点击进入该路视频列表 -> 点击视频名称播放。
 * 支持多选模式：可同时勾选多个文件/文件夹批量删除（车机无返回键，提供半透明返回按钮）。
 */
class PlaybackActivity : AppCompatActivity() {

    private lateinit var adapter: MediaAdapter
    private lateinit var storage: StorageManager
    private lateinit var tvTitle: TextView
    private lateinit var btnEdit: ImageButton
    private lateinit var btnDelete: ImageButton
    private var currentDir: File? = null

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (adapter.selectionMode) {
                adapter.setSelectionMode(false)
                updateHeader()
            } else if (currentDir != null) {
                currentDir = null
                showFolders()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        storage = StorageManager(this)
        tvTitle = findViewById(R.id.tvTitle)
        btnEdit = findViewById(R.id.btnEdit)
        btnDelete = findViewById(R.id.btnDelete)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = MediaAdapter { index -> onItemClick(index) }
        adapter.onSelectionChanged = { count -> updateHeader(count) }
        recycler.adapter = adapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            backCallback.handleOnBackPressed()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        btnEdit.setOnClickListener {
            adapter.setSelectionMode(!adapter.selectionMode)
            updateHeader(adapter.selected.size)
        }

        btnDelete.setOnClickListener { confirmDelete() }

        showFolders()
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

    private fun updateHeader(selectedCount: Int = adapter.selected.size) {
        if (adapter.selectionMode) {
            tvTitle.text = if (selectedCount > 0) "已选 $selectedCount 项（点条目勾选/取消）"
            else "多选模式：点条目勾选要删除的项"
            btnDelete.visibility = View.VISIBLE
            btnEdit.alpha = 0.95f
        } else {
            btnDelete.visibility = View.GONE
            btnEdit.alpha = 0.55f
            if (currentDir == null) tvTitle.setText(R.string.select_camera_folder)
            else tvTitle.text = "CAM ${currentDir?.name} 的录像"
        }
    }

    private fun showFolders() {
        updateHeader(0)
        val dirs = storage.listCameraDirs()
        if (dirs.isEmpty()) {
            adapter.submit(listOf(Pair("（当前存储位置暂无录像文件夹）", null as File?)))
        } else {
            adapter.submit(dirs.map { Pair<String, File?>("📁 ${it.name}", it) })
        }
    }

    private fun showFiles(dir: File) {
        updateHeader(0)
        val files = storage.listVideos(dir)
        if (files.isEmpty()) {
            adapter.submit(listOf(Pair("（该摄像头暂无录像）", null as File?)))
        } else {
            // 文件名即 "年月日-ID-序号"，直接按名称展示
            adapter.submit(files.map { Pair<String, File?>("🎬 ${it.name}", it) })
        }
    }

    private fun onItemClick(index: Int) {
        val doc = adapter.itemAt(index)?.second ?: return
        if (currentDir == null) {
            currentDir = doc
            showFiles(doc)
        } else {
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra(PlayerActivity.EXTRA_PATH, doc.absolutePath)
            intent.putExtra(PlayerActivity.EXTRA_NAME, doc.name)
            startActivity(intent)
        }
    }

    /** 多选删除：文件直接删，文件夹递归删除，二次确认防误删 */
    private fun confirmDelete() {
        val targets = adapter.selected.toList()
        if (targets.isEmpty()) {
            Toast.makeText(this, "请先勾选要删除的文件或文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        val fileCount = targets.count { it.isFile }
        val dirCount = targets.count { it.isDirectory }
        val desc = buildString {
            append("将删除 ")
            if (fileCount > 0) append("$fileCount 个视频文件")
            if (fileCount > 0 && dirCount > 0) append(" 和 ")
            if (dirCount > 0) append("$dirCount 个文件夹（含其中全部录像）")
            append("，删除后不可恢复，确定吗？")
        }
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage(desc)
            .setPositiveButton("删除") { _, _ ->
                var ok = 0
                var fail = 0
                targets.forEach { f ->
                    val done = if (f.isDirectory) f.deleteRecursively() else f.delete()
                    if (done) ok++ else fail++
                }
                if (fail > 0) {
                    Toast.makeText(this, "已删除 $ok 项，$fail 项删除失败（可能正在录制或只读）",
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "已删除 $ok 项", Toast.LENGTH_SHORT).show()
                }
                adapter.setSelectionMode(false)
                // 刷新当前视图
                val dir = currentDir
                if (dir != null && dir.isDirectory) showFiles(dir) else {
                    currentDir = null
                    showFolders()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
