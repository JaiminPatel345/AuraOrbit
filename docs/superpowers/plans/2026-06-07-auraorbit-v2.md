# AuraOrbit v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the black-wallpaper and stale-settings bugs, add a robust folders/groups system, and rebuild the settings UI in Material 3 — per `docs/superpowers/specs/2026-06-07-auraorbit-v2-design.md`.

**Architecture:** Keep the libGDX 1.13.0 live-wallpaper engine. Add a JSON-backed `GroupStore` data layer, a file-based `BackgroundStore` (photo-picker image → `filesDir/background.jpg`), live pref-change propagation into the GL thread, and a fragment-based Material 3 settings app.

**Tech Stack:** Java 17, libGDX 1.13.0, AndroidX Preference/Fragment/Activity, Material Components 1.12 (Material 3 + DynamicColors), org.json, JUnit 4.

**Execution model:** Wave 1 = Tasks 1 & 2 in parallel (independent files). Wave 2 = Tasks 3 & 4 in parallel (depend on Wave 1). Wave 3 = integration verification, then Opus QA review loop.

---

## Shared Contracts (all agents read this first)

### SharedPreferences schema (default shared prefs)

| Key | Type | Range/Default | Meaning |
|---|---|---|---|
| `selected_app_packages` | StringSet | empty | packages shown on sphere |
| `groups_json` | String | `[]` | groups (see GroupStore) |
| `pref_show_background` | boolean | true | render background image |
| `pref_background_version` | int | 0 | bumped on image change |
| `pref_sphere_radius` | int | 20–100, 50 | maps to world units 3.0–8.0 |
| `pref_icon_size` | int | 20–100, 50 | maps to world units 0.6–2.0 |
| `pref_rotation_speed` | int | 10–300, 100 | maps to 0.1×–3.0× |
| `pref_active_page` | int | 0–6, 0 | full-render page |
| `pref_target_fps` | String | "120" | setFrameRate hint |

Legacy keys to migrate then delete: `groups_list`, `group_<name>_color`, `group_<name>_apps`. Legacy `pref_keep_wallpaper` is simply abandoned (unused).

### GroupStore public API (Task 1 owns; Tasks 3 & 4 consume)

```java
public final class GroupStore {
    public static final String PREF_GROUPS_JSON = "groups_json";
    public static final String[] PALETTE = {
        "#7F77DD","#1D9E75","#D85A30","#D4537E",
        "#4A90D9","#C9A227","#8E5AC8","#5AA88A"};
    public static final class Group {
        public String name;
        public String color;                                  // "#RRGGBB"
        public final LinkedHashSet<String> packages = new LinkedHashSet<>();
        public Group(String name, String color) { ... }
    }
    public static List<Group> parse(String json);             // tolerant: null/bad → empty list
    public static String serialize(List<Group> groups);
    public static List<Group> load(SharedPreferences prefs);  // runs legacy migration once
    public static void save(SharedPreferences prefs, List<Group> groups);
    public static Group find(List<Group> groups, String name);            // case-insensitive
    public static boolean upsert(List<Group> groups, String oldName,
                                 String newName, String color, Set<String> packages);
        // oldName == null → create. false if newName collides (case-insensitive)
        // with a *different* group. Enforces single membership: packages are
        // removed from every other group.
    public static boolean delete(List<Group> groups, String name);
    public static Map<String, Group> packageToGroup(List<Group> groups);
}
```

### BackgroundStore public API (Task 1 owns; Tasks 3 & 4 consume)

```java
public final class BackgroundStore {
    public static final String FILE_NAME = "background.jpg";
    public static final String PREF_BACKGROUND_VERSION = "pref_background_version";
    public static File file(Context ctx);                     // new File(ctx.getFilesDir(), FILE_NAME)
    public static boolean exists(Context ctx);
    public static boolean saveFromUri(Context ctx, Uri uri);  // decode ≤2048px, JPEG q90, atomic write, bump version
    public static void clear(Context ctx);                    // delete + bump version
}
```

### Resource names Task 2 defines (Task 4 must NOT add its own strings — everything it needs is listed in Task 2)

Layouts Task 2 creates: `fragment_app_picker.xml`, `row_app.xml`, `fragment_group_list.xml`, `row_group.xml`, `fragment_group_edit.xml`, `row_group_member.xml`.

---

## File Structure

