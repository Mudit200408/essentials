/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Domain Layer Models & Registries
 * File: AppRefreshRateConfig.kt
 * Description: Domain model for Per-App Refresh Rate configuration.
 */

package com.sameerasw.essentials.domain.model

data class AppRefreshRateConfig(
    val packageName: String,
    val refreshRate: Float,
    val isFixed: Boolean = false,
    val isEnabled: Boolean = true,
    val landscapeRefreshRate: Float? = null,
    val onlyOnMediaPlaying: Boolean = false
)
