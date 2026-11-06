package com.sameerasw.essentials.domain.model.livemedia

import com.sameerasw.essentials.R

enum class MusicProvider(
    val appName: String,
    val packageNames: List<String>,
    val iconRes: Int
) {
    UNKNOWN(
        "Unknown Player",
        listOf("unknown.package"),
        R.drawable.apple_music
    ),
    SPOTIFY(
        "Spotify",
        listOf("com.spotify.music"),
        R.drawable.spotify
    ),
    YOUTUBE(
        "YouTube",
        listOf("com.google.android.youtube", "app.morphe.android.youtube"),
        R.drawable.youtube
    ),
    YOUTUBE_MUSIC(
        "YouTube Music",
        listOf("com.google.android.apps.youtube.music", "app.morphe.android.apps.youtube.music"),
        R.drawable.youtube_music
    ),
    SOUNDCLOUD(
        "SoundCloud",
        listOf("com.soundcloud.android"),
        R.drawable.soundcloud
    ),
    APPLE_MUSIC(
        "Apple Music",
        listOf("com.apple.android.music"),
        R.drawable.apple_music
    ),
    DEEZER(
        "Deezer",
        listOf("deezer.android.app"),
        R.drawable.deezer
    ),
    TIDAL(
        "TIDAL",
        listOf("com.aspiro.tidal"),
        R.drawable.tidal
    ),
    AMAZON_MUSIC(
        "Amazon Music",
        listOf("com.amazon.mp3"),
        R.drawable.amazon_music
    ),
    TELEGRAM(
        "Telegram",
        listOf("org.telegram.messenger"),
        R.drawable.telegram
    ),
    NETFLIX(
        "Netflix",
        listOf("com.netflix.mediaclient"),
        R.drawable.netflix
    ),
    TWITCH(
        "Twitch",
        listOf("tv.twitch.android.app"),
        R.drawable.twitch
    ),
    NOTEBOOK_LM(
        "NotebookLM",
        listOf("com.google.android.apps.labs.language.tailwind"),
        R.drawable.notebook_lm
    ),
    QOBUZ(
        "Qobuz",
        listOf("com.qobuz.music"),
        R.drawable.qobuz_com
    ),
    UAPP(
        "USB Audio Player PRO",
        listOf("com.extreamsd.usbaudioplayerpro"),
        R.drawable.usb_audio_player_pro
    );

    val packageName: String
        get() = packageNames.firstOrNull() ?: ""

    companion object {
        fun getByAppNameOrPackage(appName: String, appPackage: String): MusicProvider {
            return entries.firstOrNull {
                it.packageNames.contains(appPackage) || it.appName.equals(appName, ignoreCase = true)
            } ?: UNKNOWN
        }
    }
}