```
app/src/main/java/dev/jaimin/auraorbit/
  GroupStore.java              CREATE   (Task 1) JSON group model + migration
  BackgroundStore.java         CREATE   (Task 1) background image file pipeline
  AppFetcher.java              MODIFY   (Task 3) GroupStore wiring; drop peekDrawable; bg texture loader
  SphereEngine.java            MODIFY   (Task 3) rotation, depth fx, live reload, bg, empty state
  MyWallpaperService.java      KEEP     (no changes)
  LiveWallpaperSettings.java   REWRITE  (Task 4) M3 activity + MainSettingsFragment
  ui/AppPickerFragment.java    CREATE   (Task 4)
  ui/GroupListFragment.java    CREATE   (Task 4)
  ui/GroupEditFragment.java    CREATE   (Task 4)
app/src/test/java/dev/jaimin/auraorbit/
  FakeSharedPreferences.java   CREATE   (Task 1)
  GroupStoreTest.java          CREATE   (Task 1)
app/src/main/res/
  values/strings.xml           REWRITE  (Task 2)
  values/colors.xml            REWRITE  (Task 2)
  values/themes.xml            REWRITE  (Task 2)
  xml/preferences.xml          REWRITE  (Task 2)
  layout/*.xml                 CREATE   (Task 2) six layouts listed above
AndroidManifest.xml            MODIFY   (Task 2) <queries> instead of QUERY_ALL_PACKAGES
app/build.gradle               MODIFY   (Task 1) test deps + androidx.activity
```

Build command for every task: `JAVA_HOME=~/.sdkman/candidates/java/21.0.8-tem ./gradlew <target> -q`

---

