/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Display Features
 * File: PerAppRefreshRateSettingsUI.kt
 * Description: UI settings component for Per-App Refresh Rate.
 */

package com.sameerasw.essentials.ui.features.display

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.domain.model.AppRefreshRateConfig
import com.sameerasw.essentials.ui.components.menus.SegmentedDropdownMenuItem
import com.sameerasw.essentials.ui.core.cards.FeatureCard
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.ui.core.sheets.PerAppRefreshRateSettingsSheet
import com.sameerasw.essentials.ui.core.sheets.PermissionsBottomSheet
import com.sameerasw.essentials.ui.core.sheets.SingleAppSelectionSheet
import com.sameerasw.essentials.ui.modifiers.highlight
import com.sameerasw.essentials.utils.AppUtil
import com.sameerasw.essentials.utils.PermissionUIHelper
import com.sameerasw.essentials.utils.PermissionUtils
import com.sameerasw.essentials.utils.ShellUtils
import com.sameerasw.essentials.viewmodels.MainViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PerAppRefreshRateSettingsUI(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    highlightSetting: String? = null
) {
    val context = LocalContext.current
    var isAppSelectionSheetOpen by remember { mutableStateOf(false) }
    var isEditSheetOpen by remember { mutableStateOf(false) }
    var editingPackageName by remember { mutableStateOf("") }
    var editingCurrentRate by remember { mutableStateOf(0f) }
    var editingIsFixed by remember { mutableStateOf(true) }
    var editingLandscapeRate by remember { mutableStateOf<Float?>(null) }
    var editingOnlyOnMediaPlaying by remember { mutableStateOf(false) }

    val configs by viewModel.perAppRefreshRateConfigs
    var showPermissionSheet by remember { mutableStateOf(false) }

    val checkPermissionAndRun: (onGranted: () -> Unit) -> Unit = { onGranted ->
        val isUseUsageAccessVal = viewModel.isUseUsageAccess.value
        val hasDetectionPermission = if (isUseUsageAccessVal) {
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
                featureTitle = R.string.refresh_rate_per_app_enable_title,
                permissions = PermissionUIHelper.getPermissionItems(
                    missingPermissions,
                    context,
                    viewModel,
                    context as? Activity
                )
            )
        } else {
            showPermissionSheet = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RoundedCardContainer(
            modifier = Modifier.highlight(highlightSetting == "per_app_refresh_rate_card"),
            spacing = 2.dp,
            cornerRadius = 24.dp
        ) {
            FeatureCard(
                title = stringResource(R.string.refresh_rate_per_app_add_app),
                description = stringResource(R.string.refresh_rate_per_app_add_app_desc),
                iconRes = R.drawable.rounded_add_24,
                isEnabled = true,
                showToggle = false,
                hasMoreSettings = false,
                onToggle = {},
                onClick = {
                    checkPermissionAndRun {
                        isAppSelectionSheetOpen = true
                    }
                }
            )
        }

        if (configs.isNotEmpty()) {
            RoundedCardContainer(
                modifier = Modifier,
                spacing = 2.dp,
                cornerRadius = 24.dp
            ) {
                configs.forEach { config ->
                    val appName = remember(config.packageName) {
                        try {
                            val appInfo = context.packageManager.getApplicationInfo(config.packageName, 0)
                            context.packageManager.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) {
                            config.packageName
                        }
                    }

                    val appIconPainter = remember(config.packageName) {
                        try {
                            val drawable = context.packageManager.getApplicationIcon(config.packageName)
                            BitmapPainter(
                                AppUtil.drawableToBitmap(drawable).asImageBitmap()
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }

                    val modeText = if (config.isFixed) stringResource(R.string.refresh_rate_per_app_mode_fixed) else stringResource(R.string.refresh_rate_per_app_mode_dynamic)
                    val suffix = if (config.onlyOnMediaPlaying) stringResource(R.string.refresh_rate_per_app_media_only_suffix) else ""
                    val cardDesc = if (config.landscapeRefreshRate != null) {
                        stringResource(
                            R.string.refresh_rate_per_app_landscape_desc,
                            config.refreshRate.toInt(),
                            modeText,
                            config.landscapeRefreshRate.toInt(),
                            suffix,
                        )
                    } else {
                        stringResource(
                            R.string.refresh_rate_per_app_portrait_desc,
                            config.refreshRate.toInt(),
                            modeText,
                        )
                    }

                    FeatureCard(
                        title = appName,
                        description = cardDesc,
                        isEnabled = config.isEnabled,
                        showToggle = true,
                        onToggle = { isChecked ->
                            if (isChecked) {
                                checkPermissionAndRun {
                                    viewModel.updatePerAppRefreshRateConfig(config.copy(isEnabled = true))
                                    val anyEnabled = configs.any { it.packageName != config.packageName && it.isEnabled } || true
                                    viewModel.setPerAppRefreshRateEnabled(anyEnabled, context)
                                }
                            } else {
                                viewModel.updatePerAppRefreshRateConfig(config.copy(isEnabled = false))
                                val anyEnabled = configs.any { it.packageName != config.packageName && it.isEnabled }
                                viewModel.setPerAppRefreshRateEnabled(anyEnabled, context)
                            }
                        },
                        onClick = {
                            editingPackageName = config.packageName
                            editingCurrentRate = config.refreshRate
                            editingIsFixed = config.isFixed
                            editingLandscapeRate = config.landscapeRefreshRate
                            editingOnlyOnMediaPlaying = config.onlyOnMediaPlaying
                            isEditSheetOpen = true
                        },
                        iconPainter = appIconPainter,
                        hasMoreSettings = true,
                        additionalMenuItems = { onDismiss ->
                            SegmentedDropdownMenuItem(
                                text = { Text(stringResource(R.string.action_remove)) },
                                onClick = {
                                    onDismiss()
                                    viewModel.removePerAppRefreshRateConfig(config.packageName)
                                    val anyEnabled = configs.filter { it.packageName != config.packageName }.any { it.isEnabled }
                                    viewModel.setPerAppRefreshRateEnabled(anyEnabled, context)
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.rounded_delete_24),
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    )
                }
            }
        }

        if (isAppSelectionSheetOpen) {
            SingleAppSelectionSheet(
                onDismissRequest = { isAppSelectionSheetOpen = false },
                onAppSelected = { app ->
                    isAppSelectionSheetOpen = false
                    editingPackageName = app.packageName
                    editingCurrentRate = 0f
                    editingIsFixed = true
                    editingLandscapeRate = null
                    editingOnlyOnMediaPlaying = false
                    isEditSheetOpen = true
                }
            )
        }

        if (isEditSheetOpen) {
            PerAppRefreshRateSettingsSheet(
                packageName = editingPackageName,
                currentRate = editingCurrentRate,
                isFixed = editingIsFixed,
                landscapeRate = editingLandscapeRate,
                onlyOnMediaPlaying = editingOnlyOnMediaPlaying,
                onSave = { rate, isFixed, landscapeRate, onlyOnMedia ->
                    viewModel.updatePerAppRefreshRateConfig(
                        AppRefreshRateConfig(
                            packageName = editingPackageName,
                            refreshRate = rate,
                            isFixed = isFixed,
                            landscapeRefreshRate = landscapeRate,
                            onlyOnMediaPlaying = onlyOnMedia,
                            isEnabled = true
                        )
                    )
                    viewModel.setPerAppRefreshRateEnabled(true, context)
                },
                onDelete = {
                    viewModel.removePerAppRefreshRateConfig(editingPackageName)
                    val anyEnabled = configs.filter { it.packageName != editingPackageName }.any { it.isEnabled }
                    viewModel.setPerAppRefreshRateEnabled(anyEnabled, context)
                },
                onDismissRequest = { isEditSheetOpen = false }
            )
        }
    }
}
