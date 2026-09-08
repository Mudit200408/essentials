/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: ShutUpManager.kt
 * Description: Utility coordinator for applying and restoring Shut-Up! volume/mute settings.
 */

package com.sameerasw.essentials.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.sameerasw.essentials.R
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.domain.model.ShutUpAppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

object ShutUpManager {
    private const val TAG = "ShutUpManager"

    // Every caller (shortcut, accessibility event, and foreground service) uses the same
    // serialized setting transaction. This prevents an old restore from racing a new apply.
    private val settingsMutex = Mutex()

    @Volatile var isAccessibilityMuted: Boolean = false
    @Volatile var settingsCurrentlyApplied: Boolean = false
    // AtomicBoolean ensures compare-and-set is atomic, preventing a TOCTOU race
    // if two coroutines both see restoreInProgress==false at the same instant.
    private val _restoreInProgress = AtomicBoolean(false)
    val restoreInProgress: Boolean get() = _restoreInProgress.get()

    private val ignoredSystemPackages = listOf(
        "android",
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.google.android.gms",
        "com.android.pixeldisplayservice"
    )

    fun isPackageIgnored(packageName: String): Boolean {
        if (ignoredSystemPackages.contains(packageName)) return true

        val lowerPkg = packageName.lowercase()
        if (lowerPkg.contains("systemui") ||
            lowerPkg.contains("keyguard") ||
            lowerPkg.contains("volume") ||
            lowerPkg.contains("soundassistant") ||
            lowerPkg.contains("dialer") ||
            lowerPkg.contains("telecom") ||
            lowerPkg.contains("phone") ||
            lowerPkg.contains("incallui") ||
            lowerPkg.contains("packageinstaller") ||
            lowerPkg.contains("permissioncontroller") ||
            lowerPkg.contains("displayservice") ||
            lowerPkg.contains("pixeldisplay") ||
            lowerPkg.startsWith("com.android.inputmethod") ||
            lowerPkg.startsWith("com.google.android.inputmethod") ||
            lowerPkg.contains("autofill")
        ) {
            return true
        }

        return false
    }

    fun isAppRunning(context: Context, packageName: String): Boolean {
        // Fast path: Check ActivityManager first without spawning a shell process
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses
            if (processes != null) {
                for (process in processes) {
                    if (process.processName == packageName) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ActivityManager check failed for $packageName", e)
        }

        // Fallback: Check via shell if available
        if (ShellUtils.isAvailable(context) && ShellUtils.hasPermission(context)) {
            try {
                val output = ShellUtils.runCommandWithOutput(context, "pidof $packageName")
                if (!output.isNullOrBlank()) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "pidof check failed for $packageName", e)
            }
        }

        return false
    }

    fun safeWriteSetting(context: Context, type: String, key: String, value: String): Boolean {
        return safeWriteSettingInternal(context, type, key, value)
    }

    suspend fun safeWriteSettingSync(context: Context, type: String, key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        safeWriteSettingInternal(context, type, key, value)
    }

    private fun safeWriteSettingInternal(context: Context, type: String, key: String, value: String): Boolean {
        val resolver = context.contentResolver
        val resolverSuccess = try {
            val result = when (type.uppercase()) {
                "GLOBAL" -> if (value.isBlank()) Settings.Global.putString(resolver, key, null) else Settings.Global.putString(resolver, key, value)
                "SECURE" -> if (value.isBlank()) Settings.Secure.putString(resolver, key, null) else Settings.Secure.putString(resolver, key, value)
                "SYSTEM" -> if (value.isBlank()) Settings.System.putString(resolver, key, null) else Settings.System.putString(resolver, key, value)
                else -> false
            }
            Log.d(TAG, "Wrote setting via ContentResolver: [$type] $key = $value (success=$result)")
            result
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException writing setting via ContentResolver: [$type] $key = $value", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error writing setting via ContentResolver: [$type] $key = $value", e)
            false
        }

        // Special handling for wireless debugging key: write to global table
        if (key == "adb_wifi_enabled") {
            try {
                Settings.Global.putString(resolver, key, value)
            } catch (e: Exception) { }
        }

        // If ContentResolver write succeeded, do not spawn expensive shell processes
        if (resolverSuccess) {
            return true
        }

