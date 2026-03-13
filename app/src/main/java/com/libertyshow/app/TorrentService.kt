package com.libertyshow.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * TorrentService — Foreground service that keeps downloads alive
 * when the app is backgrounded.
 * Integrates with TorrentStream-Android library.
 */
class TorrentService : Service() {

    companion object {
        private const val CHANNEL_ID   = "liberty_downloads"
        private const val NOTIF_ID     = 101
        private val activeTorrents     = mutableMapOf<String, Any>() // id -> TorrentStream handle

        fun pause(id: String)  { /* activeTorrents[id]?.pause()  */ }
        fun resume(id: String) { /* activeTorrents[id]?.resume() */ }
        fun delete(id: String) { activeTorrents.remove(id) }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val magnet   = intent?.getStringExtra("magnet")   ?: return START_NOT_STICKY
        val savePath = intent.getStringExtra("savePath")  ?: return START_NOT_STICKY
        val id       = intent.getStringExtra("id")        ?: return START_NOT_STICKY

        startForeground(NOTIF_ID, buildNotification("Starting download..."))

        // ── TorrentStream integration point ──
        // val options = TorrentOptions.Builder()
        //     .saveLocation(savePath)
        //     .removeFilesAfterStop(false)
        //     .build()
        // val ts = TorrentStream.init(options)
        // ts.addListener(object : TorrentListener { ... })
        // ts.startStream(magnet)
        // activeTorrents[id] = ts

        return START_STICKY
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Liberty Show")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Liberty Show active downloads" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
