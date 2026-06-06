package dev.jaimin.auraorbit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LiveWallpaperSettings.java — Settings Activity for AuraOrbit Live Wallpaper
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This Activity uses the modern AndroidX Preference library pattern:
 *   AppCompatActivity → hosts → PreferenceFragmentCompat
 *
 * This avoids the deprecated PreferenceActivity entirely. The fragment inflates
 * preferences from res/xml/preferences.xml, while custom click handlers on
 * "pref_select_apps" and "pref_manage_groups" launch programmatic AlertDialogs.
 *
 * ─── Architecture ────────────────────────────────────────────────────────────
 *
 * 1. The Activity is a thin shell — it only sets the content view and loads
 *    the fragment. All preference logic lives inside SettingsFragment.
 *
 * 2. App selection uses a RecyclerView inside an AlertDialog. Each row shows
 *    the app icon (48dp), name, and a checkbox. On save, the selected package
 *    names are persisted as a StringSet under "selected_app_packages".
 *
 * 3. Group management uses a series of chained dialogs:
 *    - List dialog: shows existing groups with Edit/Delete, plus "Add Group"
 *    - Create/Edit dialog: name field + color picker (8 colored circles) +
 *      app assignment checkboxes (only apps already selected in step 2)
 *
 * 4. All views inside dialogs are built programmatically — no XML layout files
 *    are needed. This keeps the project lightweight and self-contained.
 *
 * ─── SharedPreferences Schema ────────────────────────────────────────────────
 *
 *   Key                        Type          Description
 *   ──────────────────────     ──────────    ──────────────────────────────────
 *   selected_app_packages      StringSet     Package names shown on the sphere
 *   groups_list                 StringSet     Names of all groups
 *   group_<name>_color          String        Hex color for the group
 *   group_<name>_apps           StringSet     Package names assigned to group
 *   pref_keep_wallpaper         boolean       Retain system wallpaper as BG
 *   pref_active_page            int           Home screen page for full render
 *   pref_sphere_radius          int           Sphere size (20–100)
 *   pref_icon_size              int           Icon size (20–100)
 *   pref_target_fps             String        "60", "90", or "120"
 */
public class LiveWallpaperSettings extends AppCompatActivity {

    /**
     * Entry point. We use a FrameLayout as the fragment container.
     * The activity_settings layout isn't needed — we use the built-in
     * android.R.id.content as the container, which is the root FrameLayout
     * provided by every Activity's window decor.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Only create the fragment on fresh launch — not on config change
        // (e.g., rotation). savedInstanceState != null means the system
        // already recreated the fragment for us.
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Inner class — The PreferenceFragmentCompat that holds all settings UI
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fragment that inflates the preference hierarchy from XML and attaches
     * custom click listeners for the app-selection and group-management
     * preferences. SeekBar and Switch preferences are handled automatically
     * by the AndroidX Preference library — no custom code needed for those.
     */
    public static class SettingsFragment extends PreferenceFragmentCompat {

        /** Cached SharedPreferences — we read/write frequently during dialogs. */
        private SharedPreferences prefs;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            // Inflate the preference hierarchy from res/xml/preferences.xml.
            // This creates all the Preference objects (SwitchPreferenceCompat,
            // SeekBarPreference, ListPreference, etc.) and binds them to the
            // default SharedPreferences file automatically.
            setPreferencesFromResource(R.xml.preferences, rootKey);

            prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

            // ─── Attach click handler: Select Apps ──────────────────────
            Preference selectApps = findPreference("pref_select_apps");
            if (selectApps != null) {
                selectApps.setOnPreferenceClickListener(pref -> {
                    showAppSelectionDialog();
                    return true; // consumed
                });
            }

            // ─── Attach click handler: Manage Groups ────────────────────
            Preference manageGroups = findPreference("pref_manage_groups");
            if (manageGroups != null) {
                manageGroups.setOnPreferenceClickListener(pref -> {
                    showGroupManagementDialog();
                    return true; // consumed
                });
            }

            // ─── Set initial summaries ──────────────────────────────────
            updateAppsSummary();
            updateGroupsSummary();
        }

        // ═════════════════════════════════════════════════════════════════
        //  Summary Helpers — dynamically update subtitle text
        // ═════════════════════════════════════════════════════════════════

