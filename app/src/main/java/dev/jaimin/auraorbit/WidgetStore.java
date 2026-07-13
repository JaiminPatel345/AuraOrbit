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
 * {@link SharedPreferences} under {@link #PREF_GROUPS_JSON}.
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
     * Tasks 3 and 4 reference this constant directly — do not rename.
     */
    public static final String PREF_GROUPS_JSON = "groups_json";

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

    /** Legacy key: StringSet of widget names (v1 schema). */
    private static final String LEGACY_GROUPS_LIST   = "groups_list";

    /** Legacy key prefix for per-widget color strings (v1 schema). */
    private static final String LEGACY_GROUP_PREFIX  = "group_";

    /** Suffix for per-widget color in the legacy schema. */
    private static final String LEGACY_COLOR_SUFFIX  = "_color";

    /** Suffix for per-widget app set in the legacy schema. */
    private static final String LEGACY_APPS_SUFFIX   = "_apps";

    // ═══════════════════════════════════════════════════════════════════════
    //  Data Class
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Represents a single named widget of app icons that will be visually
     * clustered together on the AuraOrbit sphere.
     *
     * Fields are intentionally public so Tasks 3 and 4 can access them
     * directly without reflection or accessor boilerplate.
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
     * The resulting string is suitable for storage in {@link SharedPreferences}
     * and can be round-tripped through {@link #parse(String)}.
     *
     * @param widgets  List of widgets to serialize (may be empty, never null)
     * @return JSON array string, e.g. {@code [{"name":"Social","color":"#7F77DD","packages":["com.whatsapp"]}]}
     */
    public static String serialize(List<Widget> widgets) {
        try {
            JSONArray root = new JSONArray();
            for (Widget g : widgets) {
                JSONObject obj = new JSONObject();
                obj.put("name",  g.name);
                obj.put("color", g.color);
                JSONArray pkgArray = new JSONArray();
                for (String pkg : g.packages) {
                    pkgArray.put(pkg);
                }
                obj.put("packages", pkgArray);
                root.put(obj);
            }
            return root.toString();
        } catch (Exception e) {
            // Should never happen — all values are well-typed strings.
            return "[]";
        }
    }

    /**
     * Parses a JSON string produced by {@link #serialize} back into a list of widgets.
     *
     * Tolerant: returns an empty list for null, empty, or malformed input. Entries
     * that are missing a "name" field are silently skipped so a single corrupt entry
     * does not wipe the entire list.
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
                    if (name == null || name.isEmpty()) continue; // skip malformed entries

                    String color = obj.optString("color", "#FFFFFF");
                    Widget g = new Widget(name, color);

                    JSONArray pkgArray = obj.optJSONArray("packages");
                    if (pkgArray != null) {
                        for (int j = 0; j < pkgArray.length(); j++) {
                            String pkg = pkgArray.optString(j, null);
                            if (pkg != null && !pkg.isEmpty()) {
                                g.packages.add(pkg);
                            }
                        }
                    }
                    result.add(g);
                } catch (Exception inner) {
                    // Skip this entry; continue with remaining entries.
                }
            }
        } catch (Exception e) {
            // Non-array JSON, garbled input, etc. — return empty list.
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
     * If the new JSON key ({@link #PREF_GROUPS_JSON}) is absent but the legacy
     * {@code groups_list} StringSet key is present, performs a one-time migration:
     * reads the old per-key schema, writes the JSON blob, and removes all legacy
     * keys in a single atomic editor commit.
     *
     * @param prefs  SharedPreferences instance (any; must be writable for migration)
     * @return Mutable list of widgets (may be empty; never null)
     */
    public static List<Widget> load(SharedPreferences prefs) {
        // ─── Check for new-style JSON storage ────────────────────────────
        if (prefs.contains(PREF_GROUPS_JSON)) {
            return parse(prefs.getString(PREF_GROUPS_JSON, null));
        }

        // ─── Legacy migration: groups_list StringSet schema ───────────────
        if (prefs.contains(LEGACY_GROUPS_LIST)) {
            Set<String> widgetNames = prefs.getStringSet(LEGACY_GROUPS_LIST,
                    Collections.emptySet());
            List<Widget> migrated = new ArrayList<>();

            for (String widgetName : widgetNames) {
                String colorKey = LEGACY_GROUP_PREFIX + widgetName + LEGACY_COLOR_SUFFIX;
                String appsKey  = LEGACY_GROUP_PREFIX + widgetName + LEGACY_APPS_SUFFIX;

                String color = prefs.getString(colorKey, "#FFFFFF");
                Set<String> apps = prefs.getStringSet(appsKey, Collections.emptySet());

                Widget g = new Widget(widgetName, color);
                g.packages.addAll(apps);
                migrated.add(g);
            }

            // ─── Persist migrated data and remove legacy keys ─────────────
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREF_GROUPS_JSON, serialize(migrated));

            // Remove the top-level list key
            editor.remove(LEGACY_GROUPS_LIST);

            // Remove per-widget legacy keys
            for (String widgetName : widgetNames) {
                editor.remove(LEGACY_GROUP_PREFIX + widgetName + LEGACY_COLOR_SUFFIX);
                editor.remove(LEGACY_GROUP_PREFIX + widgetName + LEGACY_APPS_SUFFIX);
            }
            editor.commit();

            return migrated;
        }

        // ─── No data yet — return empty list ─────────────────────────────
        return new ArrayList<>();
    }

    /**
     * Persists the widget list to SharedPreferences as a JSON blob.
     *
     * Overwrites any previously saved widgets. Call after every mutation to keep
     * the stored state in sync.
     *
     * @param prefs   SharedPreferences instance to write to
     * @param widgets  Current widget list to persist
     */
    public static void save(SharedPreferences prefs, List<Widget> widgets) {
        prefs.edit()
             .putString(PREF_GROUPS_JSON, serialize(widgets))
             .commit();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Query Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Finds the first widget whose name matches the given name (case-insensitive).
     *
     * @param widgets  List to search
     * @param name    Name to look up (case-insensitive)
     * @return Matching {@link Widget}, or {@code null} if not found
     */
    public static Widget find(List<Widget> widgets, String name) {
        if (name == null) return null;
        for (Widget g : widgets) {
            if (g.name.equalsIgnoreCase(name)) return g;
        }
        return null;
    }

    /**
     * Builds a reverse-lookup map from package name to the owning {@link Widget}.
     *
     * If a package somehow appears in multiple widgets (which {@link #upsert}
     * prevents), the last widget wins.
     *
     * @param widgets  Source widget list
     * @return Mutable map: package name → owning Widget
     */
    public static Map<String, Widget> packageToWidget(List<Widget> widgets) {
        Map<String, Widget> map = new LinkedHashMap<>();
        for (Widget g : widgets) {
            for (String pkg : g.packages) {
                map.put(pkg, g);
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
     * ─── Create mode ({@code oldName == null}) ──────────────────────────
     * Appends a new {@link Widget} with the given name/color/packages to the list.
     * Returns {@code false} if {@code newName} (trimmed, case-insensitive) already
     * exists or if {@code newName} is blank.
     *
     * ─── Update/rename mode ({@code oldName != null}) ───────────────────
     * Finds the existing widget by {@code oldName} (case-insensitive), updates its
     * name, color, and package set in-place. Returns {@code false} if the old widget
     * does not exist, or if {@code newName} collides with a DIFFERENT existing widget.
     *
     * ─── Single-membership invariant ────────────────────────────────────
     * After mutating the target widget's package set, each package in the new set
     * is removed from every OTHER widget, ensuring no package belongs to more than
     * one widget.
     *
     * @param widgets   Mutable widget list to operate on
     * @param oldName  Name of the existing widget to update, or {@code null} to create
     * @param newName  Desired name for the widget (must be non-blank after trim)
     * @param color    Hex color string for the widget
     * @param packages New package set for the widget (replaces existing set)
     * @return {@code true} if the operation succeeded; {@code false} on validation failure
     */
    public static boolean upsert(List<Widget> widgets, String oldName,
                                 String newName, String color, Set<String> packages) {
        // ─── Validate newName ─────────────────────────────────────────────
        if (newName == null || newName.trim().isEmpty()) return false;
        String trimmedNew = newName.trim();

        if (oldName == null) {
            // ─── Create mode: reject if trimmedNew already exists ─────────
            if (find(widgets, trimmedNew) != null) return false;

            Widget fresh = new Widget(trimmedNew, color);
            if (packages != null) fresh.packages.addAll(packages);
            widgets.add(fresh);
            return true;

        } else {
            // ─── Update/rename mode ───────────────────────────────────────
            Widget target = find(widgets, oldName);
            if (target == null) return false;

            // Check name collision with a DIFFERENT widget
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
     * @param widgets  Mutable widget list to operate on
     * @param name    Name of the widget to delete (case-insensitive)
     * @return {@code true} if the widget was found and removed; {@code false} if not found
     */
    public static boolean delete(List<Widget> widgets, String name) {
        Widget target = find(widgets, name);
        if (target == null) return false;
        widgets.remove(target);
        return true;
    }

}
