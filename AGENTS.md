# AI Agent Guidelines for Essentials

This document is the authoritative, comprehensive guide for any AI Agent working on the **Essentials** codebase. Read this document before reading, modifying, or creating any code in this repository.

---

## 1. Project Overview & Core Philosophy

**Essentials** is a specialized Android utility application designed primarily for Pixel devices (and modern AOSP-based Android devices, `minSdk = 26`, `targetSdk = 37`, compiled on **JDK 21** with Kotlin 2.x and Jetpack Compose Material 3 Expressive).

### Core Mission
Provide system mods, quick settings tiles, automation, ambient lock screen features, dynamic cutout overlays (Island), app management (AppLock, Freeze, Shut-Up), and granular device controls without requiring a custom ROM.

### Privilege Hierarchy
Essentials operates across four distinct privilege tiers:
1. **Standard Android APIs**: User-granted runtime permissions (Notifications, Location, Calendar, Bluetooth).
2. **Privileged System Permissions**:
   - `WRITE_SECURE_SETTINGS`: Granted via ADB (`adb shell pm grant com.sameerasw.essentials android.permission.WRITE_SECURE_SETTINGS`) for changing settings in `Settings.Secure` and `Settings.Global`.
   - `BIND_ACCESSIBILITY_SERVICE`: Granted by user in System Accessibility settings. Used by `ScreenOffAccessibilityService` for window state inspection and lock screen event handling.
   - `BIND_NOTIFICATION_LISTENER_SERVICE`: Used by `NotificationListener` for intercepting alerts, media state, and notifications.
   - `SYSTEM_ALERT_WINDOW`: For overlay surfaces (Island, Duo, Status Glance, Notification Lighting).
   - `PACKAGE_USAGE_STATS`: For foreground app detection via `AppDetectionService`.
3. **Shizuku Binder IPC**: Executes privileged system APIs directly as ADB user without root.
4. **Root Shell (`su`)**: Used for direct kernel sysfs writes (e.g., `/sys/class/power_supply` charging current control, SurfaceFlinger refresh rate modes).

---

## 2. Repository & Package Blueprint

```
app/src/main/java/com/sameerasw/essentials/
├── MainActivity.kt                  # Root Activity launching Compose UI
├── EssentialsApp.kt                 # Application class (Sentry, initialization)
├── data/
│   ├── model/                       # DTOs, data classes, entities
│   └── repository/                  # Repositories (SettingsRepository, GitHubRepository, etc.)
├── domain/
│   ├── controller/                  # Feature controllers (e.g., CaffeinateController)
│   ├── model/                       # Domain models, AppPermission, Enums
│   └── registry/                    # FeatureRegistry, PermissionRegistry, SearchRegistry
├── island/                          # Camera-cutout overlay subsystem (Dynamic Island)
│   ├── model/                       # IslandModels, IslandPlugin, IslandItem, CompactCell
│   ├── state/                       # IslandController (stage machine), CompactLayoutEngine
│   ├── service/                     # IslandCoordinator, IslandWindowHost, CameraGeometryResolver
│   ├── ui/                          # IslandRoot, CompactTemplate, LineTemplate, ExpandedHost
│   └── plugins/                     # Feature plugins (media, call, timer, weather, etc.)
├── services/
│   ├── tiles/                       # Quick Settings Tile services & ScreenOffAccessibilityService
│   ├── handlers/                    # Decoupled feature handlers (AppFlowHandler, FlashlightHandler, etc.)
│   ├── receivers/                   # BroadcastReceivers & QsTileActionRouter
│   ├── automation/                  # DIY automation rules and executors
│   ├── dreams/                      # DreamService implementations (screensavers)
│   └── widgets/                     # Glance app widgets (QsTilesWidget, ScreenOffWidget)
├── ui/
│   ├── core/                        # Shared design system components
│   │   ├── containers/              # RoundedCardContainer, RoundedCardLazyContainer
│   │   ├── cards/                   # IconToggleItem, ConfigPickerItem, FeatureCard, PermissionCard
│   │   ├── pickers/                 # SegmentedPicker, MultiSegmentedPicker, SwatchPicker
│   │   └── sheets/                  # EssentialsBottomSheet, PermissionsBottomSheet, FeatureHelpBottomSheet
│   ├── features/                    # 17 Feature-based UI screens (display, audio, security, etc.)
│   └── theme/                       # Color, Theme, Shape, Typography tokens
├── utils/                           # Specialized utility helpers
│   ├── hardware/                    # SurfaceFlinger, Flashlight, RefreshRate, Thermal
│   ├── security/                    # ShizukuUtils, RootUtils, BiometricHelper, ShellUtils
│   ├── ui/                          # HapticUtil, ColorUtil, PermissionUIHelper
│   ├── battery/                     # BatteryStatsUtil, ChargingModeUtil, BatteryRingDrawer
│   └── call/                        # CallNotificationParser, CallStateRepository
└── viewmodels/                      # Domain-specific ViewModels + MainViewModel
    ├── MainViewModel.kt             # Primary state coordinator
    ├── PermissionViewModel.kt       # System permission states
    ├── SettingsViewModel.kt         # Preference configurations
    ├── SecurityViewModel.kt         # AppLock & RemoteLock
    ├── QuickSettingsTilesViewModel.kt
    ├── StatusBarIconViewModel.kt    # Status bar icon blacklist
    └── ...                          # AppUpdates, Battery, Caffeinate, DIY, Location, Watch, Watermark
```

