package com.libertyshow.app

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * PlayerActivity — Native ExoPlayer/Media3 activity
 * Hardware-accelerated, supports HLS/DASH, subtitles.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )

        player     = ExoPlayer.Builder(this).build()
        playerView = PlayerView(this)
        playerView.player = player
        setContentView(playerView)

        val filePath = intent.getStringExtra("filePath")
        val fileUri  = intent.getStringExtra("fileUri")

        val uri = when {
            fileUri  != null -> Uri.parse(fileUri)
            filePath != null -> Uri.parse("file://$filePath")
            else -> { finish(); return }
        }

        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    override fun onStop()    { super.onStop();    player.release() }
    override fun onDestroy() { super.onDestroy(); player.release() }
}
