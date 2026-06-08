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

import dev.jaimin.auraorbit.GroupStore;
import dev.jaimin.auraorbit.R;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * GroupListFragment.java — Scrollable list of all configured app groups
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Inflates {@code fragment_group_list} (ids: {@code group_list}, {@code empty_view},
 * {@code fab_add}). Each row uses {@code row_group} (ids: {@code group_color_dot},
 * {@code group_name}, {@code group_count}).
 *
 * ─── Data refresh ────────────────────────────────────────────────────────────
 *
 * Groups are reloaded from SharedPreferences in {@link #onResume} so that
 * returning from {@link GroupEditFragment} (after a save or delete) always
 * shows the current state without manual cache invalidation.
 *
 * ─── Empty state ─────────────────────────────────────────────────────────────
 *
 * When no groups exist, the RecyclerView is hidden (GONE) and {@code empty_view}
 * is shown (VISIBLE). The inverse applies when at least one group exists.
 *
 * ─── Navigation ──────────────────────────────────────────────────────────────
 *
 * Row taps and the FAB both navigate to {@link GroupEditFragment} using the
 * shared {@code R.id.settings_container} back-stack pattern.
 */
public class GroupListFragment extends Fragment {

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
        return inflater.inflate(R.layout.fragment_group_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        recyclerView = root.findViewById(R.id.group_list);
        emptyView    = root.findViewById(R.id.empty_view);
        FloatingActionButton fab = root.findViewById(R.id.fab_add);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new GroupAdapter();
        recyclerView.setAdapter(adapter);

        // FAB: navigate to GroupEditFragment in "create new" mode (groupName = null).
        fab.setOnClickListener(v -> navigateToEdit(null));
    }

    @Override
    public void onResume() {
        super.onResume();
        // Set the fragment title in the Activity's action bar.
        requireActivity().setTitle(R.string.title_groups);
        // Reload groups — this covers the "return from GroupEditFragment" case.
        reloadGroups();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Data reload
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Reads the current group list from SharedPreferences and updates the
     * adapter and empty-state visibility. Safe to call from the main thread
     * because {@link GroupStore#load} only does a JSON parse.
     */
    private void reloadGroups() {
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());
        List<GroupStore.Group> groups = GroupStore.load(prefs);

        adapter.setGroups(groups);

        // Toggle empty state visibility
        if (groups.isEmpty()) {
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
     * Navigates to {@link GroupEditFragment}, passing the group name as an
     * argument (or {@code null} for the create-new flow).
     *
     * @param groupName  Name of the group to edit, or {@code null} to create new.
     */
    private void navigateToEdit(@Nullable String groupName) {
        getParentFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, GroupEditFragment.newInstance(groupName))
                .addToBackStack(null)
                .commit();
    }

    /**
     * Shows a confirmation dialog before deleting the group.
     */
    private void confirmDelete(@NonNull String groupName) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_delete_confirm, groupName))
                .setMessage(R.string.dialog_delete_confirm_msg)
                .setPositiveButton(R.string.btn_delete_group, (dialog, which) -> {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                    List<GroupStore.Group> freshGroups = GroupStore.load(prefs);
                    GroupStore.delete(freshGroups, groupName);
                    GroupStore.save(prefs, freshGroups);

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
     * Adapter for the group list.
     *
     * <p>Each row shows a coloured oval ({@code group_color_dot}), the group name
     * ({@code group_name}), and the member count ({@code group_count}).
     * Tapping a row opens {@link GroupEditFragment} for that group.</p>
     */
    private final class GroupAdapter
            extends RecyclerView.Adapter<GroupAdapter.VH> {

        private final List<GroupStore.Group> items = new ArrayList<>();

        void setGroups(@NonNull List<GroupStore.Group> groups) {
            items.clear();
            items.addAll(groups);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_group, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            GroupStore.Group group = items.get(position);

            // ─── Color dot: oval GradientDrawable filled with group color ─
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            try {
                oval.setColor(Color.parseColor(group.color));
            } catch (IllegalArgumentException e) {
                // Defensive: fall back to white if the stored hex is malformed.
                oval.setColor(Color.WHITE);
            }
            holder.colorDot.setBackground(oval);

            holder.name.setText(group.name);
            holder.count.setText(getString(R.string.group_member_count,
                    group.packages.size()));

            // Row tap → edit this group
            holder.itemView.setOnClickListener(v -> navigateToEdit(group.name));

            // Delete button tap
            holder.btnDelete.setOnClickListener(v -> confirmDelete(group.name));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        // ─── ViewHolder ───────────────────────────────────────────────────

        final class VH extends RecyclerView.ViewHolder {
            final View     colorDot;
            final TextView name;
            final TextView count;
            final android.widget.ImageView btnDelete;

            VH(@NonNull View itemView) {
                super(itemView);
                colorDot  = itemView.findViewById(R.id.group_color_dot);
                name      = itemView.findViewById(R.id.group_name);
                count     = itemView.findViewById(R.id.group_count);
                btnDelete = itemView.findViewById(R.id.btn_delete_group);
            }
        }
    }
}
