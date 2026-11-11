# Essentials Game Mode: Engineering Architecture & Optimization Context

## 1. Executive Summary

This document captures the complete architectural design, root-cause diagnostics, and production optimizations implemented for the **Essentials Game Mode** on Android 17 (tested and validated on Google Pixel 9 / Tensor G4).

Game Mode provides a zero-compromise, stable gaming environment by eliminating micro-stutters, stabilizing touch responsiveness, preventing unexpected thermal/system throttling, and locking network latency. Every optimization is **fail-safe, persistent across process restarts, and fully reversible upon exiting the game**.

---

## 2. Core Architecture & Lifecycle Management

Game Mode is coordinated by `GameModeManager.kt` and driven by foreground window events in `AppFlowHandler.kt`:

```
+-----------------------------------------------------------+
|               Foreground Detection Layer                  |
|  - ScreenOffAccessibilityService (Window Event Stream)    |
|  - AppDetectionService (UsageStats Polling Fallback)      |
+-----------------------------+-----------------------------+
                              |
                              v
+-----------------------------+-----------------------------+
|                     AppFlowHandler                        |
|  - Filters ignored packages (IME, SystemUI, VolDialog)     |
|  - Dispatches onGameForeground / onGameBackground         |
+-----------------------------+-----------------------------+
                              |
                              v
+-----------------------------+-----------------------------+
|                    GameModeManager                        |
|  - Snapshot original states (SharedPreferences & Memory)  |
|  - Execute Kernel / Shell / Service Optimizations         |
|  - 15s-60s Exit Debounce & Dumpsys Foreground Validation  |
|  - Restore all states in `finally` blocks                 |
+-----------------------------------------------------------+
```

### Self-Healing & Accidental Exit Protection
- **Multi-Source Validation**: When a game is backgrounded (e.g. pulling down Quick Settings, receiving a heads-up notification, or opening a quick chat bubble), Game Mode does **not** instantly restore. It enters a configurable delay window (default 15–60s).
- **Shell Focus Verification**: Before executing restoration, it verifies `dumpsys activity activities | grep topResumedActivity` and `dumpsys window | grep mCurrentFocus`. If the game is still resumed or focused, restoration is aborted.
- **Persistent Snapshots in `SettingsRepository.kt`**: All critical system states (Auto Brightness, Columbus/Quick Tap, Private DNS, Wi-Fi parameters) are persisted to disk so that even if Essentials is killed under intense gaming memory pressure (LMK) or updated mid-session, all settings are guaranteed to restore cleanly when the game closes.

---

## 3. Display & Touch Latency Optimizations

### 3.1. Physical Display Mode Locking (Preventing 30Hz / 1Hz Idle Drops)
- **Problem**: Modern LTPO/VRR displays dynamically drop refresh rate to 30Hz or 1Hz when finger contact ceases for even a fraction of a second. In fast-paced games running at 60, 80, or 90 FPS, this causes severe frame-pacing stutter and inconsistent touch response.
- **Mechanism**:
  - Sets peak and minimum refresh rates to 120Hz via `RefreshRateUtils`.
  - Executes hardware display mode override:
    ```shell
    cmd display set-user-preferred-display-mode <width> <height> 120 0
    ```
    This locks the display panel controller to 120Hz at the hardware layer, preventing driver-level downclocking during idle touch intervals.
  - **Restoration**: Cleared with `cmd display clear-user-preferred-display-mode 0` on exit.

### 3.2. Elimination of Input Jank (Quick Tap / Columbus)
- **Problem**: Google’s Quick Tap feature (back-of-phone tap detection) runs an accelerometer/gyroscope gesture classifier that hooks into every screen touch event. In Android's `InputJankTracker`, this logged hundreds of jank warnings per match:
  ```log
  InputJankTracker: Input Jank Detected (InputConsumer[... com.activision.callofduty.shooter]): Processed 2 frame(s), detected 1 jank frame(s).
  ```
- **Mechanism**:
  - Game Mode snapshots `columbus_enabled` and turns it off:
    ```shell
    settings put secure columbus_enabled 0
    ```
  - Eliminates input interception overhead during gameplay.
  - **Restoration**: Restores `columbus_enabled` to its original value.

