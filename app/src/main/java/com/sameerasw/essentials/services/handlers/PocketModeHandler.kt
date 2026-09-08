/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: PocketModeHandler.kt
 * Description: Background service component for PocketModeHandler.kt.
 */

package com.sameerasw.essentials.services.handlers

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sameerasw.essentials.R
import com.sameerasw.essentials.utils.OverlayHelper

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.view.Display
import android.view.Surface
import com.google.gson.GsonBuilder
import com.sameerasw.essentials.domain.model.AppSelection
import com.sameerasw.essentials.services.NotificationListener
import java.util.concurrent.ConcurrentHashMap

class PocketModeHandler(
    private val service: AccessibilityService,
    private val isBypassedCheck: (() -> Boolean)? = null,
) {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    var isBypassed = false
    var isOverlayVisible = false

    val prefs by lazy { service.getSharedPreferences("essentials_prefs", Context.MODE_PRIVATE) }
    private val keyguardManager by lazy {
        service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
    private val notificationListenerComponent by lazy {
        ComponentName(service, NotificationListener::class.java)
    }

    var pocketModeEnabled = false
        private set
    var pocketModeUseLightSensor = false
        private set
    var pocketModeTriggerDelayMs = 3000L
        private set
    var pocketModeLockScreenOnly = false
        private set
    var flashlightPocketTurnOffEnabled = false
        private set

    @Volatile private var pocketModeExcludedAppsSet: Set<String> = emptySet()
    private val appCategoryCache = ConcurrentHashMap<String, Boolean>()

    @Volatile private var cachedBypassedPackage: String? = null
    @Volatile private var cachedBypassedKeyguardLocked: Boolean? = null
    @Volatile private var cachedBypassedLandscape: Boolean? = null
    @Volatile private var cachedBypassedResult: Boolean = false
    @Volatile private var isMediaCurrentlyPlaying: Boolean = false

    init {
        updatePocketModePrefs()
        updatePocketModeExcludedAppsSet()
    }

    fun updatePocketModePrefs() {
        pocketModeEnabled = prefs.getBoolean("pocket_mode_enabled", false)
        pocketModeUseLightSensor = prefs.getBoolean("pocket_mode_use_light_sensor", false)
        pocketModeTriggerDelayMs = (prefs.getFloat("pocket_mode_trigger_delay", 3f) * 1000).toLong()
        pocketModeLockScreenOnly = prefs.getBoolean("pocket_mode_lock_screen_only", false)
        flashlightPocketTurnOffEnabled = prefs.getBoolean("flashlight_pocket_turn_off_enabled", false)
        invalidateBypassCache()
    }

    fun updatePocketModeExcludedAppsSet() {
        val json = prefs.getString("pocket_mode_excluded_apps", null)
        pocketModeExcludedAppsSet =
            if (json != null) {
                try {
                    val gson = GsonBuilder().create()
                    gson.fromJson(json, Array<AppSelection>::class.java)
                        .filter { it.isEnabled }
                        .map { it.packageName }
                        .toSet()
                } catch (e: Exception) {
                    emptySet()
                }
            } else {
                emptySet()
            }
        invalidateBypassCache()
    }

    fun invalidateBypassCache(currentPackage: String? = null) {
        cachedBypassedPackage = null
        cachedBypassedKeyguardLocked = null
        cachedBypassedLandscape = null
        isMediaCurrentlyPlaying = if (currentPackage != null) hasActiveMediaSession(currentPackage) else false
    }

    fun isDeviceInLandscape(): Boolean {
        return try {
            val displayManager = service.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            val rotation = display?.rotation ?: Surface.ROTATION_0
            rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        } catch (_: Exception) {
            service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    fun isGameOrVideoApp(packageName: String): Boolean =
        appCategoryCache.getOrPut(packageName) {
            if (KNOWN_STREAMING_PACKAGES.contains(packageName)) {
                return@getOrPut true
            }
            try {
                val info = service.packageManager.getApplicationInfo(packageName, 0)
                val isLegacyGame = (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                val isCategoryMatch =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val category = info.category
                        category == ApplicationInfo.CATEGORY_GAME ||
                            category == ApplicationInfo.CATEGORY_VIDEO
                    } else {
                        false
                    }
                isLegacyGame || isCategoryMatch
            } catch (e: Exception) {
                false
            }
        }

    fun hasActiveMediaSession(packageName: String): Boolean {
        return try {
            val msm = service.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return false
            val sessions = msm.getActiveSessions(notificationListenerComponent)
            val hasActiveSession = sessions.any {
                it.packageName == packageName &&
                        it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            if (hasActiveSession) return true

            val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager?.isMusicActive == true) {
                val otherAppPlaying = sessions.any {
                    it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING
                }
                if (!otherAppPlaying) return true
            }
            false
        } catch (e: SecurityException) {
            Log.w("PocketModeHandler", "SecurityException checking media sessions for $packageName: ${e.message}")
            val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.isMusicActive == true
        } catch (e: Exception) {
            false
        }
    }

    fun isAppBypassed(packageName: String?, isKeyguardLocked: Boolean = false): Boolean {
        val isLandscape = isDeviceInLandscape()
        if (packageName == cachedBypassedPackage &&
            isKeyguardLocked == cachedBypassedKeyguardLocked &&
            isLandscape == cachedBypassedLandscape
        ) {
            return cachedBypassedResult
        }

        val isExcluded = !isKeyguardLocked && (
            isLandscape || (packageName != null && (
                pocketModeExcludedAppsSet.contains(packageName) ||
                isGameOrVideoApp(packageName) ||
                isMediaCurrentlyPlaying
            ))
        )
        val isKeyguardBypassed = pocketModeLockScreenOnly && !isKeyguardLocked
        val result = isExcluded || isKeyguardBypassed

        cachedBypassedPackage = packageName
        cachedBypassedKeyguardLocked = isKeyguardLocked
        cachedBypassedLandscape = isLandscape
        cachedBypassedResult = result
        return result
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isPending = false
    private val showOverlayRunnable =
        Runnable {
            isPending = false
            val isCurrentAppBypassed = isBypassedCheck?.invoke() ?: isAppBypassed(
                AppFlowHandler.getInstance(service).currentPackage,
                keyguardManager?.isKeyguardLocked ?: false,
            )
            if (!isBypassed && !isCurrentAppBypassed) {
                showOverlay()
            }
        }
    private val screenOffRunnable =
        Runnable {
            if (isOverlayVisible) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
        }

    fun showOverlay() {
        if (isOverlayVisible || overlayView != null) return

        Log.d("PocketModeHandler", "Showing pocket mode overlay")
        windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val context = service

        val owner = OverlayLifecycleOwner()
        lifecycleOwner = owner

        val frameLayout = FrameLayout(context)
        owner.onCreate()
        frameLayout.setViewTreeLifecycleOwner(owner)
        frameLayout.setViewTreeSavedStateRegistryOwner(owner)
        frameLayout.setViewTreeViewModelStoreOwner(owner)

        val composeView =
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    PocketModeOverlayContent()
                }
            }
        frameLayout.addView(composeView)

        overlayView = frameLayout

        val params =
            OverlayHelper.createOverlayLayoutParams(
                overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags = 0,
                isTouchable = true,
            )

        try {
            windowManager?.addView(frameLayout, params)
            isOverlayVisible = true
            handler.postDelayed(screenOffRunnable, 5000L)
        } catch (e: Exception) {
            Log.e("PocketModeHandler", "Error adding pocket overlay", e)
            removeOverlay()
        }
    }

    fun removeOverlay() {
        handler.removeCallbacks(screenOffRunnable)
        if (overlayView != null) {
            Log.d("PocketModeHandler", "Removing pocket mode overlay")
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.e("PocketModeHandler", "Error removing pocket overlay view", e)
            }
            overlayView = null
        }
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        isOverlayVisible = false
        isBypassed = false
    }

    fun onProximityChanged(
        isBlocked: Boolean,
        isLightDark: Boolean,
        useLightSensor: Boolean,
        triggerDelayMs: Long = 3000L,
    ) {
        val shouldShow = isBlocked && (!useLightSensor || isLightDark)
        if (shouldShow) {
            if (!isBypassed && !isOverlayVisible && !isPending) {
                isPending = true
                handler.postDelayed(showOverlayRunnable, triggerDelayMs)
            }
        } else {
            if (isPending) {
                handler.removeCallbacks(showOverlayRunnable)
                isPending = false
            }
            removeOverlay()
        }
    }

    fun onScreenOff() {
        handler.removeCallbacks(showOverlayRunnable)
        handler.removeCallbacks(screenOffRunnable)
        isPending = false
        removeOverlay()
        isBypassed = false
    }

    /** Cancels a pending (not-yet-shown) overlay scheduled for this sensor tick.
     *  Does NOT remove an already-visible overlay and does NOT reset [isBypassed]. */
    fun cancelPending() {
        handler.removeCallbacks(showOverlayRunnable)
        isPending = false
    }

    /** Called when the user switches into a bypassed/excluded app.
     *  Removes any pending timer and the active overlay, but preserves [isBypassed]
     *  so a user-initiated volume-key bypass is not cleared. */
    fun dismissForAppSwitch() {
        handler.removeCallbacks(showOverlayRunnable)
        handler.removeCallbacks(screenOffRunnable)
        isPending = false
        removeOverlay()
    }

    private class OverlayLifecycleOwner :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry =
            savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore = store

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }

    @Composable
    private fun PocketModeOverlayContent() {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "scale",
        )
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.9f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "alpha",
        )

        val context = LocalContext.current

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
        ) {
            // Pulsing circle at top center (near front camera/proximity sensor)
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 140.dp)
                        .size(52.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            alpha = alpha,
                        ).background(Color.White.copy(alpha = 0.3f), shape = CircleShape),
            )

            // Center details
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = context.getString(R.string.pocket_mode_active),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = context.getString(R.string.pocket_mode_dismiss_hint),
                    color = Color.LightGray,
                    fontSize = 14.sp,
                )
            }
        }
    }

    companion object {
        val KNOWN_STREAMING_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.google.android.apps.youtube.kids",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "tv.twitch.android.app",
            "com.disney.disneyplus",
            "in.startv.hotstar",
            "com.crunchyroll.crunchyroid",
            "com.wbd.stream",
            "com.hbo.hbonow",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "org.xbmc.kodi",
            "com.plexapp.android",
            "com.spotify.music",
            "com.soundcloud.android",
            "com.jio.media.ondemand",
            "com.graymatrix.did",
            "com.zee5.hilgard",
        )
    }
}