## Task 1 (Wave 1, dev agent A): Data layer — GroupStore + BackgroundStore, TDD

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/test/java/dev/jaimin/auraorbit/FakeSharedPreferences.java`
- Create: `app/src/test/java/dev/jaimin/auraorbit/GroupStoreTest.java`
- Create: `app/src/main/java/dev/jaimin/auraorbit/GroupStore.java`
- Create: `app/src/main/java/dev/jaimin/auraorbit/BackgroundStore.java`

- [ ] **Step 1: Add dependencies** to `app/build.gradle` `dependencies {}` block:

```gradle
    // ─── Settings UI / Activity Result APIs ───────────────────────────
    implementation 'androidx.activity:activity:1.9.3'

    // ─── Unit tests (GroupStore runs on host JVM; org.json is stubbed
    //     in android.jar, so pull the real artifact for tests) ─────────
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.json:json:20240303'
```

- [ ] **Step 2: Create `FakeSharedPreferences`** — a Map-backed `SharedPreferences` implementation for tests (implement the interface; `edit()` returns an inner `FakeEditor` whose `commit()` copies staged values into the backing map; implement `getStringSet`, `getString`, `getInt`, `getBoolean`, `contains`, `getAll`; listener methods may be no-ops; unused getters return defaults).

- [ ] **Step 3: Write the failing tests** `GroupStoreTest.java` (complete file):

```java
package dev.jaimin.auraorbit;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GroupStoreTest {

    private static GroupStore.Group g(String name, String color, String... pkgs) {
        GroupStore.Group group = new GroupStore.Group(name, color);
        group.packages.addAll(new LinkedHashSet<>(List.of(pkgs)));
        return group;
    }

    @Test public void serializeParseRoundTrip() {
        List<GroupStore.Group> in = new ArrayList<>(List.of(
                g("Social", "#7F77DD", "com.whatsapp", "com.instagram.android"),
                g("Work", "#1D9E75", "com.slack")));
        List<GroupStore.Group> out = GroupStore.parse(GroupStore.serialize(in));
        assertEquals(2, out.size());
        assertEquals("Social", out.get(0).name);
        assertEquals("#7F77DD", out.get(0).color);
        assertEquals(Set.of("com.whatsapp", "com.instagram.android"), out.get(0).packages);
        assertEquals(Set.of("com.slack"), out.get(1).packages);
    }

    @Test public void parseGarbageReturnsEmpty() {
        assertTrue(GroupStore.parse(null).isEmpty());
        assertTrue(GroupStore.parse("").isEmpty());
        assertTrue(GroupStore.parse("not json").isEmpty());
        assertTrue(GroupStore.parse("{\"a\":1}").isEmpty());
    }

    @Test public void upsertCreatesGroup() {
        List<GroupStore.Group> groups = new ArrayList<>();
        assertTrue(GroupStore.upsert(groups, null, "Games", "#D85A30",
                Set.of("com.supercell.clashofclans")));
        assertEquals(1, groups.size());
        assertEquals("Games", groups.get(0).name);
    }

    @Test public void upsertRejectsDuplicateNameCaseInsensitive() {
        List<GroupStore.Group> groups = new ArrayList<>(List.of(g("Social", "#7F77DD")));
        assertFalse(GroupStore.upsert(groups, null, "sOcIaL", "#1D9E75", Set.of()));
        assertEquals(1, groups.size());
    }

    @Test public void upsertRenameKeepsIdentity() {
        List<GroupStore.Group> groups = new ArrayList<>(
                List.of(g("Social", "#7F77DD", "com.whatsapp")));
        assertTrue(GroupStore.upsert(groups, "Social", "Chat", "#D4537E",
                Set.of("com.whatsapp")));
        assertEquals(1, groups.size());
        assertEquals("Chat", groups.get(0).name);
        assertEquals("#D4537E", groups.get(0).color);
    }

    @Test public void upsertEnforcesSingleMembership() {
        List<GroupStore.Group> groups = new ArrayList<>(List.of(
                g("Social", "#7F77DD", "com.whatsapp", "com.telegram"),
                g("Work", "#1D9E75")));
        assertTrue(GroupStore.upsert(groups, "Work", "Work", "#1D9E75",
                Set.of("com.whatsapp")));
        Map<String, GroupStore.Group> map = GroupStore.packageToGroup(groups);
        assertEquals("Work", map.get("com.whatsapp").name);
        assertEquals("Social", map.get("com.telegram").name);
        assertEquals(Set.of("com.telegram"), GroupStore.find(groups, "Social").packages);
    }

    @Test public void deleteRemovesGroup() {
        List<GroupStore.Group> groups = new ArrayList<>(
                List.of(g("Social", "#7F77DD", "com.whatsapp")));
        assertTrue(GroupStore.delete(groups, "social"));   // case-insensitive
        assertTrue(groups.isEmpty());
        assertFalse(GroupStore.delete(groups, "Social"));  // already gone
    }

    @Test public void saveLoadThroughPrefs() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        List<GroupStore.Group> in = new ArrayList<>(List.of(g("Social", "#7F77DD", "a.b")));
        GroupStore.save(prefs, in);
        List<GroupStore.Group> out = GroupStore.load(prefs);
        assertEquals(1, out.size());
        assertEquals(Set.of("a.b"), out.get(0).packages);
    }

    @Test public void loadMigratesLegacySchemaOnce() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit()
                .putStringSet("groups_list", new HashSet<>(Set.of("Social")))
                .putString("group_Social_color", "#FF6B6B")
                .putStringSet("group_Social_apps", new HashSet<>(Set.of("com.whatsapp")))
                .commit();
        List<GroupStore.Group> out = GroupStore.load(prefs);
        assertEquals(1, out.size());
        assertEquals("Social", out.get(0).name);
        assertEquals("#FF6B6B", out.get(0).color);
        assertEquals(Set.of("com.whatsapp"), out.get(0).packages);
        // legacy keys gone, JSON written
        assertFalse(prefs.contains("groups_list"));
        assertFalse(prefs.contains("group_Social_color"));
        assertFalse(prefs.contains("group_Social_apps"));
        assertTrue(prefs.contains(GroupStore.PREF_GROUPS_JSON));
    }
}
```

- [ ] **Step 4: Run tests, verify they FAIL** (class missing):
`JAVA_HOME=~/.sdkman/candidates/java/21.0.8-tem ./gradlew testDebugUnitTest -q` → compilation error referencing `GroupStore`.

- [ ] **Step 5: Implement `GroupStore`** exactly per the API contract above. JSON shape: `[{"name":"Social","color":"#7F77DD","packages":["com.whatsapp"]}]`. `parse` wraps everything in try/catch returning partial/empty results, skipping entries without a name. `load`: if `PREF_GROUPS_JSON` absent and `groups_list` present → build groups from legacy keys, `save()`, and remove the legacy keys in one editor commit. `upsert`: validate non-empty trimmed `newName`; collision check against other groups case-insensitively; mutate-in-place (replace name/color/packages of the matched group or add new); then strip the assigned packages from all other groups. No Android imports other than `android.content.SharedPreferences`.

- [ ] **Step 6: Run tests, verify PASS**: same command → `BUILD SUCCESSFUL`, all 9 tests green.

- [ ] **Step 7: Implement `BackgroundStore`** per contract. `saveFromUri`: two-pass decode (`inJustDecodeBounds` then power-of-2 `inSampleSize` so max dimension ≤ 2048), compress JPEG quality 90 to `background.jpg.tmp` in `filesDir`, `renameTo` final, then increment `PREF_BACKGROUND_VERSION` int pref. Returns false (and cleans tmp) on any exception. `clear`: delete file + bump version. Uses `PreferenceManager.getDefaultSharedPreferences`.

- [ ] **Step 8: Full build + commit**:
`JAVA_HOME=... ./gradlew testDebugUnitTest assembleDebug -q` → SUCCESS, then
`git add -A && git commit -m "feat: GroupStore JSON group model with migration + BackgroundStore"`

---

## Task 2 (Wave 1, dev agent B): Resources, theme, manifest, layouts

**Files:**
- Rewrite: `app/src/main/res/values/strings.xml`, `colors.xml`, `themes.xml`, `app/src/main/res/xml/preferences.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: 6 layout files listed in Shared Contracts

- [ ] **Step 1: `AndroidManifest.xml`** — delete the `QUERY_ALL_PACKAGES` permission element and its comment; add after the `<uses-feature>` elements:

