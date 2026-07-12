package tun.proxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.lonelytragedy.r1999trackerapp.Bus
import com.lonelytragedy.r1999trackerapp.MainActivity
import com.lonelytragedy.r1999trackerapp.MitmProxy
import com.lonelytragedy.r1999trackerapp.R
import tun.utils.Util

class Tun2HttpVpnService : VpnService() {

    companion object {
        const val ACTION_ARM = "com.lonelytragedy.r1999trackerapp.VPN_ARM"
        const val ACTION_ENABLE = "com.lonelytragedy.r1999trackerapp.VPN_ENABLE"
        const val ACTION_STOP = "com.lonelytragedy.r1999trackerapp.VPN_STOP"
        private const val PROXY_PORT = 8080
        private const val NOTIF_ID = 2
        private const val FOUND_ID = 3
        private const val CHANNEL_ID = "vpn"
        private const val CHANNEL_FOUND = "found_link"

        init {
            System.loadLibrary("tun2http")
        }
    }

    private var vpn: ParcelFileDescriptor? = null
    private var proxy: MitmProxy? = null
    private var capturing = false
    private var stopping = false
    private val handler = Handler(Looper.getMainLooper())

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
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ENABLE -> enable()
            else -> arm()
        }
        return START_STICKY
    }

    private fun arm() {
        capturing = false
        stopping = false
        Bus.vpnRunning = true
        Bus.emitState()
        startForegroundCompat(armedNotification())
        Bus.logLine("VPN armed — waiting for Start VPN")
    }

    private fun enable() {
        if (capturing) return
        try {
            startProxy()
            val pfd = build().establish() ?: throw IllegalStateException("establish() returned null")
            vpn = pfd
            jni_start(pfd.fd, false, 3, "127.0.0.1", PROXY_PORT)
            capturing = true
            Bus.vpnRunning = true
            Bus.emitState()
            notifyForeground(capturingNotification())
            Bus.logLine("VPN capture started")
        } catch (ex: Throwable) {
            Bus.logLine("VPN start failed: ${ex.message ?: ex.javaClass.simpleName}")
            stopEverything()
            stopSelf()
        }
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
        capturing = false
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
        Bus.pendingImportUrl = url
        Bus.emitUrl(url)
        vibrate()
        getSystemService(NotificationManager::class.java).notify(FOUND_ID, foundNotification())
        if (!stopping) {
            stopping = true
            Bus.logLine("link captured — stopping VPN")
            handler.postDelayed({
                stopEverything()
                stopSelf()
            }, 1500)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FOUND, getString(R.string.notif_channel_found), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun serviceIntent(action: String, req: Int): PendingIntent {
        val i = Intent(this, Tun2HttpVpnService::class.java).setAction(action)
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openAppIntent(req: Int, importLink: Boolean = false): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (importLink) i.putExtra(MainActivity.EXTRA_IMPORT_LINK, true)
        return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun vibrate() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            vib.vibrate(android.os.VibrationEffect.createOneShot(250, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun baseBuilder(text: String, icon: Int): Notification.Builder {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(openAppIntent(0))
    }

    private fun armedNotification(): Notification {
        return baseBuilder(getString(R.string.notif_armed), android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_play, getString(R.string.action_start_vpn), serviceIntent(ACTION_ENABLE, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_cancel), serviceIntent(ACTION_STOP, 2))
            .build()
    }

    private fun capturingNotification(): Notification {
        return baseBuilder(getString(R.string.notif_capturing), android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), serviceIntent(ACTION_STOP, 2))
            .build()
    }

    private fun foundNotification(): Notification {
        return Notification.Builder(this, CHANNEL_FOUND)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_found))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(3, importLink = true))
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyForeground(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }
}
