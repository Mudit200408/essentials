/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Domain Layer Models & Registries
 * File: AppSetting.kt
 * Description: Domain model for per-app system settings configuration.
 */

package com.sameerasw.essentials.domain.model

data class AppSetting(
    val enabled: Boolean = true,
    val settingType: String, // "GLOBAL", "SECURE", "SYSTEM"
    val key: String,
    val valueOnLaunch: String,
    val valueOnRevert: String,
    val label: String
)