```xml
    <!-- Package-visibility: we only need apps with a launcher activity.
         This replaces QUERY_ALL_PACKAGES (Play-policy restricted). -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>
```

- [ ] **Step 2: `themes.xml`** — replace file content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Base application theme — Material 3, follows system dark mode.
         DynamicColors.applyToActivityIfAvailable() overlays Material You
         palettes at runtime on Android 12+. -->
    <style name="Theme.AuraOrbit" parent="Theme.Material3.DayNight.NoActionBar" />

    <!-- Settings theme: Material 3 with an action bar for fragment titles
         and back navigation. -->
    <style name="Theme.AuraOrbit.Settings" parent="Theme.Material3.DayNight">
        <item name="preferenceTheme">@style/PreferenceThemeOverlay</item>
    </style>
</resources>
```

- [ ] **Step 3: `colors.xml`** — replace file content (palette from spec §5; names match order):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Group palette — single source of truth is GroupStore.PALETTE;
         this array mirrors it for UI pickers. Keep both in sync. -->
    <string-array name="group_color_hex">
        <item>#7F77DD</item>
        <item>#1D9E75</item>
        <item>#D85A30</item>
        <item>#D4537E</item>
        <item>#4A90D9</item>
        <item>#C9A227</item>
        <item>#8E5AC8</item>
        <item>#5AA88A</item>
    </string-array>
</resources>
```

- [ ] **Step 4: `strings.xml`** — replace file content. Keep every existing key that survives (app_name, settings_title, wallpaper_description, pref_category/select/manage strings, fps arrays, btn_*, toast_*, summary_*, dialog_delete_confirm*, dialog_group_name_hint, dialog_edit_group, dialog_create_group, **and — because the old `LiveWallpaperSettings.java` still compiles against them until Task 4 lands — `dialog_select_apps_title`, `dialog_group_color`, `dialog_assign_apps`**), update `group_color_names` to: Aura Violet, Emerald, Ember Orange, Rose, Azure, Gold, Royal Purple, Sage — and add:

```xml
    <string name="pref_category_background">Background</string>
    <string name="pref_background_title">Background image</string>
    <string name="summary_background_set">Custom image set — tap to replace or remove</string>
    <string name="summary_background_not_set">Tap to choose an image from your gallery</string>
    <string name="pref_show_background_title">Show background image</string>
    <string name="pref_show_background_summary">When off, a dark gradient is used instead</string>
    <string name="pref_category_sphere">Sphere</string>
    <string name="pref_rotation_speed_title">Rotation speed</string>
    <string name="pref_rotation_speed_summary">Idle spin and fling momentum multiplier (100 = 1×)</string>
    <string name="background_replace">Replace image</string>
    <string name="background_remove">Remove image</string>
    <string name="toast_background_saved">Background image saved.</string>
    <string name="toast_background_failed">Could not load that image.</string>
    <string name="search_apps_hint">Search apps…</string>
    <string name="title_select_apps">Select apps</string>
    <string name="title_groups">Groups</string>
    <string name="title_new_group">New group</string>
    <string name="title_edit_group">Edit group</string>
    <string name="fab_new_group">New group</string>
    <string name="empty_groups">No groups yet.\nTap + to create one.</string>
    <string name="member_in_other_group">In %s — saving will move it</string>
    <string name="group_member_count">%d apps</string>
    <string name="wallpaper_hint_no_apps">Open AuraOrbit settings\nto pick your apps</string>
    <string name="selected_count">%d selected</string>
```

- [ ] **Step 5: `preferences.xml`** — replace file content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <PreferenceCategory android:title="@string/pref_category_apps">
        <Preference
            android:key="pref_select_apps"
            android:title="@string/pref_select_apps_title"
            android:summary="@string/pref_select_apps_summary" />
    </PreferenceCategory>

    <PreferenceCategory android:title="@string/pref_category_groups">
        <Preference
            android:key="pref_manage_groups"
            android:title="@string/pref_manage_groups_title"
            android:summary="@string/pref_manage_groups_summary" />
    </PreferenceCategory>

    <PreferenceCategory android:title="@string/pref_category_background">
        <Preference
            android:key="pref_background_image"
            android:title="@string/pref_background_title"
            android:summary="@string/summary_background_not_set" />
        <SwitchPreferenceCompat
            android:key="pref_show_background"
            android:title="@string/pref_show_background_title"
            android:summary="@string/pref_show_background_summary"
            android:defaultValue="true" />
    </PreferenceCategory>

    <PreferenceCategory android:title="@string/pref_category_sphere">
        <SeekBarPreference
            android:key="pref_sphere_radius"
            android:title="@string/pref_sphere_radius_title"
            android:summary="@string/pref_sphere_radius_summary"
            android:defaultValue="50" android:max="100"
            app:min="20" app:showSeekBarValue="true" />
        <SeekBarPreference
            android:key="pref_icon_size"
            android:title="@string/pref_icon_size_title"
            android:summary="@string/pref_icon_size_summary"
            android:defaultValue="50" android:max="100"
            app:min="20" app:showSeekBarValue="true" />
        <SeekBarPreference
            android:key="pref_rotation_speed"
            android:title="@string/pref_rotation_speed_title"
            android:summary="@string/pref_rotation_speed_summary"
            android:defaultValue="100" android:max="300"
            app:min="10" app:showSeekBarValue="true" />
    </PreferenceCategory>

    <PreferenceCategory android:title="@string/pref_category_performance">
        <ListPreference
            android:key="pref_target_fps"
            android:title="@string/pref_target_fps_title"
            android:summary="@string/pref_target_fps_summary"
            android:entries="@array/target_fps_entries"
            android:entryValues="@array/target_fps_values"
            android:defaultValue="120" />
        <SeekBarPreference
            android:key="pref_active_page"
            android:title="@string/pref_active_page_title"
            android:summary="@string/pref_active_page_summary"
            android:defaultValue="0" android:max="6"
            app:min="0" app:showSeekBarValue="true" />
    </PreferenceCategory>
