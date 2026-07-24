package dev.jaimin.auraorbit;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * WidgetStore.java — Persistent Widget Configuration Data Layer
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Manages the list of user-defined app widgets that drive visual clustering on
 * the AuraOrbit sphere. Widgets are persisted as a single JSON blob in
 * {@link SharedPreferences} under {@link #PREF_WIDGETS_JSON}.
 *
 * ─── JSON Shape ─────────────────────────────────────────────────────────────
 *
 * <pre>
 * [
 *   { "name": "Social",  "color": "#7F77DD", "packages": ["com.whatsapp"] },
 *   { "name": "Work",    "color": "#1D9E75", "packages": ["com.slack"]    }
 * ]
 * </pre>
 *
 * ─── Legacy Migration ───────────────────────────────────────────────────────
 *
 * Earlier builds stored widget data as individual SharedPreferences keys
 * ("groups_list", "group_<name>_color", "group_<name>_apps"). On first load,
 * {@link #load(SharedPreferences)} detects the old schema, converts it to JSON,
 * persists the new form, and removes all legacy keys atomically.
 *
 * ─── Single-Membership Invariant ────────────────────────────────────────────
 *
 * Each package name may belong to at most one widget. {@link #upsert} enforces
 * this by removing a package from all OTHER widgets whenever it is added to a
 * widget via upsert.
 *
 * ─── Thread Safety ──────────────────────────────────────────────────────────
 *
 * All methods are stateless pure functions operating on caller-supplied lists
 * and SharedPreferences. Callers are responsible for external synchronisation
 * when multiple threads access the same list or prefs object.
 */
public final class WidgetStore {

    // ─── SharedPreferences key ───────────────────────────────────────────────

    /**
     * SharedPreferences key under which the full widget list is stored as JSON.
     * Keeps compatibility with older versions by using "groups_json".
     */
    public static final String PREF_WIDGETS_JSON = "groups_json";

    // ─── Default color palette (ARGB hex) ────────────────────────────────────

    /**
     * Eight-color palette offered in the widget creation UI. Colors are visually
     * distinct on both light and dark sphere backgrounds.
     */
    public static final String[] PALETTE = {
        "#7F77DD", "#1D9E75", "#D85A30", "#D4537E",
        "#4A90D9", "#C9A227", "#8E5AC8", "#5AA88A"
    };

    // ─── Private legacy key constants ─────────────────────────────────────────

    /** Legacy key: StringSet of group names (v1 schema). */
    private static final String LEGACY_GROUPS_LIST   = "groups_list";

    /** Legacy key prefix for per-group color strings (v1 schema). */
    private static final String LEGACY_GROUP_PREFIX  = "group_";

    /** Suffix for per-group color in the legacy schema. */
    private static final String LEGACY_COLOR_SUFFIX  = "_color";

    /** Suffix for per-group app set in the legacy schema. */
    private static final String LEGACY_APPS_SUFFIX   = "_apps";

    // ═══════════════════════════════════════════════════════════════════════
    //  Data Class
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Represents a single named widget of app icons that will be visually
     * clustered together on the AuraOrbit sphere.
     */
    public static final class Widget {

        /** Human-readable display name (e.g., "Social", "Work"). */
        public String name;

        /** Hex color string (e.g., "#7F77DD") applied to the cluster backdrop. */
        public String color;

        /**
         * Ordered set of package names in this widget.
         * {@link LinkedHashSet} preserves insertion order for deterministic
         * sphere layout while still providing O(1) membership tests.
         */
        public final LinkedHashSet<String> packages = new LinkedHashSet<>();

        /**
         * Creates a new widget with the given display name and color.
         *
         * @param name   Non-null, non-empty display name
         * @param color  Hex color string e.g. "#7F77DD"
         */
        public Widget(String name, String color) {
            this.name  = name;
            this.color = color;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Serialization
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Serializes a list of widgets to a compact JSON string.
     *
     * @param widgets List of widgets to serialize (may be empty, never null)
     * @return JSON array string
     */
    public static String serialize(List<Widget> widgets) {
        try {
            JSONArray root = new JSONArray();
            for (Widget w : widgets) {
                JSONObject obj = new JSONObject();
                obj.put("name",  w.name);
                obj.put("color", w.color);
                JSONArray pkgArray = new JSONArray();
                for (String pkg : w.packages) {
                    pkgArray.put(pkg);
                }
                obj.put("packages", pkgArray);
                root.put(obj);
            }
            return root.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Parses a JSON string produced by {@link #serialize} back into a list of widgets.
     *
     * @param json  JSON string (may be null or malformed)
     * @return Mutable list of parsed widgets; empty on any parse error
     */
    public static List<Widget> parse(String json) {
        List<Widget> result = new ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONArray root = new JSONArray(json);
            for (int i = 0; i < root.length(); i++) {
                try {
                    JSONObject obj = root.getJSONObject(i);
                    String name = obj.optString("name", null);
                    if (name == null || name.isEmpty()) continue;

                    String color = obj.optString("color", "#FFFFFF");
                    Widget w = new Widget(name, color);

                    JSONArray pkgArray = obj.optJSONArray("packages");
                    if (pkgArray != null) {
                        for (int j = 0; j < pkgArray.length(); j++) {
                            String pkg = pkgArray.optString(j, null);
                            if (pkg != null && !pkg.isEmpty()) {
                                w.packages.add(pkg);
                            }
                        }
                    }
                    result.add(w);
                } catch (Exception inner) {
                    // Skip corrupt entry
                }
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Persistence
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Loads the widget list from SharedPreferences.
     *
     * Performs migration from legacy schemas if necessary.
     *
     * @param prefs  SharedPreferences instance
     * @return Mutable list of widgets
     */
    public static List<Widget> load(SharedPreferences prefs) {
        if (prefs.contains(PREF_WIDGETS_JSON)) {
            return parse(prefs.getString(PREF_WIDGETS_JSON, null));
        }

        // Legacy migration
        if (prefs.contains(LEGACY_GROUPS_LIST)) {
            Set<String> groupNames = prefs.getStringSet(LEGACY_GROUPS_LIST,
                    Collections.emptySet());
            List<Widget> migrated = new ArrayList<>();

            for (String groupName : groupNames) {
                String colorKey = LEGACY_GROUP_PREFIX + groupName + LEGACY_COLOR_SUFFIX;
                String appsKey  = LEGACY_GROUP_PREFIX + groupName + LEGACY_APPS_SUFFIX;

                String color = prefs.getString(colorKey, "#FFFFFF");
                Set<String> apps = prefs.getStringSet(appsKey, Collections.emptySet());

                Widget w = new Widget(groupName, color);
                w.packages.addAll(apps);
                migrated.add(w);
            }

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREF_WIDGETS_JSON, serialize(migrated));
            editor.remove(LEGACY_GROUPS_LIST);

            for (String groupName : groupNames) {
                editor.remove(LEGACY_GROUP_PREFIX + groupName + LEGACY_COLOR_SUFFIX);
                editor.remove(LEGACY_GROUP_PREFIX + groupName + LEGACY_APPS_SUFFIX);
            }
            editor.commit();

            return migrated;
        }

        return new ArrayList<>();
    }

    /**
     * Persists the widget list to SharedPreferences as a JSON blob.
     *
     * @param prefs   SharedPreferences instance
     * @param widgets Current widget list to persist
     */
    public static void save(SharedPreferences prefs, List<Widget> widgets) {
        prefs.edit()
             .putString(PREF_WIDGETS_JSON, serialize(widgets))
             .commit();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Query Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Finds the first widget whose name matches the given name (case-insensitive).
     *
     * @param widgets List to search
     * @param name    Name to look up
     * @return Matching {@link Widget}, or {@code null}
     */
    public static Widget find(List<Widget> widgets, String name) {
        if (name == null) return null;
        for (Widget w : widgets) {
            if (w.name.equalsIgnoreCase(name)) return w;
        }
        return null;
    }

    /**
     * Builds a reverse-lookup map from package name to the owning {@link Widget}.
     *
     * @param widgets Source widget list
     * @return Mutable map: package name → owning Widget
     */
    public static Map<String, Widget> packageToWidget(List<Widget> widgets) {
        Map<String, Widget> map = new LinkedHashMap<>();
        for (Widget w : widgets) {
            for (String pkg : w.packages) {
                map.put(pkg, w);
            }
        }
        return map;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Mutation Operations
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Creates a new widget or updates an existing one in-place.
     *
     * @param widgets  Mutable widget list to operate on
     * @param oldName  Name of the existing widget to update, or {@code null} to create
     * @param newName  Desired name for the widget
     * @param color    Hex color string
     * @param packages New package set
     * @return {@code true} if successful
     */
    public static boolean upsert(List<Widget> widgets, String oldName,
                                 String newName, String color, Set<String> packages) {
        if (newName == null || newName.trim().isEmpty()) return false;
        String trimmedNew = newName.trim();

        if (oldName == null) {
            if (find(widgets, trimmedNew) != null) return false;

            Widget fresh = new Widget(trimmedNew, color);
            if (packages != null) fresh.packages.addAll(packages);
            widgets.add(fresh);
            return true;
        } else {
            Widget target = find(widgets, oldName);
            if (target == null) return false;

            if (!target.name.equalsIgnoreCase(trimmedNew)) {
                if (find(widgets, trimmedNew) != null) return false;
            }

            target.name  = trimmedNew;
            target.color = color;
            target.packages.clear();
            if (packages != null) target.packages.addAll(packages);
            return true;
        }
    }

    /**
     * Removes the widget whose name matches the given name (case-insensitive).
     *
     * @param widgets Mutable widget list to operate on
     * @param name    Name of the widget to delete
     * @return {@code true} if found and removed
     */
    public static boolean delete(List<Widget> widgets, String name) {
        Widget target = find(widgets, name);
        if (target == null) return false;
        widgets.remove(target);
        return true;
    }
}
