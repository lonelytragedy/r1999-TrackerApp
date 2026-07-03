package com.lonelytragedy.r1999trackerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import tun.proxy.service.Tun2HttpVpnService

class MainActivity : AppCompatActivity() {

    private val trackerUrl = "https://lonelytragedy.github.io/r1999-tracker/"

    private lateinit var status: TextView
    private lateinit var proxyInfo: TextView
    private lateinit var urlView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var vpnBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var openBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var logView: TextView

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val vpnPrepare =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        proxyInfo = findViewById(R.id.proxyInfo)
        urlView = findViewById(R.id.urlView)
        toggleBtn = findViewById(R.id.toggleBtn)
        vpnBtn = findViewById(R.id.vpnBtn)
        copyBtn = findViewById(R.id.copyBtn)
        openBtn = findViewById(R.id.openBtn)
        clearBtn = findViewById(R.id.clearBtn)
        logView = findViewById(R.id.logView)

        proxyInfo.text = getString(R.string.proxy_hint, NetUtil.wifiIp(), ProxyService.PORT)

        toggleBtn.setOnClickListener { toggle() }
        vpnBtn.setOnClickListener { toggleVpn() }
        copyBtn.setOnClickListener { copyLink() }
        openBtn.setOnClickListener { openTracker() }
        clearBtn.setOnClickListener { Bus.clear() }

        Bus.listener = { url -> runOnUiThread { showUrl(url) } }
        Bus.stateListener = { runOnUiThread { refreshState() } }
        Bus.logListener = { runOnUiThread { logView.text = Bus.snapshot() } }
        logView.text = Bus.snapshot()

        maybeRequestNotifications()
        refreshState()
        Bus.lastUrl?.let { showUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        proxyInfo.text = getString(R.string.proxy_hint, NetUtil.wifiIp(), ProxyService.PORT)
        refreshState()
    }

    private fun toggle() {
        val intent = Intent(this, ProxyService::class.java)
        if (Bus.running) {
            stopService(intent)
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun toggleVpn() {
        if (Bus.vpnRunning) {
            startService(Intent(this, Tun2HttpVpnService::class.java).setAction(Tun2HttpVpnService.ACTION_STOP))
        } else {
            val prepare = VpnService.prepare(this)
            if (prepare != null) vpnPrepare.launch(prepare) else startVpn()
        }
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, Tun2HttpVpnService::class.java).setAction(Tun2HttpVpnService.ACTION_START)
        )
    }

    private fun refreshState() {
        vpnBtn.text = getString(if (Bus.vpnRunning) R.string.stop_vpn else R.string.start_vpn)
        toggleBtn.text = getString(if (Bus.running) R.string.stop else R.string.start)
        status.text = if (Bus.vpnRunning || Bus.running) {
            getString(R.string.status_waiting)
        } else {
            getString(R.string.status_idle)
        }
    }

    private fun showUrl(url: String) {
        urlView.text = url
        status.text = getString(R.string.status_found)
    }

    private fun copyLink() {
        val url = Bus.lastUrl ?: return
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("summon", url))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun openTracker() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trackerUrl)))
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
