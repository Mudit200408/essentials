package com.sameerasw.essentials.ui.composables.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.ui.components.cards.IconToggleItem
import com.sameerasw.essentials.ui.components.containers.RoundedCardContainer
import com.sameerasw.essentials.ui.components.sliders.ConfigSliderItem
import com.sameerasw.essentials.ui.modifiers.highlight
import com.sameerasw.essentials.viewmodels.MainViewModel
import androidx.compose.ui.platform.LocalContext

@Composable
fun WifiOptimizationSettingsUI(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    highlightSetting: String? = null
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.feat_wifi_optimizer_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        RoundedCardContainer(
            modifier = Modifier,
            spacing = 2.dp,
            cornerRadius = 24.dp
        ) {
            IconToggleItem(
                title = stringResource(R.string.wifi_optimizer_enable),
                description = stringResource(R.string.wifi_optimizer_enable_desc),
                isChecked = viewModel.isWifiOptimizerEnabled.value,
                onCheckedChange = { isChecked ->
                    viewModel.setWifiOptimizerEnabled(isChecked, context)
                },
                enabled = true,
                iconRes = R.drawable.rounded_android_wifi_4_bar_plus_24,
                modifier = Modifier.highlight(highlightSetting == "wifi_optimizer_toggle")
            )

            IconToggleItem(
                title = stringResource(R.string.wifi_software_pno_title),
                description = stringResource(R.string.wifi_software_pno_desc),
                isChecked = viewModel.isWifiSoftwarePnoEnabled.value,
                onCheckedChange = { isChecked ->
                    viewModel.setWifiSoftwarePnoEnabled(isChecked, context)
                },
                enabled = viewModel.isWifiOptimizerEnabled.value,
                iconRes = R.drawable.rounded_android_wifi_3_bar_24,
                modifier = Modifier.highlight(highlightSetting == "wifi_software_pno_toggle")
            )

            ConfigSliderItem(
                title = stringResource(R.string.wifi_health_monitor_min_rssi_title),
                value = viewModel.wifiHealthMonitorMinRssi.floatValue,
                onValueChange = { viewModel.setWifiHealthMonitorMinRssi(it, context) },
                valueRange = -85f..-45f,
                steps = 40,
                increment = 1f,
                valueFormatter = { "${it.toInt()} dBm" },
                enabled = viewModel.isWifiOptimizerEnabled.value,
                iconRes = R.drawable.rounded_cell_wifi_24
            )

            ConfigSliderItem(
                title = stringResource(R.string.wifi_low_score_threshold_title),
                value = viewModel.wifiLowScoreThreshold.floatValue,
                onValueChange = { viewModel.setWifiLowScoreThreshold(it, context) },
                valueRange = 30f..60f,
                steps = 30,
                increment = 1f,
                valueFormatter = { it.toInt().toString() },
                enabled = viewModel.isWifiOptimizerEnabled.value,
                iconRes = R.drawable.rounded_signal_cellular_alt_24
            )

            IconToggleItem(
                title = stringResource(R.string.wifi_auto_off_title),
                description = stringResource(R.string.wifi_auto_off_desc),
                isChecked = viewModel.isWifiAutoOffEnabled.value,
                onCheckedChange = { isChecked ->
                    viewModel.setWifiAutoOffEnabled(isChecked)
                },
                enabled = viewModel.isWifiOptimizerEnabled.value,
                iconRes = R.drawable.rounded_power_settings_new_24,
                modifier = Modifier.highlight(highlightSetting == "wifi_auto_off_toggle")
            )

            ConfigSliderItem(
                title = stringResource(R.string.wifi_auto_off_timeout_title),
                value = viewModel.wifiAutoOffTimeout.floatValue,
                onValueChange = { viewModel.setWifiAutoOffTimeout(it) },
                valueRange = 10f..300f,
                steps = 29,
                increment = 10f,
                valueFormatter = { "${it.toInt()}s" },
                enabled = viewModel.isWifiOptimizerEnabled.value && viewModel.isWifiAutoOffEnabled.value,
                iconRes = R.drawable.rounded_timer_24
            )
        }
    }
}
