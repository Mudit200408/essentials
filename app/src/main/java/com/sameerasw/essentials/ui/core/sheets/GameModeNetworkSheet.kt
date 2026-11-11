/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Core Sheets
 * File: GameModeNetworkSheet.kt
 * Description: Bottom sheet for fine-tuning Wi-Fi and network latency parameters in Game Mode.
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
fun GameModeNetworkSheet(
    viewModel: MainViewModel,
    onDismissRequest: () -> Unit,
) {
    val view = LocalView.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isLowLatency = viewModel.isGameModeWifiLowLatencyEnabled.value
    val isSuppressScans = viewModel.isGameModeWifiSuppressScansEnabled.value
    val isLockSufficiency = viewModel.isGameModeWifiLockSufficiencyEnabled.value
    val isPreventArpDisconnect = viewModel.isGameModeWifiPreventArpDisconnectEnabled.value
    val isDisablePrivateDns = viewModel.isGameModeDisablePrivateDnsEnabled.value

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
                    text = stringResource(R.string.game_mode_network_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.game_mode_network_sheet_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RoundedCardContainer(
                spacing = 2.dp,
                cornerRadius = 24.dp,
            ) {
                // 1. Low-latency Wi-Fi Lock
                IconToggleItem(
                    title = stringResource(R.string.game_mode_wifi_low_latency_title),
                    description = stringResource(R.string.game_mode_wifi_low_latency_desc),
                    isChecked = isLowLatency,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeWifiLowLatencyEnabled(it)
                    },
                )

                // 2. Suppress Background Scans
                IconToggleItem(
                    title = stringResource(R.string.game_mode_wifi_suppress_scans_title),
                    description = stringResource(R.string.game_mode_wifi_suppress_scans_desc),
                    isChecked = isSuppressScans,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeWifiSuppressScansEnabled(it)
                    },
                )

                // 3. Lock Network Sufficiency
                IconToggleItem(
                    title = stringResource(R.string.game_mode_wifi_lock_sufficiency_title),
                    description = stringResource(R.string.game_mode_wifi_lock_sufficiency_desc),
                    isChecked = isLockSufficiency,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeWifiLockSufficiencyEnabled(it)
                    },
                )

                // 4. Disable Private DNS (DoT)
                IconToggleItem(
                    title = stringResource(R.string.game_mode_disable_private_dns_title),
                    description = stringResource(R.string.game_mode_disable_private_dns_desc),
                    isChecked = isDisablePrivateDns,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeDisablePrivateDnsEnabled(it)
                    },
                )

                // 5. Prevent ARP Disconnect
                IconToggleItem(
                    title = stringResource(R.string.game_mode_wifi_prevent_arp_disconnect_title),
                    description = stringResource(R.string.game_mode_wifi_prevent_arp_disconnect_desc),
                    isChecked = isPreventArpDisconnect,
                    onCheckedChange = {
                        HapticUtil.performUIHaptic(view)
                        viewModel.setGameModeWifiPreventArpDisconnectEnabled(it)
                    },
                )
            }
        }
    }
}
