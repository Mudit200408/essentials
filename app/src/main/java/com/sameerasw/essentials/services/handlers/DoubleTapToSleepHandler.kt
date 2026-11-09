/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Handlers
 * File: DoubleTapToSleepHandler.kt
 * Description: Touch handler for double-tap-to-sleep on home and lock screen.
 */

package com.sameerasw.essentials.services.handlers

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.PathInterpolator
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.domain.HapticFeedbackType
import com.sameerasw.essentials.utils.performHapticFeedback

class DoubleTapToSleepHandler(
    private val service: AccessibilityService,
    private val currentPackageProvider: () -> String?,
    private val isScreenOnProvider: () -> Boolean,
) {
    private val windowManager by lazy { service.getSystemService(WindowManager::class.java) }
    private val keyguardManager by lazy { service.getSystemService(KeyguardManager::class.java) }
    private val vibrator by lazy { service.getSystemService(Vibrator::class.java) }
    private val settingsRepository by lazy { SettingsRepository(service) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val standardInterpolator = PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f)

    private var overlayView: View? = null
    private var flickerOverlayView: View? = null
    private var lastTapTime: Long = 0L
    private var cachedLauncherPackage: String? = null
    private var cachedMaxTimeout: Long = 400L
    private var suppressUntil: Long = 0L

    fun suppressTaps(durationMs: Long) {
        suppressUntil = SystemClock.uptimeMillis() + durationMs
    }

    fun init() {
        updateLauncherPackage()
        updateState()
    }

    fun updateLauncherPackage() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                service.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            cachedLauncherPackage = resolveInfo?.activityInfo?.packageName
        } catch (_: Exception) {
        }
    }

    fun updateState() {
        mainHandler.post {
            cachedMaxTimeout = settingsRepository.getDoubleTapTimeout().coerceIn(100L, 750L)
            val enabled = settingsRepository.isDoubleTapToSleepEnabled()
            val screenOn = isScreenOnProvider()

            if (enabled && screenOn) {
                addOverlay()
            } else {
                removeOverlay()
            }
        }
    }

    fun onScreenOn() {
        mainHandler.post {
            removeFlickerOverlay()
        }
        updateState()
    }

    fun onScreenOff() {
        mainHandler.post {
            if (settingsRepository.isAodEnabled()) {
                dismissFlickerOverlaySmoothly()
            } else {
                removeFlickerOverlay()
            }
            removeOverlay()
        }
    }

    fun onDestroy() {
        mainHandler.post {
            removeFlickerOverlay()
            removeOverlay()
        }
    }

    private fun addOverlay() {
        if (overlayView != null) {
            if (overlayView?.isAttachedToWindow == true) return
            removeOverlay()
        }

        val view = View(service).apply {
            setBackgroundColor(0) // completely transparent
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    val eventTime = event.eventTime
                    val delta = eventTime - lastTapTime
                    lastTapTime = eventTime

                    if (delta in 80..cachedMaxTimeout) {
                        handleDoubleTap()
                    }
                }
                false
            }
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            android.util.Log.w("DoubleTapToSleepHandler", "Failed to add overlay: ${e.message}")
            overlayView = null
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
            overlayView = null
        }
        lastTapTime = 0L
    }

    private val safetyRemoveFlickerRunnable = Runnable {
        removeFlickerOverlay()
    }

    private fun removeFlickerOverlay() {
        mainHandler.removeCallbacks(safetyRemoveFlickerRunnable)
        flickerOverlayView?.let { view ->
            view.animate().cancel()
            view.alpha = 0f
            view.visibility = View.GONE
            runCatching {
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                } else {
                    windowManager.removeView(view)
                }
            }
            flickerOverlayView = null
        }
    }

    private fun dismissFlickerOverlaySmoothly() {
        mainHandler.removeCallbacks(safetyRemoveFlickerRunnable)
        val view = flickerOverlayView ?: return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(standardInterpolator)
            .withEndAction {
                removeFlickerOverlay()
            }
            .start()
    }

    private fun performLockWithFlickerProtection(jumpHome: Boolean = false) {
        removeFlickerOverlay()

        val blackView = View(service).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            windowManager.addView(blackView, params)
            flickerOverlayView = blackView

            // Safety watchdog timeout: under no circumstance may the black overlay persist longer than 2000ms
            mainHandler.postDelayed(safetyRemoveFlickerRunnable, 2000L)

            blackView.animate()
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(standardInterpolator)
                .withEndAction {
                    if (jumpHome) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    }
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                }
                .start()
        } catch (_: Exception) {
            removeFlickerOverlay()
            if (jumpHome) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    private fun isOverviewOrNonWorkspace(): Boolean {
        return try {
            val root = service.rootInActiveWindow ?: return false
            val overviewIds = listOf(
                "com.google.android.apps.nexuslauncher:id/overview_panel",
                "com.android.launcher3:id/overview_panel",
                "com.google.android.apps.nexuslauncher:id/recents_view",
                "com.android.launcher3:id/recents_view",
            )
            for (id in overviewIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) return true
            }
            val workspaceIds = listOf(
                "com.google.android.apps.nexuslauncher:id/workspace",
                "com.android.launcher3:id/workspace",
            )
            var hasWorkspace = false
            for (id in workspaceIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    hasWorkspace = true
                    break
                }
            }
            !hasWorkspace
        } catch (_: Exception) {
            false
        }
    }

    private fun handleDoubleTap() {
        if (!isScreenOnProvider()) return
        if (SystemClock.uptimeMillis() < suppressUntil) return

        // Ignore double taps if software keyboard (IME) is currently open
        val isImeOpen = runCatching {
            service.windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        }.getOrDefault(false)
        if (isImeOpen) return

        val isKeyguard = keyguardManager.isKeyguardLocked
        if (cachedLauncherPackage == null) {
            updateLauncherPackage()
        }
        val currentPkg = currentPackageProvider() ?: runCatching {
            service.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()

        // Double tap only triggers on home launcher workspace or lockscreen/SystemUI
        val isHome = currentPkg != null && currentPkg == cachedLauncherPackage
        if (isHome && !isKeyguard && isOverviewOrNonWorkspace()) {
            return
        }

        val isLockscreen = isKeyguard && (currentPkg == "com.android.systemui" || currentPkg == null)

        // Ignore double taps on lockscreen if PIN keypad or QS panel are active
        if (isLockscreen) {
            val hasKeypadOrQs = runCatching {
                val root = service.rootInActiveWindow ?: return@runCatching false
                val pinNodes = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/keyguard_pin_view")
                val qsNodes = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/quick_qs_panel")
                !pinNodes.isNullOrEmpty() || !qsNodes.isNullOrEmpty()
            }.getOrDefault(false)
            if (hasKeypadOrQs) return
        }

        val allowHome = settingsRepository.isDoubleTapOnHomeEnabled()
        val allowLockscreen = settingsRepository.isDoubleTapOnLockscreenEnabled()

        if ((isHome && allowHome) || (isLockscreen && allowLockscreen)) {
            suppressTaps(1500L)

            if (settingsRepository.isDoubleTapToSleepVibrateEnabled() && vibrator != null) {
                try {
                    val hapticType = settingsRepository.getDoubleTapToSleepHapticType()
                    performHapticFeedback(vibrator!!, hapticType)
                } catch (_: Exception) {
                }
            }

            val shouldJumpHome = isHome && !isKeyguard && settingsRepository.isDoubleTapJumpHomeEnabled()

            if (settingsRepository.isDoubleTapFlickerProtectionEnabled()) {
                performLockWithFlickerProtection(jumpHome = shouldJumpHome)
            } else {
                if (shouldJumpHome) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
        }
    }
}
