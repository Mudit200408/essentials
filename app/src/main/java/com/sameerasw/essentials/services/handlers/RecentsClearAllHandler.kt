/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Handlers
 * File: RecentsClearAllHandler.kt
 * Description: Floating Clear All button overlay for the recent apps overview.
 */

package com.sameerasw.essentials.services.handlers

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.sameerasw.essentials.R
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.domain.HapticFeedbackType
import com.sameerasw.essentials.utils.performHapticFeedback
import android.os.Vibrator

class RecentsClearAllHandler(
    private val service: AccessibilityService,
    var onClearAllTriggered: (() -> Unit)? = null,
) {
    private val windowManager by lazy { service.getSystemService(WindowManager::class.java) }
    private val vibrator by lazy { service.getSystemService(Vibrator::class.java) }
    private val settingsRepository by lazy { SettingsRepository(service) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var buttonView: TextView? = null
    private var isOverviewVisible = false
    private var isClickInProgress = false

    private val checkOverviewDismissRunnable = object : Runnable {
        override fun run() {
            if (isOverviewVisible && !isOverviewPanelInHierarchy()) {
                hideButton()
            } else if (isOverviewVisible) {
                mainHandler.postDelayed(this, 300)
            }
        }
    }

    fun init() {
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!settingsRepository.isRecentsClearAllEnabled()) {
            if (buttonView != null) hideButton()
            return
        }

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            checkAndToggleRecentsState()
        }
    }

    fun onRecentAppsBroadcast() {
        if (!settingsRepository.isRecentsClearAllEnabled()) return
        mainHandler.postDelayed({
            checkAndToggleRecentsState()
        }, 150)
    }

    fun updateState() {
        if (!settingsRepository.isRecentsClearAllEnabled()) {
            hideButton()
        }
    }

    private fun checkAndToggleRecentsState() {
        if (isOverviewPanelInHierarchy()) {
            showButton()
        } else {
            hideButton()
        }
    }

    private fun isOverviewPanelInHierarchy(): Boolean {
        return try {
            val root = service.rootInActiveWindow ?: return false
            hasOverviewNodes(root)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasOverviewNodes(root: AccessibilityNodeInfo): Boolean {
        val launcherPanels = listOf(
            "com.google.android.apps.nexuslauncher:id/overview_panel",
            "com.android.launcher3:id/overview_panel",
            "com.google.android.apps.nexuslauncher:id/recents_view",
            "com.android.launcher3:id/recents_view",
        )
        for (id in launcherPanels) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) return true
        }
        val clearAllNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.nexuslauncher:id/clear_all")
            .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.android.launcher3:id/clear_all") }
        return !clearAllNodes.isNullOrEmpty()
    }

    private fun showButton() {
        mainHandler.post {
            if (buttonView == null) {
                createButtonView()
            }

            buttonView?.let { view ->
                if (!isOverviewVisible) {
                    isOverviewVisible = true
                    view.visibility = View.VISIBLE
                    view.animate().cancel()
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(250).start()
                    mainHandler.removeCallbacks(checkOverviewDismissRunnable)
                    mainHandler.postDelayed(checkOverviewDismissRunnable, 500)
                }
            }
        }
    }

    fun hideButton() {
        mainHandler.post {
            isOverviewVisible = false
            mainHandler.removeCallbacks(checkOverviewDismissRunnable)
            buttonView?.let { view ->
                view.animate().cancel()
                view.animate().alpha(0f).setDuration(200).withEndAction {
                    view.visibility = View.GONE
                }.start()
            }
        }
    }

    fun onScreenOff() {
        hideButton()
    }

    fun onDestroy() {
        mainHandler.removeCallbacks(checkOverviewDismissRunnable)
        mainHandler.post {
            buttonView?.let { view ->
                runCatching { windowManager.removeView(view) }
                buttonView = null
            }
        }
    }

    private fun createButtonView() {
        val dp = service.resources.displayMetrics.density
        val navBarHeight = getNavigationBarHeight()
        val isNight = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // Matches OverviewBlurStyle / @drawable/overview_action_button_background_blur from NexusLauncherRelease.apk
        val effect1Res = service.resources.getIdentifier("customColorSurfaceEffect1", "color", "android")
        val baseColor = if (effect1Res != 0) {
            ContextCompat.getColor(service, effect1Res)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val colorRes = if (isNight) android.R.color.system_surface_bright_dark else android.R.color.system_surface_bright_light
            ContextCompat.getColor(service, colorRes)
        } else {
            if (isNight) Color.parseColor("#38393C") else Color.parseColor("#EAEBEE")
        }
        val bgAlpha = if (isNight) 180 else 200 // ~70% dark, ~78% light for frosted glass transparency
        val bgColor = ColorUtils.setAlphaComponent(baseColor, bgAlpha)

        // Exactly matches @color/materialColorOnSurface from NexusLauncherRelease.apk
        val textColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val colorRes = if (isNight) android.R.color.system_on_surface_dark else android.R.color.system_on_surface_light
            ContextCompat.getColor(service, colorRes)
        } else {
            if (isNight) Color.parseColor("#E3E2E6") else Color.parseColor("#1D1B20")
        }

        val rippleColor = ColorStateList.valueOf(if (isNight) Color.parseColor("#26FFFFFF") else Color.parseColor("#1F000000"))

        val tv = TextView(service).apply {
            text = service.getString(R.string.clear_all_recents_button)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isAllCaps = false
            typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 600, false)
            } else {
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            gravity = Gravity.CENTER
            // Matches @dimen/overview_actions_button_horizontal_padding (16dp) and vertical padding (4dp)
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            // Matches @dimen/overview_actions_height (48dp)
            minimumHeight = (48 * dp).toInt()

            // Matches ic_screenshot.xml: 20dp x 20dp vector icon, tinted with materialColorOnSurface
            val icon = ContextCompat.getDrawable(service, R.drawable.rounded_clear_all_24)?.mutate()
            icon?.setBounds(0, 0, (20 * dp).toInt(), (20 * dp).toInt())
            icon?.setTint(textColor)
            setCompoundDrawables(icon, null, null, null)
            // Matches drawablePadding (8dp)
            compoundDrawablePadding = (8 * dp).toInt()

            // Matches @dimen/overview_actions_button_radius (28dp)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * dp
                setColor(bgColor)
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * dp
                setColor(Color.WHITE)
            }
            background = RippleDrawable(rippleColor, bg, mask)
            elevation = 0f
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        if (settingsRepository.isRecentsClearAllVibrateEnabled() && vibrator != null) {
                            try {
                                val hapticType = settingsRepository.getRecentsClearAllHapticType()
                                performHapticFeedback(vibrator!!, hapticType)
                            } catch (_: Exception) {
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        v.performClick()
                        triggerClearAll()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        true
                    }
                    else -> false
                }
            }
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val yMargin = navBarHeight + (16 * dp).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            (48 * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = yMargin
        }

        try {
            windowManager.addView(tv, params)
            buttonView = tv
        } catch (_: Exception) {
            buttonView = null
        }
    }

    private fun triggerClearAll() {
        if (isClickInProgress) return
        isClickInProgress = true

        onClearAllTriggered?.invoke()
        hideButton()

        Thread {
            try {
                val isQuick = settingsRepository.isRecentsQuickCloseEnabled()
                val root = service.rootInActiveWindow
                if (root != null) {
                    val directClearAll = findClearAllNode(root)
                    if (isQuick && directClearAll != null) {
                        if (!directClearAll.isVisibleToUser) {
                            directClearAll.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                        }
                        directClearAll.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        val overviewPanel = findOverviewPanelNode(root)
                        if (overviewPanel != null) {
                            for (i in 0 until 40) {
                                val currentRoot = service.rootInActiveWindow ?: break
                                val clearAll = findClearAllNode(currentRoot)
                                if (clearAll != null && clearAll.isVisibleToUser && clearAll.isClickable) {
                                    clearAll.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    break
                                }
                                val scrolled = overviewPanel.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                                if (!scrolled) {
                                    val finalClearAll = findClearAllNode(currentRoot)
                                    if (finalClearAll != null && finalClearAll.isClickable) {
                                        finalClearAll.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    }
                                    break
                                }
                                Thread.sleep(70)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                isClickInProgress = false
            }
        }.start()
    }

    private fun findClearAllNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val clearAllIds = listOf(
            "com.google.android.apps.nexuslauncher:id/clear_all",
            "com.android.launcher3:id/clear_all",
        )
        for (id in clearAllIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty()) {
                val clickable = list.firstOrNull { it.isClickable } ?: list.first()
                return clickable
            }
        }
        val textList = root.findAccessibilityNodeInfosByText("Clear all")
        return textList?.firstOrNull()
    }

    private fun findOverviewPanelNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val panelIds = listOf(
            "com.google.android.apps.nexuslauncher:id/overview_panel",
            "com.android.launcher3:id/overview_panel",
            "com.google.android.apps.nexuslauncher:id/recents_view",
            "com.android.launcher3:id/recents_view",
        )
        for (id in panelIds) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty()) return list.first()
        }
        return null
    }

    private fun getNavigationBarHeight(): Int {
        val res = service.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }
}
