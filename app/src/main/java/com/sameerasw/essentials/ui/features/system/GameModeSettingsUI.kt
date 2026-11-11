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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.ui.components.menus.SegmentedDropdownMenuItem
import com.sameerasw.essentials.ui.components.sliders.ConfigSliderItem
import com.sameerasw.essentials.ui.core.cards.FeatureCard
import com.sameerasw.essentials.ui.core.cards.IconToggleItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
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
    var showPermissionSheet by remember { mutableStateOf(false) }

    val isGameModeEnabled = viewModel.isGameModeEnabled.value
    val gameApps = viewModel.gameModeApps.value
    val isBoostRam = viewModel.isGameModeBoostRamEnabled.value
    val isCachedFreezer = viewModel.isGameModeCachedFreezerEnabled.value
    val isPauseAi = viewModel.isGameModePauseAiEnabled.value
    val isSuspendHealthServices = viewModel.isGameModeSuspendHealthServicesEnabled.value
    val isLockRefreshRate = viewModel.isGameModeLockRefreshRateEnabled.value
    val isDisableAutoBrightness = viewModel.isGameModeDisableAutoBrightnessEnabled.value
    val isStabilizeWifi = viewModel.isGameModeStabilizeWifiEnabled.value
    val isPauseAirSync = viewModel.isGameModePauseAirSyncEnabled.value
    val isDisablePrivateDns = viewModel.isGameModeDisablePrivateDnsEnabled.value
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
                        title = stringResource(R.string.game_mode_pause_ai_title),
                        description = stringResource(R.string.game_mode_pause_ai_desc),
                        isChecked = isPauseAi,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModePauseAiEnabled(it)
                        },
                    )

                    IconToggleItem(
                        title = stringResource(R.string.game_mode_suspend_health_services_title),
                        description = stringResource(R.string.game_mode_suspend_health_services_desc),
                        isChecked = isSuspendHealthServices,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModeSuspendHealthServicesEnabled(it)
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
                        title = stringResource(R.string.game_mode_stabilize_wifi_title),
                        description = stringResource(R.string.game_mode_stabilize_wifi_desc),
                        isChecked = isStabilizeWifi,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModeStabilizeWifiEnabled(it)
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
                        title = stringResource(R.string.game_mode_disable_private_dns_title),
                        description = stringResource(R.string.game_mode_disable_private_dns_desc),
                        isChecked = isDisablePrivateDns,
                        onCheckedChange = {
                            HapticUtil.performUIHaptic(view)
                            viewModel.setGameModeDisablePrivateDnsEnabled(it)
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
}
