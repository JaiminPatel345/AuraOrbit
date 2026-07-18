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
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
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
 */
public class AppPickerFragment extends Fragment {

    // ─── Background loader ────────────────────────────────────────────────
    private ExecutorService executor;

    // ─── Adapter reference kept for search-filter updates ────────────────
    private AppAdapter adapter;

    // ─── Local selection set (persisted only when clicking Save button) ───
    private final Set<String> localSelectedApps = new HashSet<>();

    // ─────────────────────────────────────────────────────────────────────
    //  Fragment lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
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

        // Initialize localSelectedApps with currently saved packages (or all apps by default)
        localSelectedApps.clear();
        Set<String> savedApps = prefs.getStringSet(AppFetcher.PREF_SELECTED_APPS, null);
        if (savedApps == null || savedApps.isEmpty()) {
            List<ResolveInfo> launchable = AppFetcher.getAllLaunchableApps(requireContext());
            for (ResolveInfo info : launchable) {
                if (info.activityInfo != null && info.activityInfo.packageName != null) {
                    localSelectedApps.add(info.activityInfo.packageName);
                }
            }
        } else {
            localSelectedApps.addAll(savedApps);
        }

        // Create adapter with empty list; background loader populates it
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        // Wire search box to filter the adapter
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s == null ? "" : s.toString());
            }
        });

        // Wire "Select All" checkbox
        com.google.android.material.checkbox.MaterialCheckBox cbSelectAll = root.findViewById(R.id.cb_select_all);
        cbSelectAll.setOnClickListener(v -> {
            if (cbSelectAll.isChecked()) {
                selectAllVisible();
            } else {
                clearAllSelection();
            }
        });

        // Wire "Save" FAB to persist selection
        root.findViewById(R.id.fab_save).setOnClickListener(v -> {
            prefs.edit().putStringSet(AppFetcher.PREF_SELECTED_APPS, new HashSet<>(localSelectedApps)).apply();
            dev.jaimin.auraorbit.SphereWidgetProvider.updateAllWidgets(requireContext());
            getParentFragmentManager().popBackStack();
        });

        // Kick off background load
        loadAppsAsync(prefs);
    }

    @Override
    public void onResume() {
        super.onResume();
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

    // ─────────────────────────────────────────────────────────────────────
    //  Background data loading
    // ─────────────────────────────────────────────────────────────────────

    private void loadAppsAsync(@NonNull SharedPreferences prefs) {
        android.content.Context appCtx = requireContext().getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        executor.submit(() -> {
            PackageManager pm = appCtx.getPackageManager();
            List<ResolveInfo> resolvedApps = AppFetcher.getAllLaunchableApps(appCtx);
            dev.jaimin.auraorbit.IconPackManager iconPackManager = dev.jaimin.auraorbit.IconPackManager.getInstance(appCtx);

            List<WidgetStore.Widget> widgets = WidgetStore.load(prefs);
            Map<String, WidgetStore.Widget> pkgToWidget = WidgetStore.packageToWidget(widgets);

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

                WidgetStore.Widget owningWidget = pkgToWidget.get(pkg);
                String widgetName = owningWidget != null ? owningWidget.name : null;

                AppRow row = new AppRow(pkg, label, icon, widgetName,
                        localSelectedApps.contains(pkg));
                rows.add(row);
            }

            mainHandler.post(() -> {
                if (!isAdded()) return;
                adapter.setItems(rows);
                updateTitle();
            });
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Title helper
    // ─────────────────────────────────────────────────────────────────────

    private void updateTitle() {
        if (!isAdded()) return;
        int count = localSelectedApps.size();
        requireActivity().setTitle(getString(R.string.selected_count, count));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Bulk-selection actions
    // ─────────────────────────────────────────────────────────────────────

    private void selectAllVisible() {
        if (!isAdded() || adapter == null) return;
        for (AppRow row : adapter.displayItems) {
            row.checked = true;
            localSelectedApps.add(row.packageName);
        }
        adapter.notifyDataSetChanged();
        updateTitle();
    }

    private void clearAllSelection() {
        if (!isAdded() || adapter == null) return;
        for (AppRow row : adapter.displayItems) {
            row.checked = false;
            localSelectedApps.remove(row.packageName);
        }
        adapter.notifyDataSetChanged();
        updateTitle();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Row model
    // ═════════════════════════════════════════════════════════════════════

    private static final class AppRow {
        final String packageName;
        final String label;
        final Drawable icon;
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

    private final class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

        private final List<AppRow> allItems = new ArrayList<>();
        private final List<AppRow> displayItems = new ArrayList<>();
        private String currentQuery = "";

        AppAdapter() {
        }

        void setItems(@NonNull List<AppRow> items) {
            allItems.clear();
            allItems.addAll(items);
            filter(currentQuery);
        }

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

            if (row.widgetName != null) {
                holder.widgetBadge.setText(row.widgetName);
                holder.widgetBadge.setVisibility(View.VISIBLE);
            } else {
                holder.widgetBadge.setVisibility(View.GONE);
            }

            holder.check.setOnCheckedChangeListener(null);
            holder.check.setChecked(row.checked);

            holder.itemView.setOnClickListener(v -> {
                row.checked = !row.checked;

                if (row.checked) {
                    localSelectedApps.add(row.packageName);
                } else {
                    localSelectedApps.remove(row.packageName);
                }

                holder.check.setChecked(row.checked);
                updateTitle();
            });
        }

        @Override
        public int getItemCount() {
            return displayItems.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView  label;
            final TextView  widgetBadge;
            final CheckBox  check;

            VH(@NonNull View itemView) {
                super(itemView);
                icon       = itemView.findViewById(R.id.app_icon);
                label      = itemView.findViewById(R.id.app_label);
                widgetBadge = itemView.findViewById(R.id.app_widget_badge);
                check      = itemView.findViewById(R.id.app_check);
            }
        }
    }
}
