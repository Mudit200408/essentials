/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Feature - Network
 * File: InternetDialogSheet.kt
 * Description: Material 3 Expressive Internet and Hotspot combined bottom sheet dialog.
 */

package com.sameerasw.essentials.ui.features.network.sheets

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.domain.controller.HotspotController
import com.sameerasw.essentials.ui.core.components.M3ExpressiveSwitch
import com.sameerasw.essentials.ui.core.sheets.EssentialsBottomSheet
import com.sameerasw.essentials.utils.WifiUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternetDialogSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var wifiEnabled by remember { mutableStateOf(WifiUtil.isWifiEnabled(context)) }
    var currentSsid by remember { mutableStateOf(WifiUtil.getCurrentSsid(context)) }
    var dataEnabled by remember { mutableStateOf(WifiUtil.isDataEnabled(context)) }
    var carrierName by remember { mutableStateOf(WifiUtil.getActiveCarrierName(context)) }
    var hotspotEnabled by remember { mutableStateOf(WifiUtil.isHotspotEnabled(context)) }
    var savedNetworks by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refreshState() {
        wifiEnabled = WifiUtil.isWifiEnabled(context)
        currentSsid = WifiUtil.getCurrentSsid(context)
        dataEnabled = WifiUtil.isDataEnabled(context)
        carrierName = WifiUtil.getActiveCarrierName(context)
        hotspotEnabled = WifiUtil.isHotspotEnabled(context)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            refreshState()
            if (WifiUtil.canReadSavedNetworks(context)) {
                savedNetworks = WifiUtil.getSavedNetworkSsids(context)
            }
        }
    }

    // Monitor connectivity changes in real-time
    DisposableEffect(Unit) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder().build()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    coroutineScope.launch { refreshState() }
                }

                override fun onLost(network: Network) {
                    coroutineScope.launch { refreshState() }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    coroutineScope.launch { refreshState() }
                }
            }
        connectivityManager?.registerNetworkCallback(request, callback)

        onDispose {
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }

    EssentialsBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.internet_dialog_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text =
                            when {
                                currentSsid != null -> currentSsid ?: ""
                                wifiEnabled -> stringResource(R.string.internet_dialog_connected)
                                dataEnabled -> carrierName ?: stringResource(R.string.internet_dialog_connected)
                                else -> stringResource(R.string.internet_dialog_disconnected)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Wi-Fi Tile Card
            NetworkToggleCard(
                title = stringResource(R.string.internet_dialog_wifi),
                subtitle =
                    when {
                        !wifiEnabled -> stringResource(R.string.off)
                        currentSsid != null -> currentSsid ?: ""
                        else -> stringResource(R.string.internet_dialog_connected)
                    },
                iconRes = R.drawable.rounded_android_wifi_3_bar_24,
                isChecked = wifiEnabled,
                onCheckedChange = { targetState ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    wifiEnabled = targetState
                    coroutineScope.launch(Dispatchers.IO) {
                        WifiUtil.setWifiEnabled(context, targetState)
                        withContext(Dispatchers.Main) { refreshState() }
                    }
                },
                onClick = {
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    onDismiss()
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Mobile Data Tile Card
            NetworkToggleCard(
                title = carrierName ?: stringResource(R.string.internet_dialog_mobile_data),
                subtitle =
                    if (dataEnabled) {
                        stringResource(R.string.internet_dialog_mobile_data)
                    } else {
                        stringResource(R.string.off)
                    },
                iconRes = R.drawable.rounded_signal_cellular_alt_24,
                isChecked = dataEnabled,
                onCheckedChange = { targetState ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    dataEnabled = targetState
                    coroutineScope.launch(Dispatchers.IO) {
                        WifiUtil.setDataEnabled(context, targetState)
                        withContext(Dispatchers.Main) { refreshState() }
                    }
                },
                onClick = {
                    val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                        onDismiss()
                    } catch (_: Exception) {
                    }
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Hotspot Tile Card (Evolution-X Style)
            NetworkToggleCard(
                title = stringResource(R.string.internet_dialog_hotspot),
                subtitle = if (hotspotEnabled) stringResource(R.string.on) else stringResource(R.string.off),
                iconRes = R.drawable.rounded_wifi_tethering_24,
                isChecked = hotspotEnabled,
                onCheckedChange = { targetState ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    hotspotEnabled = targetState
                    coroutineScope.launch(Dispatchers.IO) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                HotspotController.setEnabled(context, targetState)
                            } catch (_: Exception) {
                            }
                        }
                        withContext(Dispatchers.Main) { refreshState() }
                    }
                },
                onClick = {
                    val intent = Intent().apply {
                        action = "android.settings.WIRELESS_SETTINGS"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                        onDismiss()
                    } catch (_: Exception) {
                    }
                },
            )

            // Saved / Available Networks (shown when Wi-Fi is on)
            AnimatedVisibility(
                visible = wifiEnabled && savedNetworks.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = stringResource(R.string.internet_dialog_saved_networks),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((savedNetworks.size.coerceAtMost(3) * 56).dp),
                    ) {
                        items(savedNetworks.take(5)) { ssid ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                            onDismiss()
                                        }
                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_android_wifi_3_bar_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = ssid,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (ssid == currentSsid) {
                                    Icon(
                                        painter = painterResource(R.drawable.rounded_check_24),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Actions: Network Settings & Done
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                            onDismiss()
                        } catch (_: Exception) {
                        }
                    },
                    shape = RoundedCornerShape(100.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_settings_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.internet_dialog_network_settings))
                }

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(100.dp),
                ) {
                    Text(text = stringResource(R.string.action_done))
                }
            }
        }
    }
}

@Composable
private fun NetworkToggleCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isChecked) {
            MaterialTheme.colorScheme.surfaceBright
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(containerColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isChecked) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint =
                    if (isChecked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        M3ExpressiveSwitch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
        )
    }
}