</PreferenceScreen>
```

- [ ] **Step 6: Layouts.** Create the six files. Material 3 components throughout; 16dp screen padding; list rows 56–64dp tall.

`layout/fragment_app_picker.xml`: vertical `LinearLayout` → `TextInputLayout` (style `@style/Widget.Material3.TextInputLayout.OutlinedBox`, margin 16dp, `startIconDrawable="@android:drawable/ic_menu_search"`) wrapping a single-line `TextInputEditText` `@+id/search_input` with `hint="@string/search_apps_hint"` → `RecyclerView` `@+id/app_list` (match_parent, `clipToPadding=false`, paddingBottom 16dp).

`layout/row_app.xml`: horizontal `LinearLayout` 64dp tall, `gravity=center_vertical`, padding 16/8: `ImageView` `@+id/app_icon` 44×44dp → vertical `LinearLayout` weight=1 marginStart=16dp holding `TextView` `@+id/app_label` (`textAppearanceBodyLarge`) and `TextView` `@+id/app_group_badge` (`textAppearanceBodySmall`, `visibility=gone`) → `com.google.android.material.checkbox.MaterialCheckBox` `@+id/app_check`.

`layout/fragment_group_list.xml`: `CoordinatorLayout` → `RecyclerView` `@+id/group_list` (match_parent, paddingBottom 88dp, `clipToPadding=false`) + centered `TextView` `@+id/empty_view` (`text="@string/empty_groups"`, `textAlignment=center`, `textAppearanceBodyLarge`, `visibility=gone`) + `FloatingActionButton` `@+id/fab_add` (bottom|end margin 16dp, `contentDescription="@string/fab_new_group"`, `srcCompat="@android:drawable/ic_input_add"`).

`layout/row_group.xml`: horizontal `LinearLayout` 64dp, padding 16/8, `gravity=center_vertical`: `View` `@+id/group_color_dot` 24×24dp → vertical `LinearLayout` weight=1 marginStart=16dp with `TextView` `@+id/group_name` (`textAppearanceBodyLarge`) and `TextView` `@+id/group_count` (`textAppearanceBodySmall`).

`layout/fragment_group_edit.xml`: vertical `LinearLayout` padding 16dp → `TextInputLayout` (OutlinedBox, `hint="@string/dialog_group_name_hint"`) wrapping `TextInputEditText` `@+id/group_name_input` (singleLine) → `HorizontalScrollView` (marginTop 16dp, `scrollbars=none`) wrapping horizontal `LinearLayout` `@+id/color_row` (circles added in code) → `TextInputLayout` (OutlinedBox, marginTop 16dp) wrapping `TextInputEditText` `@+id/member_search_input` (`hint="@string/search_apps_hint"`, singleLine) → `RecyclerView` `@+id/member_list` (height 0dp weight=1, marginTop 8dp) → horizontal `LinearLayout` (marginTop 8dp, `gravity=end`): `com.google.android.material.button.MaterialButton` `@+id/btn_delete` (style `@style/Widget.Material3.Button.TextButton`, `text="@string/btn_delete_group"`, `visibility=gone`, red text color `#D85A30`) + `MaterialButton` `@+id/btn_save` (`text="@string/btn_save"`, marginStart 8dp).

`layout/row_group_member.xml`: same structure as `row_app.xml` with ids `member_icon`, `member_label`, `member_subtitle` (the "in other group" hint), `member_check`.

- [ ] **Step 7: Build + commit.** `assembleDebug` must succeed even though Java still references old keys (`pref_keep_wallpaper` is read via string literal, not R — no compile break; `LiveWallpaperSettings` references `R.string` keys that must still exist — all kept keys cover it; if any `R.*` reference breaks, keep that string in strings.xml rather than editing Java — Task 4 rewrites that file).
`git add -A && git commit -m "feat: Material 3 resources, layouts, manifest queries"`

