/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: AppDetectionService.kt
 * Description: Background service component for AppDetectionService.kt.
 */

package com.sameerasw.essentials.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sameerasw.essentials.R
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.services.handlers.AppFlowHandler
import com.sameerasw.essentials.services.tiles.ScreenOffAccessibilityService
import com.sameerasw.essentials.utils.ShutUpManager

class AppDetectionService : Service() {
    private lateinit var appFlowHandler: AppFlowHandler
    private lateinit var settingsRepository: SettingsRepository
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var lastPackageName: String? = null

    companion object {
        private const val CHANNEL_ID = "app_detection_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL = 500L
        var isRunning = false
    }

    private val authReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                when (intent?.action) {
                    "APP_AUTHENTICATED" -> {
                        val packageName = intent.getStringExtra("package_name")
                        if (packageName != null) {
                            appFlowHandler.onAuthenticated(packageName)
                        }
                    }

                    "APP_AUTHENTICATION_FAILED" -> {
                        goHome()
                    }

                    "CONSCIOUS_GATE_CONFIRMED" -> {
                        val packageName = intent.getStringExtra("package_name")
                        if (packageName != null) {
                            appFlowHandler.onConsciousGateConfirmed(packageName)
                        }
                    }

                    "CONSCIOUS_GATE_CLOSED" -> {
                        val packageName = intent.getStringExtra("package_name")
                        if (packageName != null) {
                            appFlowHandler.onConsciousGateClosed(packageName)
                        }
                        goHome()
                    }

                    Intent.ACTION_SCREEN_OFF -> {
                        appFlowHandler.clearAuthenticated()
                        appFlowHandler.clearConsciousGate()
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        settingsRepository = SettingsRepository(this)
        appFlowHandler = AppFlowHandler.getInstance(this)
        createNotificationChannel()

        val filter =
            IntentFilter().apply {
                addAction("APP_AUTHENTICATED")
                addAction("APP_AUTHENTICATION_FAILED")
                addAction("CONSCIOUS_GATE_CONFIRMED")
                addAction("CONSCIOUS_GATE_CLOSED")
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(authReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(authReceiver, filter)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )

        if (!isPolling) {
            isPolling = true
            startPolling()
        }

        return START_STICKY
    }

    private fun startPolling() {
        handler.postDelayed(
            object : Runnable {
                override fun run() {
                    if (!isPolling) return

                    val useUsageAccess = settingsRepository.getBoolean(SettingsRepository.KEY_USE_USAGE_ACCESS, false)
                    val accessibilityRunning = ScreenOffAccessibilityService.instance != null

                    // If Accessibility is temporarily muted by Shut-Up, ShutUpForegroundService is the sole polling watchdog.
                    // AppDetectionService must yield completely to avoid double polling.
                    val shouldYieldToShutUp = ShutUpManager.isAccessibilityMuted

                    // Poll UsageStats if:
                    // 1) Not yielding to ShutUpForegroundService, AND
                    // 2) (User explicitly configured "Use Usage Access instead of Accessibility", OR
                    //     Accessibility Service is not active to provide window events).
                    if (!shouldYieldToShutUp && (useUsageAccess || !accessibilityRunning)) {
                        val currentPackage = getForegroundPackage()
                        if (currentPackage != null && currentPackage != lastPackageName) {
                            lastPackageName = currentPackage
                            appFlowHandler.onPackageChanged(currentPackage, isFromUsageStats = true)
                        }
                    }

                    handler.postDelayed(this, POLL_INTERVAL)
                }
            },
            POLL_INTERVAL,
        )
    }

    private fun getForegroundPackage(): String? {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()

        // 1. Try to find the last resumed activity using queryEvents (real-time & accurate)
        try {
            val events = usageStatsManager.queryEvents(time - 1000 * 30, time)
            val event = UsageEvents.Event()
            var lastResumedPackage: String? = null
            var lastEventTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                if (type == UsageEvents.Event.ACTIVITY_RESUMED ||
                    type == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= lastEventTime) {
                        lastResumedPackage = event.packageName
                        lastEventTime = event.timeStamp
                    }
                }
            }
            if (lastResumedPackage != null) {
                return lastResumedPackage
            }
        } catch (e: Exception) {
            Log.e("AppDetectionService", "Failed to query usage events", e)
        }

        // 2. Fallback to queryUsageStats
        try {
            val stats =
                usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    time - 1000 * 30,
                    time,
                )

            if (!stats.isNullOrEmpty()) {
                val recentStats = stats.maxByOrNull { it.lastTimeUsed }
                if (recentStats != null && recentStats.lastTimeUsed > 0) {
                    return recentStats.packageName
                }
            }
        } catch (e: Exception) {
            Log.e("AppDetectionService", "Failed to query usage stats fallback", e)
        }

        return null
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(homeIntent)
    }

    override fun onDestroy() {
        isRunning = false
        isPolling = false
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(authReceiver)
        } catch (_: Exception) {
        }
        if (ScreenOffAccessibilityService.instance == null) {
            try {
                appFlowHandler.destroy()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_detection_service_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.app_detection_service_running_desc)
                }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_detection_service_running_title))
            .setContentText(getString(R.string.app_detection_service_running_desc))
            .setSmallIcon(R.drawable.rounded_shield_lock_24)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
}
