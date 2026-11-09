/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Feature - System
 * File: DoubleTapToSleepSettingsUI.kt
 * Description: UI settings composable for Double tap to sleep feature.
 */

package com.sameerasw.essentials.ui.features.system

import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.domain.HapticFeedbackType
import com.sameerasw.essentials.ui.components.sliders.ConfigSliderItem
import com.sameerasw.essentials.ui.core.cards.IconToggleItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.ui.core.pickers.HapticFeedbackPicker
import com.sameerasw.essentials.ui.modifiers.highlight
import com.sameerasw.essentials.utils.HapticUtil
import com.sameerasw.essentials.utils.PermissionUtils
import com.sameerasw.essentials.utils.performHapticFeedback
import com.sameerasw.essentials.viewmodels.MainViewModel

@Composable
fun DoubleTapToSleepSettingsUI(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    highlightSetting: String? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val vibrator = context.getSystemService(Vibrator::class.java)
    val isAccessibilityEnabled = viewModel.isAccessibilityEnabled.value
    val isFeatureEnabled = viewModel.isDoubleTapToSleepEnabled.value

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Master Toggle
        RoundedCardContainer(
            modifier = Modifier,
            spacing = 2.dp,
            cornerRadius = 24.dp,
        ) {
            IconToggleItem(
                title = stringResource(R.string.feat_double_tap_to_sleep_title),
                description = stringResource(R.string.feat_double_tap_to_sleep_desc),
                isChecked = isFeatureEnabled,
                onCheckedChange = { enabled ->
                    HapticUtil.performUIHaptic(view)
                    if (isAccessibilityEnabled) {
                        viewModel.setDoubleTapToSleepEnabled(enabled)
                    } else {
                        PermissionUtils.openAccessibilitySettings(context)
                    }
                },
                enabled = isAccessibilityEnabled,
                onDisabledClick = {
                    PermissionUtils.openAccessibilitySettings(context)
                },
                iconRes = R.drawable.rounded_mobile_lock_portrait_24,
                modifier = Modifier.highlight(highlightSetting == "double_tap_to_sleep_toggle"),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Trigger Locations Category
        Text(
            text = stringResource(R.string.cat_interaction),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RoundedCardContainer(
            modifier = Modifier,
            spacing = 2.dp,
            cornerRadius = 24.dp,
        ) {
            IconToggleItem(
                title = stringResource(R.string.dt2s_enable_home_title),
                description = stringResource(R.string.dt2s_enable_home_desc),
                isChecked = viewModel.isDoubleTapOnHomeEnabled.value,
                onCheckedChange = { enabled ->
                    HapticUtil.performUIHaptic(view)
                    viewModel.setDoubleTapOnHomeEnabled(enabled)
                },
                enabled = isFeatureEnabled && isAccessibilityEnabled,
                iconRes = R.drawable.rounded_home_24,
                modifier = Modifier.highlight(highlightSetting == "dt2s_home_toggle"),
            )

            IconToggleItem(
                title = stringResource(R.string.dt2s_enable_lockscreen_title),
                description = stringResource(R.string.dt2s_enable_lockscreen_desc),
                isChecked = viewModel.isDoubleTapOnLockscreenEnabled.value,
                onCheckedChange = { enabled ->
                    HapticUtil.performUIHaptic(view)
                    viewModel.setDoubleTapOnLockscreenEnabled(enabled)
                },
                enabled = isFeatureEnabled && isAccessibilityEnabled,
                iconRes = R.drawable.rounded_lock_24,
                modifier = Modifier.highlight(highlightSetting == "dt2s_lockscreen_toggle"),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Behavior & Timing Category
        Text(
            text = stringResource(R.string.cat_interface),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RoundedCardContainer(
            modifier = Modifier,
            spacing = 2.dp,
            cornerRadius = 24.dp,
        ) {
            ConfigSliderItem(
                title = stringResource(R.string.dt2s_speed_title),
                description = stringResource(R.string.dt2s_speed_desc),
                value = viewModel.doubleTapToSleepTimeout.longValue.toFloat(),
                onValueChange = { viewModel.setDoubleTapToSleepTimeout(it.toLong()) },
                valueRange = 100f..750f,
                steps = 12,
                increment = 50f,
                iconRes = R.drawable.rounded_shutter_speed_24,
                valueFormatter = { "${it.toInt()} ms" },
                modifier = Modifier.highlight(highlightSetting == "dt2s_speed_slider"),
            )

            IconToggleItem(
                title = stringResource(R.string.dt2s_jump_home_title),
                description = stringResource(R.string.dt2s_jump_home_desc),
                isChecked = viewModel.isDoubleTapJumpHomeEnabled.value,
                onCheckedChange = { enabled ->
                    HapticUtil.performUIHaptic(view)
                    viewModel.setDoubleTapJumpHomeEnabled(enabled)
                },
                enabled = isFeatureEnabled && isAccessibilityEnabled,
                iconRes = R.drawable.rounded_home_health_24,
                modifier = Modifier.highlight(highlightSetting == "dt2s_jump_home_toggle"),
            )

            IconToggleItem(
                title = stringResource(R.string.dt2s_flicker_title),
                description = stringResource(R.string.dt2s_flicker_desc),
                isChecked = viewModel.isDoubleTapFlickerProtectionEnabled.value,
                onCheckedChange = { enabled ->
                    HapticUtil.performUIHaptic(view)
                    viewModel.setDoubleTapFlickerProtectionEnabled(enabled)
                },
                enabled = isFeatureEnabled && isAccessibilityEnabled,
                iconRes = R.drawable.rounded_brightness_medium_24,
                modifier = Modifier.highlight(highlightSetting == "dt2s_flicker_toggle"),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Haptic Feedback Category
        Text(
            text = stringResource(R.string.settings_section_haptic),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RoundedCardContainer(
            modifier = Modifier,
            spacing = 2.dp,
            cornerRadius = 24.dp,
        ) {
            IconToggleItem(
                title = stringResource(R.string.dt2s_vibrate_title),
                description = stringResource(R.string.dt2s_vibrate_desc),
                isChecked = viewModel.isDoubleTapToSleepVibrateEnabled.value,
                onCheckedChange = { enabled ->
                    HapticUtil.performUIHaptic(view)
                    viewModel.setDoubleTapToSleepVibrateEnabled(enabled)
                },
                enabled = isFeatureEnabled && isAccessibilityEnabled,
                iconRes = R.drawable.rounded_mobile_vibrate_24,
                modifier = Modifier.highlight(highlightSetting == "dt2s_vibrate_toggle"),
            )

            AnimatedVisibility(
                visible = viewModel.isDoubleTapToSleepVibrateEnabled.value && isFeatureEnabled && isAccessibilityEnabled,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                HapticFeedbackPicker(
                    selectedFeedback = viewModel.doubleTapToSleepHapticType.value,
                    onFeedbackSelected = { type ->
                        viewModel.setDoubleTapToSleepHapticType(type)
                        if (vibrator != null && type != HapticFeedbackType.NONE) {
                            performHapticFeedback(vibrator, type)
                        }
                    },
                    modifier = Modifier.highlight(highlightSetting == "dt2s_haptic_picker"),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Info Card
        RoundedCardContainer(
            spacing = 2.dp,
            cornerRadius = 24.dp,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_info_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.dt2s_info_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