---

## Task 3 (Wave 2, dev agent C): Engine fixes — SphereEngine + AppFetcher

**Files:**
- Modify: `app/src/main/java/dev/jaimin/auraorbit/AppFetcher.java`
- Modify: `app/src/main/java/dev/jaimin/auraorbit/SphereEngine.java`

### AppFetcher changes

- [ ] **Step 1:** Replace `loadGroupMappings` usage: in `fetchSelectedApps`, build the mapping via `GroupStore`:

```java
Map<String, GroupStore.Group> packageToGroup =
        GroupStore.packageToGroup(GroupStore.load(prefs));
...
GroupStore.Group g = packageToGroup.get(packageName);
if (g != null) { node.groupId = g.name; node.groupColorHex = g.color; }
```
Delete `loadGroupMappings` and the `WallpaperManager` import. **KEEP the now-unused constants `PREF_GROUPS_LIST` and `PREF_GROUP_PREFIX`** (mark `@Deprecated`) — the old `LiveWallpaperSettings.java` still references them and Task 4 runs in parallel; the orchestrator deletes them in Task 5.

- [ ] **Step 2:** Delete `fetchSystemWallpaper` entirely. Add:

```java
/**
 * Loads the user-selected background image (saved by BackgroundStore)
 * as a libGDX Texture. MUST be called on the GL thread.
 * @return Texture or null when no image is set / decode fails.
 */
public static Texture loadBackgroundTexture(Context context) {
    java.io.File f = BackgroundStore.file(context);
    if (!f.exists()) return null;
    try {
        Texture t = new Texture(Gdx.files.absolute(f.getAbsolutePath()));
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return t;
    } catch (Exception e) {
        Log.e(TAG, "Failed to load background texture", e);
        return null;
    }
}
```

### SphereEngine changes

- [ ] **Step 3: World-space rotation.** In `pan()` and in `updatePhysics()` replace both `sphereRotation.mul(tmpQuat)` with `sphereRotation.mulLeft(tmpQuat)` (comment: post-multiply rotates in sphere-local axes which skews drag once orientation accumulates; pre-multiply = world axes = screen-intuitive).

- [ ] **Step 4: Config read + rotation speed.** Extract all pref reads into `private void readConfig(SharedPreferences prefs)` setting fields: `showBackground` (`pref_show_background`, default true), `activePage`, `sphereRadius`, `iconSize`, and new `rotationSpeedFactor = prefs.getInt("pref_rotation_speed", 100) / 100f` (clamped 0.1–3.0). Idle spin uses `IDLE_SPIN_SPEED * rotationSpeedFactor`; fling uses `FLING_SENSITIVITY * rotationSpeedFactor`.

- [ ] **Step 5: Background + empty-state resources.** In `create()`:
  - build `gradientTexture`: 1×256 `Pixmap` (RGBA8888), rows lerp `#05050F` (top) → `#1A1A33` (bottom); `Texture` with Linear filter. Always created.
  - `backgroundTexture = showBackground ? AppFetcher.loadBackgroundTexture(context) : null;`
  - `hintFont = new BitmapFont(); hintFont.getData().setScale(Gdx.graphics.getDensity() * 1.1f);` and a `GlyphLayout hintLayout` for the string `context.getString(R.string.wallpaper_hint_no_apps)`.

  `renderBackground()` draws `backgroundTexture` if non-null else `gradientTexture`, full-screen (existing draw call). After the 3D layers, when `appNodes.isEmpty()`, draw the hint centered:

```java
private void renderEmptyHint() {
    spriteBatch.begin();
    hintFont.setColor(1f, 1f, 1f, 0.85f);
    hintFont.draw(spriteBatch, hintLayout,
            (Gdx.graphics.getWidth() - hintLayout.width) / 2f,
            (Gdx.graphics.getHeight() + hintLayout.height) / 2f);
    spriteBatch.end();
}
```
  Remove `keepSystemWallpaper` field everywhere; clear color becomes opaque `glClearColor(0.02f, 0.02f, 0.06f, 1f)`.

- [ ] **Step 6: Depth-based icon scale/alpha.** In `renderDecals()`, before `rotatedPos.scl(pageVisibility)`:

```java
// Normalized camera-facing depth: rotatedPos.z ∈ [-R, +R]; camera sits on +Z.
float nd = MathUtils.clamp((rotatedPos.z / sphereRadius + 1f) * 0.5f, 0f, 1f);
float depthScale = 0.5f + 0.5f * nd;          // far icons half size  (spec §4)
float depthAlpha = 0.35f + 0.65f * nd;        // far icons dimmed
```
then `decal.setDimensions(iconSize * depthScale * pageVisibility, ...)` and `decal.setColor(1f, 1f, 1f, depthAlpha * pageVisibility)`.

