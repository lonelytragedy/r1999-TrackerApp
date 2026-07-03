package com.lonelytragedy.r1999trackerapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ProxyService : Service() {

    companion object {
        const val PORT = 8080
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1
    }

    private var proxy: MitmProxy? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(getString(R.string.notif_waiting))

        if (proxy == null) {
            val p = MitmProxy(PORT) { url -> onUrlCaptured(url) }
            try {
                p.start()
                proxy = p
                Bus.running = true
                Bus.emitState()
            } catch (e: Exception) {
                Bus.logLine("START FAILED: ${e.javaClass.simpleName} ${e.message ?: ""}")
                Bus.running = false
                Bus.emitState()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun onUrlCaptured(url: String) {
        copyToClipboard(url)
        Bus.emitUrl(url)
        notify(getString(R.string.notif_found))
    }

    override fun onDestroy() {
        proxy?.stop()
        proxy = null
        Bus.running = false
        Bus.emitState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("summon", text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
