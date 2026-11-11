/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: System & Performance Utilities
 * File: GameModeManager.kt
 * Description: Manages automated performance optimization, refresh rate locking,
 * memory purging, AI Core suspension, and auto-brightness toggling when gaming.
 */

package com.sameerasw.essentials.utils

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.widget.Toast
import com.sameerasw.essentials.R
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.services.handlers.AppFlowHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object GameModeManager {
    private const val TAG = "GameModeManager"

    @Volatile
    var isGameModeActive = false
        private set

    @Volatile
    var activeGamePackage: String? = null
        private set

    private var snapshottedRefreshRate: RefreshRateUtils.RefreshRateState? = null
    private var snapshottedAutoBrightness: Int? = null
    private var snapshottedWifiScanAlwaysEnabled: Int? = null
    private var snapshottedBleScanAlwaysEnabled: Int? = null
    private var snapshottedAdaptiveConnectivity: Int? = null
    private var snapshottedNetworkRecommendations: String? = null
    private var snapshottedNetworkAvoidBadWifi: String? = null
    private var snapshottedPrivateDnsMode: String? = null
    private var snapshottedPrivateDnsSpecifier: String? = null
    private var didDisableAiCore = false
    private var didSuspendTurbo = false
    private var didDisableAirSync = false
    private var didDisablePrivateDns = false
    private var didApplyGameIntervention = false
    private var didLockDisplayPreferredMode = false
    private var didLockWifiNetworkSelection = false
    private var snapshottedWifiPowerSave: Int? = null
    private var didDisableIpReachDisconnect = false
    private var wifiHighPerfLock: WifiManager.WifiLock? = null
    private var wifiLowLatencyLock: WifiManager.WifiLock? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private var pendingRestoreJob: Job? = null

    fun onGameForeground(context: Context, packageName: String, repo: SettingsRepository) {
        val enabled = repo.isGameModeEnabled()
        val gameApps = repo.getGameModeApps()
        Log.d(TAG, "onGameForeground: pkg=$packageName, enabled=$enabled, apps=$gameApps, active=$isGameModeActive, activePkg=$activeGamePackage")
        if (!enabled) return
        if (!gameApps.contains(packageName)) return

        pendingRestoreJob?.cancel()
        pendingRestoreJob = null

        if (isGameModeActive && activeGamePackage == packageName) {
            return
        }

        activeGamePackage = packageName
        isGameModeActive = true
        Log.d(TAG, "Entering Game Mode for $packageName")

        scope.launch {
            // 1. Auto-brightness: Turn off adaptive brightness while gaming if enabled
            if (repo.isGameModeDisableAutoBrightnessEnabled()) {
                try {
                    val currentMode = Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        1,
                    )
                    if (snapshottedAutoBrightness == null) {
                        snapshottedAutoBrightness = currentMode
                    }
                    if (currentMode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                        val wrote = runCatching {
                            Settings.System.putInt(
                                context.contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS_MODE,
                                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                            )
                        }.getOrDefault(false)
                        if (!wrote) {
                            ShellUtils.runCommand(context, "settings put system screen_brightness_mode 0")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle auto brightness", e)
                }
            }

            // 2. Refresh Rate: Set to 120Hz and lock physical display preferred mode to prevent 30Hz idle drop
            if (repo.isGameModeLockRefreshRateEnabled()) {
                try {
                    if (snapshottedRefreshRate == null) {
                        snapshottedRefreshRate = RefreshRateUtils.getCurrentState(context)
                    }
                    RefreshRateUtils.applyFixedRefreshRate(context, 120f)

                    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                    val mode = display?.mode
                    val width = mode?.physicalWidth ?: 1080
                    val height = mode?.physicalHeight ?: 2424
                    ShellUtils.runCommand(
                        context,
                        "cmd display set-user-preferred-display-mode $width $height 120.0 0 false",
                    )
                    didLockDisplayPreferredMode = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply game refresh rate", e)
                }
            }

            // 3. Purge RAM & ZRAM: Evicts cached background apps, drops kswapd0 thrashing
            if (repo.isGameModeBoostRamEnabled()) {
                ShellUtils.runCommand(context, "am kill-all")
            }

            // 4. Cached Apps Freezer: Suspends cached apps via kernel cgroup freezer
            if (repo.isGameModeCachedFreezerEnabled()) {
                ShellUtils.runCommand(context, "settings put global cached_apps_freezer enabled")
            }

            // 5. Freeze On-Device AI Core: Halts Gemini Nano, model checks, and TPU wakeups
            if (repo.isGameModePauseAiEnabled()) {
                ShellUtils.runCommand(context, "pm disable-user --user 0 com.google.android.aicore")
                didDisableAiCore = true
            }

            // 6. Suspend Device Health Services (Turbo) background battery & diagnostic jobs
            if (repo.isGameModeSuspendHealthServicesEnabled()) {
                ShellUtils.runCommand(
                    context,
                    "cmd package suspend --user 0 com.google.android.apps.turbo && cmd package suspend --user 0 com.google.android.turboadapter && am force-stop com.google.android.apps.turbo && am force-stop com.google.android.turboadapter",
                )
                didSuspendTurbo = true
            }

            // 6b. Game Manager intervention (Performance Mode & custom FPS target)
            val fps = repo.getGameModeFpsOverride()
            val gameCmd = if (fps > 0) {
                "cmd game set --downscale disable --fps $fps --mode 2 $packageName && cmd game mode performance $packageName"
            } else {
                "cmd game set --downscale disable --mode 2 $packageName && cmd game mode performance $packageName"
            }
            ShellUtils.runCommand(context, gameCmd)
            didApplyGameIntervention = true

            // 7. Stabilize Wi-Fi Latency: Force low-latency mode, disable background Wi-Fi/BLE scans & Adaptive Connectivity
            if (repo.isGameModeStabilizeWifiEnabled()) {
                try {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    if (wifiHighPerfLock == null) {
                        @Suppress("DEPRECATION")
                        val highPerf = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Essentials:GameModeHighPerf")
                        highPerf?.setReferenceCounted(false)
                        highPerf?.acquire()
                        wifiHighPerfLock = highPerf
                    }
                    if (wifiLowLatencyLock == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val lowLatency = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Essentials:GameModeLowLatency")
                        lowLatency?.setReferenceCounted(false)
                        lowLatency?.acquire()
                        wifiLowLatencyLock = lowLatency
                    }
                    if (snapshottedWifiScanAlwaysEnabled == null) {
                        snapshottedWifiScanAlwaysEnabled = Settings.Global.getInt(
                            context.contentResolver,
                            "wifi_scan_always_enabled",
                            0,
                        )
                    }
                    if (snapshottedBleScanAlwaysEnabled == null) {
                        snapshottedBleScanAlwaysEnabled = Settings.Global.getInt(
                            context.contentResolver,
                            "ble_scan_always_enabled",
                            0,
                        )
                    }
                    if (snapshottedAdaptiveConnectivity == null) {
                        snapshottedAdaptiveConnectivity = runCatching {
                            Settings.Secure.getInt(
                                context.contentResolver,
                                "adaptive_connectivity_enabled",
                                1,
                            )
                        }.getOrDefault(1)
                    }
                    if (snapshottedNetworkRecommendations == null) {
                        snapshottedNetworkRecommendations = Settings.Global.getString(
                            context.contentResolver,
                            "network_recommendations_enabled",
                        ) ?: "UNSET"
                    }
                    if (snapshottedNetworkAvoidBadWifi == null) {
                        snapshottedNetworkAvoidBadWifi = Settings.Global.getString(
                            context.contentResolver,
                            "network_avoid_bad_wifi",
                        ) ?: "UNSET"
                    }
                    if (snapshottedWifiPowerSave == null) {
                        snapshottedWifiPowerSave = runCatching {
                            Settings.Global.getInt(
                                context.contentResolver,
                                "wifi_power_save",
                                120,
                            )
                        }.getOrDefault(120)
                    }
                    ShellUtils.runCommand(
                        context,
                        "settings put global wifi_scan_always_enabled 0 && settings put global ble_scan_always_enabled 0 && settings put secure adaptive_connectivity_enabled 0 && settings put global wifi_scan_throttle_enabled 1 && settings put global network_recommendations_enabled 0 && settings put global network_avoid_bad_wifi 0 && settings put global wifi_power_save 0 && cmd wifi set-scan-always-available disabled && cmd wifi set-network-selection-config disabled disabled -a 2 && cmd wifi set-ipreach-disconnect disabled && cmd package suspend --user 0 com.google.android.apps.carrier.carrierwifi",
                    )
                    didLockWifiNetworkSelection = true
                    didDisableIpReachDisconnect = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stabilize Wi-Fi latency", e)
                }
            }

            // 8. Pause AirSync background sync & WebDAV transfers
            if (repo.isGameModePauseAirSyncEnabled()) {
                val pauseIntent = Intent("com.sameerasw.airsync.action.PAUSE_SYNC").apply {
                    setPackage("com.sameerasw.airsync")
                }
                context.sendBroadcast(pauseIntent, "com.sameerasw.permission.ESSENTIALS_AIRSYNC_BRIDGE")
                didDisableAirSync = true
            }

            // 9. Disable Private DNS (DoT) while gaming to avoid DNS query latency stalls
            if (repo.isGameModeDisablePrivateDnsEnabled()) {
                try {
                    val currentMode = Settings.Global.getString(
                        context.contentResolver,
                        "private_dns_mode",
                    )
                    val currentSpecifier = Settings.Global.getString(
                        context.contentResolver,
                        "private_dns_specifier",
                    )
                    val savedSnap = repo.getGameModeSnapPrivateDnsMode()

                    // Only snapshot and disable if Private DNS is currently active or a specifier is configured
                    if (!currentMode.isNullOrEmpty() && currentMode != "off") {
                        snapshottedPrivateDnsMode = currentMode
                        snapshottedPrivateDnsSpecifier = currentSpecifier
                        repo.setGameModeSnapPrivateDnsMode(currentMode)
                        repo.setGameModeSnapPrivateDnsSpecifier(currentSpecifier)
                        repo.setGameModeDidDisablePrivateDns(true)
                        didDisablePrivateDns = true

                        runCatching {
                            Settings.Global.putString(context.contentResolver, "private_dns_mode", "off")
                        }
                        ShellUtils.runCommand(context, "settings put global private_dns_mode off")
                        Log.d(TAG, "Disabled Private DNS (was mode=$currentMode, specifier=$currentSpecifier)")
                    } else if (!savedSnap.isNullOrEmpty() && savedSnap != "off") {
                        snapshottedPrivateDnsMode = savedSnap
                        snapshottedPrivateDnsSpecifier = repo.getGameModeSnapPrivateDnsSpecifier() ?: currentSpecifier
                        didDisablePrivateDns = true
                    } else if (!currentSpecifier.isNullOrEmpty()) {
                        snapshottedPrivateDnsMode = "hostname"
                        snapshottedPrivateDnsSpecifier = currentSpecifier
                        repo.setGameModeSnapPrivateDnsMode("hostname")
                        repo.setGameModeSnapPrivateDnsSpecifier(currentSpecifier)
                        repo.setGameModeDidDisablePrivateDns(true)
                        didDisablePrivateDns = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable private DNS", e)
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.game_mode_toast_activated, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onGameBackground(
        context: Context,
        oldPackage: String?,
        newPackage: String?,
        repo: SettingsRepository,
    ) {
        Log.d(TAG, "onGameBackground: old=$oldPackage, new=$newPackage, active=$isGameModeActive, activePkg=$activeGamePackage")
        if (!isGameModeActive) return
        if (newPackage == context.packageName) return
        if (newPackage == "com.google.android.gms" ||
            newPackage == "com.android.vending" ||
            newPackage == "android" ||
            newPackage?.startsWith("android.service.games") == true
        ) return

        val gameApps = repo.getGameModeApps()
        if (newPackage != null && gameApps.contains(newPackage)) {
            // Switched directly between two games in the game list
            return
        }

        pendingRestoreJob?.cancel()
        pendingRestoreJob = scope.launch {
            val delaySeconds = repo.getGameModeRestoreDelay().coerceAtLeast(0)
            val delayMs = if (delaySeconds > 0) delaySeconds * 1000L else 300L
            Log.d(TAG, "Game backgrounded. Waiting ${delayMs}ms before restoring settings...")
            delay(delayMs)
            val currentPkg = activeGamePackage ?: oldPackage
            if (currentPkg != null) {
                // 1. Verify via AppFlowHandler
                val flowCurrent = AppFlowHandler.getInstance(context).currentPackage
                if (flowCurrent != null && gameApps.contains(flowCurrent)) {
                    Log.d(TAG, "Restore aborted: AppFlowHandler reports game $flowCurrent is still foreground")
                    return@launch
                }

                // 2. Verify with topResumedActivity and mCurrentFocus in shell
                val topResumed = ShellUtils.runCommandWithOutput(context, "dumpsys activity activities | grep topResumedActivity", notifyOnError = false)
                val currentFocus = if (topResumed.isNullOrBlank() || !topResumed.contains(currentPkg)) {
                    ShellUtils.runCommandWithOutput(context, "dumpsys window | grep mCurrentFocus", notifyOnError = false)
                } else null

                if ((!topResumed.isNullOrBlank() && (topResumed.contains(currentPkg) || gameApps.any { topResumed.contains(it) })) ||
                    (!currentFocus.isNullOrBlank() && (currentFocus.contains(currentPkg) || gameApps.any { currentFocus.contains(it) }))
                ) {
                    Log.d(TAG, "Restore aborted: Shell indicates game is still topResumed/focused: topResumed=$topResumed, focus=$currentFocus")
                    AppFlowHandler.getInstance(context).onPackageChanged(currentPkg, isFromUsageStats = true)
                    return@launch
                }

                restoreGameMode(context, currentPkg, repo)
            }
        }
    }

    fun restoreGameMode(context: Context, gamePackage: String, repo: SettingsRepository) {
        if (!isGameModeActive) return
        Log.d(TAG, "Exiting Game Mode for $gamePackage")
        isGameModeActive = false
        activeGamePackage = null

        scope.launch {
            // 1. Re-enable AI Core
            if (didDisableAiCore) {
                ShellUtils.runCommand(context, "pm enable com.google.android.aicore")
                didDisableAiCore = false
            }

            // 1b. Re-enable Device Health Services (Turbo)
            if (didSuspendTurbo) {
                ShellUtils.runCommand(
                    context,
                    "cmd package unsuspend --user 0 com.google.android.apps.turbo && cmd package unsuspend --user 0 com.google.android.turboadapter",
                )
                didSuspendTurbo = false
            }

            // 2. Resume AirSync
            if (didDisableAirSync) {
                val resumeIntent = Intent("com.sameerasw.airsync.action.RESUME_SYNC").apply {
                    setPackage("com.sameerasw.airsync")
                }
                context.sendBroadcast(resumeIntent, "com.sameerasw.permission.ESSENTIALS_AIRSYNC_BRIDGE")
                didDisableAirSync = false
            }

            // 3. Reset Game Manager intervention
            if (didApplyGameIntervention) {
                ShellUtils.runCommand(context, "cmd game reset $gamePackage && cmd game mode standard $gamePackage")
                didApplyGameIntervention = false
            }

            // 4. Restore auto-brightness
            snapshottedAutoBrightness?.let { mode ->
                val wrote = runCatching {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        mode,
                    )
                }.getOrDefault(false)
                if (!wrote) {
                    ShellUtils.runCommand(context, "settings put system screen_brightness_mode $mode")
                }
                snapshottedAutoBrightness = null
            }

            // 5. Restore Refresh Rate
            if (didLockDisplayPreferredMode) {
                ShellUtils.runCommand(context, "cmd display clear-user-preferred-display-mode 0")
                didLockDisplayPreferredMode = false
            }

            snapshottedRefreshRate?.let { snapshot ->
                if (snapshot.isSystemManaged) {
                    RefreshRateUtils.resetRefreshRate(context, snapshot.usesInfinityDefaultPeak)
                } else if (snapshot.min <= 0f && snapshot.peak > 0f) {
                    RefreshRateUtils.applyDynamicRefreshRate(context, snapshot.peak)
                } else if (snapshot.min > 0f) {
                    RefreshRateUtils.applyFixedRefreshRate(context, snapshot.min)
                }
                snapshottedRefreshRate = null
            }

            // 6. Restore Wi-Fi scanning, BLE scanning, Adaptive Connectivity, network selection config & WifiLocks
            wifiHighPerfLock?.let {
                try {
                    if (it.isHeld) it.release()
                } catch (_: Exception) { }
            }
            wifiHighPerfLock = null

            wifiLowLatencyLock?.let {
                try {
                    if (it.isHeld) it.release()
                } catch (_: Exception) { }
            }
            wifiLowLatencyLock = null

            snapshottedWifiScanAlwaysEnabled?.let { prevScan ->
                val cmd = if (didLockWifiNetworkSelection) {
                    "settings put global wifi_scan_always_enabled $prevScan && cmd wifi set-scan-always-available enabled && cmd wifi set-network-selection-config enabled enabled -a 0 && cmd package unsuspend --user 0 com.google.android.apps.carrier.carrierwifi"
                } else {
                    "settings put global wifi_scan_always_enabled $prevScan"
                }
                ShellUtils.runCommand(context, cmd)
                snapshottedWifiScanAlwaysEnabled = null
                didLockWifiNetworkSelection = false
            }

            snapshottedWifiPowerSave?.let { prevPowerSave ->
                ShellUtils.runCommand(context, "settings put global wifi_power_save $prevPowerSave")
                snapshottedWifiPowerSave = null
            }

            if (didDisableIpReachDisconnect) {
                ShellUtils.runCommand(context, "cmd wifi set-ipreach-disconnect enabled")
                didDisableIpReachDisconnect = false
            }

            snapshottedBleScanAlwaysEnabled?.let { prevBle ->
                ShellUtils.runCommand(context, "settings put global ble_scan_always_enabled $prevBle")
                snapshottedBleScanAlwaysEnabled = null
            }

            snapshottedAdaptiveConnectivity?.let { prevAdaptive ->
                ShellUtils.runCommand(context, "settings put secure adaptive_connectivity_enabled $prevAdaptive")
                snapshottedAdaptiveConnectivity = null
            }

            snapshottedNetworkRecommendations?.let { prev ->
                val cmd = if (prev == "UNSET") {
                    "settings delete global network_recommendations_enabled"
                } else {
                    "settings put global network_recommendations_enabled $prev"
                }
                ShellUtils.runCommand(context, cmd)
                snapshottedNetworkRecommendations = null
            }

            snapshottedNetworkAvoidBadWifi?.let { prev ->
                val cmd = if (prev == "UNSET") {
                    "settings delete global network_avoid_bad_wifi"
                } else {
                    "settings put global network_avoid_bad_wifi $prev"
                }
                ShellUtils.runCommand(context, cmd)
                snapshottedNetworkAvoidBadWifi = null
            }

            // 7. Restore Private DNS
            try {
                val prevMode = snapshottedPrivateDnsMode ?: repo.getGameModeSnapPrivateDnsMode()
                val prevSpecifier = snapshottedPrivateDnsSpecifier ?: repo.getGameModeSnapPrivateDnsSpecifier()
                val shouldRestore = didDisablePrivateDns || repo.didGameModeDisablePrivateDns() || !prevSpecifier.isNullOrEmpty()

                val targetMode = if (!prevMode.isNullOrEmpty() && prevMode != "off") {
                    prevMode
                } else if (!prevSpecifier.isNullOrEmpty()) {
                    "hostname"
                } else null

                if (shouldRestore && targetMode != null) {
                    Log.d(TAG, "Restoring Private DNS to mode=$targetMode, specifier=$prevSpecifier")
                    runCatching {
                        if (targetMode == "hostname" && !prevSpecifier.isNullOrEmpty()) {
                            Settings.Global.putString(context.contentResolver, "private_dns_specifier", prevSpecifier)
                        }
                        Settings.Global.putString(context.contentResolver, "private_dns_mode", targetMode)
                    }
                    val shellCmd = if (targetMode == "hostname" && !prevSpecifier.isNullOrEmpty()) {
                        "settings put global private_dns_specifier $prevSpecifier && settings put global private_dns_mode $targetMode"
                    } else {
                        "settings put global private_dns_mode $targetMode"
                    }
                    ShellUtils.runCommand(context, shellCmd)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore Private DNS", e)
            } finally {
                repo.setGameModeSnapPrivateDnsMode(null)
                repo.setGameModeSnapPrivateDnsSpecifier(null)
                repo.setGameModeDidDisablePrivateDns(false)
                snapshottedPrivateDnsMode = null
                snapshottedPrivateDnsSpecifier = null
                didDisablePrivateDns = false
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.game_mode_toast_restored, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun restorePrivateDnsIfDisabled(context: Context, repo: SettingsRepository) {
        scope.launch {
            try {
                val prevMode = snapshottedPrivateDnsMode ?: repo.getGameModeSnapPrivateDnsMode()
                val prevSpecifier = snapshottedPrivateDnsSpecifier ?: repo.getGameModeSnapPrivateDnsSpecifier()
                val shouldRestore = didDisablePrivateDns || repo.didGameModeDisablePrivateDns() || !prevSpecifier.isNullOrEmpty()

                val targetMode = if (!prevMode.isNullOrEmpty() && prevMode != "off") {
                    prevMode
                } else if (!prevSpecifier.isNullOrEmpty()) {
                    "hostname"
                } else null

                if (shouldRestore && targetMode != null) {
                    Log.d(TAG, "Restoring Private DNS early to mode=$targetMode, specifier=$prevSpecifier")
                    runCatching {
                        if (targetMode == "hostname" && !prevSpecifier.isNullOrEmpty()) {
                            Settings.Global.putString(context.contentResolver, "private_dns_specifier", prevSpecifier)
                        }
                        Settings.Global.putString(context.contentResolver, "private_dns_mode", targetMode)
                    }
                    val shellCmd = if (targetMode == "hostname" && !prevSpecifier.isNullOrEmpty()) {
                        "settings put global private_dns_specifier $prevSpecifier && settings put global private_dns_mode $targetMode"
                    } else {
                        "settings put global private_dns_mode $targetMode"
                    }
                    ShellUtils.runCommand(context, shellCmd)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore Private DNS early", e)
            } finally {
                repo.setGameModeSnapPrivateDnsMode(null)
                repo.setGameModeSnapPrivateDnsSpecifier(null)
                repo.setGameModeDidDisablePrivateDns(false)
                snapshottedPrivateDnsMode = null
                snapshottedPrivateDnsSpecifier = null
                didDisablePrivateDns = false
            }
        }
    }
}

