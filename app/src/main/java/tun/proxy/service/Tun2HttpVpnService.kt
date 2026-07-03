package tun.proxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.lonelytragedy.r1999trackerapp.Bus
import com.lonelytragedy.r1999trackerapp.MitmProxy
import com.lonelytragedy.r1999trackerapp.R
import tun.utils.Util

class Tun2HttpVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.lonelytragedy.r1999trackerapp.VPN_START"
        const val ACTION_STOP = "com.lonelytragedy.r1999trackerapp.VPN_STOP"
        private const val PROXY_PORT = 8080
        private const val NOTIF_ID = 2
        private const val CHANNEL_ID = "vpn"

        init {
            System.loadLibrary("tun2http")
        }
    }

    private var vpn: ParcelFileDescriptor? = null
    private var proxy: MitmProxy? = null

    private external fun jni_init()
    private external fun jni_start(tun: Int, fwd53: Boolean, rcode: Int, proxyIp: String, proxyPort: Int)
    private external fun jni_stop(tun: Int)
    private external fun jni_get_mtu(): Int
    private external fun jni_done()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        jni_init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        try {
            startProxy()
            val pfd = build().establish() ?: throw IllegalStateException("establish() returned null")
            vpn = pfd
            jni_start(pfd.fd, false, 3, "127.0.0.1", PROXY_PORT)
            Bus.vpnRunning = true
            Bus.emitState()
            Bus.logLine("VPN capture started")
        } catch (ex: Throwable) {
            Bus.logLine("VPN start failed: ${ex.message ?: ex.javaClass.simpleName}")
            stopEverything()
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        jni_done()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopEverything()
        stopSelf()
        super.onRevoke()
    }

    private fun startProxy() {
        if (proxy == null) {
            val p = MitmProxy(PROXY_PORT) { url -> onUrl(url) }
            p.start()
            proxy = p
        }
    }

    private fun stopEverything() {
        val pfd = vpn
        if (pfd != null) {
            try { jni_stop(pfd.fd) } catch (_: Throwable) {}
            try { pfd.close() } catch (_: Throwable) {}
            vpn = null
        } else {
            try { jni_stop(-1) } catch (_: Throwable) {}
        }
        proxy?.stop()
        proxy = null
        Bus.vpnRunning = false
        Bus.emitState()
    }

    private fun build(): Builder {
        val builder = Builder()
        builder.setSession(getString(R.string.app_name))
        builder.addAddress("10.1.10.1", 32)
        builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("0:0:0:0:0:0:0:0", 0)
        for (dns in Util.getDefaultDNS(this)) {
            try { builder.addDnsServer(dns) } catch (_: Exception) {}
        }
        try { builder.addDnsServer("8.8.8.8") } catch (_: Exception) {}
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        builder.setMtu(jni_get_mtu())
        return builder
    }

    private fun onUrl(url: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("summon", url))
        Bus.emitUrl(url)
        notifyText(getString(R.string.notif_found))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification(getString(R.string.notif_waiting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyText(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
