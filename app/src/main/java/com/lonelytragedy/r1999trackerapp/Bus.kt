package com.lonelytragedy.r1999trackerapp

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object Bus {
    @Volatile
    var running = false

    @Volatile
    var lastUrl: String? = null

    @Volatile
    var listener: ((String) -> Unit)? = null

    @Volatile
    var stateListener: (() -> Unit)? = null

    @Volatile
    var logListener: (() -> Unit)? = null

    val log: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun emitUrl(url: String) {
        lastUrl = url
        listener?.invoke(url)
    }

    fun emitState() {
        stateListener?.invoke()
    }

    fun logLine(text: String) {
        val line = fmt.format(Date()) + "  " + text
        synchronized(log) {
            log.add(line)
            while (log.size > 300) log.removeAt(0)
        }
        logListener?.invoke()
    }

    fun snapshot(): String {
        synchronized(log) {
            return log.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(log) { log.clear() }
        logListener?.invoke()
    }
}
