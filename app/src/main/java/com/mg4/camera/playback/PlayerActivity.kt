package com.mg4.camera.playback

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mg4.camera.R
import java.io.File

/**
 * 视频播放器：ExoPlayer（开源、硬件解码），支持倍速与进度条拖拽。
 * 播放本地 File 直连路径。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }

    private var player: ExoPlayer? = null
    private var speedIdx = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val playerView = findViewById<PlayerView>(R.id.playerView)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !File(path).isFile) {
            Toast.makeText(this, "无效的视频文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 车机无手势返回/返回键：提供半透明返回按钮
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            p.prepare()
            p.playWhenReady = true
        }

        val btnSpeed = findViewById<Button>(R.id.btnSpeed)
        btnSpeed.setOnClickListener {
            speedIdx = (speedIdx + 1) % SPEEDS.size
            player?.playbackParameters = PlaybackParameters(SPEEDS[speedIdx])
            btnSpeed.text = "${SPEEDS[speedIdx]}x"
        }
    }

    /** 隐藏状态栏与导航栏，实现全屏沉浸 */
    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
