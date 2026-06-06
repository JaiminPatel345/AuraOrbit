# AuraOrbit v2 — Design Spec

**Date:** 2026-06-07
**Status:** Approved by owner
**Target device:** Samsung Galaxy S25 Ultra (One UI 7, Android 14+), must remain functional for ~5 years of Android/OS updates.

## 1. Goals

1. Fix the two reported failures: **black/empty wallpaper** and **settings changes not applying** until restart.
2. Keep the existing **libGDX 1.13.0** engine (actively maintained, 16KB-page-aligned natives for Android 15+). Rajawali rewrite is explicitly rejected (unmaintained since ~2021).
3. **Folders/groups**: user can create named, colored groups and assign selected apps to them; grouped apps cluster spatially on the sphere with a translucent colored cap behind them.
4. **Material 3 settings redesign**: full-screen app picker with search, dedicated group manager screen, photo-picker background, live summaries. No stacked `AlertDialog`s.
5. Engine feel/correctness: world-space rotation, depth-based icon scale/alpha, rotation-speed preference.

## 2. Non-Goals

- No Play Store release work beyond manifest hygiene.
- No group label text rendered in 3D (color cap + clustering only).
- No automated GL rendering tests (visual verification on emulator/device instead).

## 3. Root-Cause Fixes

### 3.1 Black/empty wallpaper

| Cause | Fix |
|---|---|
| `WallpaperManager.peekDrawable()` throws `SecurityException` on Android 13+; caught → null → opaque black clear color | Remove `peekDrawable` entirely. Background = user image via Photo Picker, else procedural dark gradient (`#0a0a1a` base). |
| Fresh install has no selected apps → nothing rendered | Render GL hint text ("Open AuraOrbit settings to pick apps") + gradient background when zero apps selected. |
| Group backdrop meshes write depth → z-reject icons behind them | All blended materials set `DepthTestAttribute` with `depthMask=false`. Backdrops drawn before decals. |

### 3.2 Settings changes not applying

`SphereEngine.reloadPreferences()` only rebuilds when radius/icon-size deltas exceed thresholds; app-selection and group changes never trigger a rebuild.

Fix (two layers):
1. **`SharedPreferences.OnSharedPreferenceChangeListener`** registered in `SphereEngine.create()` (unregistered in `dispose()`). Any relevant key change sets a dirty flag and posts `rebuildSphere` via `Gdx.app.postRunnable`.
2. **Config snapshot comparison in `resume()`**: snapshot = selected package set + groups JSON + radius + icon size + background path + background toggle + rotation speed + active page + fps. Any difference → rebuild. (Backstop for missed listener events.)

## 4. Engine Changes (`SphereEngine`)

- **World-space rotation:** replace `sphereRotation.mul(tmpQuat)` with `sphereRotation.mulLeft(tmpQuat)` for drag and momentum integration, so screen-relative drag axes remain intuitive at any accumulated orientation.
- **Depth-based icon scale/alpha:** per frame, compute each node's camera-space depth, normalize across sphere diameter; `scale = 0.5 + 0.5 * normalizedDepth`, `alpha = 0.35 + 0.65 * normalizedDepth` (multiplied by page visibility). Applied via `Decal.setDimensions` and decal color alpha.
- **Rotation speed preference** (`pref_rotation_speed`, int 10–300 mapped to 0.1×–3.0×, default 100 → 1.0×): multiplies idle spin and fling sensitivity.
- **Background layer:** `SpriteBatch` draws either the user background texture (center-crop) or a procedural vertical gradient texture. Loaded from `filesDir/background.jpg`.
- **Empty state:** when `appNodes.isEmpty()`, draw hint text with libGDX default `BitmapFont`, centered, plus gradient.
- **Group caps:** keep low-poly sphere mesh per group (≥2 members) at 85% radius, alpha ≈ 0.25, `depthMask=false`; colors come from group data.
- Tap-to-launch, Fibonacci distribution, friction model, page isolation: unchanged behavior (already correct).

## 5. Groups Data Model

Single JSON pref key `groups_json` (replaces `groups_list` / `group_<name>_color` / `group_<name>_apps`):

```json
[{"name": "Social", "color": "#7F77DD", "packages": ["com.whatsapp", "..."]}]
```

- New utility class `GroupStore` (plain Java, no Android imports beyond `SharedPreferences`): parse, serialize, CRUD, one-time migration from the legacy key scheme (migrate then delete old keys).
- Invariants enforced by `GroupStore`: unique group names (case-insensitive), a package belongs to at most one group, deleting a group leaves its apps selected but ungrouped.
- Color palette (8): `#7F77DD #1D9E75 #D85A30 #D4537E #4A90D9 #C9A227 #8E5AC8 #5AA88A`.
- `AppFetcher` reads group assignments via `GroupStore`.
- JUnit tests cover parse/serialize round-trip, migration, rename, delete, single-membership invariant.

## 6. Settings UI (Material 3)

Single activity (`LiveWallpaperSettings`) hosting fragments; Material 3 theme with dynamic color (`DynamicColors.applyToActivityIfAvailable`), dark-mode aware.

### 6.1 Main screen (`PreferenceFragmentCompat`)
- **Apps** → opens App Picker. Summary: "N apps selected".
- **Groups** → opens Group Manager. Summary: "N groups".
- **Background image** → launches `ActivityResultContracts.PickVisualMedia` (zero permissions). On result: decode, downscale to ≤2048px, save as `filesDir/background.jpg`. Summary shows set/not set; long-press or row action to clear.
- **Show background image** toggle (`pref_show_background`, default true).
- Sliders: sphere radius (20–100), icon size (20–100), rotation speed (10–300).
- FPS list (60/90/120) and active home page (0–6) — retained.

### 6.2 App Picker (full-screen fragment)
- Search field filtering by label/package, RecyclerView rows: icon (48dp) + label + checkbox; tap row toggles.
- Selected-count in toolbar; selection persists live as each checkbox is toggled (no separate save step).

### 6.3 Group Manager (full-screen fragment)
- List of groups: color dot, name, member count; FAB "New group".
- Group editor (full-screen): name field, 8-color palette row, searchable member checklist limited to *selected* apps; shows current group badge if an app already belongs to another group (assigning moves it).
- Delete with confirmation; apps remain selected, become ungrouped.

## 7. Manifest / Build Hygiene

- Remove `QUERY_ALL_PACKAGES`; add `<queries><intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent></queries>` (sufficient for `queryIntentActivities`, Play-policy safe).
- No `READ_MEDIA_IMAGES` needed (Photo Picker).
- Keep `minSdk 30`, `targetSdk 35`, `compileSdk 35`, AGP 8.7.3, libGDX 1.13.0.
- Keep `Surface.setFrameRate` 120Hz hint and Samsung battery-optimization code comments.

## 8. Error Handling

- Uninstalled selected app: skipped at fetch (existing behavior); `GroupStore` prunes unknown packages lazily on load.
- Background image decode failure: log, fall back to gradient, summary reverts to "not set".
- Pref listener fires on non-GL thread: all engine mutations marshalled through `Gdx.app.postRunnable`.
- Zero-app selection always renders the hint state, never black.

## 9. Verification Plan

1. `./gradlew assembleDebug` (JDK 21) — must pass.
2. JUnit: `GroupStore` tests pass.
3. Emulator: install, set live wallpaper, screenshot-verify: gradient + hint on fresh install; icons appear after selection **without re-applying the wallpaper**; group colors cluster; background image renders; drag/fling/tap behave; page-swipe fade works.
4. Owner verifies on S25 Ultra hardware (120Hz, One UI).
