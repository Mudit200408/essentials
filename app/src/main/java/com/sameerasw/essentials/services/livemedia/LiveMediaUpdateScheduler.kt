package com.sameerasw.essentials.services.livemedia

import android.os.Handler
import android.os.Looper

class LiveMediaUpdateScheduler(
    private val nextDelayProvider: () -> Long?
) {
    private val updateHandler = Handler(Looper.getMainLooper())

    private val updateRunnable = object : Runnable {
        override fun run() {
            val delay = nextDelayProvider()
            if (delay != null) {
                updateHandler.postDelayed(this, delay)
            }
        }
    }

    fun scheduleUpdate() {
        updateHandler.removeCallbacks(updateRunnable)
        updateHandler.post(updateRunnable)
    }

    fun stop() {
        updateHandler.removeCallbacks(updateRunnable)
    }
}

