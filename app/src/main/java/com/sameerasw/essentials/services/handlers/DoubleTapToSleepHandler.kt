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
import android.os.PowerManager
import android.os.SystemClock
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.PathInterpolator
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.domain.HapticFeedbackType
import com.sameerasw.essentials.utils.ShellUtils
import com.sameerasw.essentials.utils.performHapticFeedback

class DoubleTapToSleepHandler(
    private val service: AccessibilityService,
    private val currentPackageProvider: () -> String?,
    private val isScreenOnProvider: () -> Boolean,
) {
    private val windowManager by lazy { service.getSystemService(WindowManager::class.java) }
    private val keyguardManager by lazy { service.getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { service.getSystemService(PowerManager::class.java) }
    private val vibrator by lazy { service.getSystemService(Vibrator::class.java) }
    private val settingsRepository by lazy { SettingsRepository(service) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val standardInterpolator = PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f)

    private var overlayView: View? = null
    private var flickerOverlayView: View? = null
    private val brightWakeLock: PowerManager.WakeLock? by lazy {
        try {
            @Suppress("DEPRECATION")
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "essentials:dt2s_bright"
            ).apply { setReferenceCounted(false) }
        } catch (_: Exception) {
            null
        }
    }
    private var lastTapTime: Long = 0L
    private var lastSleepTriggerTime: Long = 0L
    private var cachedLauncherPackage: String? = null
    private var cachedMaxTimeout: Long = 400L

    // Jamworks PixelService equivalence:
    //   K flag — any VIEW_CLICKED / VIEW_SCROLLED accessibility event in the last 300ms
    //   blocks DT2S. Prevents firing lockNow() into a busy/unsettled keyguard (flicker source).
    @Volatile
    private var lastA11yPulseTime: Long = 0L

    val isListeningForTaps: Boolean
        get() = overlayView != null

    fun onAccessibilityEventPulse() {
        if (overlayView != null) {
            lastA11yPulseTime = SystemClock.uptimeMillis()
        }
    }

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
            updateOverlaysForContext()
        }
    }

    fun onForegroundPackageChanged(packageName: String?) {
        mainHandler.post {
            updateOverlaysForContext(currentPackage = packageName)
        }
    }

    fun onScreenOn() {
        mainHandler.post {
            cancelPendingSleep()
            // Jamworks d4 SCREEN_ON: wait 150ms before hiding the flicker overlay
            mainHandler.removeCallbacks(screenOnOverlayResetRunnable)
            mainHandler.postDelayed(screenOnOverlayResetRunnable, 150L)
            if (cachedLauncherPackage == null) {
                updateLauncherPackage()
            }
            updateOverlaysForContext()
        }
    }

    private val screenOnOverlayResetRunnable = Runnable {
        resetFlickerOverlay(animated = true)
    }

    fun onUserPresent() {
        mainHandler.post {
            cancelPendingSleep()
            resetFlickerOverlay(animated = true)
            if (cachedLauncherPackage == null) {
                updateLauncherPackage()
            }
            updateOverlaysForContext(isKeyguard = false)
        }
    }

    fun onScreenOff() {
        // Zero window churn during screen-off transition, and keep touch overlay attached
        // so it is immediately ready on next screen-on without waiting for a window event.
    }

    fun onDestroy() {
        mainHandler.post {
            cancelPendingSleep()
            removeOverlays()
        }
    }

    private fun updateOverlaysForContext(
        isKeyguard: Boolean = keyguardManager.isKeyguardLocked,
        currentPackage: String? = currentPackageProvider(),
    ) {
        if (!settingsRepository.isDoubleTapToSleepEnabled()) {
            removeOverlays()
            return
        }

        if (cachedLauncherPackage == null) {
            updateLauncherPackage()
        }

        val resolvedPackage = currentPackage ?: runCatching {
            service.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()

        // Only detach the touch overlay when we are certain a non-launcher, non-SystemUI app is active.
        // Keeping it attached when package is unknown/SystemUI prevents missing taps after screen-on/unlock.
        val isKnownOtherApp = !isKeyguard &&
            resolvedPackage != null &&
            resolvedPackage != cachedLauncherPackage &&
            resolvedPackage != "com.android.systemui" &&
            resolvedPackage != service.packageName

        val isEligibleContext = !isKnownOtherApp && (
            (isKeyguard && settingsRepository.isDoubleTapOnLockscreenEnabled()) ||
                (!isKeyguard && settingsRepository.isDoubleTapOnHomeEnabled())
            )

        if (isEligibleContext) {
            ensureTouchOverlay()
            if (settingsRepository.isDoubleTapFlickerProtectionEnabled() && !ShellUtils.hasPermission(service)) {
                ensureFlickerOverlay()
            } else if (flickerOverlayView?.visibility != View.VISIBLE) {
                removeFlickerOverlayFromWm()
            }
        } else {
            removeTouchOverlay()
            if (flickerOverlayView?.visibility != View.VISIBLE) {
                removeFlickerOverlayFromWm()
            }
        }
    }

    private fun ensureTouchOverlay() {
        if (overlayView != null && overlayView?.isAttachedToWindow == true) {
            return
        }
        removeTouchOverlay()

        val view = View(service).apply {
            setBackgroundColor(0) // completely transparent
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    val eventTime = event.eventTime
                    val delta = eventTime - lastTapTime
                    lastTapTime = eventTime

                    // If flicker overlay is visible, tapping cancels sleep and resets it (matching Jamworks d30.onTouch)
                    if (flickerOverlayView?.visibility == View.VISIBLE) {
                        cancelPendingSleep()
                        resetFlickerOverlay()
                        return@setOnTouchListener false
                    }

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
            android.util.Log.w("DoubleTapToSleepHandler", "Failed to add touch overlay: ${e.message}")
            overlayView = null
        }
    }

    private fun ensureFlickerOverlay() {
        if (flickerOverlayView != null && flickerOverlayView?.isAttachedToWindow == true) {
            return
        }
        removeFlickerOverlayFromWm()

        val blackView = View(service).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

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
        } catch (e: Exception) {
            android.util.Log.w("DoubleTapToSleepHandler", "Failed to add flicker overlay: ${e.message}")
            flickerOverlayView = null
        }
    }

    private val safetyHideFlickerRunnable = Runnable {
        resetFlickerOverlay(animated = true)
    }

    private fun resetFlickerOverlay(animated: Boolean = false) {
        mainHandler.removeCallbacks(safetyHideFlickerRunnable)
        flickerOverlayView?.let { view ->
            if (view.visibility != View.VISIBLE) return@let
            view.animate().cancel()
            if (animated) {
                // Jamworks PixelService method c(): smooth 150ms fade-out before setting GONE
                view.animate()
                    .alpha(0f)
                    .setDuration(150L)
                    .setInterpolator(standardInterpolator)
                    .withEndAction {
                        view.alpha = 0f
                        view.visibility = View.GONE
                    }
                    .start()
            } else {
                view.alpha = 0f
                view.visibility = View.GONE
            }
        }
    }

    private fun removeTouchOverlay() {
        overlayView?.let { view ->
            runCatching {
                if (view.isAttachedToWindow) {
                    windowManager.removeViewImmediate(view)
                } else {
                    windowManager.removeView(view)
                }
            }
            overlayView = null
        }
        lastTapTime = 0L
    }

    private fun removeFlickerOverlayFromWm() {
        mainHandler.removeCallbacks(safetyHideFlickerRunnable)
        flickerOverlayView?.let { view ->
            view.animate().cancel()
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

    private fun removeOverlays() {
        resetFlickerOverlay()
        removeTouchOverlay()
        removeFlickerOverlayFromWm()
    }

    // Jamworks PixelService method f():
    // Cancels all pending sleep callbacks, resets AOD sleep state, and releases bright wake lock immediately.
    fun cancelPendingSleep() {
        isSleepingAod = false
        try {
            if (brightWakeLock?.isHeld == true) {
                brightWakeLock?.release()
            }
        } catch (_: Exception) {
        }
        mainHandler.removeCallbacks(lockscreenSleepRunnable)
        mainHandler.removeCallbacks(lockscreenSecondSleepRunnable)
        mainHandler.removeCallbacks(aodWakeLockAcquireRunnable)
        mainHandler.removeCallbacks(aodSecondSleepRunnable)
        mainHandler.removeCallbacks(aodClearStateRunnable)
        mainHandler.removeCallbacks(safetyHideFlickerRunnable)
        mainHandler.removeCallbacks(flickerSleepRunnable)
    }

    // Jamworks c30 case 14 (P runnable): performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    private val lockscreenSleepRunnable = Runnable {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    private val lockscreenSecondSleepRunnable = Runnable {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    private val flickerSleepRunnable = Runnable {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    // Jamworks m() AOD runnables
    private var isSleepingAod = false

    private val aodWakeLockAcquireRunnable = Runnable {
        try {
            brightWakeLock?.acquire(500L)
        } catch (_: Exception) {
        }
    }

    private val aodSecondSleepRunnable = Runnable {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    private val aodClearStateRunnable = Runnable {
        isSleepingAod = false
    }

    private fun triggerAodRoutine() {
        isSleepingAod = true
        mainHandler.postDelayed(aodWakeLockAcquireRunnable, 200L)
        mainHandler.removeCallbacks(aodSecondSleepRunnable)
        mainHandler.postDelayed(aodSecondSleepRunnable, 420L)
        mainHandler.removeCallbacks(aodClearStateRunnable)
        mainHandler.postDelayed(aodClearStateRunnable, 650L)
    }

    private fun performLock(jumpHome: Boolean = false) {
        cancelPendingSleep()

        if (jumpHome) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }

        if (settingsRepository.isDoubleTapFlickerProtectionEnabled()) {
            val view = flickerOverlayView
            if (view != null) {
                view.animate().cancel()
                view.alpha = 0f
                view.visibility = View.VISIBLE

                mainHandler.removeCallbacks(safetyHideFlickerRunnable)
                mainHandler.postDelayed(safetyHideFlickerRunnable, 2000L)

                view.animate()
                    .alpha(1f)
                    .setDuration(350L)
                    .setInterpolator(standardInterpolator)
                    .withEndAction {
                        mainHandler.post(flickerSleepRunnable)
                    }
                    .start()
            } else {
                mainHandler.postDelayed(lockscreenSleepRunnable, 10L)
            }
        } else {
            // Jamworks clean sleep dispatch: 10ms
            mainHandler.postDelayed(lockscreenSleepRunnable, 10L)

            val isAod = runCatching {
                Settings.Secure.getInt(service.contentResolver, "doze_always_on", 0) == 1
            }.getOrDefault(false)

            if (isAod) {
                triggerAodRoutine()
            }
        }
    }

    private fun isOverviewOrNonWorkspace(): Boolean {
        return try {
            val root = service.rootInActiveWindow ?: return false
            val rootPkg = root.packageName?.toString()
            if (rootPkg != null && cachedLauncherPackage != null && rootPkg != cachedLauncherPackage) {
                return false
            }
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

    private fun hasVisiblePasswordOrBouncerNode(root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>(64)
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 80) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser) {
                if (node.isPassword) return true
                val viewId = node.viewIdResourceName
                if (viewId != null && (
                        viewId.contains("bouncer", ignoreCase = true) ||
                            viewId.contains("pin_pad", ignoreCase = true) ||
                            viewId.contains("pinEntry", ignoreCase = true)
                        )
                ) {
                    return true
                }
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return false
    }

    private fun isPictureInPictureActive(windowsSnapshot: List<AccessibilityWindowInfo>?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return windowsSnapshot?.any { it.isInPictureInPictureMode } == true
    }

    private fun triggerVibrationIfEnabled() {
        if (settingsRepository.isDoubleTapToSleepVibrateEnabled() && vibrator != null) {
            try {
                val hapticType = settingsRepository.getDoubleTapToSleepHapticType()
                performHapticFeedback(vibrator!!, hapticType)
            } catch (_: Exception) {
            }
        }
    }

    private fun sleepViaPowerKey() {
        android.util.Log.d("DoubleTapToSleepHandler", "sleeping via shell KEYCODE_POWER")
        ShellUtils.runCommand(
            service,
            "input keyevent ${KeyEvent.KEYCODE_POWER}",
            notifyOnError = false,
        )
    }

    private fun handleDoubleTap() {
        if (!isScreenOnProvider()) {
            android.util.Log.d("DoubleTapToSleepHandler", "gate: screen off")
            return
        }

        val now = SystemClock.uptimeMillis()
        // Jamworks d30 gate (m > 200): 200ms cooldown since last successful sleep trigger
        if (now - lastSleepTriggerTime < 200L) return

        if (now < suppressUntil) return

        // Jamworks d30 outer gate (K flag): refuse while accessibility interaction events are churning
        if (now - lastA11yPulseTime < 300L) {
            android.util.Log.d("DoubleTapToSleepHandler", "gate: a11y busy")
            return
        }

        val isKeyguard = keyguardManager.isKeyguardLocked
        if (isKeyguard) {
            if (!settingsRepository.isDoubleTapOnLockscreenEnabled()) return

            val windowsSnapshot = runCatching { service.windows }.getOrNull()
            // Ignore double taps if software keyboard (IME) is currently open
            if (windowsSnapshot?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true) return

            // Jamworks d30.onTouch gate: the lockscreen (SystemUI) must be the focused window.
            val focusedPkg = runCatching {
                windowsSnapshot
                    ?.firstOrNull {
                        (it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                            it.type == AccessibilityWindowInfo.TYPE_SYSTEM) && it.isFocused
                    }
                    ?.root?.packageName?.toString()
            }.getOrNull()
            if (focusedPkg != "com.android.systemui") {
                android.util.Log.d("DoubleTapToSleepHandler", "lockscreen gate: focused=$focusedPkg")
                return
            }

            // Ignore double taps on lockscreen if PIN/password/pattern bouncer or QS panel are active
            val hasBouncerOrQs = runCatching {
                val blockedIds = listOf(
                    "com.android.systemui:id/keyguard_bouncer_container",
                    "com.android.systemui:id/bouncer_container",
                    "com.android.systemui:id/keyguard_pin_view",
                    "com.android.systemui:id/keyguard_password_view",
                    "com.android.systemui:id/keyguard_pattern_view",
                    "com.android.systemui:id/pinEntry",
                    "com.android.systemui:id/passwordEntry",
                    "com.android.systemui:id/lockPatternView",
                    "com.android.systemui:id/quick_qs_panel",
                )
                windowsSnapshot?.any { win ->
                    if ((win.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                            win.type != AccessibilityWindowInfo.TYPE_SYSTEM) ||
                        !win.isFocused
                    ) {
                        return@any false
                    }
                    val root = win.root ?: return@any false
                    root.refresh()
                    if (blockedIds.any { id ->
                            root.findAccessibilityNodeInfosByViewId(id)?.any { it.isVisibleToUser } == true
                        }
                    ) {
                        return@any true
                    }
                    // Compose Bouncer (Android 15/16 SceneContainer) may not expose legacy XML IDs:
                    // check a bounded BFS for visible password/bouncer nodes.
                    hasVisiblePasswordOrBouncerNode(root)
                } == true
            }.getOrDefault(false)

            if (hasBouncerOrQs) {
                android.util.Log.d("DoubleTapToSleepHandler", "lockscreen gate: bouncer/qs visible")
                return
            }

            val hasShell = ShellUtils.hasPermission(service)
            if (!hasShell && isPictureInPictureActive(windowsSnapshot)) {
                android.util.Log.d(
                    "DoubleTapToSleepHandler",
                    "gate: PiP active without shell permission on lockscreen, ignoring DT2S to protect screen-off animation",
                )
                return
            }

            lastSleepTriggerTime = now
            triggerVibrationIfEnabled()
            cancelPendingSleep()

            if (hasShell) {
                sleepViaPowerKey()
                return
            }

            // Jamworks h() lockscreen branch: dispatch GLOBAL_ACTION_LOCK_SCREEN twice,
            // at 10ms AND 100ms. The 2nd dispatch re-sleeps the display when the 2nd tap's
            // finger-lift pulse (WAKE_REASON_TAP) briefly wakes doze ~60ms after the 1st
            // dispatch.
            mainHandler.postDelayed(lockscreenSleepRunnable, 10L)
            mainHandler.postDelayed(lockscreenSecondSleepRunnable, 100L)
            return
        }

        // Home screen DT2S: fail fast in-memory before any Binder IPC
        if (!settingsRepository.isDoubleTapOnHomeEnabled()) return

        if (cachedLauncherPackage == null) {
            updateLauncherPackage()
        }
        val currentPkg = currentPackageProvider() ?: runCatching {
            service.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()

        val isHome = currentPkg != null && currentPkg == cachedLauncherPackage
        val isOverview = if (isHome) isOverviewOrNonWorkspace() else false
        if (!isHome || isOverview) {
            android.util.Log.d(
                "DoubleTapToSleepHandler",
                "home gate: currentPkg=$currentPkg launcher=$cachedLauncherPackage isOverview=$isOverview",
            )
            return
        }

        val windowsSnapshot = runCatching { service.windows }.getOrNull()
        if (windowsSnapshot?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true) return

        val hasShell = ShellUtils.hasPermission(service)
        if (!hasShell && isPictureInPictureActive(windowsSnapshot)) {
            android.util.Log.d(
                "DoubleTapToSleepHandler",
                "gate: PiP active without shell permission on home screen, ignoring DT2S to protect screen-off animation",
            )
            return
        }

        lastSleepTriggerTime = now
        triggerVibrationIfEnabled()

        val shouldJumpHome = settingsRepository.isDoubleTapJumpHomeEnabled()
        if (hasShell) {
            cancelPendingSleep()
            if (shouldJumpHome) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
            sleepViaPowerKey()
            return
        }

        performLock(jumpHome = shouldJumpHome)
    }
}