### 3.3. Persistent Auto Brightness Control
- **Problem**: Ambient light fluctuations (shadows from hands or room lighting) trigger auto-dimming or brightness jumps. Furthermore, Adaptive Brightness actively participates in display thermal throttling.
- **Mechanism**:
  - Game Mode reads `Settings.System.SCREEN_BRIGHTNESS_MODE`, snapshots it persistently in `SettingsRepository`, and disables it:
    ```shell
    settings put system screen_brightness_mode 0
    ```
  - **Restoration**: Restores `settings put system screen_brightness_mode 1`. Verified in `DisplayPowerController` switching immediately to `AutomaticBrightnessStrategy`.

---

## 4. Android Game Manager Interventions

- **Profiles Supported**:
  - **Performance (Mode 2, Default)**: Uncapped frame delivery, aggressive CPU/GPU governors, optimal touch sampling.
  - **Standard (Mode 1)**: Android default scheduling without power throttling.
  - **Battery (Mode 3)**: Frame scheduling tuned for lower heat and extended gameplay sessions.
- **Execution**:
  ```shell
  cmd game set --downscale disable [--fps <target_fps>] --mode <1|2|3> <package> && cmd game mode <performance|standard|battery> <package>
  ```
- **Why**:
  - Enforces Android’s internal `GameManagerService` power and frame scheduling profile.
  - Disables dynamic resolution downscaling (ensuring native display sharpness).
  - Supplies the explicit target frame rate (e.g. 80, 90, 120) to Android's frame scheduler when configured.
- **Restoration**:
  ```shell
  cmd game reset <package> && cmd game mode standard <package>
  ```

---

## 5. Granular Network Latency & Wi-Fi Optimization

In multiplayer gaming, packet jitter and ping spikes are far more disruptive than raw throughput. Users can fine-tune specific network parameters via the dedicated **Network Latency Tuning** bottom sheet (`GameModeNetworkSheet.kt`):

### 5.1. Hardware Low-Latency Wi-Fi Mode
- Acquires `WifiManager.WIFI_MODE_FULL_LOW_LATENCY` (`wifiLowLatencyLock`) and `WifiManager.WIFI_MODE_FULL_HIGH_PERF` (`wifiHighPerfLock`).
- Low-latency mode forces the Wi-Fi PHY/MAC chip to bypass packet buffering and disables 802.11 power-save sleep between access point beacons (`wifi_power_save 0`).

### 5.2. Suppress Background Wi-Fi & BLE Scans
- `settings put global wifi_scan_always_enabled 0`: Disables background Wi-Fi scanning.
- `cmd wifi set-scan-always-available disabled`: Disables scanning when Wi-Fi is idle.
- `settings put global ble_scan_always_enabled 0`: Disables background Bluetooth LE scanning.
- `settings put global wifi_scan_throttle_enabled 1`: Enforces strict 30-minute scan throttling.
- `settings put global wifi_wakeup_enabled 0`: Disables location-based geofence scans that attempt to automatically turn on Wi-Fi.
- `settings put global wifi_networks_available_notification_on 0`: Disables periodic background scans searching for open public Wi-Fi networks.

### 5.3. Lock Network Selection Sufficiency
- `cmd wifi set-network-selection-config enabled enabled -a 0`: Informs Android that the active Wi-Fi **is sufficient**, halting candidate scans and score re-evaluations every 3 seconds.
- Disables `adaptive_connectivity_enabled 0`, `network_recommendations_enabled 0`, and `network_avoid_bad_wifi 0` to prevent cellular fallback ping churn.

### 5.4. Disable Private DNS (DoT) During Gameplay
- Temporarily sets `private_dns_mode off` to eliminate DNS-over-TLS handshake latency delays. Automatically restores original mode (e.g., `hostname`) on exit.

### 5.5. Prevent ARP Loss Disconnects
- `cmd wifi set-ipreach-disconnect disabled`: Prevents Android from dropping Wi-Fi connections when temporary ARP gateway probe packets are delayed or dropped under high network load.

---

## 6. Granular Suspended Background Services

All background packages frozen during Game Mode are consolidated in a unified, transparent bottom sheet (`GameModeServicesSheet.kt`). Users can inspect what each service does and toggle them individually:

