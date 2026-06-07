package dev.jaimin.auraorbit.ui;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jaimin.auraorbit.AppFetcher;
import dev.jaimin.auraorbit.GroupStore;
import dev.jaimin.auraorbit.R;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * GroupEditFragment.java — Create or edit a single app group
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Inflates {@code fragment_group_edit}. Key view ids:
 *   {@code group_name_input}, {@code color_row}, {@code member_search_input},
 *   {@code member_list}, {@code btn_delete} (gone by default), {@code btn_save}.
 *
 * Rows use {@code row_group_member}: {@code member_icon}, {@code member_label},
 * {@code member_subtitle} (gone by default), {@code member_check} (non-clickable).
 *
 * ─── Modes ───────────────────────────────────────────────────────────────────
 *
 * Create mode ({@code groupName} arg is {@code null}):
 *   - Title = {@code title_new_group}
 *   - {@code btn_delete} remains GONE.
 *
 * Edit mode ({@code groupName} arg is non-null):
 *   - Title = {@code title_edit_group}
 *   - Prefills name, selected color, and member checkboxes from the stored group.
 *   - {@code btn_delete} is made VISIBLE.
 *
 * ─── Member list ─────────────────────────────────────────────────────────────
 *
 * Only apps that are currently in the "selected apps" set
 * ({@link AppFetcher#PREF_SELECTED_APPS}) appear in the member list. Uninstalled
 * apps are silently skipped. The list is loaded off the main thread and filtered
 * by the {@code member_search_input} TextWatcher. Each row's subtitle shows
 * "In <other group> — saving will move it" when the app belongs to a different group.
 *
 * ─── Save ────────────────────────────────────────────────────────────────────
 *
 * {@link GroupStore#upsert} is used for both create and edit. On success,
 * {@link GroupStore#save} persists the new list, a toast is shown, and the
 * fragment pops off the back stack. On validation failure (empty name or duplicate)
 * a descriptive toast is shown and the fragment stays open.
 *
 * ─── Delete ──────────────────────────────────────────────────────────────────
 *
 * A {@link MaterialAlertDialogBuilder} confirmation dialog is shown before
 * {@link GroupStore#delete} + {@link GroupStore#save} + pop.
 */
public class GroupEditFragment extends Fragment {

    // ─── Fragment argument key ────────────────────────────────────────────
    private static final String ARG_GROUP_NAME = "group_name";

    // ─── Background loader (icons + labels) ──────────────────────────────
    private ExecutorService executor;

    // ─── State ────────────────────────────────────────────────────────────
    /** The original name of the group being edited, or {@code null} in create mode. */
    @Nullable private String originalGroupName;
    /** Currently selected color hex string. */
    private String selectedColor;
    /**
     * Working membership set — modified by row clicks, committed on Save.
     * Starts as a copy of the group's current members (edit mode) or empty
     * (create mode).
     */
    private final Set<String> workingMembers = new HashSet<>();

    // ─── Color palette (from res/values/colors.xml) ───────────────────────
    // Loaded in onViewCreated; stored as fields so color-circle click lambdas
    // can update the stroke without re-reading resources.
    private String[] colorHexValues;
    private String[] colorNames;
    /** Circle views in the color row; needed to redraw strokes on selection change. */
    private final List<View> colorCircles = new ArrayList<>();

    // ─── Member adapter reference kept for search-filter updates ─────────
    private MemberAdapter memberAdapter;

    // ─────────────────────────────────────────────────────────────────────
    //  Factory
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Creates an instance of {@link GroupEditFragment}.
     *
     * @param groupName  Name of the group to edit, or {@code null} to create a new group.
     * @return Configured fragment.
     */
    @NonNull
    public static GroupEditFragment newInstance(@Nullable String groupName) {
        GroupEditFragment f = new GroupEditFragment();
        Bundle args = new Bundle();
        args.putString(ARG_GROUP_NAME, groupName); // putString(key, null) is valid
        f.setArguments(args);
        return f;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Fragment lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();

        // Read the argument once; keep in a field for use across methods.
        if (getArguments() != null) {
            originalGroupName = getArguments().getString(ARG_GROUP_NAME);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_group_edit, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Load the color palette from resources.
        colorHexValues = requireContext().getResources()
                .getStringArray(R.array.group_color_hex);
        colorNames = requireContext().getResources()
                .getStringArray(R.array.group_color_names);

        // ─── Views ────────────────────────────────────────────────────────
        TextInputEditText nameInput    = root.findViewById(R.id.group_name_input);
        LinearLayout      colorRow    = root.findViewById(R.id.color_row);
        TextInputEditText memberSearch = root.findViewById(R.id.member_search_input);
        RecyclerView      memberList  = root.findViewById(R.id.member_list);
        MaterialButton    btnDelete   = root.findViewById(R.id.btn_delete);
        MaterialButton    btnSave     = root.findViewById(R.id.btn_save);

        memberList.setLayoutManager(new LinearLayoutManager(requireContext()));
        memberAdapter = new MemberAdapter();
        memberList.setAdapter(memberAdapter);

        // ─── Load existing group data if editing ──────────────────────────
        List<GroupStore.Group> groups = GroupStore.load(prefs);
        GroupStore.Group existingGroup = (originalGroupName != null)
                ? GroupStore.find(groups, originalGroupName)
                : null;

        // Seed the working members set.
        if (existingGroup != null) {
            workingMembers.addAll(existingGroup.packages);

            // "What you see is what you save": retain only packages that are
            // currently selected (visible in the member list). A package that
            // was deselected in the App Picker would otherwise stay invisibly
            // in the working set and get re-saved on the next Save tap.
            Set<String> selectedSet = prefs.getStringSet(
                    AppFetcher.PREF_SELECTED_APPS, new HashSet<>());
            workingMembers.retainAll(selectedSet);
        }

        // Prefill name.
        if (existingGroup != null) {
            nameInput.setText(existingGroup.name);
        }

        // Determine initial selected color.
        selectedColor = (existingGroup != null && existingGroup.color != null)
                ? existingGroup.color
                : colorHexValues[0];

        // ─── Color circles ────────────────────────────────────────────────
        buildColorRow(colorRow);

        // ─── Delete button (edit mode only) ──────────────────────────────
        if (existingGroup != null) {
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> confirmDelete(prefs, groups));
        }
        // In create mode, btnDelete remains GONE as per the XML default.

        // ─── Save button ──────────────────────────────────────────────────
        btnSave.setOnClickListener(v -> onSaveClicked(nameInput, prefs));

        // ─── Member search ────────────────────────────────────────────────
        memberSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                memberAdapter.filter(s == null ? "" : s.toString());
            }
        });

        // ─── Load members asynchronously ──────────────────────────────────
        loadMembersAsync(prefs, groups);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Set the appropriate title.
        requireActivity().setTitle(originalGroupName == null
                ? R.string.title_new_group
                : R.string.title_edit_group);
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
    //  Color row builder
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Populates {@code color_row} with 8 colored circles (40dp each, 8dp end margin).
     * The currently-selected color gets a 3dp white stroke ring.
     * Clicking a circle updates {@link #selectedColor} and redraws all strokes.
     *
     * @param colorRow  The {@link LinearLayout} that hosts the circles.
     */
    private void buildColorRow(@NonNull LinearLayout colorRow) {
        colorRow.removeAllViews(); // defensive — fragment might be re-created
        colorCircles.clear();

        int circleSizePx = dpToPx(40);
        int marginEndPx  = dpToPx(8);

        for (int i = 0; i < colorHexValues.length; i++) {
            final String hex = colorHexValues[i];
            final int    idx = i;

            View circle = new View(requireContext());
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(circleSizePx, circleSizePx);
            lp.setMarginEnd(marginEndPx);
            circle.setLayoutParams(lp);
            circle.setContentDescription(colorNames[i]);

            colorCircles.add(circle);
            colorRow.addView(circle);

            // Draw the circle (and initial stroke if selected).
            applyCircleDrawable(circle, hex, hex.equalsIgnoreCase(selectedColor));

            circle.setOnClickListener(v -> {
                selectedColor = hex;
                // Redraw all circles: only the new selection gets a stroke.
                for (int j = 0; j < colorCircles.size(); j++) {
                    applyCircleDrawable(
                            colorCircles.get(j),
                            colorHexValues[j],
                            colorHexValues[j].equalsIgnoreCase(hex)
                    );
                }
            });
        }
    }

    /**
     * Applies (or re-applies) a {@link GradientDrawable} oval background to
     * {@code circle} in the given {@code hex} color. If {@code selected} is
     * {@code true}, adds a 3dp white stroke.
     */
    private void applyCircleDrawable(@NonNull View circle,
                                     @NonNull String hex,
                                     boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        try {
            d.setColor(Color.parseColor(hex));
        } catch (IllegalArgumentException e) {
            d.setColor(Color.WHITE);
        }
        if (selected) {
            d.setStroke(dpToPx(3), Color.WHITE);
        }
        circle.setBackground(d);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Async member loading
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Loads the member list (apps currently in "selected apps") on a background
     * thread, resolving labels and icons via PackageManager. Uninstalled apps
     * are silently skipped. Results are posted to the main thread.
     *
     * @param prefs   SharedPreferences for reading selected-app and group data.
     * @param groups  Full group list, used for "in other group" subtitle logic.
     */
    private void loadMembersAsync(@NonNull SharedPreferences prefs,
                                  @NonNull List<GroupStore.Group> groups) {
        android.content.Context appCtx = requireContext().getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        // Build a package→group reverse map to detect conflicting membership.
        Map<String, GroupStore.Group> pkgToGroup = GroupStore.packageToGroup(groups);

        // Read selected apps before going off-thread (prefs access is thread-safe).
        Set<String> selectedApps = prefs.getStringSet(
                AppFetcher.PREF_SELECTED_APPS, new HashSet<>());

        executor.submit(() -> {
            PackageManager pm = appCtx.getPackageManager();

            List<MemberRow> rows = new ArrayList<>();
            for (String pkg : selectedApps) {
                try {
                    android.content.pm.ApplicationInfo info =
                            pm.getApplicationInfo(pkg, 0);
                    String label    = pm.getApplicationLabel(info).toString();
                    Drawable icon   = pm.getApplicationIcon(info);

                    // Determine if this app already belongs to a DIFFERENT group.
                    GroupStore.Group owningGroup = pkgToGroup.get(pkg);
                    String otherGroupName = null;
                    if (owningGroup != null
                            && !owningGroup.name.equalsIgnoreCase(
                                    originalGroupName == null ? "" : originalGroupName)) {
                        otherGroupName = owningGroup.name;
                    }

                    rows.add(new MemberRow(pkg, label, icon, otherGroupName));
                } catch (PackageManager.NameNotFoundException e) {
                    // App uninstalled since selection — skip silently.
                }
            }

            // Sort alphabetically by label for a predictable order.
            rows.sort((a, b) -> a.label.compareToIgnoreCase(b.label));

            mainHandler.post(() -> {
                if (!isAdded()) return; // Fragment detached while loading
                memberAdapter.setItems(rows);
            });
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Save handler
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Validates the input and calls {@link GroupStore#upsert}. On success,
     * saves to SharedPreferences, shows a toast, and pops the fragment.
     * On validation failure, shows a descriptive toast and stays open.
     *
     * @param nameInput  The name text input view.
     * @param prefs      SharedPreferences instance.
     */
    private void onSaveClicked(@NonNull TextInputEditText nameInput,
                               @NonNull SharedPreferences prefs) {
        Editable editable = nameInput.getText();
        String newName = (editable == null) ? "" : editable.toString().trim();

        // Validate: name must not be empty.
        if (newName.isEmpty()) {
            Toast.makeText(requireContext(),
                    R.string.toast_group_name_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Reload latest group list to prevent stale-data issues (another agent
        // may have saved groups while this fragment was open, but in practice
        // the list is fresh because GroupStore.load is side-effect-free here).
        List<GroupStore.Group> groups = GroupStore.load(prefs);

        boolean ok = GroupStore.upsert(
                groups,
                originalGroupName, // null → create; non-null → edit
                newName,
                selectedColor,
                new HashSet<>(workingMembers) // pass a copy
        );

        if (!ok) {
            // upsert returns false on: name collision with different group,
            // or empty name (already guarded above), or old-name-not-found.
            Toast.makeText(requireContext(),
                    R.string.toast_group_exists,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Persist the mutated list.
        GroupStore.save(prefs, groups);

        Toast.makeText(requireContext(),
                R.string.toast_saved,
                Toast.LENGTH_SHORT).show();

        // Return to GroupListFragment (or wherever we came from).
        getParentFragmentManager().popBackStack();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Delete handler
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Shows a confirmation dialog before deleting the group.
     *
     * @param prefs   SharedPreferences instance.
     * @param groups  Current in-memory group list (will be mutated on confirm).
     */
    private void confirmDelete(@NonNull SharedPreferences prefs,
                               @NonNull List<GroupStore.Group> groups) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_delete_confirm, originalGroupName))
                .setMessage(R.string.dialog_delete_confirm_msg)
                .setPositiveButton(R.string.btn_delete_group, (dialog, which) -> {
                    // Re-load to get the freshest state before deleting.
                    List<GroupStore.Group> freshGroups = GroupStore.load(prefs);
                    GroupStore.delete(freshGroups, originalGroupName);
                    GroupStore.save(prefs, freshGroups);

                    Toast.makeText(requireContext(),
                            R.string.toast_group_deleted,
                            Toast.LENGTH_SHORT).show();

                    getParentFragmentManager().popBackStack();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────────────────────────────

    /** Converts dp to pixels using the current display density. */
    private int dpToPx(int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                requireContext().getResources().getDisplayMetrics()));
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Member row model
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Data bag for a single row in the member list.
     * {@code inOtherGroupName} is {@code null} when the app belongs to no
     * group, the current group, or no group at all.
     */
    private static final class MemberRow {
        final String   packageName;
        final String   label;
        final Drawable icon;
        /**
         * Non-null only when the app is currently in a DIFFERENT group,
         * triggering the "In X — saving will move it" subtitle.
         */
        @Nullable final String inOtherGroupName;

        MemberRow(String packageName, String label, Drawable icon,
                  @Nullable String inOtherGroupName) {
            this.packageName      = packageName;
            this.label            = label;
            this.icon             = icon;
            this.inOtherGroupName = inOtherGroupName;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  RecyclerView Adapter
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Adapter for the member list inside GroupEditFragment.
     *
     * <p>Maintains a full list and a filtered display list, just like
     * {@link AppPickerFragment}'s adapter. Toggling a row updates
     * {@link #workingMembers} in the enclosing fragment — the set is then
     * passed to {@link GroupStore#upsert} on Save.</p>
     *
     * <p>The checkbox in each row is {@code clickable=false} (declared in
     * {@code row_group_member.xml}), so only the row's root click fires.</p>
     *
     * <p>The {@code member_subtitle} visibility is explicitly set both ways
     * in {@link #onBindViewHolder} to handle recycled views correctly.</p>
     */
    private final class MemberAdapter
            extends RecyclerView.Adapter<MemberAdapter.VH> {

        private final List<MemberRow> allItems     = new ArrayList<>();
        private final List<MemberRow> displayItems = new ArrayList<>();
        private String currentQuery = "";

        void setItems(@NonNull List<MemberRow> items) {
            allItems.clear();
            allItems.addAll(items);
            filter(currentQuery);
        }

        /**
         * Filters the displayed list by label or package name
         * (case-insensitive contains).
         */
        void filter(@Nullable String query) {
            currentQuery = query == null ? "" : query.trim().toLowerCase();
            displayItems.clear();
            if (currentQuery.isEmpty()) {
                displayItems.addAll(allItems);
            } else {
                for (MemberRow r : allItems) {
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
                    .inflate(R.layout.row_group_member, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            MemberRow row = displayItems.get(position);

            holder.icon.setImageDrawable(row.icon);
            holder.label.setText(row.label);

            // ─── "Already in other group" subtitle + disabled state ───────
            // Handle BOTH states explicitly to handle recycled views that
            // previously showed the subtitle but now should not.
            boolean lockedByOtherGroup = row.inOtherGroupName != null;
            if (lockedByOtherGroup) {
                holder.subtitle.setText(getString(
                        R.string.member_in_other_group, row.inOtherGroupName));
                holder.subtitle.setVisibility(View.VISIBLE);
            } else {
                holder.subtitle.setVisibility(View.GONE);
            }

            // ─── Checkbox state ───────────────────────────────────────────
            // Detach listener before setting state to avoid re-entrant calls.
            holder.check.setOnCheckedChangeListener(null);

            if (lockedByOtherGroup) {
                // App belongs to a DIFFERENT group: disable the row entirely.
                // The checkbox must be unchecked so it can never enter workingMembers.
                holder.check.setChecked(false);
                holder.check.setEnabled(false);
                holder.itemView.setEnabled(false);
                holder.itemView.setAlpha(0.45f);
                holder.itemView.setOnClickListener(null); // row click does nothing
            } else {
                // App is ungrouped or already in THIS group: fully interactive.
                holder.check.setEnabled(true);
                holder.itemView.setEnabled(true);
                holder.itemView.setAlpha(1f);

                boolean isMember = workingMembers.contains(row.packageName);
                holder.check.setChecked(isMember);

                // Row click toggles membership in the working set.
                holder.itemView.setOnClickListener(v -> {
                    boolean nowMember = workingMembers.contains(row.packageName);
                    if (nowMember) {
                        workingMembers.remove(row.packageName);
                        holder.check.setChecked(false);
                    } else {
                        workingMembers.add(row.packageName);
                        holder.check.setChecked(true);
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return displayItems.size();
        }

        // ─── ViewHolder ───────────────────────────────────────────────────

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView  label;
            final TextView  subtitle;
            final CheckBox  check;

            VH(@NonNull View itemView) {
                super(itemView);
                icon     = itemView.findViewById(R.id.member_icon);
                label    = itemView.findViewById(R.id.member_label);
                subtitle = itemView.findViewById(R.id.member_subtitle);
                check    = itemView.findViewById(R.id.member_check);
            }
        }
    }
}