---

## 3. End-to-End Feature Implementation Pipeline

Whenever adding a new user setting or feature toggle, you **must complete all five layers of the pipeline**. Never leave orphan UI states or hardcoded preferences.

```
┌────────────────────────────────────────┐
│ 1. SettingsRepository.kt               │ Constant key + Getter + Setter + Gson serialization
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│ 2. Target ViewModel / MainViewModel.kt │ Reactive mutableStateOf + Mutator updating repository
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│ 3. Feature UI Composable               │ IconToggleItem / ConfigPickerItem inside RoundedCardContainer
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│ 4. FeatureRegistry.kt                  │ Register Feature object (title, icon, permissions, onToggle)
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│ 5. Universal Search Binding            │ SearchSetting(...) + Modifier.highlight(highlightSetting == "key")
└────────────────────────────────────────┘
```

### Layer 1: Data Repository (`SettingsRepository.kt`)
- Declare constant key: `const val KEY_MY_SETTING = "my_setting_key"`
- Expose getter: `fun isMySettingEnabled(): Boolean = getBoolean(KEY_MY_SETTING, false)`
- Expose setter: `fun setMySettingEnabled(enabled: Boolean) = putBoolean(KEY_MY_SETTING, enabled)`
- For complex objects, use `gson.fromJson(...)` and `gson.toJson(...)` with proper type tokens.

### Layer 2: ViewModel State (`MainViewModel.kt` or Domain ViewModel)
- Declare observable Compose state:
  ```kotlin
  var isMySettingEnabled by mutableStateOf(settingsRepository.isMySettingEnabled())
      private set
  ```
- Expose mutator function:
  ```kotlin
  fun setMySettingEnabled(enabled: Boolean) {
      settingsRepository.setMySettingEnabled(enabled)
      isMySettingEnabled = enabled
  }
  ```

### Layer 3: UI Screen (`ui/features/<module>/<Module>SettingsUI.kt`)
- Render using design system components:
  ```kotlin
  RoundedCardContainer {
      IconToggleItem(
          iconRes = R.drawable.rounded_my_icon_24,
          title = stringResource(R.string.feat_my_setting_title),
          description = stringResource(R.string.feat_my_setting_desc),
          checked = viewModel.isMySettingEnabled,
          onCheckedChange = { viewModel.setMySettingEnabled(it) },
          index = 0,
          count = 1,
          modifier = Modifier.highlight(highlightSetting == "my_setting_key")
      )
  }
  ```

### Layer 4: Feature Registry (`domain/registry/FeatureRegistry.kt`)
- Register the feature under `ALL_FEATURES`:
  ```kotlin
  object : Feature(
      id = "My Feature ID",
      title = R.string.feat_my_feature_title,
      iconRes = R.drawable.rounded_my_feature_24,
      category = R.string.cat_system,
      description = R.string.feat_my_feature_desc,
      aboutDescription = R.string.about_desc_my_feature,
      permissionKeys = listOf("WRITE_SECURE_SETTINGS"),
      searchableSettings = listOf(
          SearchSetting(
              R.string.search_my_setting_title,
              R.string.search_my_setting_desc,
              "my_setting_key"
          )
      ),
      onToggle = { context, enabled ->
          (context.applicationContext as EssentialsApp).mainViewModel?.setMySettingEnabled(enabled)
      },
      isEnabled = { context ->
          (context.applicationContext as EssentialsApp).settingsRepository.isMySettingEnabled()
      }
  )
  ```

