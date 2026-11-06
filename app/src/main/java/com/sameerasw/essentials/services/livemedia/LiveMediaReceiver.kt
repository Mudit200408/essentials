package com.sameerasw.essentials.services.livemedia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LiveMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        LiveMediaManager.getInstance(context).handleTransportControl(action)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.sameerasw.essentials.livemedia.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.sameerasw.essentials.livemedia.PREVIOUS"
        const val ACTION_NEXT = "com.sameerasw.essentials.livemedia.NEXT"
    }
}