- [ ] **Step 7: Backdrop depth-write fix.** In `buildGroupBackdrops()` material, add `new DepthTestAttribute(GL20.GL_LEQUAL, false)` (import `com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute`) so translucent caps never z-reject icons; change alpha 0.35 → 0.25 (both ColorAttribute color and BlendingAttribute opacity).

- [ ] **Step 8: Live settings propagation.**
  - Field: `private final Set<String> RELEVANT_KEYS = Set.of("selected_app_packages", GroupStore.PREF_GROUPS_JSON, "pref_show_background", BackgroundStore.PREF_BACKGROUND_VERSION, "pref_sphere_radius", "pref_icon_size", "pref_rotation_speed", "pref_active_page");`
  - Field `private SharedPreferences.OnSharedPreferenceChangeListener prefListener;` — **strong reference is mandatory** (SharedPreferences holds listeners weakly).
  - In `create()`: `prefListener = (p, key) -> { if (key != null && RELEVANT_KEYS.contains(key)) Gdx.app.postRunnable(this::applyConfig); }; prefs.registerOnSharedPreferenceChangeListener(prefListener);`
  - `applyConfig()` (GL thread): re-read config, dispose icon textures + group models + backgroundTexture, re-fetch apps, redistribute, recreate decals + backdrops, reload background, reposition camera. (Refactor: `create()` and `applyConfig()` share a `rebuildScene()` helper; `rebuildSphere()`/`reloadPreferences()` are replaced by this.)
  - `resume()`: compute `configSnapshot()` string = join of all relevant pref values (for the StringSet, sort first); if different from the stored one, run `applyConfig` via postRunnable. Update stored snapshot inside `applyConfig`.
  - `dispose()`: `prefs.unregisterOnSharedPreferenceChangeListener(prefListener)`, dispose `hintFont`, `gradientTexture`.

- [ ] **Step 9: Build + commit.**
`./gradlew assembleDebug -q` → SUCCESS.
`git add -A && git commit -m "fix: world-space rotation, depth-scaled icons, live settings reload, photo background, empty-state hint"`

---

## Task 4 (Wave 2, dev agent D): Material 3 settings UI

**Files:**
- Rewrite: `app/src/main/java/dev/jaimin/auraorbit/LiveWallpaperSettings.java`
- Create: `app/src/main/java/dev/jaimin/auraorbit/ui/AppPickerFragment.java`
- Create: `app/src/main/java/dev/jaimin/auraorbit/ui/GroupListFragment.java`
- Create: `app/src/main/java/dev/jaimin/auraorbit/ui/GroupEditFragment.java`

Use ONLY strings/layouts defined in Task 2. Navigation pattern everywhere:

```java
getParentFragmentManager().beginTransaction()
        .replace(android.R.id.content, fragment)
        .addToBackStack(null)
        .commit();
```

- [ ] **Step 1: `LiveWallpaperSettings`** (full rewrite, ~80 lines): `AppCompatActivity`; `onCreate`: `DynamicColors.applyToActivityIfAvailable(this);` then if `savedInstanceState == null` load `new MainSettingsFragment()` into `android.R.id.content`. Enable action-bar up arrow when back stack non-empty via `addOnBackStackChangedListener`; `onSupportNavigateUp()` pops the back stack. Keep `MainSettingsFragment` as a public static inner class:
  - `onCreatePreferences`: `setPreferencesFromResource(R.xml.preferences, rootKey)`.
  - Photo picker: `ActivityResultLauncher<PickVisualMediaRequest> pickMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> { if (uri != null) saveBackground(uri); });` `saveBackground` runs `BackgroundStore.saveFromUri` on a single-thread `Executor` field, posts result to main thread (`requireActivity().runOnUiThread`) → toast `toast_background_saved`/`toast_background_failed` + `updateSummaries()`. Guard with `isAdded()`.
  - Click handlers: `pref_select_apps` → navigate `AppPickerFragment`; `pref_manage_groups` → navigate `GroupListFragment`; `pref_background_image` → if `BackgroundStore.exists()` show `MaterialAlertDialogBuilder` with items {`background_replace`, `background_remove`} (replace → launch picker with `ImageOnly`; remove → `BackgroundStore.clear` + summaries) else launch picker directly.
  - `updateSummaries()` in `onResume`: apps count (`summary_apps_selected`/`summary_no_apps`), groups count via `GroupStore.load` (`summary_groups_count`/`summary_no_groups`), background (`summary_background_set`/`summary_background_not_set`). Set activity title to `settings_title`.

- [ ] **Step 2: `AppPickerFragment`** (~200 lines): inflates `fragment_app_picker`. Loads `AppFetcher.getAllLaunchableApps` + labels + icons on a background `Executor`, then binds adapter on main thread (guard `isAdded()`). Pre-computes a row model list `{packageName, label, Drawable icon, String groupName?}` (groupName from `GroupStore.packageToGroup`, shown in `app_group_badge` when non-null). Search box filters by label/package (lowercase contains), updates adapter via `notifyDataSetChanged` on a filtered copy. Checkbox/row toggle persists IMMEDIATELY:

