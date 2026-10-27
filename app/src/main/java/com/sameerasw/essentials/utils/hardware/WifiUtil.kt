/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Hardware Utilities
 * File: WifiUtil.kt
 * Description: Helper utility for managing Wi-Fi state and network scanning.
 */

package com.sameerasw.essentials.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager

object WifiUtil {
    private const val UNKNOWN_SSID = "<unknown ssid>"
    private val SSID_REGEX = Regex("\"([^\"]*)\"")

    @Suppress("DEPRECATION")
    fun getCurrentSsid(context: Context): String? {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
        val ssid = wifiManager.connectionInfo?.ssid ?: return null
        if (ssid.isEmpty() || ssid == UNKNOWN_SSID) return null
        return ssid.removeSurrounding("\"")
    }

    /**
     * Shizuku/root grant shell-level privileges, which can run `cmd wifi list-networks`
     * (normally restricted to system apps) to read the device's saved Wi-Fi networks.
     */
    fun canReadSavedNetworks(context: Context): Boolean = ShellUtils.isAvailable(context) && ShellUtils.hasPermission(context)

    fun getSavedNetworkSsids(context: Context): List<String> {
        val output =
            ShellUtils.runCommandWithOutput(context, "cmd wifi list-networks")
                ?: return emptyList()
        return output
            .lineSequence()
            .drop(1) // header row: "Network Id      SSID      Security type"
            .mapNotNull { line -> SSID_REGEX.find(line)?.groupValues?.get(1) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false
        return wifiManager.isWifiEnabled
    }

    fun setWifiEnabled(context: Context, enabled: Boolean) {
        val cmd = if (enabled) "svc wifi enable" else "svc wifi disable"
        ShellUtils.runCommand(context, cmd, featureName = "Wi-Fi")
    }

    fun isHotspotEnabled(context: Context): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false
        return try {
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as? Boolean ?: false
        } catch (_: Exception) {
            try {
                val method = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
                method.isAccessible = true
                val state = method.invoke(wifiManager) as? Int ?: 0
                state == 13
            } catch (_: Exception) {
                false
            }
        }
    }

    fun isDataEnabled(context: Context): Boolean {
        val telephonyManager =
            context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return false
        return try {
            telephonyManager.isDataEnabled
        } catch (_: Exception) {
            false
        }
    }

    fun setDataEnabled(context: Context, enabled: Boolean) {
        val cmd = if (enabled) "svc data enable" else "svc data disable"
        ShellUtils.runCommand(context, cmd, featureName = "Mobile data")
    }

    fun getActiveCarrierName(context: Context): String? {
        return try {
            val telephonyManager =
                context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephonyManager?.networkOperatorName?.takeIf { it.isNotBlank() }
                ?: telephonyManager?.simOperatorName?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
