/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: ScreenOffAccessibilityService.kt
 * Description: Background service component for ScreenOffAccessibilityService.kt.
 */

package com.sameerasw.essentials.services.tiles

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.domain.HapticFeedbackType
import com.sameerasw.essentials.services.AppDetectionService
import com.sameerasw.essentials.services.InputEventListenerService
import com.sameerasw.essentials.services.NotificationListener
import com.sameerasw.essentials.services.handlers.AmbientGlanceHandler
import com.sameerasw.essentials.services.handlers.AodForceTurnOffHandler
import com.sameerasw.essentials.services.handlers.AodWallpaperOverlayHandler
import com.sameerasw.essentials.services.handlers.AppFlowHandler
import com.sameerasw.essentials.services.handlers.ButtonRemapHandler
import com.sameerasw.essentials.services.handlers.DuoOverlayHandler
import com.sameerasw.essentials.services.handlers.FlashlightHandler
import com.sameerasw.essentials.services.handlers.IslandOverlayHandler
import com.sameerasw.essentials.services.handlers.NotificationLightingHandler
import com.sameerasw.essentials.services.handlers.OmniGestureOverlayHandler
import com.sameerasw.essentials.services.handlers.PocketModeHandler
import com.sameerasw.essentials.services.handlers.SmartPixelsHandler
import com.sameerasw.essentials.services.handlers.StatusBarIconHandler
import com.sameerasw.essentials.services.handlers.StatusGlanceHandler
import com.sameerasw.essentials.services.receivers.FlashlightActionReceiver
import com.sameerasw.essentials.utils.AppUtil
import com.sameerasw.essentials.utils.FreezeManager
import com.sameerasw.essentials.utils.ServiceUtils
import com.sameerasw.essentials.utils.performHapticFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ScreenOffAccessibilityService :
    AccessibilityService(),
    SensorEventListener {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private var proximitySensor: Sensor? = null

    // Handlers
    lateinit var flashlightHandler: FlashlightHandler
    private lateinit var notificationLightingHandler: NotificationLightingHandler
    private lateinit var buttonRemapHandler: ButtonRemapHandler
    private lateinit var appFlowHandler: AppFlowHandler
    private lateinit var ambientGlanceHandler: AmbientGlanceHandler
    private lateinit var aodForceTurnOffHandler: AodForceTurnOffHandler
    private lateinit var aodWallpaperOverlayHandler: AodWallpaperOverlayHandler
    private lateinit var omniGestureOverlayHandler: OmniGestureOverlayHandler
    private lateinit var statusBarIconHandler: StatusBarIconHandler
    private lateinit var pocketModeHandler: PocketModeHandler
    private lateinit var smartPixelsHandler: SmartPixelsHandler
    private lateinit var duoOverlayHandler: DuoOverlayHandler
    lateinit var islandOverlayHandler: IslandOverlayHandler
    private lateinit var statusGlanceHandler: StatusGlanceHandler

    private var lightSensor: Sensor? = null
    private var lightSensorLux: Float = 100f
    private val keyguardManager by lazy { getSystemService(KEYGUARD_SERVICE) as KeyguardManager }

    private var isScreenOn = true
    private var isKeyguardLocked = false
    private var isLightSensorRegistered = false
    private var isProximityRegisteredForPocket = false
    private var isProximityRegistered = false

    private val prefs by lazy { getSharedPreferences("essentials_prefs", MODE_PRIVATE) }

    fun invalidateBypassCache() {
        if (::pocketModeHandler.isInitialized && ::appFlowHandler.isInitialized) {
            pocketModeHandler.invalidateBypassCache(appFlowHandler.currentPackage)
        }
    }

    private fun updatePocketModePrefs() {
        if (::pocketModeHandler.isInitialized) {
            pocketModeHandler.updatePocketModePrefs()
        }
        invalidateBypassCache()
    }

    private fun updatePocketModeExcludedAppsSet() {
        if (::pocketModeHandler.isInitialized) {
            pocketModeHandler.updatePocketModeExcludedAppsSet()
        }
        invalidateBypassCache()
    }

    private var screenReceiver: BroadcastReceiver? = null

    // Freeze Logic
    private val freezeHandler = Handler(Looper.getMainLooper())
    private val freezeRunnable =
        Runnable {
            FreezeManager.freezeAll(this)
        }

    // Pocket Detection
    private val pocketFlashlightHandler = Handler(Looper.getMainLooper())
    private val pocketFlashlightRunnable = Runnable {
        // Re-check at fire time — guards against external torch-off between scheduling and firing
        if (::pocketModeHandler.isInitialized && pocketModeHandler.flashlightPocketTurnOffEnabled && flashlightHandler.isProximityBlocked && flashlightHandler.isTorchOn) {
            flashlightHandler.toggleFlashlight()
        }
    }

    private fun schedulePocketFlashlightTurnOff() {
        pocketFlashlightHandler.removeCallbacks(pocketFlashlightRunnable)
        pocketFlashlightHandler.postDelayed(pocketFlashlightRunnable, 1500L)
    }

    private fun cancelPocketFlashlightTurnOff() {
        pocketFlashlightHandler.removeCallbacks(pocketFlashlightRunnable)
    }

    private fun updateProximitySensorRegistration() {
        val flashlightNeedsProximity =
            ::pocketModeHandler.isInitialized && pocketModeHandler.flashlightPocketTurnOffEnabled && flashlightHandler.isTorchOn

        val shouldRegister = isProximityRegisteredForPocket || flashlightNeedsProximity

        if (shouldRegister) {
            if (!isProximityRegistered) {
                if (proximitySensor == null) {
                    proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                }
                proximitySensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                    isProximityRegistered = true
                    android.util.Log.d("ScreenOffService", "Registered proximity sensor listener")
                }
            }
        } else {
            if (isProximityRegistered) {
                proximitySensor?.let {
                    sensorManager.unregisterListener(this, it)
                }
                isProximityRegistered = false
                android.util.Log.d("ScreenOffService", "Unregistered proximity sensor listener")
            }
        }
    }

    fun updateFlashlightProximityRegistration() {
        updateProximitySensorRegistration()
    }

    private val preferenceChangeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "circle_to_search_gesture_enabled" ||
                key == "circle_to_search_gesture_height" ||
                key == "circle_to_search_gesture_width" ||
                key == "circle_to_search_preview_enabled"
            ) {
                updateOmniOverlay()
            } else if (key == "smart_wifi_enabled" ||
                key == "smart_data_enabled" ||
                key == "battery_percent_mode" ||
                key?.startsWith(
                    "icon_",
                ) == true
            ) {
                statusBarIconHandler.updateAll()
            } else if (key == "pocket_mode_enabled" || key == "pocket_mode_use_light_sensor" || key == "pocket_mode_trigger_delay" || key == "pocket_mode_lock_screen_only" || key == "flashlight_pocket_turn_off_enabled") {
                updatePocketModePrefs()
                updatePocketModeSensors()
                ServiceUtils.startRequiredServices(this)
            } else if (key == "pocket_mode_excluded_apps") {
                updatePocketModeExcludedAppsSet()
                ServiceUtils.startRequiredServices(this)
            } else if (key == SettingsRepository.KEY_SMART_PIXELS_ENABLED ||
                key == SettingsRepository.KEY_SMART_PIXELS_INTENSITY ||
                key == SettingsRepository.KEY_SMART_PIXELS_DISABLE_ON_CAST
            ) {
                smartPixelsHandler.updateState()
            } else if (key == SettingsRepository.KEY_AOD_WALLPAPER_ENABLED ||
                key == SettingsRepository.KEY_AOD_WALLPAPER_OPACITY ||
                key == SettingsRepository.KEY_AOD_WALLPAPER_TIMEOUT ||
                key == SettingsRepository.KEY_AOD_WALLPAPER_BLUR ||
                key == SettingsRepository.KEY_AOD_WALLPAPER_VIGNETTE ||
                key == SettingsRepository.KEY_AOD_WALLPAPER_BLACK_THRESHOLD ||
                key == SettingsRepository.KEY_AOD_WALLPAPER_CUSTOM_IMAGE ||
                key == SettingsRepository.KEY_AOD_WALLPAPER_USE_ALBUM_ART ||
                key == SettingsRepository.KEY_AOD_WALLPAPER_KEEP_ON_MEDIA ||
                key == SettingsRepository.KEY_AOD_WALLPAPER_MEDIA_EXCLUDED_APPS
            ) {
                if (key == SettingsRepository.KEY_AOD_WALLPAPER_CUSTOM_IMAGE) {
                    aodWallpaperOverlayHandler.invalidateWallpaperCache()
                }
                aodWallpaperOverlayHandler.updateState()
                if (key == SettingsRepository.KEY_AOD_WALLPAPER_MEDIA_EXCLUDED_APPS) {
                    duoOverlayHandler.updateState()
                    statusGlanceHandler.updateState()
                }
            } else if (key?.startsWith("duo_") == true ||
                key == SettingsRepository.KEY_DUO_ENABLED ||
                key == SettingsRepository.KEY_ENABLE_UNSUPPORTED_FEATURES
            ) {
                duoOverlayHandler.updateState()
            } else if (key == SettingsRepository.KEY_ISLAND_SUPPRESS_SYSTEM_HEADS_UP) {
                SettingsRepository(this).applyHeadsUpSuppression()
            } else if (key?.startsWith("status_glance_") == true ||
                key == SettingsRepository.KEY_STATUS_GLANCE_ENABLED ||
                key == SettingsRepository.KEY_STATUS_GLANCE_USE_AUTO_DETECT ||
                key == SettingsRepository.KEY_STATUS_GLANCE_OFFSET_X ||
                key == SettingsRepository.KEY_STATUS_GLANCE_OFFSET_Y ||
                key == SettingsRepository.KEY_STATUS_GLANCE_MAX_WIDTH ||
                key == SettingsRepository.KEY_STATUS_GLANCE_FONT_SIZE ||
                key == SettingsRepository.KEY_STATUS_GLANCE_SHOW_FLASHLIGHT ||
                key == SettingsRepository.KEY_STATUS_GLANCE_SHOW_CALENDAR ||
                key == SettingsRepository.KEY_STATUS_GLANCE_SHOW_MEDIA ||
                key == SettingsRepository.KEY_STATUS_GLANCE_SHOW_TIME ||
                key == SettingsRepository.KEY_STATUS_GLANCE_BACKGROUND_PILL ||
                key == SettingsRepository.KEY_STATUS_GLANCE_ALBUM_ART_COLORS ||
                key == SettingsRepository.KEY_STATUS_GLANCE_HIDE_WHEN_FULLSCREEN ||
                key == SettingsRepository.KEY_STATUS_GLANCE_HIDE_IN_QUICK_SETTINGS ||
                key == SettingsRepository.KEY_STATUS_GLANCE_HIDE_WHEN_LOCKED
            ) {
                statusGlanceHandler.updateState()
            }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Handlers
        flashlightHandler = FlashlightHandler(this, serviceScope)
        notificationLightingHandler = NotificationLightingHandler(this)
        buttonRemapHandler = ButtonRemapHandler(this, flashlightHandler)
        appFlowHandler = AppFlowHandler.getInstance(this)
        ambientGlanceHandler = AmbientGlanceHandler(this)
        aodForceTurnOffHandler = AodForceTurnOffHandler(this)
        aodWallpaperOverlayHandler = AodWallpaperOverlayHandler(this)
        omniGestureOverlayHandler = OmniGestureOverlayHandler(this)
        statusBarIconHandler = StatusBarIconHandler(this)
        pocketModeHandler = PocketModeHandler(this)
        smartPixelsHandler = SmartPixelsHandler(this)
        duoOverlayHandler = DuoOverlayHandler(this)
        islandOverlayHandler = IslandOverlayHandler(this)
        statusGlanceHandler = StatusGlanceHandler(this)

        flashlightHandler.register()
        statusBarIconHandler.register()
        smartPixelsHandler.init()
        duoOverlayHandler.init()
        statusGlanceHandler.init()
        setupReceivers()
    }

    fun getActiveConsciousGateSession(): AppFlowHandler.ConsciousGateSession? =
        if (::appFlowHandler.isInitialized) appFlowHandler.getActiveConsciousGateSession() else null

    private fun setupReceivers() {
        screenReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            isScreenOn = true
                            isKeyguardLocked = keyguardManager.isKeyguardLocked
                            invalidateBypassCache()
                            notificationLightingHandler.onScreenOn()
                            ambientGlanceHandler.dismissImmediately()
                            aodForceTurnOffHandler.removeOverlay()
                            aodWallpaperOverlayHandler.onScreenOn()
                            duoOverlayHandler.onScreenOn()
                            statusGlanceHandler.onScreenOn()
                            islandOverlayHandler.updateState()
                            freezeHandler.removeCallbacks(freezeRunnable)
                            stopInputEventListener()
                            updateOmniOverlay()
                            updatePocketModeSensors()
                        }

                        Intent.ACTION_SCREEN_OFF -> {
                            isScreenOn = false
                            isKeyguardLocked = true
                            invalidateBypassCache()
                            appFlowHandler.clearAuthenticated()
                            appFlowHandler.clearConsciousGate()
                            appFlowHandler.onScreenOff()
                            scheduleFreeze()
                            startInputEventListenerIfEnabled()
                            ambientGlanceHandler.checkAndShowOnScreenOff()
                            aodWallpaperOverlayHandler.onScreenOff()
                            duoOverlayHandler.onScreenOff()
                            statusGlanceHandler.onScreenOff()
                            omniGestureOverlayHandler.updateOverlay(false) // Always hide when screen is off
                            pocketModeHandler.onScreenOff()
                            updatePocketModeSensors()
                        }

                        Intent.ACTION_USER_PRESENT -> {
                            isKeyguardLocked = false
                            invalidateBypassCache()
                            aodWallpaperOverlayHandler.onScreenOn()
                            statusGlanceHandler.onUserPresent()
                            islandOverlayHandler.updateState()
                            appFlowHandler.onScreenOn()
                            val currentApp = appFlowHandler.currentPackage
                            if (pocketModeHandler.pocketModeLockScreenOnly || isAppBypassedForPocketMode(currentApp)) {
                                pocketModeHandler.onScreenOff() // cancel pending timer + remove overlay + reset isBypassed
                            }
                            updateOmniOverlay()
                        }

                        "com.sameerasw.essentials.MEDIA_PLAYBACK_CHANGED" -> {
                            invalidateBypassCache()
                            val currentApp = appFlowHandler.currentPackage
                            if (isAppBypassedForPocketMode(currentApp)) {
                                pocketModeHandler.dismissForAppSwitch()
                            }
                        }

                        InputEventListenerService.ACTION_VOLUME_LONG_PRESSED -> {
                            buttonRemapHandler.handleExternalVolumeLongPress(intent)
                        }

                        "SHOW_AMBIENT_GLANCE",
                        "HIDE_AMBIENT_GLANCE_TEMPORARILY",
                        -> {
                            ambientGlanceHandler.handleIntent(intent)
                        }

                        "FORCE_TURN_OFF_AOD" -> {
                            aodForceTurnOffHandler.forceTurnOff()
                        }

                        "CONSCIOUS_GATE_CONFIRMED" -> {
                            intent?.getStringExtra("package_name")?.let { appFlowHandler.onConsciousGateConfirmed(it) }
                            islandOverlayHandler.updateConsciousGateState()
                        }

                        "CONSCIOUS_GATE_CLOSED" -> {
                            intent?.getStringExtra("package_name")?.let { appFlowHandler.onConsciousGateClosed(it) }
                            islandOverlayHandler.updateConsciousGateState()
                        }

                        FlashlightActionReceiver.ACTION_TOGGLE,
                        FlashlightActionReceiver.ACTION_OFF,
                        FlashlightActionReceiver.ACTION_SET_INTENSITY,
                        FlashlightActionReceiver.ACTION_INCREASE,
                        FlashlightActionReceiver.ACTION_DECREASE,
                        -> {
                            flashlightHandler.handleIntent(intent)
                        }
                    }
                }
            }

        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction("com.sameerasw.essentials.MEDIA_PLAYBACK_CHANGED")
                addAction(InputEventListenerService.ACTION_VOLUME_LONG_PRESSED)
                addAction("SHOW_AMBIENT_GLANCE")
                addAction("HIDE_AMBIENT_GLANCE_TEMPORARILY")
                addAction("FORCE_TURN_OFF_AOD")
                addAction("CONSCIOUS_GATE_CONFIRMED")
                addAction("CONSCIOUS_GATE_CLOSED")
                addAction(FlashlightActionReceiver.ACTION_TOGGLE)
                addAction(FlashlightActionReceiver.ACTION_OFF)
                addAction(FlashlightActionReceiver.ACTION_SET_INTENSITY)
                addAction(FlashlightActionReceiver.ACTION_INCREASE)
                addAction(FlashlightActionReceiver.ACTION_DECREASE)
            }
        registerReceiver(screenReceiver, filter, RECEIVER_EXPORTED)

        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        val powerManager = getSystemService(POWER_SERVICE) as? android.os.PowerManager
        isScreenOn = powerManager?.isInteractive ?: true
        isKeyguardLocked = keyguardManager.isKeyguardLocked

        updatePocketModePrefs()
        updatePocketModeExcludedAppsSet()
        updatePocketModeSensors()
    }

    private fun scheduleFreeze() {
        val isFreezeWhenLockedEnabled = prefs.getBoolean("freeze_when_locked_enabled", false)

        if (isFreezeWhenLockedEnabled) {
            val delayIndex = prefs.getInt("freeze_lock_delay_index", 1)
            val delayMs =
                when (delayIndex) {
                    0 -> 0L // Immediately
                    1 -> 60_000L // 1 minute
                    2 -> 300_000L // 5 minutes
                    3 -> 900_000L // 15 minutes
                    else -> -1L // Never
                }

            if (delayMs >= 0) {
                freezeHandler.removeCallbacks(freezeRunnable)
                freezeHandler.postDelayed(freezeRunnable, delayMs)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo =
            serviceInfo.apply {
                flags = flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        updateOmniOverlay()
        duoOverlayHandler.updateState()
        statusGlanceHandler.updateState()
        islandOverlayHandler.updateState()
    }

    private fun updateOmniOverlay() {
        val isGestureEnabled = prefs.getBoolean("circle_to_search_gesture_enabled", false)
        val height =
            try {
                prefs.getFloat("circle_to_search_gesture_height", 48f)
            } catch (e: Exception) {
                48f
            }
        val width =
            try {
                prefs.getFloat("circle_to_search_gesture_width", 240f)
            } catch (e: Exception) {
                240f
            }
        val isPreview = prefs.getBoolean("circle_to_search_preview_enabled", false)
        val shouldShow = isGestureEnabled && isScreenOn && !keyguardManager.isKeyguardLocked
        omniGestureOverlayHandler.updateOverlay(shouldShow, height, width, isPreview)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        flashlightHandler.unregister()
        notificationLightingHandler.removeOverlay()
        ambientGlanceHandler.removeOverlay()
        aodForceTurnOffHandler.removeOverlay()
        aodWallpaperOverlayHandler.removeOverlay()
        pocketModeHandler.removeOverlay()
        buttonRemapHandler.isVolumeDialogVisible = false
        omniGestureOverlayHandler.removeOverlay()
        smartPixelsHandler.destroy()
        duoOverlayHandler.destroy()
        islandOverlayHandler.onDestroy()
        statusGlanceHandler.destroy()
        statusBarIconHandler.unregister()
        stopInputEventListener()
        cancelPocketFlashlightTurnOff()
        if (isProximityRegistered) {
            proximitySensor?.let {
                sensorManager.unregisterListener(this, it)
            }
            isProximityRegistered = false
        }
        if (isLightSensorRegistered) {
            lightSensor?.let {
                sensorManager.unregisterListener(this, it)
            }
            isLightSensorRegistered = false
        }

        serviceScope.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        if (!AppDetectionService.isRunning) {
            try {
                appFlowHandler.destroy()
            } catch (_: Exception) {
            }
        }
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        var detectedPackage = event.packageName?.toString()
        if (detectedPackage == null || detectedPackage == "android") {
            try {
                val activePkg = rootInActiveWindow?.packageName?.toString()
                    ?: windows?.firstOrNull { it.isFocused }?.root?.packageName?.toString()
                if (activePkg != null) {
                    detectedPackage = activePkg
                }
            } catch (_: Exception) {}
        }

        if (detectedPackage != null) {
            appFlowHandler.onPackageChanged(detectedPackage)
            islandOverlayHandler.updateConsciousGateState()
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            checkFullscreenState()
            checkStatusBarExpansion()
            checkVolumeDialogState()
        }
    }

    private fun checkVolumeDialogState() {
        try {
            val prefs = getSharedPreferences("essentials_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("button_remap_pause_on_volume_dialog", true)) {
                buttonRemapHandler.isVolumeDialogVisible = false
                return
            }
            val currentWindows = windows
            val isVisible = currentWindows?.any { window ->
                window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                    window.root?.packageName?.toString() == "com.android.systemui" &&
                    window.title?.contains("Volume", ignoreCase = true) == true
            } ?: false
            buttonRemapHandler.isVolumeDialogVisible = isVisible
        } catch (_: Exception) {
            buttonRemapHandler.isVolumeDialogVisible = false
        }
    }

    private fun checkStatusBarExpansion() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                if (keyguardManager.isKeyguardLocked || !isScreenOn) {
                    statusGlanceHandler.setShadeExpanded(false)
                    return
                }
                val currentWindows = windows
                if (currentWindows.isNullOrEmpty()) {
                    statusGlanceHandler.setShadeExpanded(false)
                    return
                }

                val isShadeExpanded = currentWindows.any { window ->
                    window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                        window.title?.contains("NotificationShade", ignoreCase = true) == true
                }
                statusGlanceHandler.setShadeExpanded(isShadeExpanded)
            } catch (_: Exception) {
                statusGlanceHandler.setShadeExpanded(false)
            }
        }
    }

    private fun checkFullscreenState() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                val currentWindows = windows
                if (!currentWindows.isNullOrEmpty()) {
                    val hasStatusBar = currentWindows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM }
                    val appWindow = currentWindows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
                        ?: currentWindows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }

                    if (appWindow != null) {
                        val outBounds = android.graphics.Rect()
                        appWindow.getBoundsInScreen(outBounds)
                        val displayMetrics = resources.displayMetrics
                        val isCoveringFullDisplay = outBounds.width() >= displayMetrics.widthPixels &&
                            outBounds.height() >= displayMetrics.heightPixels

                        val isFullscreen = isCoveringFullDisplay && !hasStatusBar
                        duoOverlayHandler.setFullscreen(isFullscreen)
                        statusGlanceHandler.setFullscreen(isFullscreen)
                        islandOverlayHandler.setFullscreen(isFullscreen)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}

    fun isAppBypassedForPocketMode(packageName: String?): Boolean {
        if (!::pocketModeHandler.isInitialized) return false
        return pocketModeHandler.isAppBypassed(packageName, isKeyguardLocked)
    }

    fun dismissPocketMode() {
        pocketModeHandler.dismissForAppSwitch()
    }

    private fun updatePocketModeSensors() {
        if (!::pocketModeHandler.isInitialized) return
        val shouldRegisterLight = pocketModeHandler.pocketModeEnabled && pocketModeHandler.pocketModeUseLightSensor && isScreenOn
        val shouldRegisterProximity = pocketModeHandler.pocketModeEnabled && isScreenOn

        if (shouldRegisterLight) {
            if (lightSensor == null) {
                lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            }
            if (lightSensor != null && !isLightSensorRegistered) {
                sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                isLightSensorRegistered = true
                android.util.Log.d("ScreenOffService", "Registered light sensor for pocket mode")
            }
        } else {
            if (isLightSensorRegistered) {
                lightSensor?.let {
                    sensorManager.unregisterListener(this, it)
                }
                isLightSensorRegistered = false
                android.util.Log.d("ScreenOffService", "Unregistered light sensor for pocket mode")
            }
            lightSensorLux = 100f
        }

        isProximityRegisteredForPocket = shouldRegisterProximity

        updateProximitySensorRegistration()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !::pocketModeHandler.isInitialized) return
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            lightSensorLux = event.values[0]
            if (pocketModeHandler.pocketModeEnabled && !pocketModeHandler.isBypassed) {
                val currentApp = appFlowHandler.currentPackage
                val shouldBypass = isAppBypassedForPocketMode(currentApp)
                if (shouldBypass) {
                    pocketModeHandler.dismissForAppSwitch()
                } else {
                    pocketModeHandler.onProximityChanged(
                        isBlocked = flashlightHandler.isProximityBlocked,
                        isLightDark = lightSensorLux <= 3f,
                        useLightSensor = pocketModeHandler.pocketModeUseLightSensor,
                        triggerDelayMs = pocketModeHandler.pocketModeTriggerDelayMs,
                    )
                }
            }
        } else if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = event.sensor.maximumRange
            val isBlocked = distance < maxRange && distance < 5f

            flashlightHandler.isProximityBlocked = isBlocked

            if (pocketModeHandler.flashlightPocketTurnOffEnabled && isBlocked && flashlightHandler.isTorchOn) {
                schedulePocketFlashlightTurnOff()
            } else {
                cancelPocketFlashlightTurnOff()
            }

            if (pocketModeHandler.pocketModeEnabled && !pocketModeHandler.isBypassed) {
                val currentApp = appFlowHandler.currentPackage
                val shouldBypass = isAppBypassedForPocketMode(currentApp)
                if (shouldBypass) {
                    pocketModeHandler.dismissForAppSwitch()
                } else {
                    pocketModeHandler.onProximityChanged(
                        isBlocked = isBlocked,
                        isLightDark = lightSensorLux <= 3f,
                        useLightSensor = pocketModeHandler.pocketModeUseLightSensor,
                        triggerDelayMs = pocketModeHandler.pocketModeTriggerDelayMs,
                    )
                }
            }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOmniOverlay() // Force refresh overlay on rotation
        duoOverlayHandler.onConfigurationChanged(newConfig)
        islandOverlayHandler.onConfigurationChanged()
        statusGlanceHandler.onConfigurationChanged(newConfig)
        ambientGlanceHandler.onConfigurationChanged()
        invalidateBypassCache()
        val currentApp = appFlowHandler.currentPackage
        if (isAppBypassedForPocketMode(currentApp)) {
            pocketModeHandler.dismissForAppSwitch()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isVolumeKey =
            keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

        if (isVolumeKey) {
            if (pocketModeHandler.isOverlayVisible) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    pocketModeHandler.isBypassed = true
                    pocketModeHandler.removeOverlay()
                }
                return true
            }
            // Bypass logic for Camera apps to resolve conflicts with shutter/zoom functions
            val foregroundPackage =
                rootInActiveWindow?.packageName?.toString() ?: appFlowHandler.currentPackage
            if (appFlowHandler.isCameraApp(foregroundPackage)) {
                return false
            }
        }

        val handled = buttonRemapHandler.onKeyEvent(event)
        if (handled) {
            return true
        }

        if (isVolumeKey) {
            val powerManager = getSystemService(POWER_SERVICE) as? android.os.PowerManager
            if (powerManager?.isInteractive == false && event.action == KeyEvent.ACTION_DOWN) {
                triggerAmbientGlanceVolume(keyCode)
            }
        }
        return super.onKeyEvent(event)
    }

    private fun triggerAmbientGlanceVolume(keyCode: Int) {
        if (prefs.getBoolean(SettingsRepository.KEY_AMBIENT_MUSIC_GLANCE_ENABLED, false)) {
            // Skip if Android Auto is running
            if (AppUtil.isAndroidAutoRunning(this)) {
                return
            }

            val mediaSessionManager =
                getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName =
                ComponentName(this, NotificationListener::class.java)
            val sessions =
                try {
                    mediaSessionManager.getActiveSessions(componentName)
                } catch (e: Exception) {
                    emptyList()
                }
            val isPlaying =
                sessions.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            if (!isPlaying) {
                return
            }

            val title = prefs.getString("current_media_title", null)
            val artist = prefs.getString("current_media_artist", null)

            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val currentVolume =
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val percentage = (currentVolume.toFloat() / maxVolume.toFloat() * 100).toInt()

            val isDockedMode =
                prefs.getBoolean(SettingsRepository.KEY_AMBIENT_MUSIC_GLANCE_DOCKED_MODE, false)

            val intent =
                Intent("SHOW_AMBIENT_GLANCE").apply {
                    putExtra("event_type", "volume")
                    putExtra("track_title", title)
                    putExtra("artist_name", artist)
                    putExtra("volume_percentage", percentage)
                    putExtra("volume_key_code", keyCode)
                    putExtra("is_docked_mode", isDockedMode)
                }
            ambientGlanceHandler.handleIntent(intent)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val action = intent?.action ?: return super.onStartCommand(intent, flags, startId)

        when (action) {
            "LOCK_SCREEN" -> {
                val hapticTypeStr =
                    prefs.getString("haptic_feedback_type", HapticFeedbackType.NONE.name)
                val hapticType =
                    try {
                        HapticFeedbackType.valueOf(hapticTypeStr ?: HapticFeedbackType.NONE.name)
                    } catch (e: Exception) {
                        HapticFeedbackType.NONE
                    }

                if (hapticType != HapticFeedbackType.NONE) {
                    val vibrator = ContextCompat.getSystemService(this, Vibrator::class.java)
                    vibrator?.let { performHapticFeedback(it, hapticType) }
                }
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }

            "SHOW_NOTIFICATION_LIGHTING" -> notificationLightingHandler.handleIntent(intent)
            "SHOW_AMBIENT_GLANCE" -> ambientGlanceHandler.handleIntent(intent)
            "FORCE_TURN_OFF_AOD" -> aodForceTurnOffHandler.forceTurnOff()

            "APP_AUTHENTICATED" ->
                intent
                    .getStringExtra("package_name")
                    ?.let { appFlowHandler.onAuthenticated(it) }

            "APP_AUTHENTICATION_FAILED" -> performGlobalAction(GLOBAL_ACTION_HOME)

            "CONSCIOUS_GATE_CONFIRMED" -> {
                intent
                    .getStringExtra("package_name")
                    ?.let { appFlowHandler.onConsciousGateConfirmed(it) }
                islandOverlayHandler.updateConsciousGateState()
            }

            "CONSCIOUS_GATE_CLOSED" -> {
                intent
                    .getStringExtra("package_name")
                    ?.let { appFlowHandler.onConsciousGateClosed(it) }
                islandOverlayHandler.updateConsciousGateState()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }

            FlashlightActionReceiver.ACTION_INCREASE,
            FlashlightActionReceiver.ACTION_DECREASE,
            FlashlightActionReceiver.ACTION_OFF,
            FlashlightActionReceiver.ACTION_TOGGLE,
            FlashlightActionReceiver.ACTION_SET_INTENSITY,
            FlashlightActionReceiver.ACTION_START_SOS,
            FlashlightActionReceiver.ACTION_START_STROBE,
            FlashlightActionReceiver.ACTION_STOP_SPECIAL_MODES,
            FlashlightActionReceiver.ACTION_PULSE_NOTIFICATION,
            ->
                flashlightHandler.handleIntent(
                    intent,
                )
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startInputEventListenerIfEnabled() {
        val isEnabled = prefs.getBoolean("button_remap_enabled", false)
        val useShizuku = prefs.getBoolean("button_remap_use_shizuku", false)

        if (isEnabled && useShizuku) {
            try {
                val intent = Intent(this, InputEventListenerService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun stopInputEventListener() {
        try {
            stopService(Intent(this, InputEventListenerService::class.java))
        } catch (e: Exception) {
            // Ignore
        }
    }

    companion object {
        var instance: ScreenOffAccessibilityService? = null

        fun updateSmartPixelsState() {
            instance?.smartPixelsHandler?.updateState()
        }
    }
}
