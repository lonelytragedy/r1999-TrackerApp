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
import org.json.JSONObject
import tun.proxy.service.Tun2HttpVpnService

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_LINK = "show_link"
        const val EXTRA_IMPORT_LINK = "import_link"
        const val GAME_PACKAGE = "com.bluepoch.m.en.reverse1999"
        private const val STEP_TODO = 0
        private const val STEP_CURRENT = 1
        private const val STEP_DONE = 2
        private val SECTION_BY_NAV = mapOf(
            R.id.navOverview to "top",
            R.id.navBanners to "bannerTimelineBox",
            R.id.navHistory to "historyBox",
            R.id.navStats to "statsBox",
        )
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

    private lateinit var subVpn: View
    private lateinit var subProxy: View
    private lateinit var step1: View
    private lateinit var step2: View
    private lateinit var step3: View
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var status: TextView
    private lateinit var stopBtn: View
    private lateinit var primaryBtn: TextView
    private lateinit var linkCard: View
    private lateinit var urlView: TextView
    private lateinit var copyBtn: View
    private lateinit var openBtn: View
    private lateinit var logToggle: View
    private lateinit var logChevron: View
    private lateinit var logTitle: TextView
    private lateinit var clearBtn: View
    private lateinit var logView: TextView

    private var useVpn = true
    private var syncingNav = false
    private var currentSection = R.id.navOverview

    private lateinit var bottomNav: BottomNavigationView

    private var pageErrored = false
    private var trackerLoaded = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val drivePrefs by lazy { getSharedPreferences("drive", MODE_PRIVATE) }
    private val appPrefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
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
        if (getSharedPreferences("app", MODE_PRIVATE).getString("skin", "reversed") == "classic") {
            setTheme(R.style.Theme_R1999TrackerApp_Classic)
        }
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
                if (grabberView.visibility == View.VISIBLE) {
                    showTrackerTab()
                } else if (trackerView.visibility == View.VISIBLE && webview.canGoBack()) {
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
            showTrackerTab()
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
        showTrackerTab()
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

        subVpn = findViewById(R.id.subVpn)
        subProxy = findViewById(R.id.subProxy)
        step1 = findViewById(R.id.step1)
        step2 = findViewById(R.id.step2)
        step3 = findViewById(R.id.step3)
        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        status = findViewById(R.id.status)
        stopBtn = findViewById(R.id.stopBtn)
        primaryBtn = findViewById(R.id.primaryBtn)
        linkCard = findViewById(R.id.linkCard)
        urlView = findViewById(R.id.urlView)
        copyBtn = findViewById(R.id.copyBtn)
        openBtn = findViewById(R.id.openBtn)
        logToggle = findViewById(R.id.logToggle)
        logChevron = findViewById(R.id.logChevron)
        logTitle = findViewById(R.id.logTitle)
        clearBtn = findViewById(R.id.clearBtn)
        logView = findViewById(R.id.logView)
    }

    private fun setupTabs() {
        bottomNav = findViewById(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            val tracker = item.itemId != R.id.navGrabber
            trackerView.visibility = if (tracker) View.VISIBLE else View.GONE
            grabberView.visibility = if (tracker) View.GONE else View.VISIBLE
            if (tracker) {
                currentSection = item.itemId
                if (!syncingNav) scrollTracker(item.itemId)
            } else {
                refreshState()
            }
            true
        }
        bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId != R.id.navGrabber) scrollTracker(item.itemId)
        }

        useVpn = appPrefs.getString("grabber_mode", "vpn") != "proxy"
        subVpn.setOnClickListener { setCaptureMode(true) }
        subProxy.setOnClickListener { setCaptureMode(false) }
    }

    private fun setCaptureMode(vpn: Boolean) {
        if (useVpn == vpn) return
        useVpn = vpn
        appPrefs.edit().putString("grabber_mode", if (vpn) "vpn" else "proxy").apply()
        refreshState()
    }

    private fun selectNav(id: Int) {
        syncingNav = true
        bottomNav.selectedItemId = id
        syncingNav = false
    }

    private fun showTrackerTab() {
        selectNav(currentSection)
    }

    private fun scrollTracker(itemId: Int) {
        val section = SECTION_BY_NAV[itemId] ?: return
        webview.evaluateJavascript("window.trackerScrollTo && window.trackerScrollTo('$section')", null)
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
                    pushWidgetData()
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
        primaryBtn.setOnClickListener { onPrimary() }
        stopBtn.setOnClickListener { stopCapture() }
        copyBtn.setOnClickListener { copyLink() }
        openBtn.setOnClickListener { importIntoTracker() }
        clearBtn.setOnClickListener { Bus.clear() }
        logToggle.setOnClickListener { toggleLog() }
        findViewById<View>(R.id.logCard).clipToOutline = true

        Bus.listener = { url -> runOnUiThread { showUrl(url) } }
        Bus.stateListener = { runOnUiThread { refreshState() } }
        Bus.logListener = { runOnUiThread { updateLog() } }
        updateLog()
    }

    private fun capturing(): Boolean = if (useVpn) Bus.vpnRunning else Bus.running

    private fun onPrimary() {
        when {
            capturing() -> launchGame()
            useVpn -> toggleVpn()
            else -> toggle()
        }
    }

    private fun stopCapture() {
        if (Bus.vpnRunning) {
            startService(Intent(this, Tun2HttpVpnService::class.java).setAction(Tun2HttpVpnService.ACTION_STOP))
        }
        if (Bus.running) stopService(Intent(this, ProxyService::class.java))
    }

    private fun toggleLog() {
        val open = logView.visibility != View.VISIBLE
        logView.visibility = if (open) View.VISIBLE else View.GONE
        clearBtn.visibility = if (open) View.VISIBLE else View.GONE
        logChevron.rotation = if (open) 0f else -90f
    }

    private fun updateLog() {
        logView.text = Bus.snapshot()
        logTitle.text = getString(R.string.log_count, Bus.log.size)
    }

    private fun attrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun setStep(step: View, number: Int, title: Int, sub: String, state: Int) {
        val mark = step.findViewById<TextView>(R.id.stepMark)
        step.findViewById<TextView>(R.id.stepTitle).setText(title)
        step.findViewById<TextView>(R.id.stepSub).text = sub
        val (bg, fg) = when (state) {
            STEP_DONE -> ContextCompat.getColor(this, R.color.status_ok_bg) to ContextCompat.getColor(this, R.color.status_ok)
            STEP_CURRENT -> attrColor(R.attr.appAccent) to attrColor(com.google.android.material.R.attr.colorOnPrimary)
            else -> attrColor(R.attr.appStroke) to attrColor(R.attr.appTextMuted)
        }
        mark.text = if (state == STEP_DONE) "✓" else number.toString()
        mark.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
        mark.setTextColor(fg)
    }

    override fun onResume() {
        super.onResume()
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
        launchGame()
    }

    private fun launchGame() {
        try {
            val i = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
            if (i != null) {
                startActivity(i)
            } else {
                Toast.makeText(this, "Game app not found", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
        }
    }

    private fun refreshState() {
        subVpn.isSelected = useVpn
        subProxy.isSelected = !useVpn

        val running = capturing()
        val url = Bus.lastUrl
        val found = url != null && !running
        val early = if (running || found) STEP_DONE else STEP_CURRENT
        val middle = if (running || found) STEP_DONE else STEP_TODO
        val last = when {
            found -> STEP_DONE
            running -> STEP_CURRENT
            else -> STEP_TODO
        }
        setStep(step1, 1, R.string.step_login_title, getString(R.string.step_login_sub), early)
        if (useVpn) {
            setStep(step2, 2, R.string.step_vpn_title, getString(R.string.step_vpn_sub), middle)
            setStep(step3, 3, R.string.step_record_title, getString(R.string.step_record_sub_vpn), last)
        } else {
            setStep(step2, 2, R.string.step_proxy_title, getString(R.string.step_proxy_sub, NetUtil.wifiIp(), ProxyService.PORT), middle)
            setStep(step3, 3, R.string.step_record_title, getString(R.string.step_record_sub_proxy), last)
        }

        val dot = when {
            running -> attrColor(R.attr.appAccent)
            found -> ContextCompat.getColor(this, R.color.status_ok)
            else -> ContextCompat.getColor(this, R.color.status_idle)
        }
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(dot)
        statusTitle.setText(
            when {
                running -> R.string.status_on_title
                found -> R.string.status_found_title
                else -> R.string.status_off_title
            }
        )
        status.setText(
            when {
                running -> R.string.status_on_sub
                found -> R.string.status_found_sub
                else -> R.string.status_off_sub
            }
        )
        stopBtn.visibility = if (running) View.VISIBLE else View.GONE

        primaryBtn.setText(if (running) R.string.open_game else R.string.start_capture)
        val play = if (running) ContextCompat.getDrawable(this, R.drawable.ic_play)?.mutate() else null
        play?.setTint(attrColor(com.google.android.material.R.attr.colorOnPrimary))
        primaryBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(play, null, null, null)

        linkCard.visibility = if (url != null) View.VISIBLE else View.GONE
        urlView.text = url ?: ""
    }

    private fun showUrl(url: String) {
        refreshState()
    }

    private fun copyLink() {
        val url = Bus.lastUrl ?: return
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("summon", url))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun importIntoTracker() {
        val url = Bus.lastUrl ?: return
        showTrackerTab()
        importUrl(url)
    }

    private fun maybeAutoImport() {
        val url = Bus.pendingImportUrl ?: return
        if (!trackerLoaded) return
        Bus.pendingImportUrl = null
        showTrackerTab()
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

    private fun pushWidgetData() {
        val js = """
        (function(){
          try{
            if(typeof ACTIVE_BANNERS==='undefined')return '[]';
            var now=Date.now();var out=[];
            ACTIVE_BANNERS.forEach(function(b){
              if(!b.startUTC||!b.endUTC)return;
              var s=Date.parse(b.startUTC),e=Date.parse(b.endUTC);
              if(isNaN(s)||isNaN(e)||e<=now)return;
              var info=(typeof BANNERS!=='undefined'&&BANNERS[b.key])||{};
              var name=info.name||b.key;var type=info.type||'';
              var r=(info.rateUp6&&info.rateUp6.length)?info.rateUp6:(b.rateUp||[]);
              out.push({name:name,type:type,rate:r,image:b.image||'',start:s,end:e});
            });
            return JSON.stringify(out);
          }catch(err){return '[]';}
        })()
        """.trimIndent()
        webview.evaluateJavascript(js) { raw -> BannerWidgetProvider.pushData(this, raw) }
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
                var apkUrl = ""
                val assets = obj.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                            if (a.optString("name") == "Reverse1999TrackerApp.apk") break
                        }
                    }
                }
                runOnUiThread { showUpdateDialog(tag, apkUrl, page) }
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

    private fun showUpdateDialog(tag: String, apkUrl: String, page: String) {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, tag))
            .setMessage(getString(R.string.update_msg))
            .setPositiveButton(R.string.update_now) { _, _ ->
                if (apkUrl.isNotEmpty()) startUpdate(apkUrl)
                else openExternal(if (page.isNotEmpty()) page else "https://github.com/lonelytragedy/r1999-TrackerApp/releases/latest")
            }
            .setNeutralButton(R.string.update_later) { d, _ -> d.dismiss() }
            .setNegativeButton(R.string.update_skip) { _, _ ->
                updatePrefs.edit().putString("skipped", tag).apply()
            }
            .show()
    }

    private fun startUpdate(url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.update_allow_installs, Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
            }
            return
        }
        downloadAndInstall(url)
    }

    private fun downloadAndInstall(url: String) {
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(this).apply { setPadding(0, 0, 0, 20) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 40, 56, 12)
            addView(label)
            addView(bar)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.update_downloading)
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.show()

        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "R1999Tracker")
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.connect()
                val total = conn.contentLengthLong
                val apk = java.io.File(cacheDir, "update.apk")
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(apk).use { out ->
                        val buf = ByteArray(16384)
                        var read: Int
                        var sum = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            sum += read
                            if (total > 0) {
                                val pct = (sum * 100 / total).toInt()
                                runOnUiThread { bar.progress = pct; label.text = "$pct%" }
                            }
                        }
                    }
                }
                runOnUiThread { dialog.dismiss(); installApk(apk) }
            } catch (e: Exception) {
                runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(this, getString(R.string.update_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installApk(apk: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.update_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
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
        fun showImport() {
            runOnUiThread { bottomNav.selectedItemId = R.id.navGrabber }
        }

        @android.webkit.JavascriptInterface
        fun setSection(section: String) {
            val id = SECTION_BY_NAV.entries.firstOrNull { it.value == section }?.key ?: return
            runOnUiThread {
                currentSection = id
                if (trackerView.visibility == View.VISIBLE) selectNav(id)
            }
        }

        @android.webkit.JavascriptInterface
        fun disconnectDrive() {
            drivePrefs.edit().remove("refresh").apply()
        }

        @android.webkit.JavascriptInterface
        fun setSkin(skin: String) {
            val s = if (skin == "classic") "classic" else "reversed"
            val prefs = getSharedPreferences("app", MODE_PRIVATE)
            if (prefs.getString("skin", "reversed") == s) return
            prefs.edit().putString("skin", s).apply()
            runOnUiThread { recreate() }
        }
    }

    override fun onDestroy() {
        Bus.listener = null
        Bus.stateListener = null
        Bus.logListener = null
        super.onDestroy()
    }
}