        /**
         * Updates the "Select Apps" preference summary to show how many
         * apps are currently selected (e.g., "12 apps selected").
         */
        private void updateAppsSummary() {
            Preference pref = findPreference("pref_select_apps");
            if (pref == null) return;

            Set<String> selected = prefs.getStringSet(
                    AppFetcher.PREF_SELECTED_APPS, new HashSet<>());
            int count = selected.size();
            pref.setSummary(count == 0
                    ? getString(R.string.pref_select_apps_summary)
                    : count + " app" + (count == 1 ? "" : "s") + " selected");
        }

        /**
         * Updates the "Manage Groups" preference summary to show how many
         * groups exist (e.g., "3 groups").
         */
        private void updateGroupsSummary() {
            Preference pref = findPreference("pref_manage_groups");
            if (pref == null) return;

            Set<String> groups = prefs.getStringSet(
                    AppFetcher.PREF_GROUPS_LIST, new HashSet<>());
            int count = groups.size();
            pref.setSummary(count == 0
                    ? getString(R.string.pref_manage_groups_summary)
                    : count + " group" + (count == 1 ? "" : "s"));
        }

        // ═════════════════════════════════════════════════════════════════
        //  App Selection Dialog
        // ═════════════════════════════════════════════════════════════════

        /**
         * Shows a full-screen AlertDialog with a RecyclerView listing all
         * launchable apps. Each row has the app icon, name, and a checkbox.
         * Pre-checks apps that were previously selected.
         *
         * On "Save", persists the checked packages to SharedPreferences
         * and shows a confirmation toast. On "Cancel", discards changes.
         */
        private void showAppSelectionDialog() {
            Context ctx = requireContext();
            PackageManager pm = ctx.getPackageManager();

            // ─── Fetch all launchable apps (sorted alphabetically) ──────
            // This runs on the main thread, which is acceptable for a
            // settings screen. PackageManager queries are fast (~50ms for
            // ~200 apps on modern devices).
            List<ResolveInfo> allApps = AppFetcher.getAllLaunchableApps(ctx);

            // ─── Load currently selected packages for pre-checking ──────
            Set<String> currentlySelected = new HashSet<>(
                    prefs.getStringSet(AppFetcher.PREF_SELECTED_APPS, new HashSet<>()));

            // ─── Build the RecyclerView ─────────────────────────────────
            RecyclerView recyclerView = new RecyclerView(ctx);
            recyclerView.setLayoutManager(new LinearLayoutManager(ctx));

            // Add some padding so the list doesn't touch the dialog edges
            int pad = dpToPx(ctx, 8);
            recyclerView.setPadding(pad, pad, pad, pad);
            recyclerView.setClipToPadding(false);

            // Create the adapter with a mutable copy of selected packages
            AppSelectionAdapter adapter = new AppSelectionAdapter(
                    ctx, allApps, currentlySelected);
            recyclerView.setAdapter(adapter);

            // ─── Build the dialog ───────────────────────────────────────
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.dialog_select_apps_title)
                    .setView(recyclerView)
                    .setPositiveButton(R.string.btn_save, (dialog, which) -> {
                        // ── Persist the selected packages ───────────────
                        Set<String> selected = adapter.getSelectedPackages();

                        if (selected.isEmpty()) {
                            Toast.makeText(ctx, R.string.toast_no_apps,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }

                        prefs.edit()
                                .putStringSet(AppFetcher.PREF_SELECTED_APPS, selected)
                                .apply();

                        updateAppsSummary();
                        Toast.makeText(ctx, R.string.toast_saved,
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
        }

        // ═════════════════════════════════════════════════════════════════
        //  App Selection RecyclerView Adapter
        // ═════════════════════════════════════════════════════════════════

        /**
         * RecyclerView.Adapter for the app selection dialog.
         *
         * Each row shows:  [48dp icon]  [App Name]  [Checkbox]
         *
         * The adapter maintains a Set<String> of currently selected package
         * names. Toggling a checkbox adds/removes the package from the set.
         * The caller retrieves the final selection via getSelectedPackages().
         */
        private static class AppSelectionAdapter
                extends RecyclerView.Adapter<AppSelectionAdapter.AppViewHolder> {

            private final Context context;
            private final List<ResolveInfo> apps;
            private final Set<String> selectedPackages;
            private final PackageManager pm;

            AppSelectionAdapter(Context context, List<ResolveInfo> apps,
                                Set<String> initiallySelected) {
                this.context = context;
                this.apps = apps;
                this.selectedPackages = new HashSet<>(initiallySelected);
                this.pm = context.getPackageManager();
            }

            /** Returns the current set of selected package names. */
            Set<String> getSelectedPackages() {
                return new HashSet<>(selectedPackages);
            }

            @NonNull
            @Override
            public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                // ─── Build the row layout programmatically ──────────────
                // Layout:  horizontal LinearLayout
                //   ├─ ImageView (48dp icon)
                //   ├─ TextView (app name, weight=1 to fill)
                //   └─ CheckBox (aligned right)

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pad = dpToPx(context, 12);
                row.setPadding(pad, dpToPx(context, 8), pad, dpToPx(context, 8));

                // App icon — 48dp square is standard Material size for list icons
                ImageView icon = new ImageView(context);
                int iconSize = dpToPx(context, 48);
                LinearLayout.LayoutParams iconParams =
                        new LinearLayout.LayoutParams(iconSize, iconSize);
                iconParams.setMarginEnd(dpToPx(context, 12));
                icon.setLayoutParams(iconParams);
                icon.setId(View.generateViewId());
                row.addView(icon);

                // App name — fills remaining space with weight=1
                TextView name = new TextView(context);
                LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                name.setLayoutParams(nameParams);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                name.setId(View.generateViewId());
                row.addView(name);

                // Checkbox — toggled by clicking anywhere on the row
                CheckBox checkBox = new CheckBox(context);
                checkBox.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                checkBox.setId(View.generateViewId());
                row.addView(checkBox);

                return new AppViewHolder(row, icon, name, checkBox);
            }

            @Override
            public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
                ResolveInfo info = apps.get(position);
                String packageName = info.activityInfo.packageName;

                // Load the app icon and label from PackageManager
                holder.icon.setImageDrawable(info.loadIcon(pm));
                holder.name.setText(info.loadLabel(pm));

                // Set checked state without triggering the listener
                // (avoids recursive toggles during RecyclerView rebinding)
                holder.checkBox.setOnCheckedChangeListener(null);
                holder.checkBox.setChecked(selectedPackages.contains(packageName));

                // Toggle on checkbox change
                holder.checkBox.setOnCheckedChangeListener((btn, isChecked) -> {
                    if (isChecked) {
                        selectedPackages.add(packageName);
                    } else {
                        selectedPackages.remove(packageName);
                    }
                });

                // Also toggle when tapping anywhere on the row (not just checkbox)
                holder.itemView.setOnClickListener(v ->
                        holder.checkBox.setChecked(!holder.checkBox.isChecked()));
            }

            @Override
            public int getItemCount() {
                return apps.size();
            }

            /** ViewHolder caches references to the 3 child views in each row. */
            static class AppViewHolder extends RecyclerView.ViewHolder {
                final ImageView icon;
                final TextView name;
                final CheckBox checkBox;

                AppViewHolder(View itemView, ImageView icon,
                              TextView name, CheckBox checkBox) {
                    super(itemView);
                    this.icon = icon;
                    this.name = name;
                    this.checkBox = checkBox;
                }
            }
        }

        // ═════════════════════════════════════════════════════════════════
        //  Group Management — List Dialog
        // ═════════════════════════════════════════════════════════════════

        /**
         * Shows the main group management dialog. Lists all existing groups
         * with Edit/Delete buttons, plus an "Add Group" button at the bottom.
         *
         * Each group row shows:  [Color dot]  [Group name (N apps)]  [Edit] [Delete]
         */
        private void showGroupManagementDialog() {
            Context ctx = requireContext();

            // ─── Read current group list from prefs ─────────────────────
            Set<String> groupsSet = prefs.getStringSet(
                    AppFetcher.PREF_GROUPS_LIST, new HashSet<>());
            List<String> groups = new ArrayList<>(groupsSet);
            // Sort alphabetically for consistent display
            groups.sort(String::compareToIgnoreCase);

            // ─── Build the scrollable content ───────────────────────────
            ScrollView scrollView = new ScrollView(ctx);
            LinearLayout container = new LinearLayout(ctx);
            container.setOrientation(LinearLayout.VERTICAL);
            int pad = dpToPx(ctx, 16);
            container.setPadding(pad, pad, pad, pad);
            scrollView.addView(container);

            if (groups.isEmpty()) {
                // Show a hint when no groups exist yet
                TextView emptyLabel = new TextView(ctx);
                emptyLabel.setText("No groups yet. Tap 'Add Group' to create one.");
                emptyLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                emptyLabel.setPadding(0, 0, 0, dpToPx(ctx, 16));
                container.addView(emptyLabel);
            } else {
                // ─── Render each group as a row ─────────────────────────
                for (String groupName : groups) {
                    container.addView(
                            buildGroupRow(ctx, groupName));
                }
            }

            // ─── "Add Group" button at the bottom ───────────────────────
            com.google.android.material.button.MaterialButton addBtn =
                    new com.google.android.material.button.MaterialButton(ctx);
            addBtn.setText(R.string.btn_add_group);
            addBtn.setOnClickListener(v -> showGroupCreateEditDialog(null));
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            btnParams.topMargin = dpToPx(ctx, 12);
            addBtn.setLayoutParams(btnParams);
            container.addView(addBtn);

            // ─── Show the dialog ────────────────────────────────────────
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.pref_manage_groups_title)
                    .setView(scrollView)
                    .setPositiveButton(R.string.btn_cancel, null)
                    .show();
        }

