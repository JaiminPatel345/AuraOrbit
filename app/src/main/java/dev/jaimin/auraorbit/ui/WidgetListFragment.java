package dev.jaimin.auraorbit.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

import dev.jaimin.auraorbit.WidgetStore;
import dev.jaimin.auraorbit.R;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * WidgetListFragment.java — Scrollable list of all configured app widgets
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Inflates {@code fragment_widget_list} (ids: {@code group_list}, {@code empty_view},
 * {@code fab_add}). Each row uses {@code row_widget} (ids: {@code group_color_dot},
 * {@code widget_name}, {@code group_count}).
 *
 * ─── Data refresh ────────────────────────────────────────────────────────────
 *
 * Widgets are reloaded from SharedPreferences in {@link #onResume} so that
 * returning from {@link WidgetEditFragment} (after a save or delete) always
 * shows the current state without manual cache invalidation.
 *
 * ─── Empty state ─────────────────────────────────────────────────────────────
 *
 * When no widgets exist, the RecyclerView is hidden (GONE) and {@code empty_view}
 * is shown (VISIBLE). The inverse applies when at least one widget exists.
 *
 * ─── Navigation ──────────────────────────────────────────────────────────────
 *
 * Row taps and the FAB both navigate to {@link WidgetEditFragment} using the
 * shared {@code R.id.settings_container} back-stack pattern.
 */
public class WidgetListFragment extends Fragment {

    // ─── Adapter reference kept so onResume can swap data ─────────────────
    private GroupAdapter adapter;
    private View emptyView;
    private RecyclerView recyclerView;

    // ─────────────────────────────────────────────────────────────────────
    //  Fragment lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_widget_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        recyclerView = root.findViewById(R.id.group_list);
        emptyView    = root.findViewById(R.id.empty_view);
        com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fab = root.findViewById(R.id.fab_add);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new GroupAdapter();
        recyclerView.setAdapter(adapter);

        // FAB: navigate to WidgetEditFragment in "create new" mode (widgetName = null).
        fab.setOnClickListener(v -> navigateToEdit(null));
    }

    @Override
    public void onResume() {
        super.onResume();
        // Set the fragment title in the Activity's action bar.
        requireActivity().setTitle(R.string.title_groups);
        // Reload widgets — this covers the "return from WidgetEditFragment" case.
        reloadGroups();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Data reload
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Reads the current widget list from SharedPreferences and updates the
     * adapter and empty-state visibility. Safe to call from the main thread
     * because {@link WidgetStore#load} only does a JSON parse.
     */
    private void reloadGroups() {
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());
        List<WidgetStore.Widget> widgets = WidgetStore.load(prefs);

        adapter.setGroups(widgets);

        // Toggle empty state visibility
        if (widgets.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Navigation helper
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Navigates to {@link WidgetEditFragment}, passing the widget name as an
     * argument (or {@code null} for the create-new flow).
     *
     * @param widgetName  Name of the widget to edit, or {@code null} to create new.
     */
    private void navigateToEdit(@Nullable String widgetName) {
        getParentFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, WidgetEditFragment.newInstance(widgetName))
                .addToBackStack(null)
                .commit();
    }

    /**
     * Shows a confirmation dialog before deleting the widget.
     */
    private void confirmDelete(@NonNull String widgetName) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_delete_confirm, widgetName))
                .setMessage(R.string.dialog_delete_confirm_msg)
                .setPositiveButton(R.string.btn_delete_group, (dialog, which) -> {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                    List<WidgetStore.Widget> freshGroups = WidgetStore.load(prefs);
                    WidgetStore.delete(freshGroups, widgetName);
                    WidgetStore.save(prefs, freshGroups);
                    dev.jaimin.auraorbit.SphereWidgetProvider.updateAllWidgets(requireContext());

                    android.widget.Toast.makeText(requireContext(),
                            R.string.toast_group_deleted,
                            android.widget.Toast.LENGTH_SHORT).show();

                    reloadGroups();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  RecyclerView Adapter
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Adapter for the widget list.
     *
     * <p>Each row shows a coloured oval ({@code group_color_dot}), the widget name
     * ({@code widget_name}), and the member count ({@code group_count}).
     * Tapping a row opens {@link WidgetEditFragment} for that widget.</p>
     */
    private final class GroupAdapter
            extends RecyclerView.Adapter<GroupAdapter.VH> {

        private final List<WidgetStore.Widget> items = new ArrayList<>();

        void setGroups(@NonNull List<WidgetStore.Widget> widgets) {
            items.clear();
            items.addAll(widgets);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_widget, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            WidgetStore.Widget widget = items.get(position);

            // ─── Color dot: oval GradientDrawable filled with widget color ─
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            try {
                oval.setColor(Color.parseColor(widget.color));
            } catch (IllegalArgumentException e) {
                // Defensive: fall back to white if the stored hex is malformed.
                oval.setColor(Color.WHITE);
            }
            holder.colorDot.setBackground(oval);
            
            // ─── Custom Logo Preview ───────────────────────────────────────
            boolean hideLogo = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getBoolean("pref_widget_hide_logo_" + widget.name, false);
                    
            if (hideLogo) {
                holder.planetIcon.setVisibility(View.GONE);
                holder.colorDot.setVisibility(View.GONE);
                holder.customLogo.setVisibility(View.GONE);
            } else if (dev.jaimin.auraorbit.WidgetLogoStore.exists(requireContext(), widget.name)) {
                holder.planetIcon.setVisibility(View.GONE);
                holder.colorDot.setVisibility(View.GONE);
                holder.customLogo.setVisibility(View.VISIBLE);
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(dev.jaimin.auraorbit.WidgetLogoStore.file(requireContext(), widget.name).getAbsolutePath());
                if (bitmap != null) {
                    holder.customLogo.setImageBitmap(bitmap);
                }
            } else {
                holder.planetIcon.setVisibility(View.VISIBLE);
                holder.colorDot.setVisibility(View.VISIBLE);
                holder.customLogo.setVisibility(View.GONE);
            }

            holder.name.setText(widget.name);
            holder.count.setText(getString(R.string.group_member_count,
                    widget.packages.size()));

            // Row tap → edit this widget
            holder.itemView.setOnClickListener(v -> navigateToEdit(widget.name));

            // Delete button tap
            holder.btnDelete.setOnClickListener(v -> confirmDelete(widget.name));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        // ─── ViewHolder ───────────────────────────────────────────────────

        final class VH extends RecyclerView.ViewHolder {
            final View     colorDot;
            final android.widget.ImageView planetIcon;
            final android.widget.ImageView customLogo;
            final TextView name;
            final TextView count;
            final android.widget.ImageView btnDelete;

            VH(@NonNull View itemView) {
                super(itemView);
                colorDot  = itemView.findViewById(R.id.group_color_dot);
                planetIcon = itemView.findViewById(R.id.group_icon_planet);
                customLogo = itemView.findViewById(R.id.group_custom_logo);
                name      = itemView.findViewById(R.id.widget_name);
                count     = itemView.findViewById(R.id.group_count);
                btnDelete = itemView.findViewById(R.id.btn_delete_group);
            }
        }
    }
}
