# Shut-Up! Architecture Overhaul & App Detection Improvements

This document is based on a full read of the actual source files — `ShutUpForegroundService.kt`, `AppFlowHandler.kt`, `AppDetectionService.kt`, `ShutUpManager.kt`, and `ShutUpShortcutActivity.kt` — plus a direct comparison with Geto v1.17.0. No guessing. Every claim maps to a specific line number.

---

## 1. Problem Statement & Root Cause Analysis

### Symptoms
- Target apps (DigiLocker, BHIM UPI, banking apps) detect Developer Options, USB Debugging, or Accessibility permissions on initial cold launch.
- Settings are sometimes restored prematurely while the user is still actively inside the target app.
- The shortcut sometimes does nothing or opens the app without having hidden settings first.

### Root Causes Identified

#### RC-1: `shutUpSettingsApplied` Flag Desync (Most Critical)
`AppFlowHandler` tracks whether Shut-Up settings are active using a `@Volatile shutUpSettingsApplied` flag ([AppFlowHandler.kt L162](app/src/main/java/com/sameerasw/essentials/services/handlers/AppFlowHandler.kt#L162)).

`ShutUpForegroundService` also independently tracks the active target via `activeTargetConfig` ([ShutUpForegroundService.kt L54](app/src/main/java/com/sameerasw/essentials/services/ShutUpForegroundService.kt#L54)).

**These two flags are never synchronized.** When `ShutUpForegroundService` restores settings and clears the SharedPrefs backup, `AppFlowHandler` still has `shutUpSettingsApplied = true`. The next time the user opens the protected app, `checkShutUp()` sees the flag is still set, thinks settings are already applied, and skips the apply step entirely — so the app launches with Developer Options still visible.

#### RC-2: Double-Restore on Exit
When the user leaves a protected app, both services independently schedule their own restore jobs:
- `AppFlowHandler.checkShutUpRestore()` → launches `pendingShutUpRestoreJob` ([AppFlowHandler.kt L781](app/src/main/java/com/sameerasw/essentials/services/handlers/AppFlowHandler.kt#L781))
- `ShutUpForegroundService.onPackageChanged()` → launches `pendingRestoreJob` ([ShutUpForegroundService.kt L212](app/src/main/java/com/sameerasw/essentials/services/ShutUpForegroundService.kt#L212))

The first one to complete clears the SharedPrefs backup (`saveShutUpOriginalSettings(emptyMap())`). The second one then calls `restoreOriginalSettings()`, finds `originalSettings.isEmpty()`, and no-ops silently. This is only safe by accident. If restore order is reversed (FG service restores first, AppFlowHandler restores second), and the user re-opens the app in the gap between the two restores, they will see the wrong settings momentarily because the backup was already cleared.

#### RC-3: Divergent `isIgnoredPackage` Lists — Permission Dialogs Cause False Restores
There are two different ignored-package filters in the codebase that are NOT in sync:

**`AppFlowHandler.isIgnoredPackage()`** ([L326–L365](app/src/main/java/com/sameerasw/essentials/services/handlers/AppFlowHandler.kt#L326)) ignores:
`android`, `systemui`, `keyguard`, `volume`, `soundassistant`, `dialer`, `telecom`, `phone`, `incallui`, `packageinstaller`, `permissioncontroller`, `displayservice`, `pixeldisplay`, active calls (via `AudioManager.mode`), and all IMEs.

**`ShutUpManager.isPackageIgnored()`** ([L34–L46](app/src/main/java/com/sameerasw/essentials/utils/ShutUpManager.kt#L34)) only ignores:
`android`, `com.android.systemui`, `com.google.android.inputmethod.latin`, `com.google.android.gms`, plus packages starting with `com.android.inputmethod`, `com.google.android.inputmethod`, and packages containing `autofill`.

**`ShutUpForegroundService.onPackageChanged()`** calls `ShutUpManager.isPackageIgnored()` ([ShutUpForegroundService.kt L199](app/src/main/java/com/sameerasw/essentials/services/ShutUpForegroundService.kt#L199)) — the shorter list.

**Consequence:** When the user is inside DigiLocker and Android shows a system permission dialog (`com.android.packageinstaller`) or a network/permission prompt, `AppFlowHandler` correctly ignores it (it's in its list), but `ShutUpForegroundService` sees it as a genuine package change and triggers its restore pipeline.

#### RC-4: The Accessibility Catch-22 (BHIM UPI / Banking Apps)
BHIM UPI and other NPCI apps check both:
1. `Settings.Secure.ACCESSIBILITY_ENABLED`
2. `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` — if `com.sameerasw.essentials` appears in this list, the app blocks execution.

When Essentials sets `enabled_accessibility_services = ""`, the Android framework pauses `ScreenOffAccessibilityService` (`ScreenOffAccessibilityService.instance` becomes `null`). This causes **both** `AppDetectionService` and `ShutUpForegroundService` to wake up and start polling UsageStats simultaneously (each checks `ScreenOffAccessibilityService.instance == null` before polling). Now two polling loops are running at different intervals — `AppDetectionService` at 500ms ([L42](app/src/main/java/com/sameerasw/essentials/services/AppDetectionService.kt#L42)), `ShutUpForegroundService` at ~400ms ([L174](app/src/main/java/com/sameerasw/essentials/services/ShutUpForegroundService.kt#L174)) — creating a double-ownership race.

#### RC-5: `ShutUpForegroundService` Does Not Respect "Notify" Restore Mode
`AppFlowHandler.restoreShutUpSettings()` checks `repository.getShutUpRestoreMode()` ([L1138](app/src/main/java/com/sameerasw/essentials/services/handlers/AppFlowHandler.kt#L1138)). In `"Notify"` mode, it does NOT restore immediately — it shows a notification and waits for the user to tap "Restore Now".

`ShutUpForegroundService.onPackageChanged()` has **no such check** — it always calls `ShutUpManager.restoreOriginalSettings()` directly. So in "Notify" mode, the foreground service restores immediately while `AppFlowHandler` is waiting for the user's confirmation. This makes "Notify" mode unreliable when the FG service is active.

#### RC-6: `isAppRunning()` is Unreliable on API 26+
`ShutUpForegroundService.onDestroy()` calls `ShutUpManager.isAppRunning()` ([ShutUpForegroundService.kt L316](app/src/main/java/com/sameerasw/essentials/services/ShutUpForegroundService.kt#L316)) to guard against restoring while the target is still running.

`isAppRunning()` falls back to `ActivityManager.getRunningAppProcesses()` ([ShutUpManager.kt L70](app/src/main/java/com/sameerasw/essentials/utils/ShutUpManager.kt#L70)). Since API 26, `getRunningAppProcesses()` only returns the **calling app's own processes**. For any third-party app, it always returns nothing → `isAppRunning()` always returns `false` → the `onDestroy` path **always restores**, even if the target app is actively in the foreground. The `pidof`/`pgrep` shell fallback only works if Shizuku is running, which is not guaranteed.

#### RC-7: `checkShutUp` Deserializes JSON on the Main Thread on Every Package Change
[AppFlowHandler.kt L452–L456](app/src/main/java/com/sameerasw/essentials/services/handlers/AppFlowHandler.kt#L452): `checkShutUp` calls `prefs.getString("shut_up_selected_apps", null)` + `Gson().fromJson(...)` synchronously on `Dispatchers.Main.immediate`. This runs on every single package change event, including ones that are not ShutUp-related. SharedPreferences reads can block briefly on first access, and Gson allocation on a hot path is wasteful.

#### RC-8: Screen Unlock Into Target App — Backup Already Cleared
Edge case not covered: User opens DigiLocker → Shut-Up applies → user presses power button to lock screen → `ACTIVITY_PAUSED` fires → settings restore, backup cleared from SharedPrefs → user unlocks → Android fires `ACTIVITY_RESUMED` for DigiLocker (app is still alive in background).

At this point, `preApplyShutUpSettings` runs again. It correctly reads the current setting values (now restored to `1`) and snapshots them. Then it applies `0`. This is technically correct. **However**, the `ShutUpForegroundService` re-enforcement ticker ([L156–L170](app/src/main/java/com/sameerasw/essentials/services/ShutUpForegroundService.kt#L156)) fires every ~2s based on `activeTargetConfig`. If the ticker fires **after** screen unlock but **before** `ACTIVITY_RESUMED` is detected, it finds `activeTargetConfig != null` and calls `applyShutUpSettings(reinforcement = true)` — but reinforcement mode never snapshots. If the backup was already cleared, it writes `0` with no backup, meaning restore will later restore to `""` (empty string) instead of the original `1`.

---

## 2. Architecture Comparison: Geto vs. Essentials

| Architectural Aspect | Geto (v1.17.0) | Essentials (Current) | Gap |
| :--- | :--- | :--- | :--- |
| **Detection Ownership** | Single `UsageStats` polling loop. One state machine. | `AppFlowHandler` (via Accessibility OR `AppDetectionService`) + `ShutUpForegroundService` — two independent loops when accessibility is muted | Double-ownership race |
| **State Flags** | `activeConfig` in-memory, set/cleared in one place | `shutUpSettingsApplied` (AppFlowHandler) + `activeTargetConfig` (ShutUpFGService) — never synced | Desync (RC-1) |
| **Restore Pipeline** | One path, one restore job | Two parallel restore jobs from two services (RC-2) | Double-restore |
| **Ignored Packages** | Not applicable (Geto doesn't have other features sharing the pipeline) | Two divergent lists across three classes (RC-3) | False restores on permission dialogs |
| **Restore Mode** | N/A | "Notify" mode respected in AppFlowHandler, **ignored** in FG service (RC-5) | Mode override |
| **Accessibility Muting** | Writes `accessibility_enabled = 0` and `enabled_accessibility_services = ""`. `UsageStats` loop is the only watchdog — no risk of double polling | When Essentials mutes accessibility, both AppDetectionService + ShutUpFGService wake up (RC-4) | Ownership handoff missing |
| **Mutual Exclusion When Muted** | Not needed — single loop | Missing — both services poll when accessibility is muted | Race condition |
| **Shortcut Launch** | Pre-apply sync → start target → finish 0,0 | Headless trampoline `ShutUpShortcutActivity` (recently fixed) | OK |

### The Missing Ownership Handoff Protocol

The critical design rule that Geto implicitly has and Essentials lacks:

> **When `ScreenOffAccessibilityService` is muted (because `enabled_accessibility_services` was set to `""`), exactly one service must own the monitoring loop. `AppDetectionService` must detect this condition and yield. `ShutUpForegroundService` must become the sole polling owner for the duration of the session.**

Currently, both services check `ScreenOffAccessibilityService.instance == null` to decide whether to poll — and when accessibility is muted, `instance` becomes `null`, so both start polling simultaneously.

---

## 3. Comprehensive Edge Cases & Solutions

### Edge Case 1: BHIM UPI / NPCI Accessibility Inspection
- **Requirement:** BHIM UPI checks `ACCESSIBILITY_ENABLED` and `ENABLED_ACCESSIBILITY_SERVICES`. If `com.sameerasw.essentials` is listed, it blocks immediately.
- **Behavior:**
  1. On target app launch, snapshot `enabled_accessibility_services` (e.g. `com.sameerasw.essentials/...:com.eg.app/...`).
  2. Set `accessibility_enabled = "0"` and `enabled_accessibility_services = ""`.
  3. **Ownership handoff:** When accessibility is muted, `ShutUpForegroundService` must become the sole polling watchdog. `AppDetectionService` must yield. A shared `@Volatile` flag (e.g. `ShutUpManager.isAccessibilityMuted`) is the cleanest signal for this, readable by both services without either depending on the other's instance.
  4. On `ACTIVITY_PAUSED` / package transition away (detected by FG service): restore the exact snapshot for `enabled_accessibility_services` and set `accessibility_enabled = "1"`.
  5. After restore, `ScreenOffAccessibilityService` will restart automatically (Android framework rebinds it) — at that point, `AppDetectionService` can resume.

### Edge Case 2: Permission Dialogs, Biometric Prompts, and Autofill
- **Scenario:** User is inside DigiLocker and Android shows a system permission dialog (`com.android.packageinstaller`), fingerprint prompt (`com.android.systemui`), or autofill (`com.google.android.gms`).
- **Root Cause:** `ShutUpForegroundService` uses the shorter `ShutUpManager.isPackageIgnored()` list which doesn't include `packageinstaller` or `permissioncontroller`.
- **Fix:** Unify the ignored-package filter. `ShutUpManager.isPackageIgnored()` must be expanded to match `AppFlowHandler.isIgnoredPackage()` exactly. Since `ShutUpManager` is shared by all callers, fixing it there fixes all three services at once.

### Edge Case 3: Switching Directly Between Two Protected Apps
- **Scenario:** User opens DigiLocker → switches to BHIM UPI via Recents without going to Home.
- **Behavior:** Do NOT restore settings in the intermediate frame. Transfer session ownership to the new target. `AppFlowHandler` already handles this correctly via the `isNewAppShutUp` check ([L763](app/src/main/java/com/sameerasw/essentials/services/handlers/AppFlowHandler.kt#L763)). `ShutUpForegroundService` also handles it via `activeTargetConfig` transition ([L229–L231](app/src/main/java/com/sameerasw/essentials/services/ShutUpForegroundService.kt#L229)). However, these two guards are not atomic — there is a window where one service starts a restore and the other hasn't cancelled it yet. The `shutUpSettingsApplied` / `activeTargetConfig` sync fix (RC-1) is the correct solution here too.

### Edge Case 4: App Crash / Force Stop
- **Scenario:** Protected app crashes or user force-stops it from Recent Apps.
- **Behavior:** `UsageStats` emits `ACTIVITY_PAUSED`. Both services detect the exit and schedule restore. The double-restore issue (RC-2) applies here. Fix: single owner of restore.
- **The `isAppRunning()` guard does not work here** (RC-6). After a crash, `getRunningAppProcesses()` returns nothing (as always on API 26+). This means the guard is vacuously passed and restore proceeds — which is actually correct behavior post-crash, but only by coincidence. The `onDestroy` restore guard should be removed or replaced with a timestamp-based check (e.g. "was the FG service killed within X seconds of the target app leaving?").

### Edge Case 5: Screen Lock → Unlock Into Target App (Backup Already Cleared)
- **Scenario:** User opens BHIM UPI → locks screen → settings restore + backup cleared → user unlocks → BHIM UPI resumes.
- **Risk:** `ShutUpForegroundService` re-enforcement ticker fires after unlock but before `ACTIVITY_RESUMED` is detected. It calls `applyShutUpSettings(reinforcement = true)` which never writes a new backup. If the backup is empty at this point, subsequent restore will use an empty map and restore to `""` instead of `"1"`.
- **Fix:** Re-enforcement must check whether a valid backup exists in SharedPrefs **before** writing reinforcement values. If backup is empty, it should treat the tick as a fresh apply (i.e. run the full snapshot path, not the reinforcement path).

### Edge Case 6: User Disables Shut-Up Mid-Session
- **Scenario:** User toggles off the Shut-Up service toggle while BHIM UPI is open.
- **Behavior:** `AppFlowHandler.checkShutUp()` won't apply on next event (service disabled check at L449). But `ShutUpForegroundService` only checks configs in `loadShutUpConfigs()` — if the service is disabled globally but the FG service is still running, it will still re-enforce. The FG service startup/stop should be tied to the service-enabled toggle, and any active enforcement should be torn down immediately when the toggle is turned off.

### Edge Case 7: Rapid Open → Back → Open Again (Re-apply on Second Launch)
- **Scenario:** User opens DigiLocker → immediately presses Back → immediately opens DigiLocker again.
- **Risk:** The pending restore job from the first exit may still be running when the second open fires. `AppFlowHandler.checkShutUp()` cancels `pendingShutUpRestoreJob` ([L474](app/src/main/java/com/sameerasw/essentials/services/handlers/AppFlowHandler.kt#L474)) before re-applying — but if `ShutUpForegroundService`'s separate `pendingRestoreJob` is still running and hasn't been cancelled, it completes after `AppFlowHandler` re-applies settings, wiping them back to `1`.
- **Fix:** RC-2 (single restore owner) resolves this.

---

## 4. Architectural Design Plan

The architecture follows **Essentials Contribution Guidelines** (centralized repository, domain/controller decoupling, minimal service surface area).

### The Core Principle

> One session = one owner. When `ShutUpForegroundService` is running and accessibility is muted, it is the sole coordinator. `AppDetectionService` must fully yield. When the FG service is not running, `AppFlowHandler` (via accessibility events or `AppDetectionService`) is the sole coordinator.

```
                        Package Event Source (only ONE active)
                                        │
           ┌────────────────────────────┴────────────────────────────┐
           ▼                                                         ▼
 [Accessibility Event]                                 [UsageStats Polling]
 ScreenOffAccessibilityService                         ShutUpForegroundService
 → AppFlowHandler                                      (active ONLY when:
   (active when accessibility                           a) FG service running
    is NOT muted)                                       b) accessibility muted)
           │                                                         │
           └────────────────────────────┬────────────────────────────┘
                                        ▼
                            Single Restore Decision Point
                                        │
                          ┌─────────────┴───────────────┐
                          ▼                             ▼
                    ShutUpManager               SettingsRepository
              (single source of truth       (single backup map,
               for apply/restore logic)      cleared only after
                                             confirmed restore)
```

### Specific Changes Required (Not Overengineered)

#### Change 1: Add `ShutUpManager.isAccessibilityMuted: Boolean` flag
A single `@Volatile` boolean on `ShutUpManager`. Set to `true` in `applyShutUpSettings` when `enabled_accessibility_services` is being cleared. Set back to `false` in `restoreOriginalSettings` after the services list is restored.

Both `AppDetectionService` and `ShutUpForegroundService` read this flag to decide polling ownership:
- `AppDetectionService.startPolling()`: if `ShutUpManager.isAccessibilityMuted`, do nothing (yield completely).
- `ShutUpForegroundService.startMonitoring()`: existing defer-to-accessibility check already present; when `isAccessibilityMuted = true`, accessibility is gone by design, so the FG service must poll unconditionally.

This is a 2-line add in each service — no refactoring.

#### Change 2: Unify `isIgnoredPackage` Lists
Move the full filter from `AppFlowHandler.isIgnoredPackage()` into `ShutUpManager.isPackageIgnored()`. Delete the duplicate list from `AppFlowHandler` and have it call `ShutUpManager.isPackageIgnored()` for the ShutUp-specific check. (AppFlowHandler still needs its full `isIgnoredPackage` for non-ShutUp features like App Lock and Per-App Refresh Rate.)

Concretely: expand `ShutUpManager.isPackageIgnored()` to also cover `packageinstaller`, `permissioncontroller`, `keyguard`, `telecom`, `incallui`, `dialer`. `ShutUpForegroundService.onPackageChanged()` already calls this — so the fix is automatic.

#### Change 3: Designate a Single Restore Owner
Add a `@Volatile` flag `ShutUpManager.restoreInProgress: Boolean`. When either service starts a restore job, it sets this flag. If the other service's restore job detects `restoreInProgress = true`, it skips. Cleared when `restoreOriginalSettings` completes. This is a 3-line guard, not a rewrite.

Alternatively (simpler): make `ShutUpForegroundService.onPackageChanged()` NOT schedule its own restore job when `AppFlowHandler`'s service (`ScreenOffAccessibilityService` or `AppDetectionService`) is the active event source. Since `ShutUpForegroundService` already defers when accessibility is running, this is a symmetric rule: when accessibility is muted and FG service is the sole owner, AppFlowHandler must defer its restore.

#### Change 4: Sync `shutUpSettingsApplied` Flag
`ShutUpManager.restoreOriginalSettings()` must clear the flag after a successful restore. Add a callback or a simple boolean field on `ShutUpManager`:
```
ShutUpManager.settingsCurrentlyApplied: Boolean
```
Both `AppFlowHandler` and `ShutUpForegroundService` read/write this single field instead of maintaining separate flags.

#### Change 5: Fix Re-enforcement Backup Guard
In `ShutUpForegroundService`'s re-enforcement tick ([L159–L170](app/src/main/java/com/sameerasw/essentials/services/ShutUpForegroundService.kt#L159)):
```
// Before calling applyShutUpSettings(reinforcement=true):
if (settingsRepository.getShutUpOriginalSettings().isEmpty()) {
    // Backup was cleared (e.g. after screen-off restore). Re-run full apply instead.
    applyShutUpSettings(config, reinforcement = false)
    return
}
```
This is a 4-line guard.

#### Change 6: Respect Restore Mode in `ShutUpForegroundService`
Before calling `ShutUpManager.restoreOriginalSettings()` in `ShutUpForegroundService.onPackageChanged()`, check `settingsRepository.getShutUpRestoreMode()`. If `"Notify"`, delegate the notification/restore flow to `AppFlowHandler` (via a broadcast or by doing nothing — `AppFlowHandler` will handle it via its own event).

#### Change 7: Expand Testing Checklist (See Section 5)

---

## 5. Verification & Testing Checklist

### Happy Path
- [ ] **DigiLocker Cold Launch (App Drawer):** No developer options/root/accessibility warnings on first open.
- [ ] **BHIM UPI Cold Launch (App Drawer):** Accessibility permissions check passes — app does not block.
- [ ] **Shortcut Launch:** Zero UI flicker, app opens immediately with settings pre-applied.
- [ ] **App Exit & Auto-Restore:** Developer Options and Accessibility Services restore cleanly on return to Home.
- [ ] **Shizuku Reconnection:** Shizuku starts automatically if configured.
- [ ] **Auto-Freeze / Archive:** Countdown notification fires and freeze works after restore.

### Edge Cases (Regression Tests)
- [ ] **Biometric Prompt Inside App:** Fingerprint dialog (`com.android.systemui`) does NOT trigger premature restore.
- [ ] **Permission Dialog Inside App:** Runtime permission prompt (`com.android.packageinstaller`) does NOT trigger premature restore.
- [ ] **App-to-App Protected Transition:** Open DigiLocker → switch to BHIM UPI via Recents. Settings must NOT flicker back to `1` in the transition frame.
- [ ] **Rapid Open/Back/Open:** Open target → press Back within 1 second → open again. Settings must be correctly applied on second launch (no "already applied" skip).
- [ ] **Screen Lock → Unlock Into Target:** Lock screen while in BHIM UPI → unlock → app resumes. Settings must be re-applied with a fresh backup snapshot.
- [ ] **Re-enforcement After Unlock:** Confirm that re-enforcement tick after unlock does not write `0` with an empty backup.
- [ ] **User Disables Shut-Up Mid-Session:** Toggle off Shut-Up while inside protected app. Settings should restore immediately.
- [ ] **Force Stop from Recents:** Force-stop DigiLocker from Recents. Settings must restore within the restore delay window.
- [ ] **`ShutUpForegroundService` killed by OS:** Service is killed (low memory). `onDestroy` restore path must run. Verify settings are restored.
- [ ] **"Notify" Mode with FG Service Active:** In "Notify" restore mode, confirm that `ShutUpForegroundService` does NOT restore immediately without user confirmation.
- [ ] **Both polling loops when accessibility muted:** Open BHIM UPI (accessibility muted). Confirm only ONE polling loop is active (check logs — only `ShutUpForegroundService` should log package events, not `AppDetectionService`).

---

## 6. What Must NOT Change (Existing Functionality Preservation)

- **App Lock:** `checkAppLock()` in `AppFlowHandler` — completely untouched.
- **Per-App Refresh Rate:** `checkPerAppRefreshRate()` — untouched.
- **App Automations / Night Light / Gesture Bar:** `checkAppAutomations()`, `checkHighlightNightLight()`, `checkGestureBarAutomation()` — untouched.
- **Auto-Freeze Countdown:** The 5-second countdown in `ShutUpForegroundService` and the 10-second countdown in `AppFlowHandler` — both must remain, with no change to their timing or notification actions.
- **Shizuku Restart Logic:** `ShutUpManager.restartShizuku()` — untouched.
- **Restore Mode Settings:** "Notify", "Auto", and custom delay — all must be respected in both services.
- **`ShutUpShortcutActivity` Trampoline:** The headless zero-flicker shortcut launch — keep as-is.
- **`preApplyShutUpSettings`:** The synchronous pre-apply path used by the shortcut — keep as-is.
