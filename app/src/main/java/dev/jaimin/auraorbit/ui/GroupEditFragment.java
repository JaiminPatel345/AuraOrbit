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

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.app.PendingIntent;
import android.content.Intent;
import dev.jaimin.auraorbit.WidgetPinnedReceiver;
import dev.jaimin.auraorbit.SphereWidgetProvider;

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
        MaterialButton    btnCancel   = root.findViewById(R.id.btn_cancel);
        MaterialButton    btnSave     = root.findViewById(R.id.btn_save);
        MaterialButton    btnPinWidget= root.findViewById(R.id.btn_pin_widget);

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

        // ─── Cancel button ────────────────────────────────────────────────
        btnCancel.setOnClickListener(v -> getParentFragmentManager().popBackStack());

        // ─── Save button ──────────────────────────────────────────────────
        btnSave.setOnClickListener(v -> onSaveClicked(nameInput, prefs));

        // ─── Pin Widget button ────────────────────────────────────────────
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(requireContext());
        if (originalGroupName != null && appWidgetManager.isRequestPinAppWidgetSupported()) {
            btnPinWidget.setVisibility(View.VISIBLE);
            btnPinWidget.setOnClickListener(v -> requestPinWidget(originalGroupName));
        }

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

    private void requestPinWidget(String groupName) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(requireContext());
        ComponentName myProvider = new ComponentName(requireContext(), SphereWidgetProvider.class);

        if (appWidgetManager.isRequestPinAppWidgetSupported()) {
            Intent callbackIntent = new Intent(requireContext(), WidgetPinnedReceiver.class);
            callbackIntent.putExtra(WidgetPinnedReceiver.EXTRA_GROUP_NAME, groupName);
            
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                // Before Android 12, FLAG_MUTABLE is not strictly required but we shouldn't use FLAG_IMMUTABLE 
                // because the system needs to modify the intent to add EXTRA_APPWIDGET_ID.
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent successCallback = PendingIntent.getBroadcast(
                    requireContext(),
                    0,
                    callbackIntent,
                    flags
            );

            appWidgetManager.requestPinAppWidget(myProvider, null, successCallback);
        } else {
            Toast.makeText(requireContext(), "Pinning widgets is not supported on this device.", Toast.LENGTH_SHORT).show();
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

        boolean isCustomSelected = true;
        for (String hex : colorHexValues) {
            if (hex.equalsIgnoreCase(selectedColor)) {
                isCustomSelected = false;
                break;
            }
        }

        for (int i = 0; i < colorHexValues.length; i++) {
            final String hex = colorHexValues[i];

            View circle = new View(requireContext());
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(circleSizePx, circleSizePx);
            lp.setMarginEnd(marginEndPx);
            circle.setLayoutParams(lp);
            circle.setContentDescription(colorNames[i]);

            colorCircles.add(circle);
            colorRow.addView(circle);

            applyCircleDrawable(circle, hex, hex.equalsIgnoreCase(selectedColor));

            circle.setOnClickListener(v -> {
                selectedColor = hex;
                buildColorRow(colorRow);
            });
        }

        // Add Custom Color Circle
        View customCircle = new View(requireContext());
        LinearLayout.LayoutParams customLp =
                new LinearLayout.LayoutParams(circleSizePx, circleSizePx);
        customCircle.setLayoutParams(customLp);
        customCircle.setContentDescription("Custom Color");

        if (isCustomSelected) {
            applyCircleDrawable(customCircle, selectedColor, true);
        } else {
            // Draw a rainbow wheel
            android.graphics.drawable.ShapeDrawable rainbow = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.OvalShape());
            rainbow.getPaint().setShader(new android.graphics.SweepGradient(
                    circleSizePx / 2f, circleSizePx / 2f,
                    new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED},
                    null));
            customCircle.setBackground(rainbow);
        }

        customCircle.setOnClickListener(v -> showColorPickerDialog());
        colorRow.addView(customCircle);
    }

    private void showColorPickerDialog() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24));

        View preview = new View(requireContext());
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(dpToPx(100), dpToPx(100));
        previewLp.gravity = android.view.Gravity.CENTER;
        previewLp.bottomMargin = dpToPx(24);
        preview.setLayoutParams(previewLp);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        preview.setBackground(gd);

        int currentColor;
        try {
            currentColor = Color.parseColor(selectedColor);
        } catch (Exception e) {
            currentColor = Color.parseColor(colorHexValues[0]);
        }

        final int[] rgb = { Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor) };
        gd.setColor(Color.rgb(rgb[0], rgb[1], rgb[2]));

        com.google.android.material.slider.Slider sliderR = new com.google.android.material.slider.Slider(requireContext());
        sliderR.setValueFrom(0); sliderR.setValueTo(255); sliderR.setValue(rgb[0]);
        sliderR.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.RED));
        sliderR.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(Color.RED));

        com.google.android.material.slider.Slider sliderG = new com.google.android.material.slider.Slider(requireContext());
        sliderG.setValueFrom(0); sliderG.setValueTo(255); sliderG.setValue(rgb[1]);
        sliderG.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.GREEN));
        sliderG.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(Color.GREEN));

        com.google.android.material.slider.Slider sliderB = new com.google.android.material.slider.Slider(requireContext());
        sliderB.setValueFrom(0); sliderB.setValueTo(255); sliderB.setValue(rgb[2]);
        sliderB.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.BLUE));
        sliderB.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(Color.BLUE));

        com.google.android.material.slider.Slider.OnChangeListener listener = (slider, value, fromUser) -> {
            if (slider == sliderR) rgb[0] = (int) value;
            if (slider == sliderG) rgb[1] = (int) value;
            if (slider == sliderB) rgb[2] = (int) value;
            gd.setColor(Color.rgb(rgb[0], rgb[1], rgb[2]));
        };
        sliderR.addOnChangeListener(listener);
        sliderG.addOnChangeListener(listener);
        sliderB.addOnChangeListener(listener);

        layout.addView(preview);
        
        TextView tvR = new TextView(requireContext()); tvR.setText("Red"); layout.addView(tvR); layout.addView(sliderR);
        TextView tvG = new TextView(requireContext()); tvG.setText("Green"); layout.addView(tvG); layout.addView(sliderG);
        TextView tvB = new TextView(requireContext()); tvB.setText("Blue"); layout.addView(tvB); layout.addView(sliderB);

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("Custom Color")
            .setView(layout)
            .setPositiveButton("Select", (dialog, which) -> {
                selectedColor = String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
                View colorRow = requireView().findViewById(R.id.color_row);
                if (colorRow instanceof LinearLayout) {
                    buildColorRow((LinearLayout) colorRow);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
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

        executor.submit(() -> {
            PackageManager pm = appCtx.getPackageManager();
            java.util.List<android.content.pm.ResolveInfo> resolvedApps = AppFetcher.getAllLaunchableApps(appCtx);

            List<MemberRow> rows = new ArrayList<>();
            for (android.content.pm.ResolveInfo ri : resolvedApps) {
                String pkg = ri.activityInfo.packageName;
                String label = ri.loadLabel(pm).toString();
                Drawable icon = ri.loadIcon(pm);

                // Determine if this app already belongs to a DIFFERENT group.
                GroupStore.Group owningGroup = pkgToGroup.get(pkg);
                String otherGroupName = null;
                if (owningGroup != null
                        && !owningGroup.name.equalsIgnoreCase(
                                originalGroupName == null ? "" : originalGroupName)) {
                    otherGroupName = owningGroup.name;
                }

                rows.add(new MemberRow(pkg, label, icon, otherGroupName));
            }

            // Sort: apps which are selected (workingMembers.contains(a.packageName)) first, then alphabetically
            rows.sort((a, b) -> {
                boolean aSel = workingMembers.contains(a.packageName);
                boolean bSel = workingMembers.contains(b.packageName);
                if (aSel && !bSel) return -1;
                if (!aSel && bSel) return 1;
                return a.label.compareToIgnoreCase(b.label);
            });

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

        // Ensure apps added to this group are also visible on the sphere
        Set<String> selectedApps = new HashSet<>(prefs.getStringSet(AppFetcher.PREF_SELECTED_APPS, new HashSet<>()));
        if (selectedApps.addAll(workingMembers)) {
            prefs.edit().putStringSet(AppFetcher.PREF_SELECTED_APPS, selectedApps).apply();
        }

        Toast.makeText(requireContext(),
                R.string.toast_saved,
                Toast.LENGTH_SHORT).show();

        // Return to GroupListFragment (or wherever we came from).
        getParentFragmentManager().popBackStack();
    }


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
