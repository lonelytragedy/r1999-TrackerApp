package com.lonelytragedy.r1999trackerapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object Bus {
    private const val ACTION_STATE = "com.lonelytragedy.r1999trackerapp.CAPTURE_STATE"
    private const val ACTION_REQUEST = "com.lonelytragedy.r1999trackerapp.CAPTURE_REQUEST"
    private const val ACTION_CLEAR = "com.lonelytragedy.r1999trackerapp.CAPTURE_CLEAR"
    private const val LINK_TTL_MS = 2 * 3600_000L
    private const val LOG_LIMIT = 300

    @Volatile
    var running = false

    @Volatile
    var vpnRunning = false

    @Volatile
    var listener: ((String) -> Unit)? = null

    @Volatile
    var stateListener: (() -> Unit)? = null

    @Volatile
    var logListener: (() -> Unit)? = null

    val log: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var appCtx: Context? = null
    private var isCapture = false
    private var captureReceiver: BroadcastReceiver? = null

    fun attachCapture(ctx: Context) {
        if (isCapture) return
        isCapture = true
        appCtx = ctx.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_REQUEST -> broadcastState()
                    ACTION_CLEAR -> {
                        synchronized(log) { log.clear() }
                        broadcastState()
                    }
                }
            }
        }
        captureReceiver = receiver
        ContextCompat.registerReceiver(
            ctx.applicationContext, receiver,
            IntentFilter().apply { addAction(ACTION_REQUEST); addAction(ACTION_CLEAR) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun attachUi(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun uiReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != ACTION_STATE) return
            vpnRunning = intent.getBooleanExtra("vpn", false)
            running = intent.getBooleanExtra("proxy", false)
            val lines = intent.getStringArrayExtra("log") ?: emptyArray()
            synchronized(log) {
                log.clear()
                log.addAll(lines)
            }
            intent.getStringExtra("url")?.let { listener?.invoke(it) }
            stateListener?.invoke()
            logListener?.invoke()
        }
    }

    fun uiFilter(): IntentFilter = IntentFilter(ACTION_STATE)

    fun requestState(ctx: Context) {
        appCtx = ctx.applicationContext
        vpnRunning = false
        running = false
        ctx.sendBroadcast(Intent(ACTION_REQUEST).setPackage(ctx.packageName))
    }

    var lastUrl: String?
        get() = readLink("link_last")
        set(value) = writeLink("link_last", value)

    var pendingImportUrl: String?
        get() = readLink("link_pending")
        set(value) = writeLink("link_pending", value)

    fun emitUrl(url: String) {
        lastUrl = url
        listener?.invoke(url)
        broadcastState(url)
    }

    fun emitState() {
        stateListener?.invoke()
        broadcastState()
    }

    fun logLine(text: String) {
        val line = fmt.format(Date()) + "  " + text
        synchronized(log) {
            log.add(line)
            while (log.size > LOG_LIMIT) log.removeAt(0)
        }
        logListener?.invoke()
        broadcastState()
    }

    fun snapshot(): String {
        synchronized(log) {
            return log.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(log) { log.clear() }
        logListener?.invoke()
        val ctx = appCtx ?: return
        if (!isCapture) ctx.sendBroadcast(Intent(ACTION_CLEAR).setPackage(ctx.packageName))
    }

    private fun broadcastState(url: String? = null) {
        if (!isCapture) return
        val ctx = appCtx ?: return
        val lines = synchronized(log) { log.toTypedArray() }
        ctx.sendBroadcast(
            Intent(ACTION_STATE).setPackage(ctx.packageName)
                .putExtra("vpn", vpnRunning)
                .putExtra("proxy", running)
                .putExtra("log", lines)
                .apply { if (url != null) putExtra("url", url) }
        )
    }

    private fun linkFile(ctx: Context, name: String) = File(ctx.filesDir, name)

    private fun readLink(name: String): String? {
        val ctx = appCtx ?: return null
        val f = linkFile(ctx, name)
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > LINK_TTL_MS) {
            f.delete()
            return null
        }
        return try { f.readText().ifBlank { null } } catch (_: Exception) { null }
    }

    private fun writeLink(name: String, value: String?) {
        val ctx = appCtx ?: return
        val f = linkFile(ctx, name)
        try {
            if (value == null) f.delete() else f.writeText(value)
        } catch (_: Exception) {
        }
    }
}
