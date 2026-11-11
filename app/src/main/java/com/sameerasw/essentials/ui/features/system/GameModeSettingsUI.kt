/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Feature - System & Performance
 * File: GameModeSettingsUI.kt
 * Description: UI settings composable for automated Game Mode optimization.
 */

package com.sameerasw.essentials.ui.features.system

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.ui.components.menus.SegmentedDropdownMenuItem
import com.sameerasw.essentials.ui.components.sliders.ConfigSliderItem
import com.sameerasw.essentials.ui.core.cards.FeatureCard
import com.sameerasw.essentials.ui.core.cards.IconToggleItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.ui.core.pickers.SegmentedPicker
import com.sameerasw.essentials.ui.core.sheets.GameModeNetworkSheet
import com.sameerasw.essentials.ui.core.sheets.GameModeServicesSheet
import com.sameerasw.essentials.ui.core.sheets.PermissionsBottomSheet
import com.sameerasw.essentials.ui.core.sheets.SingleAppSelectionSheet
import com.sameerasw.essentials.ui.modifiers.highlight
import com.sameerasw.essentials.utils.AppUtil
import com.sameerasw.essentials.utils.HapticUtil
import com.sameerasw.essentials.utils.PermissionUIHelper
import com.sameerasw.essentials.utils.ShellUtils
import com.sameerasw.essentials.viewmodels.MainViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GameModeSettingsUI(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    highlightSetting: String? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var isAppSelectionSheetOpen by remember { mutableStateOf(false) }
    var isServicesSheetOpen by remember { mutableStateOf(false) }
    var isNetworkSheetOpen by remember { mutableStateOf(false) }
    var showPermissionSheet by remember { mutableStateOf(false) }

    val isGameModeEnabled = viewModel.isGameModeEnabled.value
    val gameApps = viewModel.gameModeApps.value
    val interventionMode = viewModel.gameModeInterventionMode.intValue
    val suspendedServices = viewModel.gameModeSuspendedServices.value
    val isBoostRam = viewModel.isGameModeBoostRamEnabled.value
    val isCachedFreezer = viewModel.isGameModeCachedFreezerEnabled.value
    val isLockRefreshRate = viewModel.isGameModeLockRefreshRateEnabled.value
    val isDisableAutoBrightness = viewModel.isGameModeDisableAutoBrightnessEnabled.value
    val isDisableQuickTap = viewModel.isGameModeDisableQuickTapEnabled.value
    val isStabilizeWifi = viewModel.isGameModeStabilizeWifiEnabled.value
    val isPauseAirSync = viewModel.isGameModePauseAirSyncEnabled.value
    val isBlockBatterySaver = viewModel.isGameModeBlockBatterySaverEnabled.value
    val isPauseAutoSync = viewModel.isGameModePauseAutoSyncEnabled.value
    val restoreDelay = viewModel.gameModeRestoreDelay.intValue

    val checkPermissionAndRun: (onGranted: () -> Unit) -> Unit = { onGranted ->
        val hasDetectionPermission = if (viewModel.isUseUsageAccess.value) {
            viewModel.isUsageStatsPermissionGranted.value
        } else {
            viewModel.isAccessibilityEnabled.value
        }
        val hasShellPermission = ShellUtils.hasPermission(context)

        if (!hasDetectionPermission || !hasShellPermission) {
            showPermissionSheet = true
        } else {
            onGranted()
        }
    }

    if (showPermissionSheet) {
        val missingPermissions = mutableListOf<String>().apply {
            if (viewModel.isUseUsageAccess.value) {
                if (!viewModel.isUsageStatsPermissionGranted.value) add("USAGE_STATS")
            } else {
                if (!viewModel.isAccessibilityEnabled.value) add("ACCESSIBILITY")
            }
            if (!ShellUtils.hasPermission(context)) {
                if (ShellUtils.isRootEnabled(context)) {
                    add("ROOT")
                } else {
                    add("SHIZUKU")
                }
            }
        }

        if (missingPermissions.isNotEmpty()) {
            PermissionsBottomSheet(
                onDismissRequest = { showPermissionSheet = false },
                featureTitle = R.string.feat_game_mode_title,
                permissions = PermissionUIHelper.getPermissionItems(
                    missingPermissions,
                    context,
                    viewModel,
                    context as? Activity,
                ),
            )
        } else {
            showPermissionSheet = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Master Toggle Card
        RoundedCardContainer(
            modifier = Modifier.highlight(highlightSetting == "game_mode_master"),
            spacing = 2.dp,
            cornerRadius = 24.dp,
        ) {
            IconToggleItem(
                title = stringResource(R.string.game_mode_master_title),
                description = stringResource(R.string.game_mode_master_desc),
                isChecked = isGameModeEnabled,
                onCheckedChange = { enabled ->
                    HapticUtil.performUIHaptic(view)
                    if (enabled) {
                        checkPermissionAndRun {
                            viewModel.setGameModeEnabled(true, context)
                        }
                    } else {
                        viewModel.setGameModeEnabled(false, context)
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = isGameModeEnabled,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Section: Optimization Options
                Text(
                    text = stringResource(R.string.game_mode_options_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )

                RoundedCardContainer(
                    spacing = 2.dp,
                    cornerRadius = 24.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceBright, MaterialTheme.shapes.extraSmall)
                            .padding(top = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.game_mode_intervention_mode_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        val perfLabel = stringResource(R.string.game_mode_intervention_performance)
                        val stdLabel = stringResource(R.string.game_mode_intervention_standard)
                        val battLabel = stringResource(R.string.game_mode_intervention_battery)
                        SegmentedPicker(
                            items = listOf(2, 1, 3),
                            selectedItem = interventionMode,
                            onItemSelected = {
                                HapticUtil.performVirtualKeyHaptic(view)
                                viewModel.setGameModeInterventionMode(it)
                            },
                            labelProvider = { mode ->
                                when (mode) {
                                    2 -> perfLabel
                                    1 -> stdLabel
                                    3 -> battLabel
                                    else -> ""
                                }
                            },
                            title = R.string.game_mode_intervention_mode_title,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_boost_ram_title),
                        description = stringResource(R.string.game_mode_boost_ram_desc),
                        isChecked = isBoostRam,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModeBoostRamEnabled(it)
                        },
                    )

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_cached_freezer_title),
                        description = stringResource(R.string.game_mode_cached_freezer_desc),
                        isChecked = isCachedFreezer,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModeCachedFreezerEnabled(it)
                        },
                    )

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_services_sheet_title),
                        description = stringResource(
                            R.string.game_mode_services_count_desc,
                            suspendedServices.size,
                            6,
                        ),
                        showToggle = false,
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            isServicesSheetOpen = true
                        },
                        trailingContent = {
                            Icon(
                                painter = painterResource(R.drawable.rounded_chevron_right_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                    )

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_lock_refresh_rate_title),
                        description = stringResource(R.string.game_mode_lock_refresh_rate_desc),
                        isChecked = isLockRefreshRate,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModeLockRefreshRateEnabled(it)
                        },
                    )

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_disable_auto_brightness_title),
                        description = stringResource(R.string.game_mode_disable_auto_brightness_desc),
                        isChecked = isDisableAutoBrightness,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModeDisableAutoBrightnessEnabled(it)
                        },
                    )

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_disable_quick_tap_title),
                        description = stringResource(R.string.game_mode_disable_quick_tap_desc),
                        isChecked = isDisableQuickTap,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModeDisableQuickTapEnabled(it)
                        },
                    )

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_stabilize_wifi_title),
                        description = stringResource(R.string.game_mode_stabilize_wifi_desc),
                        isChecked = isStabilizeWifi,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModeStabilizeWifiEnabled(it)
                        },
                        onSettingsClick = {
                            HapticUtil.performUIHaptic(view)
                            isNetworkSheetOpen = true
                        },
                    )

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_pause_airsync_title),
                        description = stringResource(R.string.game_mode_pause_airsync_desc),
                        isChecked = isPauseAirSync,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModePauseAirSyncEnabled(it)
                        },
                    )

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_block_battery_saver_title),
                        description = stringResource(R.string.game_mode_block_battery_saver_desc),
                        isChecked = isBlockBatterySaver,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModeBlockBatterySaverEnabled(it)
                        },
                    )

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_pause_auto_sync_title),
                        description = stringResource(R.string.game_mode_pause_auto_sync_desc),
                        isChecked = isPauseAutoSync,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModePauseAutoSyncEnabled(it)
                        },
                    )

                    ConfigSliderItem(
                        title = stringResource(R.string.game_mode_restore_delay_title),
                        subtitle = stringResource(R.string.game_mode_restore_delay_desc),
                        value = restoreDelay.toFloat(),
                        onValueChange = { viewModel.setGameModeRestoreDelay(it.toInt()) },
                        valueRange = 0f..120f,
                        increment = 5f,
                        valueFormatter = { if (it.toInt() == 0) "Immediate" else "${it.toInt()}s" },
                        iconRes = R.drawable.rounded_timer_24,
                    )
                }

                // Section: Games
                Text(
                    text = stringResource(R.string.game_mode_games_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )

                // Add Game Card
                RoundedCardContainer(
                    spacing = 2.dp,
                    cornerRadius = 24.dp,
                ) {
                    FeatureCard(
                        title = stringResource(R.string.game_mode_add_game),
                        description = stringResource(R.string.game_mode_add_game_desc),
                        iconRes = R.drawable.rounded_add_24,
                        isEnabled = true,
                        showToggle = false,
                        hasMoreSettings = false,
                        onToggle = {},
                        onClick = {
                            checkPermissionAndRun {
                                isAppSelectionSheetOpen = true
                            }
                        },
                    )
                }

                // Selected Games List
                if (gameApps.isNotEmpty()) {
                    RoundedCardContainer(
                        spacing = 2.dp,
                        cornerRadius = 24.dp,
                    ) {
                        gameApps.forEach { pkg ->
                            val appName = remember(pkg) {
                                try {
                                    val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
                                    context.packageManager.getApplicationLabel(appInfo).toString()
                                } catch (e: Exception) {
                                    pkg
                                }
                            }

                            val appIconPainter = remember(pkg) {
                                try {
                                    val drawable = context.packageManager.getApplicationIcon(pkg)
                                    BitmapPainter(
                                        AppUtil.drawableToBitmap(drawable).asImageBitmap(),
                                    )
                                } catch (e: Exception) {
                                    null
                                }
                            }

                            FeatureCard(
                                title = appName,
                                description = pkg,
                                isEnabled = true,
                                showToggle = false,
                                onToggle = {},
                                onClick = {},
                                iconPainter = appIconPainter,
                                hasMoreSettings = true,
                                additionalMenuItems = { onDismiss ->
                                    SegmentedDropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_remove)) },
                                        onClick = {
                                            onDismiss()
                                            viewModel.removeGameModeApp(pkg)
                                        },
                                    )
                                },
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.game_mode_no_games),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    if (isAppSelectionSheetOpen) {
        SingleAppSelectionSheet(
            onDismissRequest = { isAppSelectionSheetOpen = false },
            onAppSelected = { app ->
                isAppSelectionSheetOpen = false
                viewModel.addGameModeApp(app.packageName)
            },
        )
    }

    if (isServicesSheetOpen) {
        GameModeServicesSheet(
            viewModel = viewModel,
            onDismissRequest = { isServicesSheetOpen = false },
        )
    }

    if (isNetworkSheetOpen) {
        GameModeNetworkSheet(
            viewModel = viewModel,
            onDismissRequest = { isNetworkSheetOpen = false },
        )
    }
}