---

## 4. Service Decoupling & Background Architecture

### Anti-Pattern: Never bloat shared Android services
`ScreenOffAccessibilityService` and `NotificationListener` are sensitive system components. **Do not write inline feature logic inside their bodies.**

### The Handler Pattern
All background features triggered by accessibility, screen state, or key events must be encapsulated into dedicated handlers inside `services/handlers/`:
1. Create `MyFeatureHandler(private val context: Context, private val scope: CoroutineScope)`.
2. Define explicit lifecycle methods: `init()`, `onScreenStateChanged(isScreenOn: Boolean)`, `onDestroy()`, etc.
3. Instantiate and delegate to the handler inside `ScreenOffAccessibilityService.kt`:
   ```kotlin
   private lateinit var myFeatureHandler: MyFeatureHandler
   // in onCreate / onServiceConnected:
   myFeatureHandler = MyFeatureHandler(this, serviceScope)
   ```

### Foreground Detection & Service Ownership Handoff
Refer to [docs/Improvements.md](docs/Improvements.md) regarding foreground app detection:
- Default detector: `ScreenOffAccessibilityService` (via `TYPE_WINDOW_STATE_CHANGED`).
- Fallback detector: `AppDetectionService` (polling `UsageStatsManager`).
- Special case: When accessibility is muted (e.g., by Shut-Up for banking apps), `ShutUpForegroundService` becomes the sole polling owner. `AppDetectionService` must check `ShutUpManager.isAccessibilityMuted` and yield to prevent double-polling race conditions.

---

## 5. Quick Settings (QS) Tile Implementation Workflow

All QS tiles must strictly adhere to the 6-layer architecture detailed in [docs/ADD_QS_TILE.md](docs/ADD_QS_TILE.md):

1. **Service Class (`services/tiles/`)**:
   - Subclass [`BaseTileService`](app/src/main/java/com/sameerasw/essentials/services/tiles/BaseTileService.kt).
   - Implement `getTileLabel()`, `getTileSubtitle()`, `getTileState()`, `hasFeaturePermission()`, `onTileClick()`.
   - Use `putSecureInt()` / `getSecureInt()` for cached secure setting writes.
   - Handle sensitive state: Set `override val isSensitiveTile: Boolean = true` if the tile should require unlocking device first.
2. **Manifest Declaration (`AndroidManifest.xml`)**:
   - Exported service with permission `android.permission.BIND_QUICK_SETTINGS_TILE`.
   - Action `android.service.quicksettings.action.QS_TILE`.
   - Meta-data `TILE_CATEGORY`.
3. **Strings Resource (`strings.xml`)**:
   - Add `<string name="tile_my_tile_label">...</string>` and `<string name="about_desc_my_tile">...</string>`.
4. **Registry Registration (`QsTileRegistry.kt`)**:
   - Add `QsTileEntry` to `ALL_TILES`.
   - Enable Glance widget support: Ensure state can be evaluated headlessly in `isTileActive()`.
5. **Headless Action Routing (`QsTileActionRouter.kt`)**:
   - Taps from the Glance widget invoke `QsTileActionRouter`. If your tile needs custom broadcast/intent dispatch rather than default headless instantiation, add a route here.
6. **In-App Tile Manager UI (`QuickSettingsTilesSettingsUI.kt`)**:
   - Add `QSTileInfo` entry to `allTiles` with appropriate category, icon, description, and permission keys.

---

## 6. Island (Dynamic Cutout Overlay) Architecture

The Island subsystem provides a camera-cutout overlay for ambient notifications, media, timers, and alerts. See [docs/ISLAND.md](docs/ISLAND.md).

```
System Sources (Notifications, Media, Calls)
                    │
                    ▼
          IslandPlugin (one per feature)
                    │
               IslandItem (compact cells, line content, expanded content)
                    │
                    ▼
     IslandController (Stage Machine & Priority Engine)
   Stages: Hidden ──► Compact ──► Line (peek) ──► Expanded
                    │
                    ▼
   IslandWindowHost ──► IslandRoot (Jetpack Compose Surface)
```

