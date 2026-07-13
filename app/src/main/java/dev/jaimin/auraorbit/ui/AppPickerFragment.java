package dev.jaimin.auraorbit.ui;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jaimin.auraorbit.AppFetcher;
import dev.jaimin.auraorbit.WidgetStore;
import dev.jaimin.auraorbit.R;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * AppPickerFragment.java — Full-app list with search + persistent selection
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Inflates {@code fragment_app_picker} (ids: {@code search_input}, {@code app_list}).
 * Each row uses {@code row_app} (ids: {@code app_icon}, {@code app_label},
 * {@code app_group_badge}, {@code app_check}).
 *
 * ─── Data loading ────────────────────────────────────────────────────────────
 *
 * {@link AppFetcher#getAllLaunchableApps} and icon/label loading are performed on
 * a background {@link ExecutorService} field so the UI thread is never blocked.
 * Results are posted to the main thread via {@link Handler} and guarded by
 * {@link #isAdded()} before any UI access.
 *
 * ─── Selection persistence ───────────────────────────────────────────────────
 *
 * Every row click toggles its checked state AND immediately writes a FRESH
 * {@code Set<String>} to SharedPreferences under {@link AppFetcher#PREF_SELECTED_APPS}.
 * Writing a fresh set (not the same instance returned by {@code getStringSet}) is
 * required by the SharedPreferences contract — mutating the returned set has no
 * effect and loses data.
 *
 * ─── Filtering ───────────────────────────────────────────────────────────────
 *
 * The adapter keeps both a full list and a filtered list. A {@link TextWatcher}
 * on {@code search_input} re-filters by {@code label} or {@code packageName}
 * (case-insensitive contains) on every keystroke.
 */
public class AppPickerFragment extends Fragment {

    // ─── Background loader ────────────────────────────────────────────────
    // Single-thread so loads don't stack if the fragment is re-entered.
    private ExecutorService executor;

    // ─── Adapter reference kept for search-filter updates ────────────────
    private AppAdapter adapter;

    private String prefKey = dev.jaimin.auraorbit.AppFetcher.PREF_SELECTED_APPS;

    public static AppPickerFragment newInstance(String prefKey) {
        AppPickerFragment fragment = new AppPickerFragment();
        Bundle args = new Bundle();
        args.putString("pref_key", prefKey);
        fragment.setArguments(args);
        return fragment;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Fragment lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        if (getArguments() != null && getArguments().getString("pref_key") != null) {
            prefKey = getArguments().getString("pref_key");
        }
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_app_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        RecyclerView list = root.findViewById(R.id.app_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));

        TextInputEditText searchInput = root.findViewById(R.id.search_input);

        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Create adapter with an empty list; the background task fills it.
        adapter = new AppAdapter(prefs);
        list.setAdapter(adapter);

        // Wire the search box to filter the adapter.
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s == null ? "" : s.toString());
            }
        });

        // Wire up the new "Select All" checkbox next to the search bar.
        // It selects/deselects all currently visible apps based on the active filter.
        com.google.android.material.checkbox.MaterialCheckBox cbSelectAll = root.findViewById(R.id.cb_select_all);
        cbSelectAll.setOnClickListener(v -> {
            if (cbSelectAll.isChecked()) {
                selectAllVisible();
            } else {
                clearAllSelection();
            }
        });

        // Kick off the background data load.
        loadAppsAsync(prefs);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Restore title and update the selected count.
        updateTitle();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull android.view.Menu menu, @NonNull android.view.MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        android.view.MenuItem saveItem = menu.add(android.view.Menu.NONE, 1001, android.view.Menu.NONE, "Save");
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (item.getItemId() == 1001) {
            requireActivity().getSupportFragmentManager().popBackStack();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Background data loading
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Loads all launchable apps, their labels, icons, and widget membership
     * on a background thread, then hands the result to the adapter on the
     * main thread.
     *
     * <p>Guarded by {@link #isAdded()} before any UI access so that a slow
     * device completing the load after the user has left the fragment does
     * not crash with an {@code IllegalStateException}.</p>
     */
    private void loadAppsAsync(@NonNull SharedPreferences prefs) {
        android.content.Context appCtx = requireContext().getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        executor.submit(() -> {
            PackageManager pm = appCtx.getPackageManager();
            List<ResolveInfo> resolvedApps = AppFetcher.getAllLaunchableApps(appCtx);
            dev.jaimin.auraorbit.IconPackManager iconPackManager = dev.jaimin.auraorbit.IconPackManager.getInstance(appCtx);

            // Build the widget reverse-lookup once for O(1) per-app lookup.
            List<WidgetStore.Widget> widgets = WidgetStore.load(prefs);
            Map<String, WidgetStore.Widget> pkgToGroup = WidgetStore.packageToWidget(widgets);

            // Read the current selection so we can pre-check rows.
            Set<String> selectedSet = prefs.getStringSet(
                    prefKey, new HashSet<>());

            List<AppRow> rows = new ArrayList<>(resolvedApps.size());
            for (ResolveInfo ri : resolvedApps) {
                String pkg = ri.activityInfo.packageName;
                String className = ri.activityInfo.name;
                String componentName = "ComponentInfo{" + pkg + "/" + className + "}";
                String label = ri.loadLabel(pm).toString();
                
                Drawable icon = iconPackManager.getIcon(componentName);
                if (icon == null) {
                    icon = ri.loadIcon(pm);
                }

                // Determine widget membership (null → no badge shown).
                WidgetStore.Widget owningGroup = pkgToGroup.get(pkg);
                String widgetName = owningGroup != null ? owningGroup.name : null;

                AppRow row = new AppRow(pkg, label, icon, widgetName,
                        selectedSet.contains(pkg));
                rows.add(row);
            }

            mainHandler.post(() -> {
                if (!isAdded()) return; // Fragment detached while loading
                adapter.setItems(rows);
                updateTitle();
            });
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Title helper
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Updates the Activity title to show the current selection count,
     * e.g. "3 selected". Called after load and after every toggle.
     */
    private void updateTitle() {
        if (!isAdded()) return;
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());
        int count = prefs.getStringSet(
                prefKey, new HashSet<>()).size();
        requireActivity().setTitle(getString(R.string.selected_count, count));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Bulk-selection actions (wired from the toolbar menu)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Adds every app currently shown in the (possibly filtered) list to the
     * selection.  When no search filter is active this is equivalent to
     * "select all installed launchable apps".
     *
     * <p>Follows the same read → copy → mutate → write pattern used by the
     * row click handler to satisfy the SharedPreferences contract.</p>
     */
    private void selectAllVisible() {
        if (!isAdded() || adapter == null) return;
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Start from the current persisted selection so we don't lose items
        // that are selected but scrolled off the visible window.
        Set<String> sel = new HashSet<>(prefs.getStringSet(
                prefKey, new HashSet<>()));
        for (AppRow row : adapter.displayItems) {
            row.checked = true;
            sel.add(row.packageName);
        }
        prefs.edit().putStringSet(prefKey, sel).apply();

        adapter.notifyDataSetChanged();
        updateTitle();
    }

    /**
     * Clears the entire selection — not limited to the currently filtered
     * view — and immediately persists the empty set.
     */
    private void clearAllSelection() {
        if (!isAdded() || adapter == null) return;
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Start from current persisted selection and remove visible items
        Set<String> sel = new HashSet<>(prefs.getStringSet(
                prefKey, new HashSet<>()));
        for (AppRow row : adapter.displayItems) {
            row.checked = false;
            sel.remove(row.packageName);
        }
        prefs.edit().putStringSet(prefKey, sel).apply();

        adapter.notifyDataSetChanged();
        updateTitle();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Row model
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Immutable-ish data bag for a single row in the app picker.
     * {@code checked} is mutable so the adapter can toggle it in-place
     * without rebuilding the list.
     */
    private static final class AppRow {
        final String packageName;
        final String label;
        final Drawable icon;
        /** Display name of the widget this app belongs to, or {@code null}. */
        @Nullable final String widgetName;
        boolean checked;

        AppRow(String packageName, String label, Drawable icon,
               @Nullable String widgetName, boolean checked) {
            this.packageName = packageName;
            this.label       = label;
            this.icon        = icon;
            this.widgetName   = widgetName;
            this.checked     = checked;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  RecyclerView Adapter
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Adapter for the app picker list.
     *
     * <p>Maintains two lists: {@link #allItems} (full data set) and
     * {@link #displayItems} (the currently-filtered subset). Both point to
     * the same {@link AppRow} objects so a toggle on a filtered row is
     * reflected in the full list — no sync needed.</p>
     *
     * <p>The checkbox in each row is {@code clickable=false} (as declared in
     * {@code row_app.xml}), so the row's root {@code OnClickListener} is the
     * only interaction surface. This prevents double-toggle when the user
     * taps directly on the checkbox area.</p>
     */
    private final class AppAdapter
            extends RecyclerView.Adapter<AppAdapter.VH> {

        private final SharedPreferences prefs;
        /** Complete (unfiltered) list of all launchable apps. */
        private final List<AppRow> allItems = new ArrayList<>();
        /** Subset shown in the RecyclerView, updated by {@link #filter}. */
        private final List<AppRow> displayItems = new ArrayList<>();
        /** Current filter query (lowercase); empty string means "show all". */
        private String currentQuery = "";

        AppAdapter(@NonNull SharedPreferences prefs) {
            this.prefs = prefs;
        }

        /**
         * Replaces the full data set and re-applies the current filter.
         * Called once after the background load completes.
         */
        void setItems(@NonNull List<AppRow> items) {
            allItems.clear();
            allItems.addAll(items);
            filter(currentQuery);
        }

        /**
         * Filters the displayed list to rows whose label or package name
         * contains {@code query} (case-insensitive).
         *
         * @param query  Search string; empty/null means show all rows.
         */
        void filter(@Nullable String query) {
            currentQuery = query == null ? "" : query.trim().toLowerCase();
            displayItems.clear();
            if (currentQuery.isEmpty()) {
                displayItems.addAll(allItems);
            } else {
                for (AppRow r : allItems) {
                    if (r.label.toLowerCase().contains(currentQuery)
                            || r.packageName.toLowerCase().contains(currentQuery)) {
                        displayItems.add(r);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AppRow row = displayItems.get(position);

            holder.icon.setImageDrawable(row.icon);
            holder.label.setText(row.label);

            // ─── Widget badge ──────────────────────────────────────────────
            // Must explicitly handle BOTH visibility states on every bind
            // because RecyclerView recycles views — a row that was VISIBLE
            // before must be reset to GONE if the new data has no widget.
            if (row.widgetName != null) {
                holder.groupBadge.setText(row.widgetName);
                holder.groupBadge.setVisibility(View.VISIBLE);
            } else {
                holder.groupBadge.setVisibility(View.GONE);
            }

            // Detach listener before setting state to avoid re-entrant calls.
            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(row.checked);

            // Row click toggles selection and immediately persists the new set.
            holder.itemView.setOnClickListener(v -> {
                row.checked = !row.checked;

                // Always read → copy → mutate → write to satisfy the
                // SharedPreferences contract: never mutate the returned Set.
                Set<String> sel = new HashSet<>(prefs.getStringSet(
                        prefKey, new HashSet<>()));
                if (row.checked) {
                    sel.add(row.packageName);
                } else {
                    sel.remove(row.packageName);
                }
                prefs.edit()
                        .putStringSet(prefKey, sel)
                        .apply();

                // Sync the checkbox visual (it is non-clickable, so we drive it).
                holder.check.setChecked(row.checked);

                // Reflect the new count in the Activity title.
                updateTitle();
            });
        }

        @Override
        public int getItemCount() {
            return displayItems.size();
        }

        // ─────────────────────────────────────────────────────────────────
        //  ViewHolder
        // ─────────────────────────────────────────────────────────────────

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView  label;
            final TextView  groupBadge;
            final CheckBox  check;

            VH(@NonNull View itemView) {
                super(itemView);
                icon       = itemView.findViewById(R.id.app_icon);
                label      = itemView.findViewById(R.id.app_label);
                groupBadge = itemView.findViewById(R.id.app_group_badge);
                check      = itemView.findViewById(R.id.app_check);
            }
        }
    }
}
