/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Application Activities
 * File: ShutUpShortcutActivity.kt
 * Description: Activity component for ShutUpShortcutActivity.kt.
 */

package com.sameerasw.essentials

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.utils.FreezeManager
import com.sameerasw.essentials.utils.PermissionUtils
import com.sameerasw.essentials.utils.ShutUpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShutUpShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra("package_name")
        if (packageName == null) {
            finish()
            return
        }

        val settingsRepository = SettingsRepository(this)
        val config = settingsRepository.loadShutUpConfigs().find { it.packageName == packageName }

        if (config != null && config.isEnabled) {
            if (PermissionUtils.canWriteSecureSettings(this)) {
                // 1. Synchronously pre-apply settings (< 5ms) before launching target app
                ShutUpManager.preApplyShutUpSettings(this, config, settingsRepository)

                // 2. Background reinforcement via shell put if needed
                lifecycleScope.launch(Dispatchers.IO) {
                    ShutUpManager.applyShutUpSettings(
                        this@ShutUpShortcutActivity,
                        config,
                        settingsRepository,
                        reinforcement = true,
                    )
                }
            }
        }

        // 3. Handle unfreeze if the app was frozen
        if (FreezeManager.isAppFrozen(this, packageName)) {
            lifecycleScope.launch(Dispatchers.IO) {
                FreezeManager.unfreezeApp(this@ShutUpShortcutActivity, packageName)
            }
        }

        // 4. Launch the target app immediately
        launchApp(packageName)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Could not launch $packageName", Toast.LENGTH_SHORT).show()
        }
    }
}
