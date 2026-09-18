/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: WifiAutoOffHandler.kt
 * Description: Background handler for automatically disabling Wi-Fi after disconnection timeout.
 */

package com.sameerasw.essentials.services.handlers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.utils.ShellUtils

class WifiAutoOffHandler(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val settingsRepository by lazy { SettingsRepository(context) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isWifiConnected = false
    private var isWifiReceiverRegistered = false

    private val wifiAutoOffRunnable = Runnable {
        if (!isWifiConnected && settingsRepository.isWifiAutoOffEnabled()) {
            Log.d(TAG, "Executing Wi-Fi auto turn off via shell")
            try {
                ShellUtils.runCommand(context, "svc wifi disable")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable Wi-Fi", e)
            }
        }
    }

    private val wifiStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    if (state == WifiManager.WIFI_STATE_ENABLED) {
                        if (!isWifiConnected) {
                            checkAndScheduleWifiAutoOff()
                        }
                    } else if (state == WifiManager.WIFI_STATE_DISABLED || state == WifiManager.WIFI_STATE_DISABLING) {
                        handler.removeCallbacks(wifiAutoOffRunnable)
                    }
                }
            }
        }

    companion object {
        private const val TAG = "WifiAutoOffHandler"
    }

    fun register() {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        isWifiConnected = isWifiCurrentlyConnected(connectivityManager)
        if (!isWifiConnected) {
            checkAndScheduleWifiAutoOff()
        }

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                isWifiConnected = true
                handler.removeCallbacks(wifiAutoOffRunnable)
                Log.d(TAG, "Wi-Fi Connected - Cancelled auto off timer")
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                isWifiConnected = false
                checkAndScheduleWifiAutoOff()
            }
        }

        networkCallback = callback
        try {
            connectivityManager.registerNetworkCallback(networkRequest, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }

        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(wifiStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(wifiStateReceiver, filter)
            }
            isWifiReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register wifi state receiver", e)
        }
    }

    fun unregister() {
        handler.removeCallbacks(wifiAutoOffRunnable)
        if (isWifiReceiverRegistered) {
            try {
                context.unregisterReceiver(wifiStateReceiver)
            } catch (_: Exception) {
            }
            isWifiReceiverRegistered = false
        }
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    fun onPreferenceChanged(key: String?) {
        if (key == SettingsRepository.KEY_WIFI_AUTO_OFF_ENABLED || key == SettingsRepository.KEY_WIFI_AUTO_OFF_TIMEOUT) {
            val isAutoOffEnabled = settingsRepository.isWifiAutoOffEnabled()
            if (!isAutoOffEnabled) {
                handler.removeCallbacks(wifiAutoOffRunnable)
            } else if (!isWifiConnected) {
                checkAndScheduleWifiAutoOff()
            }
        }
    }

    private fun isWifiCurrentlyConnected(connectivityManager: ConnectivityManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            activeNetworkInfo != null && activeNetworkInfo.type == ConnectivityManager.TYPE_WIFI && activeNetworkInfo.isConnected
        }
    }

    private fun checkAndScheduleWifiAutoOff() {
        if (!settingsRepository.isWifiAutoOffEnabled()) return

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager?.isWifiEnabled != true) return

        val timeoutSeconds = settingsRepository.getWifiAutoOffTimeout()
        val delayMs = (timeoutSeconds * 1000).toLong()

        handler.removeCallbacks(wifiAutoOffRunnable)
        handler.postDelayed(wifiAutoOffRunnable, delayMs)
        Log.d(TAG, "Scheduled Wi-Fi auto turn off in $timeoutSeconds seconds")
    }
}
