/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Core Sheets
 * File: GameModeServicesSheet.kt
 * Description: Bottom sheet for customizing which background services are suspended during Game Mode.
 */

package com.sameerasw.essentials.ui.core.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.ui.core.cards.IconToggleItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.utils.HapticUtil
import com.sameerasw.essentials.viewmodels.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameModeServicesSheet(
    viewModel: MainViewModel,
    onDismissRequest: () -> Unit,
) {
    val view = LocalView.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val suspendedServices = viewModel.gameModeSuspendedServices.value

    EssentialsBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.game_mode_services_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.game_mode_services_sheet_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RoundedCardContainer(
                spacing = 2.dp,
                cornerRadius = 24.dp,
            ) {
                // 1. Android Auto (gearhead)
                val isGearhead = suspendedServices.contains("com.google.android.projection.gearhead")
                IconToggleItem(
                    title = stringResource(R.string.game_mode_service_gearhead_title),
                    description = "com.google.android.projection.gearhead\n${stringResource(R.string.game_mode_service_gearhead_desc)}",
                    isChecked = isGearhead,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeServiceSuspended("com.google.android.projection.gearhead", it)
                    },
                )

                // 2. Adaptive Connectivity (scone)
                val isScone = suspendedServices.contains("com.google.android.apps.scone")
                IconToggleItem(
                    title = stringResource(R.string.game_mode_service_scone_title),
                    description = "com.google.android.apps.scone\n${stringResource(R.string.game_mode_service_scone_desc)}",
                    isChecked = isScone,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeServiceSuspended("com.google.android.apps.scone", it)
                    },
                )

                // 3. Device Health Services (turbo)
                val isTurbo = suspendedServices.contains("com.google.android.apps.turbo")
                IconToggleItem(
                    title = stringResource(R.string.game_mode_service_turbo_title),
                    description = "com.google.android.apps.turbo\n${stringResource(R.string.game_mode_service_turbo_desc)}",
                    isChecked = isTurbo,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeServiceSuspended("com.google.android.apps.turbo", it)
                    },
                )

                // 4. Settings Intelligence
                val isSettingsIntel = suspendedServices.contains("com.google.android.settings.intelligence")
                IconToggleItem(
                    title = stringResource(R.string.game_mode_service_settings_intel_title),
                    description = "com.google.android.settings.intelligence\n${stringResource(R.string.game_mode_service_settings_intel_desc)}",
                    isChecked = isSettingsIntel,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeServiceSuspended("com.google.android.settings.intelligence", it)
                    },
                )

                // 5. On-Device AI Core
                val isAiCore = suspendedServices.contains("com.google.android.aicore")
                IconToggleItem(
                    title = stringResource(R.string.game_mode_service_aicore_title),
                    description = "com.google.android.aicore\n${stringResource(R.string.game_mode_service_aicore_desc)}",
                    isChecked = isAiCore,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeServiceSuspended("com.google.android.aicore", it)
                    },
                )

                // 6. Carrier Wi-Fi
                val isCarrierWifi = suspendedServices.contains("com.google.android.apps.carrier.carrierwifi")
                IconToggleItem(
                    title = stringResource(R.string.game_mode_service_carrierwifi_title),
                    description = "com.google.android.apps.carrier.carrierwifi\n${stringResource(R.string.game_mode_service_carrierwifi_desc)}",
                    isChecked = isCarrierWifi,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeServiceSuspended("com.google.android.apps.carrier.carrierwifi", it)
                    },
                )
            }
        }
    }
}