```java
Set<String> sel = new HashSet<>(prefs.getStringSet(AppFetcher.PREF_SELECTED_APPS,
        new HashSet<>()));
if (checked) sel.add(pkg); else sel.remove(pkg);
prefs.edit().putStringSet(AppFetcher.PREF_SELECTED_APPS, sel).apply();
// NOTE: must always write a NEW set instance — mutating the returned set
// is a documented SharedPreferences trap and persists nothing.
```
Title = `getString(R.string.selected_count, sel.size())`, updated on every toggle and in `onResume`.

- [ ] **Step 3: `GroupListFragment`** (~120 lines): inflates `fragment_group_list`; title `title_groups`. `onResume` reloads `GroupStore.load(prefs)` into adapter; `empty_view` visible when empty. Row: `group_color_dot` background = oval `GradientDrawable` of `group.color`; `group_name`; `group_count` = `group_member_count` format. Row click → `GroupEditFragment.newInstance(group.name)`; FAB → `GroupEditFragment.newInstance(null)`.

- [ ] **Step 4: `GroupEditFragment`** (~260 lines): arg `"group_name"` (null = create); title `title_new_group`/`title_edit_group`.
  - Loads groups; when editing, prefills name, color, members; `btn_delete` visible.
  - Color row: 8 circles (40dp `GradientDrawable` ovals from `R.array.group_color_hex`, 8dp margins); selected one gets a 3dp white stroke; clicking reselects + redraws all.
  - Member list: rows for **selected apps only** (read `selected_app_packages`, resolve label+icon, sort by label); checkbox = membership in the working set; `member_subtitle` shows `member_in_other_group` (formatted with the other group's name) when the app currently belongs to a different group; search filters like Task 4 Step 2.
  - Save: validate non-empty name else toast `toast_group_name_empty`; `GroupStore.upsert(groups, oldName, newName, color, workingSet)`; on false → toast `toast_group_exists`; on success `GroupStore.save`, toast `toast_saved`, pop back stack.
  - Delete: `MaterialAlertDialogBuilder` title `dialog_delete_confirm` (formatted), message `dialog_delete_confirm_msg`; confirm → `GroupStore.delete` + `save`, toast `toast_group_deleted`, pop back stack.

- [ ] **Step 5: Build + commit.**
`./gradlew assembleDebug -q` → SUCCESS.
`git add -A && git commit -m "feat: Material 3 settings — app picker with search, group manager, photo-picker background"`

---

## Task 5 (Wave 3, orchestrator): Integration verification on emulator

- [ ] `./gradlew testDebugUnitTest assembleDebug` → all green.
- [ ] `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- [ ] Fresh-install state: clear data, open live-wallpaper preview:
`adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER --es android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT dev.jaimin.auraorbit/.MyWallpaperService` → screenshot → expect **gradient + hint text, never black**.
- [ ] Open settings (`adb shell am start -n dev.jaimin.auraorbit/.LiveWallpaperSettings`), drive UI (input taps / uiautomator dump for coordinates): select ~8 apps, create 2 groups with different colors, assign apps.
- [ ] Return to preview → screenshot → icons visible, group color caps visible, **without re-applying the wallpaper** (live reload check).
- [ ] Drag (input swipe) → rotation; tap an icon → app launches.
- [ ] Logcat sweep: `adb logcat -d | grep -E "AuraOrbit|FATAL|AndroidRuntime"` → no crashes/exceptions.
- [ ] Cleanup: delete the `@Deprecated` `PREF_GROUPS_LIST`/`PREF_GROUP_PREFIX` constants from `AppFetcher` and any strings no longer referenced (`dialog_select_apps_title`, `dialog_group_color`, `dialog_assign_apps`, `btn_add_group`, `dialog_create_group` if unused) — verify with a grep before deleting.
- [ ] Commit any fixups.

---

## Task 6 (Wave 3): QA loop — Opus QA agent

- [ ] Dispatch QA agent (model: opus). Inputs: spec path, this plan path, `git diff cd07d7e..HEAD` scope. Mandate: **report only, no code changes** — correctness bugs, lifecycle/threading issues (GL thread vs main thread, listener leaks, fragment lifecycle), GL resource leaks (undisposed textures on rebuild), pref-schema mismatches, UX gaps vs spec. Output: severity-ranked findings list.
- [ ] Dispatch dev agent(s) (model: sonnet) to fix every confirmed finding; re-run build + unit tests.
- [ ] Repeat QA → fix until QA reports no high/medium findings.
- [ ] Final emulator re-verification (Task 5 checklist) + commit.