        /**
         * Builds a single row for the group list dialog.
         *
         * Layout:  [24dp color circle]  [Group name + app count]  [Edit btn] [Delete btn]
         *
         * @param ctx       Activity context
         * @param groupName The group's name
         * @return A LinearLayout representing one group row
         */
        private View buildGroupRow(Context ctx, String groupName) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dpToPx(ctx, 8);
            row.setPadding(pad, pad, pad, pad);

            // ─── Color indicator circle ─────────────────────────────────
            // Uses a GradientDrawable configured as an oval to render a
            // filled circle in the group's assigned color.
            View colorDot = new View(ctx);
            int dotSize = dpToPx(ctx, 24);
            LinearLayout.LayoutParams dotParams =
                    new LinearLayout.LayoutParams(dotSize, dotSize);
            dotParams.setMarginEnd(dpToPx(ctx, 12));
            colorDot.setLayoutParams(dotParams);

            String colorHex = prefs.getString(
                    AppFetcher.PREF_GROUP_PREFIX + groupName + "_color", "#FFFFFF");
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.parseColor(colorHex));
            colorDot.setBackground(circle);
            row.addView(colorDot);

            // ─── Group name + app count ─────────────────────────────────
            TextView label = new TextView(ctx);
            Set<String> groupApps = prefs.getStringSet(
                    AppFetcher.PREF_GROUP_PREFIX + groupName + "_apps", new HashSet<>());
            label.setText(groupName + " (" + groupApps.size() + " apps)");
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(labelParams);
            row.addView(label);

            // ─── Edit button ────────────────────────────────────────────
            TextView editBtn = new TextView(ctx);
            editBtn.setText("Edit");
            editBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            editBtn.setTextColor(Color.parseColor("#6C63FF")); // settings_primary
            editBtn.setPadding(dpToPx(ctx, 8), dpToPx(ctx, 4),
                    dpToPx(ctx, 8), dpToPx(ctx, 4));
            editBtn.setOnClickListener(v -> showGroupCreateEditDialog(groupName));
            row.addView(editBtn);

            // ─── Delete button ──────────────────────────────────────────
            TextView deleteBtn = new TextView(ctx);
            deleteBtn.setText(R.string.btn_delete_group);
            deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            deleteBtn.setTextColor(Color.parseColor("#FF6B6B")); // coral red
            deleteBtn.setPadding(dpToPx(ctx, 8), dpToPx(ctx, 4),
                    dpToPx(ctx, 8), dpToPx(ctx, 4));
            deleteBtn.setOnClickListener(v -> confirmDeleteGroup(groupName));
            row.addView(deleteBtn);

            return row;
        }

        // ═════════════════════════════════════════════════════════════════
        //  Group Create / Edit Dialog
        // ═════════════════════════════════════════════════════════════════

        /**
         * Shows a dialog for creating a new group or editing an existing one.
         *
         * Layout (vertical LinearLayout inside ScrollView):
         *   1. EditText — group name
         *   2. Label "Choose Color" + horizontal row of 8 colored circles
         *   3. Label "Assign apps" + vertical list of checkboxes for selected apps
         *
         * @param existingGroupName  null to create new, non-null to edit existing
         */
        private void showGroupCreateEditDialog(String existingGroupName) {
            Context ctx = requireContext();
            boolean isEditing = (existingGroupName != null);
            Resources res = ctx.getResources();

            // ─── Load color palette from resources ──────────────────────
            String[] colorNames = res.getStringArray(R.array.group_color_names);
            String[] colorHexValues = res.getStringArray(R.array.group_color_hex);

            // ─── Load existing group data if editing ────────────────────
            String currentColor = isEditing
                    ? prefs.getString(AppFetcher.PREF_GROUP_PREFIX + existingGroupName + "_color",
                    colorHexValues[0])
                    : colorHexValues[0]; // default to first color

            Set<String> currentGroupApps = isEditing
                    ? new HashSet<>(prefs.getStringSet(
                    AppFetcher.PREF_GROUP_PREFIX + existingGroupName + "_apps",
                    new HashSet<>()))
                    : new HashSet<>();

            // ─── Track the selected color index ─────────────────────────
            // Using an int array so we can mutate it inside the lambda.
            final int[] selectedColorIndex = {0};
            for (int i = 0; i < colorHexValues.length; i++) {
                if (colorHexValues[i].equalsIgnoreCase(currentColor)) {
                    selectedColorIndex[0] = i;
                    break;
                }
            }

            // ═══════════════════════════════════════════════════════════
            //  Build the dialog content view
            // ═══════════════════════════════════════════════════════════

            ScrollView scrollView = new ScrollView(ctx);
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = dpToPx(ctx, 20);
            root.setPadding(pad, pad, pad, pad);
            scrollView.addView(root);

            // ─── 1. Group Name EditText ─────────────────────────────────
            EditText nameInput = new EditText(ctx);
            nameInput.setHint(R.string.dialog_group_name_hint);
            nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
            nameInput.setSingleLine(true);
            if (isEditing) {
                nameInput.setText(existingGroupName);
            }
            root.addView(nameInput);

            // ─── 2. Color Picker ────────────────────────────────────────
            // Label
            TextView colorLabel = new TextView(ctx);
            colorLabel.setText(R.string.dialog_group_color);
            colorLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            LinearLayout.LayoutParams colorLabelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            colorLabelParams.topMargin = dpToPx(ctx, 16);
            colorLabelParams.bottomMargin = dpToPx(ctx, 8);
            colorLabel.setLayoutParams(colorLabelParams);
            root.addView(colorLabel);

            // Horizontal row of colored circles
            LinearLayout colorRow = new LinearLayout(ctx);
            colorRow.setOrientation(LinearLayout.HORIZONTAL);
            colorRow.setGravity(Gravity.CENTER_VERTICAL);

            // Array of circle views so we can update their stroke (selection ring)
            final View[] colorCircles = new View[colorHexValues.length];

            for (int i = 0; i < colorHexValues.length; i++) {
                final int colorIdx = i;
                View circle = new View(ctx);
                int circleSize = dpToPx(ctx, 36);
                LinearLayout.LayoutParams circleParams =
                        new LinearLayout.LayoutParams(circleSize, circleSize);
                circleParams.setMarginEnd(dpToPx(ctx, 8));
                circle.setLayoutParams(circleParams);

                // Draw the circle with a stroke for the selected one
                GradientDrawable drawable = new GradientDrawable();
                drawable.setShape(GradientDrawable.OVAL);
                drawable.setColor(Color.parseColor(colorHexValues[i]));
                if (i == selectedColorIndex[0]) {
                    // White ring around the selected color for visual feedback
                    drawable.setStroke(dpToPx(ctx, 3), Color.WHITE);
                }
                circle.setBackground(drawable);
                circle.setContentDescription(colorNames[i]);

                // Click handler: select this color and update visual state
                circle.setOnClickListener(v -> {
                    selectedColorIndex[0] = colorIdx;
                    // Refresh all circles — remove old ring, add new one
                    for (int j = 0; j < colorCircles.length; j++) {
                        GradientDrawable d = new GradientDrawable();
                        d.setShape(GradientDrawable.OVAL);
                        d.setColor(Color.parseColor(colorHexValues[j]));
                        if (j == colorIdx) {
                            d.setStroke(dpToPx(ctx, 3), Color.WHITE);
                        }
                        colorCircles[j].setBackground(d);
                    }
                });

                colorCircles[i] = circle;
                colorRow.addView(circle);
            }
            root.addView(colorRow);

            // ─── 3. App Assignment ──────────────────────────────────────
            // Only show apps that are currently selected in "Select Apps".
            // It doesn't make sense to assign unselected apps to a group.
            Set<String> selectedApps = prefs.getStringSet(
                    AppFetcher.PREF_SELECTED_APPS, new HashSet<>());

            if (!selectedApps.isEmpty()) {
                // Label
                TextView appsLabel = new TextView(ctx);
                appsLabel.setText(R.string.dialog_assign_apps);
                appsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                LinearLayout.LayoutParams appsLabelParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                appsLabelParams.topMargin = dpToPx(ctx, 16);
                appsLabelParams.bottomMargin = dpToPx(ctx, 8);
                appsLabel.setLayoutParams(appsLabelParams);
                root.addView(appsLabel);

                // Build a sorted list of selected app packages with labels
                PackageManager pm = ctx.getPackageManager();
                List<String> sortedPackages = new ArrayList<>(selectedApps);
                sortedPackages.sort((a, b) -> {
                    try {
                        String labelA = pm.getApplicationLabel(
                                pm.getApplicationInfo(a, 0)).toString();
                        String labelB = pm.getApplicationLabel(
                                pm.getApplicationInfo(b, 0)).toString();
                        return labelA.compareToIgnoreCase(labelB);
                    } catch (PackageManager.NameNotFoundException e) {
                        return a.compareTo(b);
                    }
                });

                // Mutable set for tracking assignment during this dialog session
                Set<String> assignedApps = new HashSet<>(currentGroupApps);

                // Create a CheckBox for each selected app
                for (String pkgName : sortedPackages) {
                    CheckBox cb = new CheckBox(ctx);
                    try {
                        String appLabel = pm.getApplicationLabel(
                                pm.getApplicationInfo(pkgName, 0)).toString();
                        cb.setText(appLabel);
                    } catch (PackageManager.NameNotFoundException e) {
                        cb.setText(pkgName); // fallback to package name
                    }
                    cb.setChecked(assignedApps.contains(pkgName));
                    cb.setOnCheckedChangeListener((btn, isChecked) -> {
                        if (isChecked) {
                            assignedApps.add(pkgName);
                        } else {
                            assignedApps.remove(pkgName);
                        }
                    });
                    cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    cb.setPadding(0, dpToPx(ctx, 4), 0, dpToPx(ctx, 4));
                    root.addView(cb);
                }

                // ─── Build and show the dialog ──────────────────────────
                String dialogTitle = isEditing
                        ? "Edit Group"
                        : ctx.getString(R.string.dialog_create_group);

                new AlertDialog.Builder(ctx)
                        .setTitle(dialogTitle)
                        .setView(scrollView)
                        .setPositiveButton(R.string.btn_save, (dialog, which) -> {
                            String name = nameInput.getText().toString().trim();
                            if (name.isEmpty()) {
                                Toast.makeText(ctx, "Group name cannot be empty",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            saveGroup(existingGroupName, name,
                                    colorHexValues[selectedColorIndex[0]],
                                    assignedApps);
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
            } else {
                // No apps selected — still allow group creation, just without
                // app assignment. The user can assign apps later.
                String dialogTitle = isEditing
                        ? "Edit Group"
                        : ctx.getString(R.string.dialog_create_group);

                // Show a note about no apps being selected
                TextView noAppsNote = new TextView(ctx);
                noAppsNote.setText("Select apps first to assign them to this group.");
                noAppsNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                noteParams.topMargin = dpToPx(ctx, 16);
                noAppsNote.setLayoutParams(noteParams);
                root.addView(noAppsNote);

                new AlertDialog.Builder(ctx)
                        .setTitle(dialogTitle)
                        .setView(scrollView)
                        .setPositiveButton(R.string.btn_save, (dialog, which) -> {
                            String name = nameInput.getText().toString().trim();
                            if (name.isEmpty()) {
                                Toast.makeText(ctx, "Group name cannot be empty",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            saveGroup(existingGroupName, name,
                                    colorHexValues[selectedColorIndex[0]],
                                    new HashSet<>());
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
            }
        }

        // ═════════════════════════════════════════════════════════════════
        //  Group Persistence — Save / Delete
        // ═════════════════════════════════════════════════════════════════

        /**
         * Persists a group to SharedPreferences. Handles both create and
         * edit (including rename, which requires deleting the old keys).
         *
         * SharedPreferences schema for groups:
         *   - "groups_list"              → StringSet of all group names
         *   - "group_<name>_color"       → String hex color
         *   - "group_<name>_apps"        → StringSet of assigned packages
         *
         * @param oldName      Previous group name (null if creating new)
         * @param newName      New (or same) group name
         * @param colorHex     Selected color hex string
         * @param assignedApps Set of package names assigned to this group
         */
        private void saveGroup(String oldName, String newName,
                               String colorHex, Set<String> assignedApps) {

            SharedPreferences.Editor editor = prefs.edit();

            // ─── Handle rename: remove old keys ─────────────────────────
            if (oldName != null && !oldName.equals(newName)) {
                // Remove old group entries
                editor.remove(AppFetcher.PREF_GROUP_PREFIX + oldName + "_color");
                editor.remove(AppFetcher.PREF_GROUP_PREFIX + oldName + "_apps");

                // Update the groups_list: remove old name, add new name
                Set<String> groups = new HashSet<>(
                        prefs.getStringSet(AppFetcher.PREF_GROUPS_LIST, new HashSet<>()));
                groups.remove(oldName);
                groups.add(newName);
                editor.putStringSet(AppFetcher.PREF_GROUPS_LIST, groups);
            } else if (oldName == null) {
                // ─── Creating a new group: add to the groups_list ───────
                Set<String> groups = new HashSet<>(
                        prefs.getStringSet(AppFetcher.PREF_GROUPS_LIST, new HashSet<>()));
                groups.add(newName);
                editor.putStringSet(AppFetcher.PREF_GROUPS_LIST, groups);
            }

            // ─── Write group data ───────────────────────────────────────
            editor.putString(
                    AppFetcher.PREF_GROUP_PREFIX + newName + "_color", colorHex);
            editor.putStringSet(
                    AppFetcher.PREF_GROUP_PREFIX + newName + "_apps", assignedApps);

            editor.apply();

            // ─── Update UI ──────────────────────────────────────────────
            updateGroupsSummary();
            Toast.makeText(requireContext(), R.string.toast_saved,
                    Toast.LENGTH_SHORT).show();

            // Re-open the group management dialog to show updated list.
            // Small delay isn't needed — the dialog dismiss is synchronous.
            showGroupManagementDialog();
        }

        /**
         * Shows a confirmation dialog before deleting a group.
         * On confirm, removes all SharedPreferences keys for that group
         * and refreshes the group management dialog.
         *
         * @param groupName  The name of the group to delete
         */
        private void confirmDeleteGroup(String groupName) {
            Context ctx = requireContext();

            new AlertDialog.Builder(ctx)
                    .setTitle("Delete Group")
                    .setMessage("Delete group \"" + groupName + "\"? "
                            + "Apps will remain selected but become ungrouped.")
                    .setPositiveButton(R.string.btn_delete_group, (dialog, which) -> {
                        SharedPreferences.Editor editor = prefs.edit();

                        // Remove group-specific keys
                        editor.remove(AppFetcher.PREF_GROUP_PREFIX + groupName + "_color");
                        editor.remove(AppFetcher.PREF_GROUP_PREFIX + groupName + "_apps");

                        // Remove from the master group list
                        Set<String> groups = new HashSet<>(
                                prefs.getStringSet(AppFetcher.PREF_GROUPS_LIST,
                                        new HashSet<>()));
                        groups.remove(groupName);
                        editor.putStringSet(AppFetcher.PREF_GROUPS_LIST, groups);

                        editor.apply();

                        updateGroupsSummary();
                        Toast.makeText(ctx, R.string.toast_saved,
                                Toast.LENGTH_SHORT).show();

                        // Refresh the group management dialog
                        showGroupManagementDialog();
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
        }

        // ═════════════════════════════════════════════════════════════════
        //  Utility — dp to px conversion
        // ═════════════════════════════════════════════════════════════════

        /**
         * Converts density-independent pixels to physical pixels using the
         * device's display density. Essential for building programmatic
         * layouts that render consistently across screen densities.
         *
         * @param ctx  Context for accessing display metrics
         * @param dp   Value in density-independent pixels
         * @return     Equivalent value in physical pixels (rounded)
         */
        private static int dpToPx(Context ctx, int dp) {
            return Math.round(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, dp,
                    ctx.getResources().getDisplayMetrics()));
        }
    }
}
