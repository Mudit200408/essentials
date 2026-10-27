/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: InternetTileService.kt
 * Description: Quick Settings Tile Service for unified Internet and Hotspot controls.
 */

package com.sameerasw.essentials.services.tiles

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.service.quicksettings.Tile
import com.sameerasw.essentials.R
import com.sameerasw.essentials.ui.activities.InternetDialogActivity
import com.sameerasw.essentials.utils.WifiUtil

class InternetTileService : BaseTileService() {

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun getTileLabel(): String = getString(R.string.tile_internet)

    override fun getTileSubtitle(): String {
        val context = this
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)

        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                WifiUtil.getCurrentSsid(context) ?: getString(R.string.internet_dialog_connected)
            }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                WifiUtil.getActiveCarrierName(context) ?: getString(R.string.internet_dialog_mobile_data)
            }
            else -> getString(R.string.internet_dialog_disconnected)
        }
    }

    override fun hasFeaturePermission(): Boolean = true

    override fun getTileIcon(): Icon {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)

        val iconRes = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                R.drawable.rounded_android_wifi_3_bar_24
            }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                R.drawable.rounded_signal_cellular_alt_24
            }
            else -> R.drawable.rounded_globe_24
        }
        return Icon.createWithResource(this, iconRes)
    }

    override fun getTileState(): Int {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)
        val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        return if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    }

    override fun onStartListening() {
        super.onStartListening()
        registerNetworkCallback()
        updateTile()
    }

    override fun onStopListening() {
        unregisterNetworkCallback()
        super.onStopListening()
    }

    override fun onClick() {
        openInternetDialog()
    }

    override fun onTileClick() {
        openInternetDialog()
    }

    private fun openInternetDialog() {
        val intent = Intent(this, InternetDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder().build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateTile()
            }

            override fun onLost(network: Network) {
                updateTile()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateTile()
            }
        }
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }
}
