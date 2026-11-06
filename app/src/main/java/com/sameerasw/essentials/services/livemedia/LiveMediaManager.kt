package com.sameerasw.essentials.services.livemedia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.domain.model.livemedia.HideOnPauseOption
import com.sameerasw.essentials.domain.model.livemedia.MusicProvider
import com.sameerasw.essentials.domain.model.livemedia.MusicState
import com.sameerasw.essentials.domain.model.livemedia.PillContent
import com.sameerasw.essentials.services.NotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveMediaManager private constructor(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val settingsRepository = SettingsRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var activeMediaController: MediaController? = null
    private var scheduler: LiveMediaUpdateScheduler? = null
    private var pauseHideJob: Job? = null

    private var isQsOpen = false
    private var isKeyguardLocked = false
    private var isNotificationDismissed = false
    private var isPausedHidden = false

    private var lastTitle: String? = null
    private var titleStartTime: Long = 0L
    private var lastIsPlaying: Boolean = false

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            onMediaStateChanged()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            onMediaStateChanged()
        }
    }

    init {
        createNotificationChannel()
    }

    private fun isFeatureEnabled(): Boolean {
        return settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_ENABLED, false)
    }

    fun onEnabledChanged(enabled: Boolean) {
        if (!enabled) {
            cancelNotification()
            cleanup()
        } else {
            maybeUpdateMediaController()
        }
    }

    fun onQuickSettingsStateChanged(isOpen: Boolean) {
        val wasOpen = isQsOpen
        isQsOpen = isOpen

        if (!isFeatureEnabled()) return

        val hideOnQs = settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_HIDE_ON_QS, false)
        if (isOpen && hideOnQs) {
            cancelNotification()
        } else if (wasOpen && !isOpen) {
            maybeUpdateMediaController()
        }
    }

    fun onKeyguardStateChanged(isLocked: Boolean) {
        val wasLocked = isKeyguardLocked
        isKeyguardLocked = isLocked

        if (!isFeatureEnabled()) return

        if (isLocked) {
            cancelNotification()
        } else if (wasLocked && !isLocked) {
            maybeUpdateMediaController()
        }
    }

    fun onNotificationDismissed() {
        isNotificationDismissed = true
    }

    fun maybeUpdateMediaController() {
        if (!isFeatureEnabled()) {
            cancelNotification()
            return
        }

        try {
            val mediaSessionManager =
                context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val componentName = ComponentName(context, NotificationListener::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(componentName) ?: emptyList()

            val newController = controllers.firstOrNull { controller ->
                val state = controller.playbackState?.state
                state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_NONE
            }

            if (newController != activeMediaController) {
                activeMediaController?.unregisterCallback(mediaControllerCallback)
                activeMediaController = newController?.also {
                    it.registerCallback(mediaControllerCallback)
                }
            }

            if (activeMediaController == null) {
                pauseHideJob?.cancel()
                pauseHideJob = null
                isPausedHidden = false
                scheduler?.stop()
                cancelNotification()
            } else {
                onMediaStateChanged()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating media controller", e)
        }
    }

    private fun onMediaStateChanged() {
        if (!isFeatureEnabled()) {
            cancelNotification()
            return
        }

        val state = getUpdatedMusicState()
        if (state == null) {
            cancelNotification()
            scheduler?.stop()
            return
        }

        updateNotification(state)
        ensureScheduler()
        scheduler?.scheduleUpdate()
    }

    private fun ensureScheduler() {
        if (scheduler == null) {
            scheduler = LiveMediaUpdateScheduler {
                val state = getUpdatedMusicState()
                if (state != null) {
                    updateNotification(state)

                    val isPlaying = state.isPlaying
                    val isTitleScrollable = state.title.trim().length > 7
                    val isScrollEnabled =
                        settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SCROLL_ENABLED, false)
                    val pillContent = getPillContent()

                    val shouldScroll = isScrollEnabled &&
                        (pillContent == PillContent.TITLE || !isPlaying) &&
                        isTitleScrollable

                    if (isPlaying || shouldScroll) {
                        if (shouldScroll) SCROLL_UPDATE_DELAY_MS else STATIC_UPDATE_DELAY_MS
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }
    }

    private fun getPillContent(): PillContent {
        val name = settingsRepository.getString(SettingsRepository.KEY_LIVE_MEDIA_PILL_CONTENT, PillContent.TITLE.name)
        return try {
            PillContent.valueOf(name ?: PillContent.TITLE.name)
        } catch (_: Exception) {
            PillContent.TITLE
        }
    }

    private fun getHideOnPauseOption(): HideOnPauseOption {
        val name = settingsRepository.getString(
            SettingsRepository.KEY_LIVE_MEDIA_HIDE_ON_PAUSE_OPTION,
            HideOnPauseOption.IMMEDIATELY.name
        )
        return try {
            HideOnPauseOption.valueOf(name ?: HideOnPauseOption.IMMEDIATELY.name)
        } catch (_: Exception) {
            HideOnPauseOption.IMMEDIATELY
        }
    }

    private fun isAppEnabled(packageName: String): Boolean {
        val disabled = settingsRepository.getStringSet(SettingsRepository.KEY_LIVE_MEDIA_DISABLED_PACKAGES, emptySet())
        return !disabled.contains(packageName)
    }

    fun getUpdatedMusicState(): MusicState? {
        val controller = activeMediaController ?: return null
        val playbackState = controller.playbackState ?: return null

        if (playbackState.state == PlaybackState.STATE_STOPPED || playbackState.state == PlaybackState.STATE_NONE) {
            return null
        }

        val metadata = controller.metadata ?: return null
        return MusicState(metadata, playbackState, controller.packageName)
    }

    private fun updateNotification(musicState: MusicState) {
        if (musicState.isPlaying) {
            isNotificationDismissed = false
            isPausedHidden = false
            pauseHideJob?.cancel()
            pauseHideJob = null
        } else {
            val hideOnPause =
                settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_HIDE_ON_PAUSE, false)
            if (hideOnPause) {
                when (getHideOnPauseOption()) {
                    HideOnPauseOption.IMMEDIATELY -> {
                        pauseHideJob?.cancel()
                        pauseHideJob = null
                        isPausedHidden = false
                        cancelNotification()
                        return
                    }
                    HideOnPauseOption.ONE_MINUTE -> {
                        if (isPausedHidden) {
                            cancelNotification()
                            return
                        }
                        if (pauseHideJob == null || pauseHideJob?.isActive != true) {
                            pauseHideJob = scope.launch {
                                delay(PAUSE_HIDE_DELAY_MS)
                                isPausedHidden = true
                                cancelNotification()
                            }
                        }
                    }
                }
            } else {
                isPausedHidden = false
                pauseHideJob?.cancel()
                pauseHideJob = null
            }
        }

        if (isNotificationDismissed) return
        if (isKeyguardLocked) {
            cancelNotification()
            return
        }
        val hideOnQs = settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_HIDE_ON_QS, false)
        if (isQsOpen && hideOnQs) {
            cancelNotification()
            return
        }
        if (!isAppEnabled(musicState.packageName)) {
            cancelNotification()
            return
        }

        scope.launch(Dispatchers.IO) {
            val notification = buildNotification(musicState)
            withContext(Dispatchers.Main) {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private suspend fun buildNotification(musicState: MusicState): Notification {
        val justPaused = lastIsPlaying && !musicState.isPlaying
        val wasShowingTime = getPillContent() != PillContent.TITLE

        if (musicState.title != lastTitle || (justPaused && wasShowingTime)) {
            lastTitle = musicState.title
            titleStartTime = System.currentTimeMillis()
        }
        lastIsPlaying = musicState.isPlaying

        val launchIntent = context.packageManager.getLaunchIntentForPackage(musicState.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val musicAppName = try {
            val appInfo = context.packageManager.getApplicationInfo(musicState.packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            musicState.packageName
        }

        val showAlbumArt = settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ALBUM_ART, true)
        val showArtist = settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ARTIST_NAME, true)
        val showAlbum = settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ALBUM_NAME, true)
        val showActionButtons = settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ACTION_BUTTONS, true)
        val showProgress = settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_PROGRESS, true)
        val showProvider = settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_PROVIDER_NAME, true)
        val showTimestamp = settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_TIMESTAMP, false)
        val isScrollEnabled = settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SCROLL_ENABLED, false)

        val pillText = providePillText(
            title = musicState.title,
            position = musicState.position.toInt(),
            duration = musicState.duration.toInt(),
            isPlaying = musicState.isPlaying,
            pillContent = getPillContent(),
            isScrollEnabled = isScrollEnabled,
            elapsedTimeMs = System.currentTimeMillis() - titleStartTime
        )

        val provider = MusicProvider.getByAppNameOrPackage(musicAppName, musicState.packageName)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(provider.iconRes)
            .setContentTitle(musicState.title)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShortCriticalText(pillText)
            .setRequestPromotedOngoing(true)
            .setShowWhen(false)
            .setStyle(NotificationCompat.BigTextStyle())

        val subText = combineProviderAndTimestamp(
            musicProvider = musicAppName,
            showMusicProvider = showProvider,
            showTimestamp = showTimestamp,
            position = musicState.position.toInt(),
            duration = musicState.duration.toInt()
        )
        if (subText != null) {
            builder.setSubText(subText)
        }

        if (showArtist || showAlbum) {
            builder.setContentText(buildArtistAlbumTitle(showArtist, showAlbum, musicState))
        }

        if (showAlbumArt) {
            var art: Bitmap? = musicState.albumArt
            if (art == null && musicState.albumArtUri != null) {
                art = try {
                    val request = ImageRequest.Builder(context)
                        .data(musicState.albumArtUri)
                        .size(144, 144)
                        .allowHardware(false)
                        .build()
                    val result = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                    result?.toBitmap()
                } catch (_: Exception) {
                    null
                }
            }
            if (art != null) {
                builder.setLargeIcon(art)
            }
        }

        if (showProgress && musicState.duration > 0) {
            builder.setProgress(
                musicState.duration.toInt(),
                musicState.position.toInt(),
                false
            )
        }

        if (showActionButtons) {
            builder.addAction(createTransportAction(android.R.drawable.ic_media_previous, "Previous", LiveMediaReceiver.ACTION_PREVIOUS, 1))
            val playPauseIcon = if (musicState.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val playPauseTitle = if (musicState.isPlaying) "Pause" else "Play"
            builder.addAction(createTransportAction(playPauseIcon, playPauseTitle, LiveMediaReceiver.ACTION_PLAY_PAUSE, 2))
            builder.addAction(createTransportAction(android.R.drawable.ic_media_next, "Next", LiveMediaReceiver.ACTION_NEXT, 3))
        }

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }

        return builder.build()
    }

    private fun createTransportAction(icon: Int, title: String, action: String, requestCode: Int): NotificationCompat.Action {
        val intent = Intent(context, LiveMediaReceiver::class.java).setAction(action)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action(icon, title, pendingIntent)
    }

    fun handleTransportControl(action: String) {
        maybeUpdateMediaController()
        val controls = activeMediaController?.transportControls ?: return

        when (action) {
            LiveMediaReceiver.ACTION_PLAY_PAUSE -> {
                if (activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controls.pause()
                } else {
                    controls.play()
                }
            }
            LiveMediaReceiver.ACTION_PREVIOUS -> controls.skipToPrevious()
            LiveMediaReceiver.ACTION_NEXT -> controls.skipToNext()
        }
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun cleanup() {
        activeMediaController?.unregisterCallback(mediaControllerCallback)
        activeMediaController = null
        scheduler?.stop()
        pauseHideJob?.cancel()
        pauseHideJob = null
        isPausedHidden = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Media Live Updates",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent live media playback controls and status bar pill"
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_ID = 8888
        const val CHANNEL_ID = "media_live_updates"
        private const val TAG = "LiveMediaManager"
        private const val SCROLL_UPDATE_DELAY_MS = 500L
        private const val STATIC_UPDATE_DELAY_MS = 1000L
        private const val PAUSE_HIDE_DELAY_MS = 60_000L

        @Volatile
        private var instance: LiveMediaManager? = null

        fun getInstance(context: Context): LiveMediaManager {
            return instance ?: synchronized(this) {
                instance ?: LiveMediaManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