### Stage Rules
- **`Hidden`**: No content or suppressed (landscape, fullscreen, screen off).
- **`Compact`**: Default stage. Shows up to 4 cells placed around the cutout by `CompactLayoutEngine`.
- **`Line`**: Automatic peek only via `PluginRequest.Peek`. Never user-triggered. Collapses after timeout.
- **`Expanded`**: User-triggered (tap) or high-priority request. Shows full plugin UI.

### Creating an Island Plugin
1. Extend `BaseIslandPlugin(context, scope)` in `island/plugins/<feature>/`.
2. Map your event source to an `IslandItem`:
   - `id`: Unique feature string.
   - `priority`: `IslandPriority.URGENT`, `HIGH`, `DEFAULT`, or `LOW`.
   - `compactLeading` / `compactTrailing`: Compose lambdas for compact mode.
   - `lineContent`: Text and icon for line peeks.
   - `expandedContent`: Full composable content inside `IslandExpandedScope`.
3. Call `updateItem(item)` or `removeItem()` to publish states to `IslandCoordinator`.
4. Plugins **never** directly manipulate window flags, screen coordinates, or animations.

---

## 7. UI & Design System Conventions (Material 3 Expressive)

### Design System Components (`ui/core/`)
Always reuse existing components; do not build ad-hoc rows or cards.

| Component | Path | Use Case |
| :--- | :--- | :--- |
| **`RoundedCardContainer`** | `ui/core/containers/` | Wraps grouped settings. Clips children with 24.dp rounded corners. |
| **`RoundedCardLazyContainer`** | `ui/core/containers/` | Scrollable container variant for long lists. |
| **`IconToggleItem`** | `ui/core/cards/` | Standard settings row with icon, title, subtitle, and switch. Always supply `index` and `count` for corner shape morphing. |
| **`ConfigPickerItem`** | `ui/core/cards/` | Row with title, value text, and chevron that opens a bottom sheet or dialog. |
| **`FeatureCard`** | `ui/core/cards/` | Hero banner card using `ColorUtil.getPastelColorFor` (background) and `ColorUtil.getVibrantColorFor` (icon). |
| **`PermissionCard`** | `ui/core/cards/` | Standard card displaying missing permission warnings with direct grant actions. |
| **`SegmentedPicker`** | `ui/core/pickers/` | Connected option picker with haptic feedback. |
| **`EssentialsBottomSheet`** | `ui/core/sheets/` | Standard modal bottom sheet for all dialogs and feature sub-settings. |

### Shape Morphing Contract
When placing items in `RoundedCardContainer`, you **must** supply `index` and `count`:
- `index = 0, count = 1`: Pill / round shape on all 4 corners (single item).
- `index = 0, count > 1`: Top corners rounded, bottom square (first item).
- `index = count - 1`: Bottom corners rounded, top square (last item).
- Intermediate items: Square corners.

### Theming Tokens
- Outer card container backgrounds: `MaterialTheme.colorScheme.surfaceContainer`.
- Modal bottom sheets: `MaterialTheme.colorScheme.surfaceContainerHigh`.
- Nested cards: `MaterialTheme.colorScheme.surfaceContainerLowest`.
- AMOLED / Pitch Black compatibility: Always test that backgrounds do not break when pure `#000000` pitch black is active.

### Tactile Feedback (`HapticUtil`)
Always provide haptics for physical interactions:
- UI clicks / toggles: `HapticUtil.performUIHaptic(view)`
- Sliders / segment steps: `HapticUtil.performVirtualKeyHaptic(view)`
- Heavy confirmation / errors: `HapticUtil.performHeavyHaptic(view)`
- Background service actions / QS tile taps: `HapticUtil.performHapticForService(context)`

### Import Hygiene
**Never use fully qualified inline package names** in Compose files (e.g. `com.sameerasw.essentials.R.string.title`). Import classes and symbols at the top of the file.

---

## 8. Privileged Execution & Shell Safety

### Checking Permissions First
Never run a privileged command or access a protected provider without checking capability first:
```kotlin
when {
    hasWriteSecureSettings(context) -> putSecureSetting(...)
    ShizukuUtils.isShizukuAvailable() -> ShizukuUtils.runCommand(...)
    RootUtils.isRootAvailable() -> ShellUtils.runRootCommand(...)
    else -> showPermissionsGuidance()
}
```