        var shellSuccess = false
        if (ShellUtils.isAvailable(context) && ShellUtils.hasPermission(context)) {
            try {
                val shellType = type.lowercase()
                if (value.isBlank()) {
                    ShellUtils.runCommand(context, "settings delete $shellType $key")
                } else {
                    ShellUtils.runCommand(context, "settings put $shellType $key $value")
                }
                shellSuccess = true
                if (key == "adb_wifi_enabled" && shellType != "global") {
                    if (value.isBlank()) {
                        ShellUtils.runCommand(context, "settings delete global $key")
                    } else {
                        ShellUtils.runCommand(context, "settings put global $key $value")
                    }
                }
                Log.d(TAG, "Executed setting via Shell: [$type] $key = $value (success=$shellSuccess)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write setting via Shell: [$type] $key = $value", e)
            }
        }

        return resolverSuccess || shellSuccess
    }

    fun safeReadSetting(context: Context, type: String, key: String): String? {
        val resolver = context.contentResolver
        if (type.equals("GLOBAL", ignoreCase = true) &&
            (key == "development_settings_enabled" || key == "adb_enabled")
        ) {
            if (ShellUtils.isAvailable(context) && ShellUtils.hasPermission(context)) {
                val output = ShellUtils.runCommandWithOutput(context, "settings get global $key", notifyOnError = false)
                if (!output.isNullOrBlank()) return output.trim()
            }
        }
        return try {
            when (type.uppercase()) {
                "GLOBAL" -> Settings.Global.getString(resolver, key)
                "SECURE" -> Settings.Secure.getString(resolver, key)
                "SYSTEM" -> Settings.System.getString(resolver, key)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Snapshot originals and apply resolver writes synchronously on the caller's thread.
     *
     * This must run before the asynchronous shell reinforcement. Otherwise the async path
     * can snapshot a value that this fast path has already overwritten.
     */
    fun preApplyShutUpSettings(
        context: Context,
        config: ShutUpAppConfig,
        repository: SettingsRepository,
    ) {
        val resolver = context.contentResolver
        val currentBackup = repository.getShutUpOriginalSettings().toMutableMap()
        var backupChanged = false

        val hiddenDebugging = repository.getBoolean(
            SettingsRepository.KEY_HIDDEN_DEBUGGING_SUPPORT,
            false,
        )
        val hiddenDebuggingKeys = setOf("development_settings_enabled", "adb_enabled")

        config.settings.forEach { setting ->
            if (!setting.enabled) return@forEach
            // On Android 17 QPR with hidden debugging enabled, developer options and USB debugging
            // are already concealed from 3rd-party apps by the OS. Modifying them breaks ADB unnecessarily.
            if (hiddenDebugging && setting.key in hiddenDebuggingKeys) return@forEach

            val resolvedType = if (setting.key == "accessibility_enabled") "SECURE" else setting.settingType
            val prefixedKey = "${resolvedType.lowercase()}:${setting.key}"
            if (!currentBackup.containsKey(prefixedKey)) {
                // If user enabled "Hide Wireless Debugging" (adb_wifi_enabled), they want Wireless Debugging enabled on their device.
                // Modern Android turns adb_wifi_enabled to 0 when not on Wi-Fi, so snapshotting 0 would permanently disable it upon restore.
                // We ensure the restored target is always "1".
                val valueToSnapshot = if (setting.key == "adb_wifi_enabled") {
                    "1"
                } else {
                    safeReadSetting(context, resolvedType, setting.key) ?: ""
                }
                currentBackup[prefixedKey] = valueToSnapshot
                backupChanged = true
            }
        }

        val disableAccessibility = config.settings.any {
            it.key == "accessibility_enabled" && it.enabled
        }
        if (disableAccessibility) {
            val accessibilityServicesKey = "secure:${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}"
            if (!currentBackup.containsKey(accessibilityServicesKey)) {
                currentBackup[accessibilityServicesKey] = safeReadSetting(
                    context,
                    "SECURE",
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ) ?: ""
                backupChanged = true
            }

            val sysMinKey = "system:min_refresh_rate"
            if (!currentBackup.containsKey(sysMinKey)) {
                currentBackup[sysMinKey] = safeReadSetting(context, "SYSTEM", "min_refresh_rate") ?: ""
                currentBackup["system:peak_refresh_rate"] = safeReadSetting(context, "SYSTEM", "peak_refresh_rate") ?: ""
                currentBackup["global:min_refresh_rate"] = safeReadSetting(context, "GLOBAL", "min_refresh_rate") ?: ""
                currentBackup["global:peak_refresh_rate"] = safeReadSetting(context, "GLOBAL", "peak_refresh_rate") ?: ""
                backupChanged = true
            }
        }

        if (backupChanged) {
            repository.saveShutUpOriginalSettings(currentBackup)
        }

        config.settings.forEach { setting ->
            if (!setting.enabled) return@forEach
            if (hiddenDebugging && setting.key in hiddenDebuggingKeys) return@forEach
            val resolvedType = if (setting.key == "accessibility_enabled") "SECURE" else setting.settingType
            try {
                when (resolvedType.uppercase()) {
                    "GLOBAL" -> Settings.Global.putString(resolver, setting.key, setting.valueOnLaunch)
                    "SECURE" -> Settings.Secure.putString(resolver, setting.key, setting.valueOnLaunch)
                    "SYSTEM" -> Settings.System.putString(resolver, setting.key, setting.valueOnLaunch)
                }
                if (setting.key == "adb_wifi_enabled") {
                    Settings.Global.putString(resolver, setting.key, setting.valueOnLaunch)
                }
            } catch (e: Exception) {
                Log.w(TAG, "preApplyShutUpSettings: failed [$resolvedType] ${setting.key}", e)
            }
        }

        if (disableAccessibility) {
            isAccessibilityMuted = true
            try {
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    "",
                )
            } catch (e: Exception) {
                Log.w(TAG, "preApplyShutUpSettings: failed to disable accessibility services", e)
            }

            // Pin min refresh rate to peak to prevent Android's LTPO governor from dropping to 60Hz idle
            val perAppConfig = repository.loadPerAppRefreshRateConfigs()
                .find { it.packageName == config.packageName && it.isEnabled }
            if (perAppConfig == null) {
                val peakRate = RefreshRateUtils.getHighestSupportedRefreshRate(context)
                val formatted = String.format(java.util.Locale.US, "%.0f", peakRate)
                try {
                    Settings.System.putString(resolver, "peak_refresh_rate", formatted)
                    Settings.System.putString(resolver, "min_refresh_rate", formatted)
                    Settings.Global.putString(resolver, "peak_refresh_rate", formatted)
                    Settings.Global.putString(resolver, "min_refresh_rate", formatted)
                } catch (_: Exception) {}
            }
        }

        settingsCurrentlyApplied = true
        Log.d(TAG, "preApplyShutUpSettings: done for ${config.packageName}")
    }

    suspend fun applyShutUpSettings(
        context: Context,
        config: ShutUpAppConfig,
        repository: SettingsRepository? = null,
        reinforcement: Boolean = false
    ) = settingsMutex.withLock {
        Log.d(TAG, "Applying ShutUp settings for ${config.packageName}")
        withContext(Dispatchers.IO) {
            val repo = repository ?: SettingsRepository(context)
            val currentBackup = repo.getShutUpOriginalSettings()
            val originalSettings = currentBackup.toMutableMap()

            val hiddenDebugging = repo.getBoolean(
                SettingsRepository.KEY_HIDDEN_DEBUGGING_SUPPORT,
                false,
            )
            val hiddenDebuggingKeys = setOf("development_settings_enabled", "adb_enabled")

            // Snapshot every original value before changing any setting. Re-enforcement never
            // creates or changes the snapshot.
            if (!reinforcement) {
                config.settings.forEach { setting ->
                    if (setting.enabled) {
                        if (hiddenDebugging && setting.key in hiddenDebuggingKeys) return@forEach
                        val resolvedType = if (setting.key == "accessibility_enabled") "SECURE" else setting.settingType
                        val prefixedKey = "${resolvedType.lowercase()}:${setting.key}"
                        if (!originalSettings.containsKey(prefixedKey)) {
                            val valueToSnapshot = if (setting.key == "adb_wifi_enabled") {
                                "1"
                            } else {
                                safeReadSetting(context, resolvedType, setting.key) ?: ""
                            }
                            originalSettings[prefixedKey] = valueToSnapshot
                        }
                    }
                }

                val disableAccessibility = config.settings.any { it.key == "accessibility_enabled" && it.enabled }
                if (disableAccessibility) {
                    val prefixedAccKey = "secure:${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}"
                    if (!originalSettings.containsKey(prefixedAccKey)) {
                        originalSettings[prefixedAccKey] = safeReadSetting(
                            context,
                            "SECURE",
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        ) ?: ""
                    }
                    val sysMinKey = "system:min_refresh_rate"
                    if (!originalSettings.containsKey(sysMinKey)) {
                        originalSettings[sysMinKey] = safeReadSetting(context, "SYSTEM", "min_refresh_rate") ?: ""
                        originalSettings["system:peak_refresh_rate"] = safeReadSetting(context, "SYSTEM", "peak_refresh_rate") ?: ""
                        originalSettings["global:min_refresh_rate"] = safeReadSetting(context, "GLOBAL", "min_refresh_rate") ?: ""
                        originalSettings["global:peak_refresh_rate"] = safeReadSetting(context, "GLOBAL", "peak_refresh_rate") ?: ""
                    }
                }

                if (originalSettings != currentBackup) {
                    repo.saveShutUpOriginalSettings(originalSettings)
                }
            }

            config.settings.forEach { setting ->
                if (setting.enabled) {
                    if (hiddenDebugging && setting.key in hiddenDebuggingKeys) return@forEach
                    val resolvedType = if (setting.key == "accessibility_enabled") "SECURE" else setting.settingType
                    val currentValue = safeReadSetting(context, resolvedType, setting.key)
                    if (currentValue != setting.valueOnLaunch) {
                        safeWriteSettingSync(context, resolvedType, setting.key, setting.valueOnLaunch)
                    }
                }
            }

            // Special handling for accessibility services
            val disableAccessibility = config.settings.any { it.key == "accessibility_enabled" && it.enabled }
            if (disableAccessibility) {
                isAccessibilityMuted = true
                safeWriteSettingSync(context, "SECURE", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")

                val perAppConfig = repo.loadPerAppRefreshRateConfigs()
                    .find { it.packageName == config.packageName && it.isEnabled }
                if (perAppConfig == null) {
                    val peakRate = RefreshRateUtils.getHighestSupportedRefreshRate(context)
                    RefreshRateUtils.applyFixedRefreshRate(context, peakRate)
                }
            }
            settingsCurrentlyApplied = true
        }
    }

    suspend fun revertShutUpSettings(context: Context, config: ShutUpAppConfig) = settingsMutex.withLock {
        Log.d(TAG, "Reverting ShutUp settings for ${config.packageName}")
        withContext(Dispatchers.IO) {
            config.settings.forEach { setting ->
                if (setting.enabled) {
                    val resolvedType = if (setting.key == "accessibility_enabled") "SECURE" else setting.settingType
                    safeWriteSettingSync(context, resolvedType, setting.key, setting.valueOnRevert)
                }
            }
        }
    }

    suspend fun restoreOriginalSettings(context: Context, repository: SettingsRepository) {
        if (!_restoreInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Restore already in progress, skipping duplicate call")
            return
        }
        try {
            settingsMutex.withLock {
                val originalSettings = repository.getShutUpOriginalSettings()
                if (originalSettings.isEmpty()) {
                    Log.d(TAG, "No original settings to restore (backup empty)")
                    isAccessibilityMuted = false
                    settingsCurrentlyApplied = false
                    return@withLock
                }

                Log.d(TAG, "Restoring original settings from backup (${originalSettings.size} entries)")
                withContext(Dispatchers.IO) {
                    var restoreSucceeded = true

                    // Dependency-ordered restoration:
                    // 1. development_settings_enabled (priority 0)
                    // 2. adb_enabled (priority 1)
                    // 3. other settings / accessibility (priority 2)
                    // 4. adb_wifi_enabled (priority 3) after 100ms for system AdbService initialization
                    val orderedKeys = originalSettings.keys.sortedWith { a, b ->
                        fun priority(key: String): Int = when {
                            key.endsWith(":development_settings_enabled") -> 0
                            key.endsWith(":adb_enabled") -> 1
                            key.endsWith(":adb_wifi_enabled") -> 3
                            else -> 2
                        }
                        priority(a).compareTo(priority(b))
                    }

                    for (prefixedKey in orderedKeys) {
                        val value = originalSettings[prefixedKey] ?: continue
                        try {
                            val parts = prefixedKey.split(":", limit = 2)
                            if (parts.size < 2) continue
                            val table = parts[0]
                            val key = parts[1]

                            if (key == "adb_wifi_enabled") {
                                // Give AdbService in system_server 100ms to acknowledge adb_enabled = 1
                                delay(100)
                            }

                            val current = safeReadSetting(context, table, key)
                            if (current != value) {
                                restoreSucceeded = safeWriteSettingSync(context, table, key, value) && restoreSucceeded
                            }
                            Log.d(TAG, "Restored $prefixedKey = $value")
                        } catch (e: Exception) {
                            restoreSucceeded = false
                            Log.e(TAG, "Failed to restore setting $prefixedKey", e)
                        }
                    }

                    // Always clear in-memory flags regardless of partial write failures.
                    // These flags represent "ShutUp is currently active" — if restore was
                    // attempted, we must unblock accessibility and app-flow events even if
                    // a single shell write failed, otherwise the accessibility service
                    // reconnects but events remain silently dropped forever.
                    isAccessibilityMuted = false
                    settingsCurrentlyApplied = false

                    if (restoreSucceeded) {
                        repository.saveShutUpOriginalSettings(emptyMap())
                    } else {
                        Log.w(TAG, "Restore completed with partial failures — flags cleared, backup retained for retry")
                    }
                }

                if (repository.getShutUpOriginalSettings().isEmpty()) withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.shut_up_toast_restored),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } finally {
            _restoreInProgress.set(false)
        }
    }

    suspend fun restartShizuku(context: Context) {
        if (ShizukuUtils.isShizukuAvailable()) {
            Log.d(TAG, "Shizuku server is already running and binder is alive; skipping unnecessary restart")
            return
        }

        Log.d(TAG, "Waiting 1500ms for developer/ADB services to stabilize before restarting Shizuku")
        delay(1500)
        if (ShizukuUtils.isShizukuAvailable()) {
            Log.d(TAG, "Shizuku reconnected during stabilization delay; skipping restart")
            return
        }
        Log.d(TAG, "Attempting Shizuku restart now")

        val repository = SettingsRepository(context)
        val savedToken = repository.getShizukuAuthToken()
        if (savedToken.isBlank()) {
            Log.w(TAG, "No Shizuku auth token saved; skipping restart broadcast")
            return
        }
        val authTokens = listOf(savedToken)

        authTokens.forEach { token ->
            // Try explicit ManualStartReceiver broadcast
            try {
                val intent = Intent("moe.shizuku.privileged.api.START").apply {
                    setClassName("moe.shizuku.privileged.api", "moe.shizuku.manager.receiver.ManualStartReceiver")
                    putExtra("auth", token)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                context.sendBroadcast(intent)
                Log.d(TAG, "Sent explicit ManualStartReceiver broadcast")
            } catch (e: Exception) {
                Log.e(TAG, "Failed explicit ManualStartReceiver broadcast", e)
            }

            // Try explicit BootReceiver broadcast
            try {
                val intent = Intent("moe.shizuku.privileged.api.START").apply {
                    setClassName("moe.shizuku.privileged.api", "moe.shizuku.manager.receiver.BootReceiver")
                    putExtra("auth", token)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                context.sendBroadcast(intent)
                Log.d(TAG, "Sent explicit BootReceiver broadcast")
            } catch (e: Exception) {
                Log.e(TAG, "Failed explicit BootReceiver broadcast", e)
            }

            // Try legacy/implicit broadcast
            try {
                val intent = Intent("moe.shizuku.privileged.api.START").apply {
                    setPackage("moe.shizuku.privileged.api")
                    putExtra("auth", token)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                context.sendBroadcast(intent)
                Log.d(TAG, "Sent legacy implicit broadcast")
            } catch (e: Exception) {
                Log.e(TAG, "Failed legacy implicit broadcast", e)
            }
        }

        // If shell/root is available, run Shizuku start script
        if (ShellUtils.isAvailable(context) && ShellUtils.hasPermission(context)) {
            Log.d(TAG, "Shell/Root is available, running Shizuku start script via Shell")
            withContext(Dispatchers.IO) {
                val scripts = listOf(
                    "sh /data/data/moe.shizuku.privileged.api/start.sh",
                    "sh /sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh",
                    "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh"
                )
                scripts.forEach { script ->
                    try {
                        val success = ShellUtils.runCommand(context, script)
                        Log.d(TAG, "Executed shell command: '$script', success: $success")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed shell command: '$script'", e)
                    }
                }
            }
        }
    }
}
