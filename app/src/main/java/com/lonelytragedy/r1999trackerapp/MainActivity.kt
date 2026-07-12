package com.lonelytragedy.r1999trackerapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import org.json.JSONObject
import tun.proxy.service.Tun2HttpVpnService

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_LINK = "show_link"
        const val EXTRA_IMPORT_LINK = "import_link"
    }

    private val trackerUrl = "https://lonelytragedy.github.io/r1999-tracker/"
    private val trackerHost = "lonelytragedy.github.io"
    private val workerBase = "https://r1999tracker.posofrefraction.workers.dev"

    private lateinit var webview: WebView
    private lateinit var webOverlay: View
    private lateinit var webProgress: View
    private lateinit var webTitle: TextView
    private lateinit var webMsg: TextView
    private lateinit var webRetry: Button

    private lateinit var trackerView: View
    private lateinit var grabberView: View
    private lateinit var vpnSection: View
    private lateinit var proxySection: View

    private lateinit var status: TextView
    private lateinit var proxyInfo: TextView
    private lateinit var urlView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var vpnBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var openBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var logView: TextView

    private lateinit var bottomNav: BottomNavigationView

    private var pageErrored = false
    private var trackerLoaded = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val drivePrefs by lazy { getSharedPreferences("drive", MODE_PRIVATE) }
    private val updatePrefs by lazy { getSharedPreferences("update", MODE_PRIVATE) }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        }

    private var pendingSaveJson: String? = null

    private val createDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val json = pendingSaveJson
            pendingSaveJson = null
            if (uri == null || json == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                Toast.makeText(this, R.string.db_saved, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val vpnPrepare =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) armVpn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupWebView()
        setupTabs()
        setupGrabber()

        maybeRequestNotifications()
        refreshState()
        Bus.lastUrl?.let { showUrl(it) }
        loadTracker()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (trackerView.visibility == View.VISIBLE && webview.canGoBack()) {
                    webview.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        handleIntent(intent)
        handleOAuthRedirect(intent)
        checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        handleOAuthRedirect(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_IMPORT_LINK, false) == true) {
            bottomNav.selectedItemId = R.id.navTracker
            maybeAutoImport()
            return
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_LINK, false) == true) {
            bottomNav.selectedItemId = R.id.navGrabber
            Bus.lastUrl?.let { showUrl(it) }
        }
    }

    private fun handleOAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "reverse1999tracker") return
        bottomNav.selectedItemId = R.id.navTracker
        val error = data.getQueryParameter("error")
        val refresh = data.getQueryParameter("refresh_token")
        if (error != null || refresh.isNullOrEmpty()) {
            Toast.makeText(this, "Drive sign-in failed", Toast.LENGTH_LONG).show()
            return
        }
        drivePrefs.edit().putString("refresh", refresh).apply()
        val js = "window.__driveConnected && window.__driveConnected(${JSONObject.quote(refresh)})"
        webview.post { webview.evaluateJavascript(js, null) }
    }

    private fun restoreDrive() {
        val rt = drivePrefs.getString("refresh", null) ?: return
        webview.evaluateJavascript("window.__driveRestore && window.__driveRestore(${JSONObject.quote(rt)})", null)
    }

    private fun bindViews() {
        webview = findViewById(R.id.webview)
        webOverlay = findViewById(R.id.webOverlay)
        webProgress = findViewById(R.id.webProgress)
        webTitle = findViewById(R.id.webTitle)
        webMsg = findViewById(R.id.webMsg)
        webRetry = findViewById(R.id.webRetry)

        trackerView = findViewById(R.id.trackerView)
        grabberView = findViewById(R.id.grabberView)
        vpnSection = findViewById(R.id.vpnSection)
        proxySection = findViewById(R.id.proxySection)

        status = findViewById(R.id.status)
        proxyInfo = findViewById(R.id.proxyInfo)
        urlView = findViewById(R.id.urlView)
        toggleBtn = findViewById(R.id.toggleBtn)
        vpnBtn = findViewById(R.id.vpnBtn)
        copyBtn = findViewById(R.id.copyBtn)
        openBtn = findViewById(R.id.openBtn)
        clearBtn = findViewById(R.id.clearBtn)
        logView = findViewById(R.id.logView)
    }

    private fun setupTabs() {
        bottomNav = findViewById(R.id.bottomNav)
        bottomNav.selectedItemId = R.id.navTracker
        bottomNav.setOnItemSelectedListener { item ->
            val tracker = item.itemId == R.id.navTracker
            trackerView.visibility = if (tracker) View.VISIBLE else View.GONE
            grabberView.visibility = if (tracker) View.GONE else View.VISIBLE
            true
        }

        val subTabs = findViewById<MaterialButtonToggleGroup>(R.id.subTabs)
        subTabs.check(R.id.subVpn)
        subTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val vpn = checkedId == R.id.subVpn
            vpnSection.visibility = if (vpn) View.VISIBLE else View.GONE
            proxySection.visibility = if (vpn) View.GONE else View.VISIBLE
        }
    }

    private fun setupWebView() {
        webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }
        webview.addJavascriptInterface(WebBridge(), "AndroidBridge")
        webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                if (host != trackerHost) {
                    openExternal(request.url.toString())
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!pageErrored) {
                    webOverlay.visibility = View.GONE
                    trackerLoaded = true
                    restoreDrive()
                    scheduleBanners()
                    maybeAutoImport()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline()
            }
        }
        webview.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                        openExternal(req.url.toString())
                        return true
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = tmp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }
        webRetry.setOnClickListener { loadTracker() }
    }

    private fun loadTracker() {
        pageErrored = false
        trackerLoaded = false
        showLoading()
        webview.loadUrl(trackerUrl)
    }

    private fun showLoading() {
        pageErrored = false
        webOverlay.visibility = View.VISIBLE
        webProgress.visibility = View.VISIBLE
        webTitle.text = getString(R.string.web_loading)
        webMsg.visibility = View.GONE
        webRetry.visibility = View.GONE
    }

    private fun showOffline() {
        pageErrored = true
        webOverlay.visibility = View.VISIBLE
        webProgress.visibility = View.GONE
        webTitle.text = getString(R.string.web_offline_title)
        webMsg.text = getString(R.string.web_offline_msg)
        webMsg.visibility = View.VISIBLE
        webRetry.visibility = View.VISIBLE
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    private fun setupGrabber() {
        proxyInfo.text = getString(R.string.proxy_hint, NetUtil.wifiIp(), ProxyService.PORT)

        toggleBtn.setOnClickListener { toggle() }
        vpnBtn.setOnClickListener { toggleVpn() }
        copyBtn.setOnClickListener { copyLink() }
        openBtn.setOnClickListener { importIntoTracker() }
        clearBtn.setOnClickListener { Bus.clear() }

        Bus.listener = { url -> runOnUiThread { showUrl(url) } }
        Bus.stateListener = { runOnUiThread { refreshState() } }
        Bus.logListener = { runOnUiThread { logView.text = Bus.snapshot() } }
        logView.text = Bus.snapshot()
    }

    override fun onResume() {
        super.onResume()
        proxyInfo.text = getString(R.string.proxy_hint, NetUtil.wifiIp(), ProxyService.PORT)
        refreshState()
        maybeAutoImport()
    }

    private fun toggle() {
        val intent = Intent(this, ProxyService::class.java)
        if (Bus.running) stopService(intent)
        else ContextCompat.startForegroundService(this, intent)
    }

    private fun toggleVpn() {
        if (Bus.vpnRunning) {
            startService(Intent(this, Tun2HttpVpnService::class.java).setAction(Tun2HttpVpnService.ACTION_STOP))
        } else {
            ensureUnrestricted()
            val prepare = VpnService.prepare(this)
            if (prepare != null) vpnPrepare.launch(prepare) else armVpn()
        }
    }

    private fun ensureUnrestricted() {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        Toast.makeText(this, R.string.battery_hint, Toast.LENGTH_LONG).show()
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun armVpn() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, Tun2HttpVpnService::class.java).setAction(Tun2HttpVpnService.ACTION_ARM)
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

    private fun importIntoTracker() {
        val url = Bus.lastUrl ?: return
        bottomNav.selectedItemId = R.id.navTracker
        importUrl(url)
    }

    private fun maybeAutoImport() {
        val url = Bus.pendingImportUrl ?: return
        if (!trackerLoaded) return
        Bus.pendingImportUrl = null
        bottomNav.selectedItemId = R.id.navTracker
        importUrl(url)
    }

    private fun importUrl(url: String) {
        val js = "(function(){var i=document.getElementById('urlInput');" +
            "if(i){i.value=" + JSONObject.quote(url) + ";" +
            "if(typeof loadFromURL==='function')loadFromURL();}})()"
        webview.post { webview.evaluateJavascript(js, null) }
    }

    private fun scheduleBanners() {
        val js = """
        (function(){
          try{
            if(typeof ACTIVE_BANNERS==='undefined')return '[]';
            var now=Date.now();var map={};
            ACTIVE_BANNERS.forEach(function(b){
              var t=Date.parse(b.startUTC); if(isNaN(t)||t<=now)return;
              var info=(typeof BANNERS!=='undefined'&&BANNERS[b.key])||{};
              var name=info.name||b.key; var water=info.type==='Water';
              var r6=(!water&&info.rateUp6)?info.rateUp6:[];
              (map[t]=map[t]||[]).push({name:name,water:water,rate6:r6});
            });
            return JSON.stringify(Object.keys(map).map(function(k){return {at:Number(k),banners:map[k]};}));
          }catch(e){return '[]';}
        })()
        """.trimIndent()
        webview.evaluateJavascript(js) { raw -> BannerScheduler.schedule(this, raw) }
    }

    private fun checkForUpdate() {
        Thread {
            try {
                val conn = java.net.URL(
                    "https://api.github.com/repos/lonelytragedy/r1999-TrackerApp/releases/latest"
                ).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "R1999Tracker")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode != 200) return@Thread
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(body)
                val tag = obj.optString("tag_name")
                val page = obj.optString("html_url")
                if (tag.isEmpty()) return@Thread
                val current = packageManager.getPackageInfo(packageName, 0).versionName ?: return@Thread
                if (compareVersions(tag, current) <= 0) return@Thread
                if (updatePrefs.getString("skipped", null) == tag) return@Thread
                runOnUiThread { showUpdateDialog(tag, page) }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.filter { it.isDigit() || it == '.' }.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.filter { it.isDigit() || it == '.' }.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    private fun showUpdateDialog(tag: String, page: String) {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, tag))
            .setMessage(getString(R.string.update_msg))
            .setPositiveButton(R.string.update_now) { _, _ ->
                openExternal(if (page.isNotEmpty()) page else "https://github.com/lonelytragedy/r1999-TrackerApp/releases/latest")
            }
            .setNeutralButton(R.string.update_later) { d, _ -> d.dismiss() }
            .setNegativeButton(R.string.update_skip) { _, _ ->
                updatePrefs.edit().putString("skipped", tag).apply()
            }
            .show()
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private inner class WebBridge {
        @android.webkit.JavascriptInterface
        fun saveDatabase(json: String, filename: String) {
            pendingSaveJson = json
            runOnUiThread { createDoc.launch(filename) }
        }

        @android.webkit.JavascriptInterface
        fun connectDrive() {
            runOnUiThread {
                try {
                    androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                        .launchUrl(this@MainActivity, Uri.parse("$workerBase/oauth/start"))
                } catch (_: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$workerBase/oauth/start")))
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun disconnectDrive() {
            drivePrefs.edit().remove("refresh").apply()
        }
    }

    override fun onDestroy() {
        Bus.listener = null
        Bus.stateListener = null
        Bus.logListener = null
        super.onDestroy()
    }
}
