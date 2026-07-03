package com.lonelytragedy.r1999trackerapp

object Bus {
    @Volatile
    var running = false

    @Volatile
    var lastUrl: String? = null

    @Volatile
    var listener: ((String) -> Unit)? = null

    @Volatile
    var stateListener: (() -> Unit)? = null

    fun emitUrl(url: String) {
        lastUrl = url
        listener?.invoke(url)
    }

    fun emitState() {
        stateListener?.invoke()
    }
}