1. **Android Auto (`com.google.android.projection.gearhead`)**:
   - `cmd package suspend --user 0 com.google.android.projection.gearhead`
   - Prevents Android Auto from waking up on Bluetooth headset connection events (`WifiBluetoothReceiver`), probing Wi-Fi/Bluetooth for wireless car projection, and creating background RF contention while gaming with Bluetooth audio.
2. **Adaptive Connectivity Services (`com.google.android.apps.scone`)**:
   - `pm disable-user --user 0 com.google.android.apps.scone`
   - Eliminates background telemetry collection and external scorer queries during matches.
3. **Device Health Services / Turbo (`com.google.android.apps.turbo` & `com.google.android.turboadapter`)**:
   - `pm disable-user --user 0 com.google.android.apps.turbo && pm disable-user --user 0 com.google.android.turboadapter`
   - Complete package-level disablement prevents Pixel system services and battery broadcasts (`PNW.batteryStatusChanged`) from unfreezing them via `bindService()` or waking FastPair/battery widgets during matches.
4. **Settings Intelligence (`com.google.android.settings.intelligence`)**:
   - `pm disable-user --user 0 com.google.android.settings.intelligence`
   - Prevents background suggestions and battery anomaly tracking during gaming sessions.
5. **On-Device AI Core (`com.google.android.aicore`)**:
   - `pm disable-user --user 0 com.google.android.aicore`
   - Freezes on-device Gemini Nano, background model maintenance, and TPU wakeups.
6. **Google Carrier Wi-Fi (`com.google.android.apps.carrier.carrierwifi`)**:
   - `cmd package suspend --user 0 com.google.android.apps.carrier.carrierwifi`
   - Prevents Google Carrier Wi-Fi from running background handovers or testing SIM-based offload networks during matches.

---

## 7. System & Synchronization Safeguards

1. **Cached Apps Freezer**:
   - `settings put global cached_apps_freezer enabled`
   - Keeps inactive background apps frozen in cgroups to eliminate CPU stealing.
2. **AirSync Bridge**:
   - Sends broadcast `com.sameerasw.airsync.action.PAUSE_SYNC` to pause clipboard sync and local WebDAV file transfers; resumes on exit.
3. **Transparent Google Account Auto-Sync**:
   - `ContentResolver.setMasterSyncAutomatically(false)` pauses account sync (Gmail, Drive, Photos, Contacts, Calendar) while gaming to prevent background disk I/O and network spikes.
   - Automatically re-enables sync upon game exit.
4. **Battery Saver Throttling Prevention**:
   - Sets `low_power_trigger_level 0` to prevent Android from enabling Battery Saver at 20% (which cuts CPU clock ceilings in half).
   - Replaces system throttling with non-intrusive toast notifications at 20%, 10%, and 5% battery levels.

---

## 8. Verification & Safety Checklist

| Feature | State During Game | State After Game Exit | Verified Via |
| :--- | :--- | :--- | :--- |
| **Display Mode** | `1080 2424 120.0` | `null` (Dynamic) | `dumpsys display` |
| **Auto Brightness** | `0` (Manual) | `1` (Automatic) | `DisplayPowerController` |
| **Quick Tap (Columbus)**| `0` (Disabled) | `1` / prior state | `settings get secure columbus_enabled` |
| **Wi-Fi Sufficiency** | `enabled enabled -a 0` | `enabled enabled -a 0`| `cmd wifi set-network-selection-config` |
| **Wi-Fi Wakeup Scans** | `0` | `1` / prior state | `settings get global wifi_wakeup_enabled` |
| **Carrier Wi-Fi** | `suspended=true` | `suspended=false` | `dumpsys package` |
| **Android Auto (Gearhead)** | `suspended=true` | `suspended=false` | `dumpsys package` |
| **Adaptive Connectivity (Scone)** | `enabled=0` | `enabled=1` | `dumpsys package` |
| **Device Health (Turbo)**| `enabled=0` | `enabled=1` | `dumpsys package` |
| **AI Core** | `enabled=0` | `enabled=1` | `dumpsys package` |
| **Game Intervention** | Mode 2 (Performance) | Mode 1 (Standard) | `cmd game mode` |
| **Low-Latency Locks** | Held | Released | `dumpsys wifi` |
