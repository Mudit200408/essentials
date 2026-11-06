package com.sameerasw.essentials.ui.features.livemedia

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.domain.model.livemedia.HideOnPauseOption
import com.sameerasw.essentials.domain.model.livemedia.PillContent
import com.sameerasw.essentials.ui.core.cards.IconToggleItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.ui.core.pickers.SegmentedPicker
import com.sameerasw.essentials.ui.core.sheets.LiveMediaAppSelectionSheet
import com.sameerasw.essentials.viewmodels.MainViewModel

@Composable
fun LiveMediaSettingsUI(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    highlightSetting: String? = null
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }

    var showAlbumArt by remember {
        mutableStateOf(settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ALBUM_ART, true))
    }
    var showArtistName by remember {
        mutableStateOf(settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ARTIST_NAME, true))
    }
    var showAlbumName by remember {
        mutableStateOf(settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ALBUM_NAME, true))
    }
    var showActionButtons by remember {
        mutableStateOf(settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ACTION_BUTTONS, true))
    }
    var showProgress by remember {
        mutableStateOf(settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_PROGRESS, true))
    }
    var showMusicProvider by remember {
        mutableStateOf(settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_PROVIDER_NAME, true))
    }
    var showTimestamp by remember {
        mutableStateOf(settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_TIMESTAMP, false))
    }
    var hideNotificationOnQsOpen by remember {
        mutableStateOf(settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_HIDE_ON_QS, false))
    }
    var isScrollEnabled by remember {
        mutableStateOf(settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_SCROLL_ENABLED, false))
    }
    var hideNotificationOnPause by remember {
        mutableStateOf(settingsRepository.getBoolean(SettingsRepository.KEY_LIVE_MEDIA_HIDE_ON_PAUSE, false))
    }

    var pillContent by remember {
        val name = settingsRepository.getString(SettingsRepository.KEY_LIVE_MEDIA_PILL_CONTENT, PillContent.TITLE.name)
        mutableStateOf(
            try {
                PillContent.valueOf(name ?: PillContent.TITLE.name)
            } catch (_: Exception) {
                PillContent.TITLE
            }
        )
    }

    var hideOnPauseOption by remember {
        val name = settingsRepository.getString(
            SettingsRepository.KEY_LIVE_MEDIA_HIDE_ON_PAUSE_OPTION,
            HideOnPauseOption.IMMEDIATELY.name
        )
        mutableStateOf(
            try {
                HideOnPauseOption.valueOf(name ?: HideOnPauseOption.IMMEDIATELY.name)
            } catch (_: Exception) {
                HideOnPauseOption.IMMEDIATELY
            }
        )
    }

    var showAppSelectionSheet by remember { mutableStateOf(false) }

    if (showAppSelectionSheet) {
        LiveMediaAppSelectionSheet(
            onDismissRequest = { showAppSelectionSheet = false }
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {

        // Status Bar Pill section
        Text(
            text = stringResource(R.string.live_media_section_status_bar_pill),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        RoundedCardContainer {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Pill Content",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val pillOptions = listOf(PillContent.TITLE, PillContent.ELAPSED, PillContent.REMAINING)
                SegmentedPicker(
                    items = pillOptions,
                    selectedItem = pillContent,
                    onItemSelected = { selected ->
                        pillContent = selected
                        settingsRepository.putString(SettingsRepository.KEY_LIVE_MEDIA_PILL_CONTENT, selected.name)
                    },
                    labelProvider = { option ->
                        when (option) {
                            PillContent.TITLE -> "Title"
                            PillContent.ELAPSED -> "Elapsed"
                            PillContent.REMAINING -> "Remaining"
                        }
                    }
                )
            }

            IconToggleItem(
                title = stringResource(R.string.live_media_setting_scroll_text),
                description = stringResource(R.string.live_media_setting_scroll_text_desc),
                iconRes = R.drawable.rounded_mobile_text_24,
                isChecked = isScrollEnabled,
                onCheckedChange = { checked ->
                    isScrollEnabled = checked
                    settingsRepository.putBoolean(SettingsRepository.KEY_LIVE_MEDIA_SCROLL_ENABLED, checked)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Notification Body section
        Text(
            text = stringResource(R.string.live_media_section_notification_body),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        RoundedCardContainer {
            IconToggleItem(
                title = stringResource(R.string.live_media_setting_show_album_art),
                description = stringResource(R.string.live_media_setting_show_album_art_desc),
                iconRes = R.drawable.rounded_image_24,
                isChecked = showAlbumArt,
                onCheckedChange = { checked ->
                    showAlbumArt = checked
                    settingsRepository.putBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ALBUM_ART, checked)
                }
            )

            IconToggleItem(
                title = stringResource(R.string.live_media_setting_show_artist_name),
                description = stringResource(R.string.live_media_setting_show_artist_name_desc),
                iconRes = R.drawable.rounded_person_24,
                isChecked = showArtistName,
                onCheckedChange = { checked ->
                    showArtistName = checked
                    settingsRepository.putBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ARTIST_NAME, checked)
                }
            )

            IconToggleItem(
                title = stringResource(R.string.live_media_setting_show_album_name),
                description = stringResource(R.string.live_media_setting_show_album_name_desc),
                iconRes = R.drawable.rounded_album_24,
                isChecked = showAlbumName,
                onCheckedChange = { checked ->
                    showAlbumName = checked
                    settingsRepository.putBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ALBUM_NAME, checked)
                }
            )

            IconToggleItem(
                title = stringResource(R.string.live_media_setting_show_action_buttons),
                description = stringResource(R.string.live_media_setting_show_action_buttons_desc),
                iconRes = R.drawable.rounded_play_arrow_24,
                isChecked = showActionButtons,
                onCheckedChange = { checked ->
                    showActionButtons = checked
                    settingsRepository.putBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_ACTION_BUTTONS, checked)
                }
            )

            IconToggleItem(
                title = stringResource(R.string.live_media_setting_show_progress),
                description = stringResource(R.string.live_media_setting_show_progress_desc),
                iconRes = R.drawable.rounded_scale_24,
                isChecked = showProgress,
                onCheckedChange = { checked ->
                    showProgress = checked
                    settingsRepository.putBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_PROGRESS, checked)
                }
            )

            IconToggleItem(
                title = stringResource(R.string.live_media_setting_show_music_provider),
                description = stringResource(R.string.live_media_setting_show_music_provider_desc),
                iconRes = R.drawable.rounded_apps_24,
                isChecked = showMusicProvider,
                onCheckedChange = { checked ->
                    showMusicProvider = checked
                    settingsRepository.putBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_PROVIDER_NAME, checked)
                }
            )

            IconToggleItem(
                title = stringResource(R.string.live_media_setting_show_timestamp),
                description = stringResource(R.string.live_media_setting_show_timestamp_desc),
                iconRes = R.drawable.rounded_schedule_24,
                isChecked = showTimestamp,
                onCheckedChange = { checked ->
                    showTimestamp = checked
                    settingsRepository.putBoolean(SettingsRepository.KEY_LIVE_MEDIA_SHOW_TIMESTAMP, checked)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Behavior section
        Text(
            text = "Behavior",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        RoundedCardContainer {
            IconToggleItem(
                title = stringResource(R.string.live_media_setting_hide_on_qs),
                description = stringResource(R.string.live_media_setting_hide_on_qs_desc),
                iconRes = R.drawable.rounded_notifications_off_24,
                isChecked = hideNotificationOnQsOpen,
                onCheckedChange = { checked ->
                    hideNotificationOnQsOpen = checked
                    settingsRepository.putBoolean(SettingsRepository.KEY_LIVE_MEDIA_HIDE_ON_QS, checked)
                }
            )

            IconToggleItem(
                title = stringResource(R.string.live_media_setting_hide_on_pause),
                description = stringResource(R.string.live_media_setting_hide_on_pause_desc),
                iconRes = R.drawable.rounded_pause_24,
                isChecked = hideNotificationOnPause,
                onCheckedChange = { checked ->
                    hideNotificationOnPause = checked
                    settingsRepository.putBoolean(SettingsRepository.KEY_LIVE_MEDIA_HIDE_ON_PAUSE, checked)
                }
            )

            AnimatedVisibility(visible = hideNotificationOnPause) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Pause Delay",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    val immediatelyLabel = stringResource(R.string.live_media_setting_hide_on_pause_immediately)
                    val oneMinLabel = stringResource(R.string.live_media_setting_hide_on_pause_1min)
                    val pauseOptions = listOf(HideOnPauseOption.IMMEDIATELY, HideOnPauseOption.ONE_MINUTE)
                    SegmentedPicker(
                        items = pauseOptions,
                        selectedItem = hideOnPauseOption,
                        onItemSelected = { selected ->
                            hideOnPauseOption = selected
                            settingsRepository.putString(
                                SettingsRepository.KEY_LIVE_MEDIA_HIDE_ON_PAUSE_OPTION,
                                selected.name
                            )
                        },
                        labelProvider = { option ->
                            when (option) {
                                HideOnPauseOption.IMMEDIATELY -> immediatelyLabel
                                HideOnPauseOption.ONE_MINUTE -> oneMinLabel
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // App Control section
        Text(
            text = stringResource(R.string.live_media_section_app_selection),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        RoundedCardContainer {
            IconToggleItem(
                title = stringResource(R.string.live_media_setting_app_selection),
                description = stringResource(R.string.live_media_setting_app_selection_desc),
                iconRes = R.drawable.rounded_apps_24,
                showToggle = false,
                onClick = { showAppSelectionSheet = true }
            )
        }
    }
}
