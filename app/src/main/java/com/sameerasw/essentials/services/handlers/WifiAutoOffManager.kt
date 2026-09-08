/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: WifiAutoOffManager.kt
 * Description: Application-level lifecycle manager for the Wi-Fi auto-turn-off feature.
 */

package com.sameerasw.essentials.services.handlers

import android.content.Context
import com.sameerasw.essentials.data.repository.SettingsRepository

object WifiAutoOffManager {
    private var handler: WifiAutoOffHandler? = null
    private var isRegistered = false

    fun init(context: Context) {
        if (handler == null) {
            handler = WifiAutoOffHandler(context.applicationContext)
        }
        val settingsRepository = SettingsRepository(context)
        if (settingsRepository.isWifiAutoOffEnabled()) {
            register()
        }
    }

    fun register() {
        if (!isRegistered) {
            handler?.register()
            isRegistered = true
        }
    }

    fun unregister() {
        if (isRegistered) {
            handler?.unregister()
            isRegistered = false
        }
    }

    fun onPreferenceChanged(key: String?) {
        handler?.onPreferenceChanged(key)
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            register()
        } else {
            unregister()
        }
    }
}
