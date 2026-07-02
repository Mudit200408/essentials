package com.sameerasw.essentials.utils

import android.content.Context
import com.sameerasw.essentials.data.repository.SettingsRepository

object WifiOptimizationManager {

    fun applyAllConfigs(context: Context) {
        val prefs = context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val isOptimizerEnabled = prefs.getBoolean(SettingsRepository.KEY_WIFI_OPTIMIZER_ENABLED, false)
        if (!isOptimizerEnabled) return

        val softwarePno = prefs.getBoolean(SettingsRepository.KEY_WIFI_SOFTWARE_PNO_ENABLED, false)
        val healthMinRssi = prefs.getFloat(SettingsRepository.KEY_WIFI_HEALTH_MONITOR_MIN_RSSI, -60f).toInt()
        val lowScoreThreshold = prefs.getFloat(SettingsRepository.KEY_WIFI_LOW_SCORE_THRESHOLD, 55f).toInt()

        applySoftwarePno(context, softwarePno)
        applyHealthMonitorMinRssi(context, healthMinRssi)
        applyLowScoreThreshold(context, lowScoreThreshold)
    }

    fun resetAllConfigs(context: Context) {
        ShellUtils.runCommand(context, "device_config delete wifi software_pno_enabled")
        ShellUtils.runCommand(context, "device_config delete wifi health_monitor_min_rssi_thr_dbm")
        ShellUtils.runCommand(context, "device_config delete wifi wifi_low_connected_score_threshold_to_trigger_scan_for_mbb")
    }

    fun applySoftwarePno(context: Context, enabled: Boolean) {
        ShellUtils.runCommand(context, "device_config put wifi software_pno_enabled $enabled")
    }

    fun applyHealthMonitorMinRssi(context: Context, rssi: Int) {
        ShellUtils.runCommand(context, "device_config put wifi health_monitor_min_rssi_thr_dbm $rssi")
    }

    fun applyLowScoreThreshold(context: Context, threshold: Int) {
        ShellUtils.runCommand(context, "device_config put wifi wifi_low_connected_score_threshold_to_trigger_scan_for_mbb $threshold")
    }
}
