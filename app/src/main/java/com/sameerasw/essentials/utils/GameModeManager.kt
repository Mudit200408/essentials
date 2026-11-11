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

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.core.content.ContextCompat
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
    private var snapshottedColumbusEnabled: Int? = null
    private var snapshottedWifiScanAlwaysEnabled: Int? = null
    private var snapshottedBleScanAlwaysEnabled: Int? = null
    private var snapshottedAdaptiveConnectivity: Int? = null
    private var snapshottedNetworkRecommendations: String? = null
    private var snapshottedNetworkAvoidBadWifi: String? = null
    private var snapshottedPrivateDnsMode: String? = null
    private var snapshottedPrivateDnsSpecifier: String? = null
    private var didDisableAiCore = false
    private var didSuspendTurbo = false
    private var didDisableSettingsIntel = false
    private var didDisableScone = false
    private var didSuspendCarrierWifi = false
    private var didSuspendGearhead = false
    private var didDisableColumbus = false
    private var didDisableAutoBrightness = false
    private var didDisableAirSync = false
    private var didDisablePrivateDns = false
    private var didApplyGameIntervention = false
    private var didLockDisplayPreferredMode = false
    private var didLockWifiNetworkSelection = false
    private var snapshottedWifiWakeup: Int? = null
    private var snapshottedWifiNotification: Int? = null
    private var snapshottedWifiPowerSave: String? = null
    private var didDisableIpReachDisconnect = false
    private var wifiHighPerfLock: WifiManager.WifiLock? = null
    private var wifiLowLatencyLock: WifiManager.WifiLock? = null
    private var snapshottedLowPowerTriggerLevel: Int? = null
    private var snapshottedLowPower: Int? = null
    private var snapshottedAutoSync: Boolean? = null
    private var batterySaverReceiver: BroadcastReceiver? = null
    private var notifiedBattery20 = false
    private var notifiedBattery10 = false
    private var notifiedBattery5 = false

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
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                    )
                    val savedSnap = repo.getGameModeSnapAutoBrightness()
                    if (currentMode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                        snapshottedAutoBrightness = currentMode
                        repo.setGameModeSnapAutoBrightness(currentMode)
                        repo.setGameModeDidDisableAutoBrightness(true)
                        didDisableAutoBrightness = true

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
                        Log.d(TAG, "Disabled Auto Brightness (was $currentMode)")
                    } else if (savedSnap != null && savedSnap != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                        snapshottedAutoBrightness = savedSnap
                        didDisableAutoBrightness = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle auto brightness", e)
                }
            }

            // 1b. Quick Tap (Columbus): Temporarily disable gesture monitor to eliminate input jank
            if (repo.isGameModeDisableQuickTapEnabled()) {
                try {
                    val currentColumbus = Settings.Secure.getInt(
                        context.contentResolver,
                        "columbus_enabled",
                        -1,
                    )
                    val savedColumbus = repo.getGameModeSnapColumbusEnabled()
                    if (currentColumbus == 1) {
                        snapshottedColumbusEnabled = 1
                        repo.setGameModeSnapColumbusEnabled(1)
                        didDisableColumbus = true
                        val wrote = runCatching {
                            Settings.Secure.putInt(
                                context.contentResolver,
                                "columbus_enabled",
                                0,
                            )
                        }.getOrDefault(false)
                        if (!wrote) {
                            ShellUtils.runCommand(context, "settings put secure columbus_enabled 0")
                        }
                        Log.d(TAG, "Disabled Quick Tap (columbus_enabled=0)")
                    } else if (savedColumbus == 1) {
                        snapshottedColumbusEnabled = 1
                        didDisableColumbus = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable Quick Tap", e)
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
                        "cmd display set-user-preferred-display-mode $width $height 120 0",
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

            // 5. Freeze Selected Background Services
            val suspended = repo.getGameModeSuspendedServices()
            val suspendCmds = mutableListOf<String>()

            if (suspended.contains("com.google.android.aicore")) {
                suspendCmds.add("pm disable-user --user 0 com.google.android.aicore")
                didDisableAiCore = true
            }
            if (suspended.contains("com.google.android.apps.turbo")) {
                suspendCmds.add("pm disable-user --user 0 com.google.android.apps.turbo && pm disable-user --user 0 com.google.android.turboadapter")
                didSuspendTurbo = true
            }
            if (suspended.contains("com.google.android.settings.intelligence")) {
                suspendCmds.add("pm disable-user --user 0 com.google.android.settings.intelligence")
                didDisableSettingsIntel = true
            }
            if (suspended.contains("com.google.android.apps.scone")) {
                suspendCmds.add("pm disable-user --user 0 com.google.android.apps.scone")
                didDisableScone = true
            }
            if (suspended.contains("com.google.android.apps.carrier.carrierwifi")) {
                suspendCmds.add("cmd package suspend --user 0 com.google.android.apps.carrier.carrierwifi")
                didSuspendCarrierWifi = true
            }
            if (suspended.contains("com.google.android.projection.gearhead")) {
                suspendCmds.add("cmd package suspend --user 0 com.google.android.projection.gearhead")
                didSuspendGearhead = true
            }

            if (suspendCmds.isNotEmpty()) {
                ShellUtils.runCommand(context, suspendCmds.joinToString(" && "))
            }

            // 6b. Game Manager intervention (Selected Profile & custom FPS target)
            val interventionMode = repo.getGameModeInterventionMode()
            val modeName = when (interventionMode) {
                1 -> "standard"
                3 -> "battery"
                else -> "performance"
            }
            val fps = repo.getGameModeFpsOverride()
            val gameCmd = if (fps > 0) {
                "cmd game set --downscale disable --fps $fps --mode $interventionMode $packageName && cmd game mode $modeName $packageName"
            } else {
                "cmd game set --downscale disable --mode $interventionMode $packageName && cmd game mode $modeName $packageName"
            }
            ShellUtils.runCommand(context, gameCmd)
            didApplyGameIntervention = true

            // 7. Stabilize Wi-Fi Latency: Force low-latency mode, disable background Wi-Fi/BLE scans & Adaptive Connectivity
            if (repo.isGameModeStabilizeWifiEnabled()) {
                try {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val wifiCmds = mutableListOf<String>()

                    // 7a. Low-Latency Wi-Fi lock & power save bypass
                    if (repo.isGameModeWifiLowLatencyEnabled()) {
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
                        if (snapshottedWifiPowerSave == null) {
                            snapshottedWifiPowerSave = Settings.Global.getString(
                                context.contentResolver,
                                "wifi_power_save",
                            ) ?: "UNSET"
                        }
                        wifiCmds.add("settings put global wifi_power_save 0")
                    }

                    // 7b. Suppress background Wi-Fi and BLE scans
                    if (repo.isGameModeWifiSuppressScansEnabled()) {
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
                        if (snapshottedWifiWakeup == null) {
                            snapshottedWifiWakeup = runCatching {
                                Settings.Global.getInt(
                                    context.contentResolver,
                                    "wifi_wakeup_enabled",
                                    1,
                                )
                            }.getOrDefault(1)
                        }
                        if (snapshottedWifiNotification == null) {
                            snapshottedWifiNotification = runCatching {
                                Settings.Global.getInt(
                                    context.contentResolver,
                                    "wifi_networks_available_notification_on",
                                    1,
                                )
                            }.getOrDefault(1)
                        }
                        wifiCmds.add("settings put global wifi_scan_always_enabled 0")
                        wifiCmds.add("settings put global ble_scan_always_enabled 0")
                        wifiCmds.add("settings put global wifi_scan_throttle_enabled 1")
                        wifiCmds.add("settings put global wifi_wakeup_enabled 0")
                        wifiCmds.add("settings put global wifi_networks_available_notification_on 0")
                        wifiCmds.add("cmd wifi set-scan-always-available disabled")
                    }

                    // 7c. Lock network sufficiency & disable network scoring ping churn
                    if (repo.isGameModeWifiLockSufficiencyEnabled()) {
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
                        wifiCmds.add("settings put secure adaptive_connectivity_enabled 0")
                        wifiCmds.add("settings put global network_recommendations_enabled 0")
                        wifiCmds.add("settings put global network_avoid_bad_wifi 0")
                        wifiCmds.add("cmd wifi set-network-selection-config enabled enabled -a 0")
                        didLockWifiNetworkSelection = true
                    }

                    // 7d. Prevent ARP disconnects
                    if (repo.isGameModeWifiPreventArpDisconnectEnabled()) {
                        wifiCmds.add("cmd wifi set-ipreach-disconnect disabled")
                        didDisableIpReachDisconnect = true
                    }

                    if (wifiCmds.isNotEmpty()) {
                        ShellUtils.runCommand(context, wifiCmds.joinToString(" && "))
                    }
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

            // 10. Suppress Battery Saver & monitor low battery thresholds
            if (repo.isGameModeBlockBatterySaverEnabled()) {
                try {
                    notifiedBattery20 = false
                    notifiedBattery10 = false
                    notifiedBattery5 = false

                    if (snapshottedLowPowerTriggerLevel == null) {
                        snapshottedLowPowerTriggerLevel = Settings.Global.getInt(
                            context.contentResolver,
                            "low_power_trigger_level",
                            0,
                        )
                    }
                    if (snapshottedLowPower == null) {
                        snapshottedLowPower = Settings.Global.getInt(
                            context.contentResolver,
                            "low_power",
                            0,
                        )
                    }

                    ShellUtils.runCommand(
                        context,
                        "settings put global low_power_trigger_level 0 && settings put global low_power 0 && cmd power set-mode 0",
                    )

                    if (batterySaverReceiver == null) {
                        val filter = IntentFilter().apply {
                            addAction(Intent.ACTION_BATTERY_CHANGED)
                            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                        }
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(c: Context?, intent: Intent?) {
                                if (!isGameModeActive) return
                                when (intent?.action) {
                                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                                        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                        if (pm?.isPowerSaveMode == true) {
                                            ShellUtils.runCommand(
                                                context,
                                                "settings put global low_power 0 && cmd power set-mode 0",
                                            )
                                        }
                                    }
                                    Intent.ACTION_BATTERY_CHANGED -> {
                                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                            status == BatteryManager.BATTERY_STATUS_FULL
                                        if (isCharging) {
                                            notifiedBattery20 = false
                                            notifiedBattery10 = false
                                            notifiedBattery5 = false
                                        } else if (level >= 0 && scale > 0) {
                                            val pct = (level * 100) / scale
                                            if (pct > 20) {
                                                notifiedBattery20 = false
                                                notifiedBattery10 = false
                                                notifiedBattery5 = false
                                            } else if (pct in 11..20 && !notifiedBattery20) {
                                                notifiedBattery20 = true
                                                showToast(context, context.getString(R.string.game_mode_battery_low_20))
                                            } else if (pct in 6..10 && !notifiedBattery10) {
                                                notifiedBattery10 = true
                                                showToast(context, context.getString(R.string.game_mode_battery_low_10))
                                            } else if (pct in 1..5 && !notifiedBattery5) {
                                                notifiedBattery5 = true
                                                showToast(context, context.getString(R.string.game_mode_battery_low_5))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        ContextCompat.registerReceiver(
                            context.applicationContext,
                            receiver,
                            filter,
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        batterySaverReceiver = receiver
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to suppress battery saver", e)
                }
            }

            // 11. Pause Master Auto-Sync to prevent background sync I/O and CPU spikes
            if (repo.isGameModePauseAutoSyncEnabled()) {
                try {
                    val isSyncActive = ContentResolver.getMasterSyncAutomatically()
                    if (snapshottedAutoSync == null) {
                        snapshottedAutoSync = isSyncActive
                    }
                    if (isSyncActive) {
                        ContentResolver.setMasterSyncAutomatically(false)
                        Log.d(TAG, "Master auto-sync paused for gaming")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pause auto-sync", e)
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

            // 1b. Re-enable Device Health & Intelligence Services (Turbo & Settings Intelligence)
            if (didSuspendTurbo) {
                ShellUtils.runCommand(
                    context,
                    "pm enable com.google.android.apps.turbo && pm enable com.google.android.turboadapter",
                )
                didSuspendTurbo = false
            }

            if (didDisableSettingsIntel) {
                ShellUtils.runCommand(context, "pm enable com.google.android.settings.intelligence")
                didDisableSettingsIntel = false
            }

            if (didDisableScone) {
                ShellUtils.runCommand(context, "pm enable com.google.android.apps.scone")
                didDisableScone = false
            }

            if (didSuspendCarrierWifi) {
                ShellUtils.runCommand(context, "cmd package unsuspend --user 0 com.google.android.apps.carrier.carrierwifi")
                didSuspendCarrierWifi = false
            }

            if (didSuspendGearhead) {
                ShellUtils.runCommand(context, "cmd package unsuspend --user 0 com.google.android.projection.gearhead")
                didSuspendGearhead = false
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
            try {
                val prevBrightnessMode = snapshottedAutoBrightness
                    ?: repo.getGameModeSnapAutoBrightness()
                    ?: if (didDisableAutoBrightness || repo.didGameModeDisableAutoBrightness()) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else null

                if (prevBrightnessMode != null && (didDisableAutoBrightness || repo.didGameModeDisableAutoBrightness() || prevBrightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)) {
                    Log.d(TAG, "Restoring Auto Brightness to $prevBrightnessMode")
                    val wrote = runCatching {
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            prevBrightnessMode,
                        )
                    }.getOrDefault(false)
                    if (!wrote) {
                        ShellUtils.runCommand(context, "settings put system screen_brightness_mode $prevBrightnessMode")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore auto brightness", e)
            } finally {
                snapshottedAutoBrightness = null
                repo.setGameModeSnapAutoBrightness(null)
                repo.setGameModeDidDisableAutoBrightness(false)
                didDisableAutoBrightness = false
            }

            // 4b. Restore Quick Tap (Columbus)
            try {
                val prevColumbus = snapshottedColumbusEnabled ?: repo.getGameModeSnapColumbusEnabled()
                if (prevColumbus != null && (didDisableColumbus || prevColumbus == 1)) {
                    val wrote = runCatching {
                        Settings.Secure.putInt(
                            context.contentResolver,
                            "columbus_enabled",
                            prevColumbus,
                        )
                    }.getOrDefault(false)
                    if (!wrote) {
                        ShellUtils.runCommand(context, "settings put secure columbus_enabled $prevColumbus")
                    }
                    Log.d(TAG, "Restored Quick Tap to $prevColumbus")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore Quick Tap", e)
            } finally {
                snapshottedColumbusEnabled = null
                repo.setGameModeSnapColumbusEnabled(null)
                didDisableColumbus = false
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
                ShellUtils.runCommand(context, "settings put global wifi_scan_always_enabled $prevScan && cmd wifi set-scan-always-available enabled")
                snapshottedWifiScanAlwaysEnabled = null
            }

            if (didLockWifiNetworkSelection) {
                ShellUtils.runCommand(context, "cmd wifi set-network-selection-config enabled enabled -a 0")
                didLockWifiNetworkSelection = false
            }

            snapshottedWifiWakeup?.let { prevWakeup ->
                ShellUtils.runCommand(context, "settings put global wifi_wakeup_enabled $prevWakeup")
                snapshottedWifiWakeup = null
            }

            snapshottedWifiNotification?.let { prevNotif ->
                ShellUtils.runCommand(context, "settings put global wifi_networks_available_notification_on $prevNotif")
                snapshottedWifiNotification = null
            }

            snapshottedWifiPowerSave?.let { prevPowerSave ->
                val cmd = if (prevPowerSave == "UNSET") {
                    "settings delete global wifi_power_save"
                } else {
                    "settings put global wifi_power_save $prevPowerSave"
                }
                ShellUtils.runCommand(context, cmd)
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

            // 8. Restore Battery Saver & Low Power trigger level
            batterySaverReceiver?.let {
                runCatching { context.applicationContext.unregisterReceiver(it) }
            }
            batterySaverReceiver = null
            notifiedBattery20 = false
            notifiedBattery10 = false
            notifiedBattery5 = false

            snapshottedLowPowerTriggerLevel?.let { prevTrigger ->
                ShellUtils.runCommand(context, "settings put global low_power_trigger_level $prevTrigger")
                snapshottedLowPowerTriggerLevel = null
            }

            snapshottedLowPower?.let { prevLowPower ->
                if (prevLowPower == 1) {
                    ShellUtils.runCommand(context, "settings put global low_power 1 && cmd power set-mode 1")
                }
                snapshottedLowPower = null
            }

            // 9. Restore Master Auto-Sync
            snapshottedAutoSync?.let { wasActive ->
                if (wasActive) {
                    try {
                        ContentResolver.setMasterSyncAutomatically(true)
                        Log.d(TAG, "Master auto-sync restored")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore auto-sync", e)
                    }
                }
                snapshottedAutoSync = null
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.game_mode_toast_restored, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showToast(context: Context, text: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
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