### Shell Commands
- Always check the exit code (`result.isSuccess` or `result.exitCode == 0`).
- Guard against null context and background execution crashes.
- Never block the Main thread with shell calls; execute on `Dispatchers.IO`.

### Testing Privileges
To grant all project permissions at once to your test device, execute:
```bash
./grant_perms.sh
```

---

## 9. String Localization & Translation Hygiene

### Rules for `strings.xml`
1. Primary strings file is located at `app/src/main/res/values/strings.xml`.
2. Over 50 internationalized locales are managed via Crowdin.
3. **Format specifiers must be numbered and positional**:
   - Correct: `Travelling to %1$s in %2$d minutes`
   - **Incorrect**: `Travelling to %s in %d minutes` (will fail Crowdin translation sync and unit tests).
4. **Apostrophe escaping**:
   - Correct: `Don\'t disturb`
   - Incorrect: `Don't disturb` (causes AAPT XML build error).
5. **No duplicate keys**: Search `strings.xml` before adding new string resources.

### Translation Validation Script
After modifying or adding strings, run the built-in validator:
```bash
python3 scripts/validate_strings.py
```
This ensures no broken format specifiers, missing positional markers, or quote escaping errors break the CI build.

---

## 10. Universal Search Integration

Users can search for any setting from the main search bar (`FeatureRegistry.kt` -> `SearchSetting`).
To make a UI element highlightable:
1. Declare a `SearchSetting` in the feature's `searchableSettings` inside `FeatureRegistry.kt`:
   ```kotlin
   SearchSetting(
       title = R.string.search_setting_title,
       description = R.string.search_setting_desc,
       key = "setting_highlight_key"
   )
   ```
2. In the target Composable, accept `highlightSetting: String? = null` and attach:
   ```kotlin
   Modifier.highlight(highlightSetting == "setting_highlight_key")
   ```
This automatically handles animated color pulses and scrolls the item into view.

---

## 11. Testing, Build & CI Verification

### Build Environment
- **JDK**: Java 21 (`sourceCompatibility = JavaVersion.VERSION_21`).
- **Android Gradle Plugin / Gradle**: Gradle 9.5+.
- **Default Branch**: `develop`. All PRs and feature branches branch from and target `develop`.

### Verification Commands
Before submitting any task or changes, verify that the build compiles cleanly and tests pass:

```bash
# 1. Run unit tests
./gradlew testDebugUnitTest --stacktrace --no-daemon

# 2. Assemble debug APK
./gradlew assembleDebug --stacktrace --no-daemon

# 3. Validate string resources
python3 scripts/validate_strings.py
```

---

## 12. Quick Reference: Golden Rules & Anti-Patterns

| Category | Golden Rule (DO) | Anti-Pattern (DON'T) |
| :--- | :--- | :--- |
| **Architecture** | Isolate feature logic in handlers under `services/handlers/` or `domain/controller/`. | Bloating `ScreenOffAccessibilityService` or `NotificationListener` with inline feature logic. |
| **State** | Maintain end-to-end flow: `SettingsRepository` -> `ViewModel` -> UI -> `FeatureRegistry`. | Creating disconnected `@Composable` `remember` states that don't persist or sync. |
| **UI Reuse** | Use `RoundedCardContainer`, `IconToggleItem` (with `index`/`count`), and `ui/core/` components. | Creating custom `Card` or `Row` implementations for standard settings toggles. |
| **Imports** | Place all package imports at the top of Kotlin files. | Using inline fully qualified package names in code. |
| **Strings** | Use positional `%1$s` specifiers and escaped apostrophes `\'`. | Using bare `%s` or unescaped single quotes in `strings.xml`. |
| **Permissions** | Gracefully check `hasFeaturePermission()` and show `PermissionsBottomSheet` on failure. | Silently failing or crashing when privileged permissions (ADB/Root/Shizuku) are absent. |
| **Island** | Write plugins that emit `IslandItem` to `IslandCoordinator`. | Writing plugins that manipulate WindowManager or animate Compose stages directly. |
| **QS Tiles** | Follow the 6-layer checklist: Service, Manifest, Strings, Registry, Router, In-App UI. | Registering a tile in the manifest without wiring `QsTileRegistry` and `QuickSettingsTilesSettingsUI`. |
