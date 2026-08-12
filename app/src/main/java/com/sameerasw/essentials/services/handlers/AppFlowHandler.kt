/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: AppFlowHandler.kt
 * Description: Background service component for AppFlowHandler.kt.
 */

package com.sameerasw.essentials.services.handlers

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.domain.diy.Automation
import com.sameerasw.essentials.domain.diy.DIYRepository
import com.sameerasw.essentials.domain.model.AppRefreshRateConfig
import com.sameerasw.essentials.domain.model.AppSelection
import com.sameerasw.essentials.domain.model.ShutUpAppConfig
import com.sameerasw.essentials.domain.model.disableWirelessDebugging
import com.sameerasw.essentials.services.NotificationListener
import com.sameerasw.essentials.services.automation.executors.CombinedActionExecutor
import com.sameerasw.essentials.utils.FreezeManager
import com.sameerasw.essentials.utils.RefreshRateUtils
import com.sameerasw.essentials.utils.ShutUpManager
import com.sameerasw.essentials.utils.StatusBarManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AppFlowHandler private constructor(
    context: Context
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var lastIsLandscape = isDeviceInLandscape()
    private val settingsRepository by lazy { SettingsRepository(context) }
    private val notificationListenerComponent by lazy {
        ComponentName(context, NotificationListener::class.java)
    }
    private val componentCallbacks = object : android.content.ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val isLandscape = isDeviceInLandscape()
            if (isLandscape != lastIsLandscape) {
                lastIsLandscape = isLandscape
                val currentPkg = currentPackage
                if (currentPkg != null) {
                    checkPerAppRefreshRate(currentPkg)
                }
            }
        }
        override fun onLowMemory() {}
        override fun onTrimMemory(level: Int) {}
    }

    private val prefsChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsRepository.KEY_PER_APP_REFRESH_RATE_CONFIGS) {
            cachedRefreshRateConfigs = null
            currentPackage?.let { checkPerAppRefreshRate(it) }
        } else if (key == SettingsRepository.KEY_PER_APP_REFRESH_RATE_ENABLED) {
            currentPackage?.let { checkPerAppRefreshRate(it) }
        }
    }

    private val mediaReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sameerasw.essentials.MEDIA_PLAYBACK_CHANGED") {
                val pkg = intent.getStringExtra("package_name")
                if (pkg != null) {
                    mediaPlayingPackages[pkg] = intent.getBooleanExtra("is_playing", false)
                    if (pkg == currentPackage) {
                        checkPerAppRefreshRate(pkg)
                    }
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                val isLandscape = isDeviceInLandscape()
                if (isLandscape != lastIsLandscape) {
                    lastIsLandscape = isLandscape
                    val currentPkg = currentPackage
                    if (currentPkg != null) {
                        Log.d("AppFlowHandler", "Display orientation changed: isLandscape=$isLandscape for $currentPkg")
                        checkPerAppRefreshRate(currentPkg)
                    }
                }
            }
        }
    }

    private val audioPlaybackCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                val currentPkg = currentPackage
                if (currentPkg != null) {
                    Log.d("AppFlowHandler", "AudioPlaybackCallback: playback config changed, checking refresh rate for $currentPkg")
                    checkPerAppRefreshRate(currentPkg)
                }
            }
        }
    } else null

    private val activeSessionsListener = android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        val currentPkg = currentPackage
        if (currentPkg != null) {
            Log.d("AppFlowHandler", "OnActiveSessionsChangedListener: active sessions changed, checking refresh rate for $currentPkg")
            checkPerAppRefreshRate(currentPkg)
        }
    }

    private val prefs by lazy { context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE) }
    private val authenticatedPackages = mutableSetOf<String>()
    private val lastLeaveTimes = mutableMapOf<String, Long>()
    private val activeCountdowns = mutableMapOf<String, Job>()

    // App Lock State
    private var lockingPackage: String? = null
    private var lastLockRequestTime: Long = 0
    @Volatile
    var currentPackage: String? = null
        private set
    private var currentUsageStatsPackage: String? = null

    // Shut-Up State
    private var pendingShutUpRestoreJob: Job? = null
    private var pendingShutUpRestorePackage: String? = null

    // Per-App Refresh Rate State
    private var perAppRateSnapshot: RefreshRateUtils.RefreshRateState? = null
    private var perAppCurrentPackage: String? = null
    private var pendingRateRunnable: Runnable? = null
    private var pendingRestoreRunnable: Runnable? = null
    private var refreshRateJob: Job? = null
    @Volatile
    private var cachedRefreshRateConfigs: List<AppRefreshRateConfig>? = null
    private val mediaPlayingPackages = ConcurrentHashMap<String, Boolean>()

    // App Automation State
    private val activeAppAutomationIds = mutableSetOf<String>()

    // Night Light State
    private var wasNightLightOnBeforeAutoToggle = false
    private var isNightLightAutoToggledOff = false
    private var pendingNLRunnable: Runnable? = null
    private val nlDebounceDelay = 500L

    private val shutUpReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                when (intent?.action) {
                    ACTION_FREEZE_NOW -> {
                        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
                        activeCountdowns[packageName]?.cancel()
                        activeCountdowns.remove(packageName)
                        context?.let { FreezeManager.freezeApp(it, packageName) }
                        cancelNotification(packageName)
                    }

                    ACTION_ABORT_FREEZE -> {
                        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
                        activeCountdowns[packageName]?.cancel()
                        activeCountdowns.remove(packageName)
                        cancelNotification(packageName)
                    }

                    ACTION_RESTORE_NOW -> {
                        cancelRestoreNotification()
                        val autoPkg = intent.getStringExtra(EXTRA_AUTO_ARCHIVE_PACKAGE)
                        val pkgName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                        val settingsRepo =
                            com.sameerasw.essentials.data.repository.SettingsRepository(
                                context ?: return,
                            )
                        val config =
                            if (pkgName != null) {
                                settingsRepo.loadShutUpConfigs().find { it.packageName == pkgName }
                            } else {
                                null
                            }
                        restoreShutUpSettings(settingsRepo, config, autoPkg, forceRestore = true)
                    }
                }
            }
        }

    init {
        this.context.registerComponentCallbacks(componentCallbacks)

        val displayManager = this.context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.registerDisplayListener(displayListener, handler)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioPlaybackCallback != null) {
            val audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.registerAudioPlaybackCallback(audioPlaybackCallback, handler)
        }

        try {
            val msm = this.context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager
            msm?.addOnActiveSessionsChangedListener(activeSessionsListener, notificationListenerComponent, handler)
        } catch (_: Exception) {}

        val mediaFilter =
            IntentFilter().apply {
                addAction("com.sameerasw.essentials.MEDIA_PLAYBACK_CHANGED")
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.context.registerReceiver(mediaReceiver, mediaFilter, Context.RECEIVER_EXPORTED)
        } else {
            this.context.registerReceiver(mediaReceiver, mediaFilter)
        }

        val shutUpFilter =
            IntentFilter().apply {
                addAction(ACTION_FREEZE_NOW)
                addAction(ACTION_ABORT_FREEZE)
                addAction(ACTION_RESTORE_NOW)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.context.registerReceiver(shutUpReceiver, shutUpFilter, Context.RECEIVER_EXPORTED)
        } else {
            this.context.registerReceiver(shutUpReceiver, shutUpFilter)
        }

        val prefs = this.context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun destroy() {
        try {
            val prefs = this.context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
        } catch (_: Exception) {}
        try {
            context.unregisterComponentCallbacks(componentCallbacks)
        } catch (_: Exception) {}
        try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioPlaybackCallback != null) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.unregisterAudioPlaybackCallback(audioPlaybackCallback)
            } catch (_: Exception) {}
        }
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager
            msm?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: Exception) {}
        try {
            context.unregisterReceiver(mediaReceiver)
        } catch (_: Exception) {}
        try {
            context.unregisterReceiver(shutUpReceiver)
        } catch (_: Exception) {}

        cancelPendingRateRunnable()
        cancelPendingRestoreRunnable()
        refreshRateJob?.cancel()
        if (perAppRateSnapshot != null) {
            val snapshotToRestore = perAppRateSnapshot
            perAppRateSnapshot = null
            try {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    snapshotToRestore?.let { restoreFromSnapshotState(it) }
                }
            } catch (e: Exception) {
                Log.e("AppFlowHandler", "Failed to restore refresh rate snapshot on destroy", e)
            }
        }
        synchronized(AppFlowHandler::class.java) {
            if (INSTANCE === this) {
                INSTANCE = null
            }
        }
    }

    private val ignoredSystemPackages = listOf(
        "android",
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.google.android.gms",
        "com.android.pixeldisplayservice"
    )

    private fun isIgnoredPackage(packageName: String): Boolean {
        if (ignoredSystemPackages.contains(packageName)) return true
        
        val lowerPkg = packageName.lowercase()
        if (lowerPkg.contains("systemui") ||
            lowerPkg.contains("keyguard") ||
            lowerPkg.contains("volume") ||
            lowerPkg.contains("soundassistant") ||
            lowerPkg.contains("dialer") ||
            lowerPkg.contains("telecom") ||
            lowerPkg.contains("phone") ||
            lowerPkg.contains("incallui") ||
            lowerPkg.contains("packageinstaller") ||
            lowerPkg.contains("permissioncontroller") ||
            lowerPkg.contains("displayservice") ||
            lowerPkg.contains("pixeldisplay")
        ) {
            return true
        }

        // Check active call state via AudioManager mode
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            val mode = audioManager.mode
            if (mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
            ) {
                return true
            }
        }

        return try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val ims = imm?.enabledInputMethodList
            ims?.any { it.packageName == packageName } == true
        } catch (_: Exception) {
            false
        }
    }

    fun onPackageChanged(
        packageName: String,
        isFromUsageStats: Boolean = false,
        isAccessibilityWindowEvent: Boolean = false,
        isFullScreenWindow: Boolean = true,
    ) {
        val useUsageAccess = settingsRepository.getBoolean(SettingsRepository.KEY_USE_USAGE_ACCESS, false) &&
                com.sameerasw.essentials.services.AppDetectionService.isRunning

        // If user enabled Usage Access mode and Accessibility Service is NOT running, drop non-usage events
        if (useUsageAccess && !isFromUsageStats && ScreenOffAccessibilityService.instance == null) {
            return
        }

        Log.d("AppFlowHandler", "onPackageChanged: packageName=$packageName, isFromUsageStats=$isFromUsageStats, useUsageAccess=$useUsageAccess, currentPackage=$currentPackage")

        // If the new foreground window belongs to a system overlay (status bar, quick settings,
        // notifications), a keyboard (IME), a volume dialog, or a phone call, completely ignore it.
        // We do NOT update currentPackage so that state-dependent features remain stable.
        if (isIgnoredPackage(packageName)) {
            Log.d("AppFlowHandler", "onPackageChanged: Ignoring system/IME/volume/call package $packageName")
            return
        }

        // Note: spurious non-full-screen events from a ShutUp target (dialogs, animations)
        // are filtered inside checkShutUp via the ShutUpManager.settingsCurrentlyApplied + currentPackage guard.

        if (packageName == currentPackage) {
            return
        }

        val oldPackage = currentPackage
        currentPackage = packageName
        if (oldPackage != null && oldPackage != packageName) {
            lastLeaveTimes[oldPackage] = System.currentTimeMillis()
            checkShutUpRestore(oldPackage, packageName)
        }
        if (packageName != context.packageName && packageName != lockingPackage) {
            lockingPackage = null
        }

        checkAppLock(packageName)
        checkHighlightNightLight(packageName)
        checkAppAutomations(packageName)
        checkGestureBarAutomation(packageName)
        checkPerAppRefreshRate(packageName)

        // Accessibility events are the fastest automatic launch signal. The manager serializes
        // this with the foreground-service fallback and periodic enforcement.
        checkShutUp(packageName)

    }

    fun onAuthenticated(packageName: String) {
        authenticatedPackages.add(packageName)
        if (packageName == lockingPackage) {
            lockingPackage = null
        }
    }

    fun clearAuthenticated() {
        authenticatedPackages.clear()
    }

    private fun isShutUpPackage(packageName: String): Boolean {
        if (!prefs.getBoolean("shutup_service_enabled", false)) return false
        val json = prefs.getString("shut_up_selected_apps", null) ?: return false
        return try {
            Gson().fromJson(json, Array<ShutUpAppConfig>::class.java)
                .any { it.packageName == packageName && it.isEnabled }
        } catch (_: Exception) {
            false
        }
    }

    private fun checkShutUp(packageName: String) {
        val serviceEnabled = prefs.getBoolean("shutup_service_enabled", false)
        if (!serviceEnabled) return

        val configs = settingsRepository.loadShutUpConfigs()
        val config = configs.find { it.packageName == packageName && it.isEnabled } ?: return

        // If settings are already applied and currentPackage hasn't transitioned to this app
        // yet, the event is from a background dialog/animation of the ShutUp target (e.g. a
        // DigiLocker activity finishing in the background while the user is on the launcher).
        // Returning here preserves any pending restore job without cancelling it.
        if (ShutUpManager.settingsCurrentlyApplied && currentPackage != packageName) {
            Log.d(
                "AppFlowHandler",
                "checkShutUp: spurious background event for $packageName (currentPackage=$currentPackage), skipping",
            )
            return
        }

        // Cancel any pending restoration immediately when entering/returning to a Shut-Up app.
        pendingShutUpRestoreJob?.cancel()
        pendingShutUpRestoreJob = null
        pendingShutUpRestorePackage = null

        ShutUpManager.preApplyShutUpSettings(context, config, settingsRepository)

        scope.launch(Dispatchers.IO) {
            Log.d("AppFlowHandler", "checkShutUp: Shell reinforcement for $packageName")
            ShutUpManager.applyShutUpSettings(
                context,
                config,
                settingsRepository,
                reinforcement = true,
            )
        }
    }

    private fun checkAppLock(packageName: String) {
        val prefs = context.getSharedPreferences("essentials_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("app_lock_enabled", false)
        if (!isEnabled) return

        if (packageName == context.packageName) {
            return
        }

        val json = prefs.getString("app_lock_selected_apps", null)
        val selectedApps: List<AppSelection> =
            if (json != null) {
                try {
                    Gson().fromJson(json, Array<AppSelection>::class.java).toList()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

        val isLocked = selectedApps.find { it.packageName == packageName }?.isEnabled ?: false

        if (isLocked && authenticatedPackages.contains(packageName)) {
            val delayIndex = prefs.getInt("app_lock_auto_lock_delay_index", 0)
            if (delayIndex > 0) {
                val delayMinutes =
                    when (delayIndex) {
                        1 -> 1
                        2 -> 5
                        3 -> 10
                        4 -> 20
                        5 -> 30
                        else -> 0
                    }

                val lastLeaveTime = lastLeaveTimes[packageName] ?: 0L
                if (lastLeaveTime > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastLeaveTime > delayMinutes * 60 * 1000L) {
                        authenticatedPackages.remove(packageName)
                        lastLeaveTimes.remove(packageName)
                    }
                }
            }
        }

        if (isLocked && !authenticatedPackages.contains(packageName)) {
            // Skip if we already requested a lock for this package very recently
            val now = System.currentTimeMillis()
            if (packageName == lockingPackage && now - lastLockRequestTime < 1500) {
                return
            }

            lockingPackage = packageName
            lastLockRequestTime = now

            Log.d(
                "AppLock",
                "App $packageName is locked and not authenticated. Showing lock screen.",
            )
            val intent =
                Intent().apply {
                    component = ComponentName(context, "com.sameerasw.essentials.AppLockActivity")
                    putExtra("package_to_lock", packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
            context.startActivity(intent)
        }
    }

    private fun checkHighlightNightLight(packageName: String) {
        val prefs = context.getSharedPreferences("essentials_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("dynamic_night_light_enabled", false)
        if (!isEnabled) return

        pendingNLRunnable?.let { handler.removeCallbacks(it) }

        if (isIgnoredPackage(packageName)) {
            Log.d("NightLight", "Ignoring system package $packageName")
            return
        }

        val runnable =
            Runnable {
                processNightLightChange(packageName)
            }
        pendingNLRunnable = runnable
        handler.postDelayed(runnable, nlDebounceDelay)
    }

    private fun processNightLightChange(packageName: String) {
        val prefs = context.getSharedPreferences("essentials_prefs", Context.MODE_PRIVATE)

        val json = prefs.getString("dynamic_night_light_selected_apps", null)
        val selectedApps: List<AppSelection> =
            if (json != null) {
                try {
                    Gson().fromJson(json, Array<AppSelection>::class.java).toList()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

        val isAppSelected = selectedApps.find { it.packageName == packageName }?.isEnabled ?: false
        val isNLCurrentlyOn = isNightLightEnabled()

        if (isAppSelected) {
            if (isNLCurrentlyOn) {
                Log.d("NightLight", "Turning off night light for $packageName")
                wasNightLightOnBeforeAutoToggle = true
                isNightLightAutoToggledOff = true
                setNightLightEnabled(false)
            }
        } else {
            if (isNightLightAutoToggledOff && wasNightLightOnBeforeAutoToggle) {
                Log.d("NightLight", "Restoring night light (was turned off for previous app)")
                setNightLightEnabled(true)
                isNightLightAutoToggledOff = false
                wasNightLightOnBeforeAutoToggle = false
            } else if (isNightLightAutoToggledOff) {
                isNightLightAutoToggledOff = false
            }
        }
    }

    private fun isNightLightEnabled(): Boolean =
        try {
            Settings.Secure.getInt(context.contentResolver, "night_display_activated", 0) == 1
        } catch (_: Exception) {
            false
        }

    private fun setNightLightEnabled(enabled: Boolean) {
        try {
            Settings.Secure.putInt(
                context.contentResolver,
                "night_display_activated",
                if (enabled) 1 else 0,
            )
        } catch (e: Exception) {
            Log.w(
                "NightLight",
                "Failed to set night light: ${e.message}. Ensure WRITE_SECURE_SETTINGS is granted.",
            )
        }
    }

    private fun checkAppAutomations(packageName: String) {
        if (isIgnoredPackage(packageName)) {
            Log.d("AppFlowHandler", "checkAppAutomations: Ignoring system/IME package $packageName")
            return
        }
        scope.launch {
            val automations = DIYRepository.automations.value
            val appAutomations =
                automations.filter { it.isEnabled && it.type == Automation.Type.APP }

            // Exiting Automations
            // An automation is exiting if it was active, but the new package is NOT in its selected apps list
            val exiting =
                appAutomations.filter {
                    activeAppAutomationIds.contains(it.id) && !it.selectedApps.contains(packageName)
                }

            exiting.forEach { automation ->
                activeAppAutomationIds.remove(automation.id)
                automation.exitAction?.let { action ->
                    CombinedActionExecutor.execute(context, action)
                }
            }

            // Entering Automations
            // An automation is entering if it was NOT active, and the new package IS in its selected apps list
            val entering =
                appAutomations.filter {
                    !activeAppAutomationIds.contains(it.id) && it.selectedApps.contains(packageName)
                }

            entering.forEach { automation ->
                activeAppAutomationIds.add(automation.id)
                automation.entryAction?.let { action ->
                    CombinedActionExecutor.execute(context, action)
                }
            }
        }
    }

    fun isCameraApp(packageName: String? = currentPackage): Boolean {
        if (packageName == null) return false

        // Known camera packages
        val cameraPackages =
            listOf(
                "com.google.android.GoogleCamera",
                "com.android.camera",
                "com.sec.android.app.camera",
                "com.huawei.camera",
                "com.oneplus.camera",
                "com.oppo.camera",
                "com.miui.camera",
                "com.sonyericsson.android.camera",
                "com.sonymobile.android.camera",
            )
        if (cameraPackages.any { packageName.startsWith(it) }) return true

        if (packageName.lowercase().contains("camera")) return true

        return false
    }

    private fun checkGestureBarAutomation(packageName: String) {
        val prefs = context.getSharedPreferences("essentials_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("hide_gesture_bar_on_launcher_enabled", false)
        if (!isEnabled) return

        if (isLauncher(packageName)) {
            StatusBarManager.requestRestore(context, "GestureBarAutomation")
        } else {
            StatusBarManager.requestDisable(
                context,
                "GestureBarAutomation",
                setOf(StatusBarManager.FLAG_HOME),
            )
        }
    }

    private fun isLauncher(packageName: String): Boolean {
        if (packageName == "com.android.systemui") return true

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo =
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val defaultLauncher = resolveInfo?.activityInfo?.packageName

        if (packageName == defaultLauncher) return true

        // Secondary check for other launchers if not default
        val launchers =
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return launchers.any { it.activityInfo.packageName == packageName }
    }

    private fun checkShutUpRestore(
        oldPackage: String?,
        newPackage: String?,
    ) {
        Log.d("AppFlowHandler", "checkShutUpRestore: old=$oldPackage, new=$newPackage")
        if (oldPackage == null || oldPackage == newPackage) return

        // Do not trigger restore if newPackage is an ignored package (system dialogs, permission controllers, etc.)
        if (newPackage != null && (isIgnoredPackage(newPackage) || ShutUpManager.isPackageIgnored(newPackage))) {
            Log.d("AppFlowHandler", "checkShutUpRestore: newPackage $newPackage is ignored, skipping restore")
            return
        }

        val settingsRepository =
            com.sameerasw.essentials.data.repository
                .SettingsRepository(context)
        val shutUpConfigs = settingsRepository.loadShutUpConfigs()

        val wasShutUpConfig = shutUpConfigs.find { it.packageName == oldPackage && it.isEnabled }

        // Check if it was already frozen to avoid duplicate triggers (e.g. on screen off)
        val isAlreadyFrozen = oldPackage.let { FreezeManager.isAppFrozen(context, it) }

        // We consider the new app a Shut-Up app if it's in the list OR if it's the shortcut activity
        val isNewAppShutUp =
            shutUpConfigs.any { it.packageName == newPackage && it.isEnabled } ||
                newPackage == "com.sameerasw.essentials.ShutUpShortcutActivity"

        Log.d(
            "AppFlowHandler",
            "checkShutUpRestore: wasShutUpConfig=${wasShutUpConfig != null}, isNewAppShutUp=$isNewAppShutUp, isAlreadyFrozen=$isAlreadyFrozen",
        )

        // If it's already frozen, we've already handled it
        if (isAlreadyFrozen) return

        // If we are entering a Shut-Up app, cancel ANY pending countdowns & restorations
        if (isNewAppShutUp) {
            pendingShutUpRestoreJob?.cancel()
            pendingShutUpRestoreJob = null
            pendingShutUpRestorePackage = null

            if (activeCountdowns.isNotEmpty()) {
                Log.d(
                    "AppFlowHandler",
                    "checkShutUpRestore: Entering Shut-Up app, cancelling all pending countdowns",
                )
                activeCountdowns.values.forEach { it.cancel() }
                activeCountdowns.keys.forEach { cancelNotification(it) }
                activeCountdowns.clear()
            }
        }

        if (wasShutUpConfig != null && !isNewAppShutUp) {
            if (ShutUpManager.restoreInProgress) {
                Log.d("AppFlowHandler", "checkShutUpRestore: restore already in progress in ShutUpManager, skipping duplicate")
                return
            }
            Log.d("AppFlowHandler", "checkShutUpRestore: Triggering restoration for $oldPackage")
            pendingShutUpRestoreJob?.cancel()
            pendingShutUpRestorePackage = oldPackage
            restoreShutUpSettings(
                settingsRepository,
                wasShutUpConfig,
                if (wasShutUpConfig.autoArchive) wasShutUpConfig.packageName else null,
            )
        }
    }

    private fun startAutoArchiveCountdown(packageName: String) {
        Log.d("AppFlowHandler", "startAutoArchiveCountdown: $packageName")
        // Cancel existing countdown for this app if any
        activeCountdowns[packageName]?.cancel()

        val appName =
            try {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                Log.e("AppFlowHandler", "Failed to get app name for $packageName", e)
                packageName
            }

        val job =
            scope.launch {
                Log.d("AppFlowHandler", "Countdown job started for $packageName")
                for (i in 10 downTo 1) {
                    Log.d("AppFlowHandler", "Countdown for $packageName: $i")
                    showCountdownNotification(packageName, appName, i)
                    delay(1000)
                }
                // countdown finished
                Log.d("AppFlowHandler", "Countdown finished for $packageName, freezing...")
                val success =
                    withContext(Dispatchers.IO) {
                        FreezeManager.freezeApp(context, packageName)
                    }
                Log.d("AppFlowHandler", "Freeze result for $packageName: $success")
                cancelNotification(packageName)
                activeCountdowns.remove(packageName)
            }
        activeCountdowns[packageName] = job
    }

    private fun showCountdownNotification(
        packageName: String,
        appName: String,
        secondsLeft: Int,
    ) {
        createNotificationChannel()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val freezeIntent =
            Intent(ACTION_FREEZE_NOW).apply {
                `package` = context.packageName
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
        val freezePendingIntent =
            PendingIntent.getBroadcast(
                context,
                packageName.hashCode() + 1,
                freezeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val abortIntent =
            Intent(ACTION_ABORT_FREEZE).apply {
                `package` = context.packageName
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
        val abortPendingIntent =
            PendingIntent.getBroadcast(
                context,
                packageName.hashCode() + 2,
                abortIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val title =
            context.getString(com.sameerasw.essentials.R.string.shut_up_auto_archive_notif_title)
        val text =
            context.getString(
                com.sameerasw.essentials.R.string.shut_up_auto_archive_notif_text,
                appName,
                secondsLeft,
            )
        val criticalText = secondsLeft.toString()

        val notification =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val builder =
                    android.app.Notification
                        .Builder(context, "shutup_alerts_channel")
                        .setSmallIcon(com.sameerasw.essentials.R.drawable.rounded_snowflake_24)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setCategory(android.app.Notification.CATEGORY_SERVICE)
                        .setShowWhen(false)
                        .setGroup("shutup_auto_archive")
                        .setColorized(false)

                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    builder.setForegroundServiceBehavior(android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }

                builder.addAction(
                    android.app.Notification.Action
                        .Builder(
                            android.graphics.drawable.Icon.createWithResource(
                                context,
                                com.sameerasw.essentials.R.drawable.rounded_snowflake_24,
                            ),
                            context.getString(com.sameerasw.essentials.R.string.shut_up_auto_archive_action_freeze),
                            freezePendingIntent,
                        ).build(),
                )
                builder.addAction(
                    android.app.Notification.Action
                        .Builder(
                            android.graphics.drawable.Icon.createWithResource(
                                context,
                                com.sameerasw.essentials.R.drawable.rounded_close_24,
                            ),
                            context.getString(com.sameerasw.essentials.R.string.shut_up_auto_archive_action_abort),
                            abortPendingIntent,
                        ).build(),
                )

                // Live Update Status Chip
                try {
                    val setRequestPromotedOngoing =
                        builder.javaClass.getMethod(
                            "setRequestPromotedOngoing",
                            Boolean::class.javaPrimitiveType,
                        )
                    setRequestPromotedOngoing.invoke(builder, true)

                    val setShortCriticalText =
                        builder.javaClass.getMethod(
                            "setShortCriticalText",
                            CharSequence::class.java,
                        )
                    setShortCriticalText.invoke(builder, criticalText)
                } catch (_: Throwable) {
                }

                val extras = android.os.Bundle()
                extras.putBoolean("android.requestPromotedOngoing", true)
                extras.putString("android.shortCriticalText", criticalText)
                builder.addExtras(extras)

                builder.setProgress(10, secondsLeft, false)

                builder.build()
            } else {
                NotificationCompat
                    .Builder(context, "shutup_alerts_channel")
                    .setSmallIcon(com.sameerasw.essentials.R.drawable.rounded_snowflake_24)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setProgress(10, secondsLeft, false)
                    .addAction(
                        com.sameerasw.essentials.R.drawable.rounded_snowflake_24,
                        context.getString(com.sameerasw.essentials.R.string.shut_up_auto_archive_action_freeze),
                        freezePendingIntent,
                    ).addAction(
                        com.sameerasw.essentials.R.drawable.rounded_close_24,
                        context.getString(com.sameerasw.essentials.R.string.shut_up_auto_archive_action_abort),
                        abortPendingIntent,
                    ).addExtras(
                        android.os.Bundle().apply {
                            putBoolean("android.requestPromotedOngoing", true)
                            putString("android.shortCriticalText", criticalText)
                        },
                    ).build()
            }

        Log.d("AppFlowHandler", "Showing notification for $packageName, secondsLeft=$secondsLeft")
        notificationManager.notify(packageName.hashCode(), notification)
    }

    private fun cancelNotification(packageName: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(packageName.hashCode())
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel =
                android.app
                    .NotificationChannel(
                        "app_detection_service_channel",
                        context.getString(com.sameerasw.essentials.R.string.app_detection_service_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Channel for app detection alerts"
                    }
            notificationManager.createNotificationChannel(channel)

            val alertChannel =
                android.app
                    .NotificationChannel(
                        "shutup_alerts_channel",
                        "Shut-Up! Alerts",
                        NotificationManager.IMPORTANCE_MAX,
                    ).apply {
                        description = "Live update notifications for auto archiving"
                        enableVibration(false)
                        setSound(null, null)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
            notificationManager.createNotificationChannel(alertChannel)

            val restoreChannel =
                android.app
                    .NotificationChannel(
                        "shutup_restore_channel",
                        "Shut-Up! Restore",
                        NotificationManager.IMPORTANCE_MAX,
                    ).apply {
                        description = "Notifications for restoring Shut-Up settings"
                        enableVibration(false)
                        setSound(null, null)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
            notificationManager.createNotificationChannel(restoreChannel)
        }
    }

    private fun showRestoreNotification(
        wasShutUpConfig: com.sameerasw.essentials.domain.model.ShutUpAppConfig?,
        autoArchivePackage: String?,
    ) {
        createNotificationChannel()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val restoreIntent =
            Intent(ACTION_RESTORE_NOW).apply {
                `package` = context.packageName
                if (autoArchivePackage != null) {
                    putExtra(EXTRA_AUTO_ARCHIVE_PACKAGE, autoArchivePackage)
                }
                if (wasShutUpConfig != null) {
                    putExtra(EXTRA_PACKAGE_NAME, wasShutUpConfig.packageName)
                }
            }
        val restorePendingIntent =
            PendingIntent.getBroadcast(
                context,
                12345,
                restoreIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val title = "Shut-Up active"
        val text = "Do you want to restore now?"
        val criticalText = "Restore Now"

        val notification =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val builder =
                    android.app.Notification
                        .Builder(context, "shutup_restore_channel")
                        .setSmallIcon(com.sameerasw.essentials.R.drawable.rounded_code_24)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setCategory(android.app.Notification.CATEGORY_SERVICE)
                        .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                        .setOnlyAlertOnce(true)
                        .setOngoing(true)
                        .addAction(
                            android.app.Notification.Action
                                .Builder(
                                    android.graphics.drawable.Icon.createWithResource(
                                        context,
                                        com.sameerasw.essentials.R.drawable.rounded_code_24,
                                    ),
                                    "Restore Now",
                                    restorePendingIntent,
                                ).build(),
                        )

                try {
                    val setShortCriticalText =
                        builder.javaClass.getMethod(
                            "setShortCriticalText",
                            CharSequence::class.java,
                        )
                    setShortCriticalText.invoke(builder, criticalText)
                } catch (_: Throwable) {
                }

                val extras = android.os.Bundle()
                extras.putBoolean("android.requestPromotedOngoing", true)
                extras.putString("android.shortCriticalText", criticalText)
                builder.addExtras(extras)
                builder.build()
            } else {
                NotificationCompat
                    .Builder(context, "shutup_restore_channel")
                    .setSmallIcon(com.sameerasw.essentials.R.drawable.rounded_code_24)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .addAction(
                        com.sameerasw.essentials.R.drawable.rounded_code_24,
                        "Restore Now",
                        restorePendingIntent,
                    ).addExtras(
                        android.os.Bundle().apply {
                            putBoolean("android.requestPromotedOngoing", true)
                            putString("android.shortCriticalText", criticalText)
                        },
                    ).build()
            }

        notificationManager.notify(NOTIFICATION_ID_SHUTUP_RESTORE, notification)
    }

    private fun cancelRestoreNotification() {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_SHUTUP_RESTORE)
    }

    private fun restoreShutUpSettings(
        repository: com.sameerasw.essentials.data.repository.SettingsRepository,
        wasShutUpConfig: com.sameerasw.essentials.domain.model.ShutUpAppConfig?,
        autoArchivePackage: String? = null,
        forceRestore: Boolean = false,
    ) {
        val originalSettings = repository.getShutUpOriginalSettings()
        if (originalSettings.isEmpty()) {
            if (autoArchivePackage != null) {
                startAutoArchiveCountdown(autoArchivePackage)
            }
            return
        }

        val mode = repository.getShutUpRestoreMode()
        if (mode == "Notify" && !forceRestore) {
            pendingShutUpRestoreJob = scope.launch {
                val delaySeconds = repository.getShutUpRestoreDelay().coerceAtLeast(0)
                if (delaySeconds > 0) {
                    delay(delaySeconds * 1000L)
                }
                val current = currentPackage
                val isCurrentShutUp = repository.loadShutUpConfigs().any { it.packageName == current && it.isEnabled }
                if (!isCurrentShutUp) {
                    showRestoreNotification(wasShutUpConfig, autoArchivePackage)
                }
                pendingShutUpRestoreJob = null
                pendingShutUpRestorePackage = null
            }
            return
        }

        pendingShutUpRestoreJob = scope.launch {
            if (!forceRestore) {
                // Delay to ensure the app has fully settled before restoring system settings
                val delaySeconds = repository.getShutUpRestoreDelay().coerceAtLeast(0)
                if (delaySeconds > 0) {
                    delay(delaySeconds * 1000L)
                }
            }

            // Check if user is currently inside a Shut-Up app before restoring
            val current = currentPackage
            val isCurrentShutUp = repository.loadShutUpConfigs().any { it.packageName == current && it.isEnabled }
            if (isCurrentShutUp && !forceRestore) {
                Log.d("AppFlowHandler", "restoreShutUpSettings: User is currently in Shut-Up app $current — skipping restore")
                pendingShutUpRestoreJob = null
                pendingShutUpRestorePackage = null
                return@launch
            }

            withContext(Dispatchers.IO) {
                ShutUpManager.restoreOriginalSettings(context, repository)
                if (wasShutUpConfig != null && wasShutUpConfig.attemptShizukuRestart && repository.isShutUpAttemptShizukuRestartEnabled()) {
                    ShutUpManager.restartShizuku(context)
                }
            }

            // Start auto-archive countdown AFTER everything is restored and Shizuku is starting
            if (autoArchivePackage != null) {
                startAutoArchiveCountdown(autoArchivePackage)
            }
            pendingShutUpRestoreJob = null
            pendingShutUpRestorePackage = null
        }
    }

    private fun isPlaybackStatePlaying(state: Int?): Boolean {
        if (state == null) return false
        return state == android.media.session.PlaybackState.STATE_PLAYING ||
               state == android.media.session.PlaybackState.STATE_BUFFERING ||
               state == android.media.session.PlaybackState.STATE_FAST_FORWARDING ||
               state == android.media.session.PlaybackState.STATE_REWINDING
    }

    private fun isMediaPlaying(packageName: String): Boolean {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager
            val sessions = try {
                msm?.getActiveSessions(notificationListenerComponent).orEmpty()
            } catch (_: Exception) {
                emptyList()
            }

            val matchingSessions = sessions.filter { it.packageName == packageName }
            if (matchingSessions.isNotEmpty() && matchingSessions.any { isPlaybackStatePlaying(it.playbackState?.state) }) {
                true
            } else if (mediaPlayingPackages[packageName] == true) {
                true
            } else {
                val activeNotifications = NotificationListener.instance?.activeNotifications.orEmpty()
                val notificationPlaying = activeNotifications.any { notification ->
                    if (notification.packageName != packageName) return@any false

                    val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notification.notification.extras.getParcelable(
                            Notification.EXTRA_MEDIA_SESSION,
                            android.media.session.MediaSession.Token::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        notification.notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
                    }

                    token?.let { mediaToken ->
                        val controller = android.media.session.MediaController(context, mediaToken)
                        isPlaybackStatePlaying(controller.playbackState?.state)
                    } ?: false
                }
                if (notificationPlaying) {
                    true
                } else {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (audioManager != null && audioManager.isMusicActive) {
                        val otherAppPlayingSession = sessions.any {
                            it.packageName != packageName && isPlaybackStatePlaying(it.playbackState?.state)
                        }
                        !otherAppPlayingSession && currentPackage == packageName
                    } else {
                        mediaPlayingPackages[packageName] ?: false
                    }
                }
            }
        } catch (e: Exception) {
            mediaPlayingPackages[packageName] ?: false
        }
    }

    private fun isDeviceInLandscape(): Boolean {
        return try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            val rotation = display?.rotation ?: Surface.ROTATION_0
            rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        } catch (_: Exception) {
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    private fun getTargetRefreshRateForConfig(config: AppRefreshRateConfig): Float {
        val landscapeRate = config.landscapeRefreshRate
        if (landscapeRate != null) {
            val isLandscape = isDeviceInLandscape()
            if (isLandscape) {
                if (config.onlyOnMediaPlaying) {
                    val mediaPlaying = isMediaPlaying(config.packageName)
                    Log.d(
                        "AppFlowHandler",
                        "per-app refresh rate: target decision package=${config.packageName}, " +
                                "landscape=true, mediaPlaying=$mediaPlaying, " +
                                "landscapeRate=$landscapeRate, portraitRate=${config.refreshRate}"
                    )
                    return if (mediaPlaying) landscapeRate else config.refreshRate
                }
                return landscapeRate
            }
            if (config.onlyOnMediaPlaying) {
                Log.d(
                    "AppFlowHandler",
                    "per-app refresh rate: target decision package=${config.packageName}, " +
                            "landscape=false, media check skipped"
                )
            }
        }
        return config.refreshRate
    }

    private fun applyRefreshRateForConfig(config: AppRefreshRateConfig, targetRate: Float) {
        if (config.isFixed) {
            RefreshRateUtils.applyFixedRefreshRate(context, targetRate)
        } else {
            RefreshRateUtils.applyDynamicRefreshRate(context, targetRate)
        }
    }

    private fun checkPerAppRefreshRate(packageName: String) {
        if (ignoredSystemPackages.contains(packageName)) {
            return
        }

        val isEnabled = settingsRepository.getBoolean(SettingsRepository.KEY_PER_APP_REFRESH_RATE_ENABLED, false)
        if (!isEnabled) {
            cancelPendingRateRunnable()
            cancelPendingRestoreRunnable()
            refreshRateJob?.cancel()
            if (perAppRateSnapshot != null) {
                val snapshot = perAppRateSnapshot
                perAppRateSnapshot = null
                refreshRateJob = scope.launch(Dispatchers.IO) {
                    snapshot?.let { restoreFromSnapshotState(it) }
                }
            }
            return
        }

        val configs = cachedRefreshRateConfigs ?: settingsRepository.loadPerAppRefreshRateConfigs().also { cachedRefreshRateConfigs = it }
        val config = configs.find { it.packageName == packageName && it.isEnabled }

        if (config != null) {
            cancelPendingRestoreRunnable()
            cancelPendingRateRunnable()
            refreshRateJob?.cancel()

            perAppCurrentPackage = packageName

            refreshRateJob = scope.launch(Dispatchers.IO) {
                if (perAppRateSnapshot == null) {
                    val snapshot = RefreshRateUtils.getCurrentState(context)
                    if (perAppRateSnapshot == null) {
                        perAppRateSnapshot = snapshot
                        Log.d("AppFlowHandler", "per-app refresh rate: snapshotted state: $snapshot")
                    }
                }
                val targetRate = getTargetRefreshRateForConfig(config)
                Log.d("AppFlowHandler", "per-app refresh rate: applying $targetRate Hz (isFixed=${config.isFixed}) for $packageName")
                applyRefreshRateForConfig(config, targetRate)

                // Re-apply after a short delay to beat OEM adaptive display controllers that
                // fire asynchronously after window transitions (e.g. resuming from recents).
                delay(400L)
                if (perAppCurrentPackage == packageName) {
                    val delayedRate = getTargetRefreshRateForConfig(config)
                    Log.d("AppFlowHandler", "per-app refresh rate: delayed re-apply $delayedRate Hz (isFixed=${config.isFixed}) for $packageName")
                    applyRefreshRateForConfig(config, delayedRate)
                }
            }
        } else {
            cancelPendingRateRunnable()
            refreshRateJob?.cancel()
            perAppCurrentPackage = null

            if (perAppRateSnapshot != null && pendingRestoreRunnable == null) {
                Log.d("AppFlowHandler", "per-app refresh rate: scheduling delayed restoration (1000ms) for leaving $packageName")
                refreshRateJob = scope.launch(Dispatchers.IO) {
                    delay(1000L)
                    if (perAppCurrentPackage == null && perAppRateSnapshot != null) {
                        val snapshot = perAppRateSnapshot
                        perAppRateSnapshot = null
                        Log.d("AppFlowHandler", "per-app refresh rate: restoring to global state from snapshot (delayed)")
                        snapshot?.let { restoreFromSnapshotState(it) }
                    }
                }
            }
        }
    }

    private fun cancelPendingRateRunnable() {
        pendingRateRunnable?.let { handler.removeCallbacks(it) }
        pendingRateRunnable = null
    }

    private fun cancelPendingRestoreRunnable() {
        pendingRestoreRunnable?.let { handler.removeCallbacks(it) }
        pendingRestoreRunnable = null
    }

    private fun restoreFromSnapshot() {
        val snapshot = perAppRateSnapshot ?: return
        perAppRateSnapshot = null
        restoreFromSnapshotState(snapshot)
    }

    private fun restoreFromSnapshotState(snapshot: RefreshRateUtils.RefreshRateState) {
        try {
            if (snapshot.isSystemManaged) {
                RefreshRateUtils.resetRefreshRate(context, snapshot.usesInfinityDefaultPeak)
            } else if (snapshot.min > 0f && snapshot.peak > 0f && snapshot.min != snapshot.peak) {
                RefreshRateUtils.applyRangeRefreshRate(context, snapshot.min, snapshot.peak)
            } else {
                RefreshRateUtils.applyFixedRefreshRate(context, snapshot.peak.coerceAtLeast(snapshot.min))
            }
        } catch (e: Exception) {
            Log.e("AppFlowHandler", "Failed to restore refresh rate from snapshot", e)
        }
    }

    companion object {
        const val ACTION_FREEZE_NOW = "com.sameerasw.essentials.ACTION_FREEZE_NOW"
        const val ACTION_ABORT_FREEZE = "com.sameerasw.essentials.ACTION_ABORT_FREEZE"
        const val ACTION_RESTORE_NOW = "com.sameerasw.essentials.ACTION_RESTORE_NOW"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_AUTO_ARCHIVE_PACKAGE = "auto_archive_package"
        const val NOTIFICATION_ID_SHUTUP_RESTORE = 9999

        @Volatile
        private var INSTANCE: AppFlowHandler? = null

        fun getInstance(context: Context): AppFlowHandler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppFlowHandler(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
