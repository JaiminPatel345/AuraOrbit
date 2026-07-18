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
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import dev.jaimin.auraorbit.WidgetPinnedReceiver;
import dev.jaimin.auraorbit.SphereWidgetProvider;
import dev.jaimin.auraorbit.WidgetLogoStore;
import java.io.File;

import dev.jaimin.auraorbit.AppFetcher;
import dev.jaimin.auraorbit.WidgetStore;
import dev.jaimin.auraorbit.R;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * WidgetEditFragment.java — Create or edit a single app widget
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Inflates {@code fragment_widget_edit}. Key view ids:
 *   {@code group_name_input}, {@code color_row}, {@code member_search_input},
 *   {@code member_list}, {@code btn_delete} (gone by default), {@code btn_save}.
 *
 * Rows use {@code row_widget_member}: {@code member_icon}, {@code member_label},
 * {@code member_subtitle} (gone by default), {@code member_check} (non-clickable).
 *
 * ─── Modes ───────────────────────────────────────────────────────────────────
 *
 * Create mode ({@code widgetName} arg is {@code null}):
 *   - Title = {@code title_new_group}
 *   - {@code btn_delete} remains GONE.
 *
 * Edit mode ({@code widgetName} arg is non-null):
 *   - Title = {@code title_edit_group}
 *   - Prefills name, selected color, and member checkboxes from the stored widget.
 *   - {@code btn_delete} is made VISIBLE.
 *
 * ─── Member list ─────────────────────────────────────────────────────────────
 *
 * Only apps that are currently in the "selected apps" set
 * ({@link AppFetcher#PREF_SELECTED_APPS}) appear in the member list. Uninstalled
 * apps are silently skipped. The list is loaded off the main thread and filtered
 * by the {@code member_search_input} TextWatcher. Each row's subtitle shows
 * "In <other widget> — saving will move it" when the app belongs to a different widget.
 *
 * ─── Save ────────────────────────────────────────────────────────────────────
 *
 * {@link WidgetStore#upsert} is used for both create and edit. On success,
 * {@link WidgetStore#save} persists the new list, a toast is shown, and the
 * fragment pops off the back stack. On validation failure (empty name or duplicate)
 * a descriptive toast is shown and the fragment stays open.
 *
 * ─── Delete ──────────────────────────────────────────────────────────────────
 *
 * A {@link MaterialAlertDialogBuilder} confirmation dialog is shown before
 * {@link WidgetStore#delete} + {@link WidgetStore#save} + pop.
 */
public class WidgetEditFragment extends Fragment {

    // ─── Fragment argument key ────────────────────────────────────────────
    private static final String ARG_WIDGET_NAME = "widget_name";

    // ─── Background loader (icons + labels) ──────────────────────────────
    private ExecutorService executor;

    // ─── State ────────────────────────────────────────────────────────────
    /** The original name of the widget being edited, or {@code null} in create mode. */
    @Nullable private String originalWidgetName;
    /** Currently selected color hex string. */
    private String selectedColor;
    /**
     * Working membership set — modified by row clicks, committed on Save.
     * Starts as a copy of the widget's current members (edit mode) or empty
     * (create mode).
     */
    private final Set<String> workingMembers = new HashSet<>();
    
    // ─── Widget Customization State ──────────────────────────────────────
    private Uri pendingLogoUri = null;
    private boolean pendingLogoClear = false;
    private boolean isHideLogo = false;
    private boolean isHideText = false;
    private boolean isTransparent = true;
    private boolean isUseThemeColor = true;
    private int customIconSize = 50;
    private int customSpeed = 100;
    private String customFps = "120";
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;
    private ActivityResultLauncher<PickVisualMediaRequest> pickBackgroundMedia;
    private Uri pendingBackgroundUri = null;
    private boolean pendingBackgroundClear = false;
    
    // ─── Preview Views ───────────────────────────────────────────────────
    private View previewIconContainer;
    private ImageView previewPlanet;
    private ImageView previewRing;
    private ImageView previewCustomLogo;
    private TextView previewLabel;
    private TextView logoStatusLabel;
    private View defaultLogoOptionsContainer;
    private View customLogoOptionsContainer;
    private MaterialButton btnWidgetLogo;
    private com.google.android.material.materialswitch.MaterialSwitch hideLogoSwitch;
    private com.google.android.material.materialswitch.MaterialSwitch hideTextSwitch;
    private com.google.android.material.materialswitch.MaterialSwitch transparentSwitch;
    private com.google.android.material.materialswitch.MaterialSwitch themeColorSwitch;
    
    private TextView tvSpherePositionStatus;
    private TextView tvBlurStatus;
    private TextView tvBackgroundStatus;

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
     * Creates an instance of {@link WidgetEditFragment}.
     *
     * @param widgetName  Name of the widget to edit, or {@code null} to create a new widget.
     * @return Configured fragment.
     */
    @NonNull
    public static WidgetEditFragment newInstance(@Nullable String widgetName) {
        WidgetEditFragment f = new WidgetEditFragment();
        Bundle args = new Bundle();
        args.putString(ARG_WIDGET_NAME, widgetName); // putString(key, null) is valid
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
            originalWidgetName = getArguments().getString(ARG_WIDGET_NAME);
        }
        
        pickMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                pendingLogoUri = uri;
                pendingLogoClear = false;
                updateLivePreview();
            }
        });
        
        pickBackgroundMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                pendingBackgroundUri = uri;
                pendingBackgroundClear = false;
                updateBackgroundStatus();
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_widget_edit, container, false);
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
        MaterialButton    btnPinWidget= root.findViewById(R.id.btn_pin_widget);
        View btnInfoPinWidget = root.findViewById(R.id.btn_info_pin_widget);

        memberList.setLayoutManager(new LinearLayoutManager(requireContext()));
        memberAdapter = new MemberAdapter();
        memberList.setAdapter(memberAdapter);
        
        previewIconContainer = root.findViewById(R.id.preview_icon_container);
        previewPlanet = root.findViewById(R.id.preview_icon_planet);
        previewRing = root.findViewById(R.id.preview_icon_ring);
        previewCustomLogo = root.findViewById(R.id.preview_custom_logo);
        previewLabel = root.findViewById(R.id.preview_label);
        logoStatusLabel = root.findViewById(R.id.tv_widget_logo_status);
        defaultLogoOptionsContainer = root.findViewById(R.id.default_logo_options_container);
        customLogoOptionsContainer = root.findViewById(R.id.custom_logo_options_container);
        hideLogoSwitch = root.findViewById(R.id.switch_hide_widget_logo);
        hideTextSwitch = root.findViewById(R.id.switch_hide_widget_text);
        transparentSwitch = root.findViewById(R.id.switch_transparent_widget);
        themeColorSwitch = root.findViewById(R.id.switch_use_theme_color);
        btnWidgetLogo = root.findViewById(R.id.btn_widget_logo);
        MaterialButton btnReplaceCustomLogo = root.findViewById(R.id.btn_replace_custom_logo);
        MaterialButton btnRemoveCustomLogo = root.findViewById(R.id.btn_remove_custom_logo);

        // ─── Info Buttons ─────────────────────────────────────────────────
        root.findViewById(R.id.btn_info_custom_config).setOnClickListener(v -> 
            showInfoDialog("Sphere Configuration", "Set unique size, speed, and FPS for this widget.")
        );
        root.findViewById(R.id.btn_info_orbit_color).setOnClickListener(v -> 
            showInfoDialog("Orbit Color", "Sets the color of the widget's ring and the widget's color in the sphere.")
        );
        root.findViewById(R.id.btn_info_theme_color).setOnClickListener(v -> 
            showInfoDialog("System Theme Color", "Overrides the custom orbit color to match your Android system's Material You theme.")
        );
        root.findViewById(R.id.btn_info_transparent).setOnClickListener(v -> 
            showInfoDialog("Transparent Widget", "Removes the solid background from the widget so it blends seamlessly into your wallpaper.")
        );
        root.findViewById(R.id.btn_info_hide_logo).setOnClickListener(v -> 
            showInfoDialog("Hide Widget Logo", "Makes the widget fully transparent by hiding the icon. Only the text label will remain visible.")
        );
        root.findViewById(R.id.btn_info_hide_text).setOnClickListener(v -> 
            showInfoDialog("Hide Widget Text", "Removes the widget name label displayed beneath the widget.")
        );

        // ─── Load existing widget data if editing ──────────────────────────
        List<WidgetStore.Widget> widgets = WidgetStore.load(prefs);
        WidgetStore.Widget existingGroup = (originalWidgetName != null)
                ? WidgetStore.find(widgets, originalWidgetName)
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
                
        // ─── Widget Customization Init ──────────────────────────────────
        if (originalWidgetName != null) {
            isHideLogo = prefs.getBoolean("pref_widget_hide_logo_" + originalWidgetName, false);
            isHideText = prefs.getBoolean("pref_widget_hide_text_" + originalWidgetName, false);
            isTransparent = prefs.getBoolean("pref_widget_transparent_" + originalWidgetName, true);
            isUseThemeColor = prefs.getBoolean("pref_widget_use_theme_color_" + originalWidgetName, true);
            customIconSize = prefs.getInt("pref_icon_size_" + originalWidgetName, prefs.getInt("pref_icon_size", 50));
            customSpeed = prefs.getInt("pref_rotation_speed_" + originalWidgetName, prefs.getInt("pref_rotation_speed", 100));
            customFps = prefs.getString("pref_target_fps_" + originalWidgetName, prefs.getString("pref_target_fps", "120"));
        } else {
            customIconSize = prefs.getInt("pref_icon_size", 50);
            customSpeed = prefs.getInt("pref_rotation_speed", 100);
            customFps = prefs.getString("pref_target_fps", "120");
        }
        
        com.google.android.material.slider.Slider sliderIconSize = root.findViewById(R.id.slider_icon_size);
        sliderIconSize.setValue(customIconSize);
        sliderIconSize.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) customIconSize = (int) value;
        });

        com.google.android.material.slider.Slider sliderSpeed = root.findViewById(R.id.slider_speed);
        sliderSpeed.setValue(customSpeed);
        sliderSpeed.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) customSpeed = (int) value;
        });

        TextView tvFpsValue = root.findViewById(R.id.tv_fps_value);
        tvFpsValue.setText(customFps + " FPS");
        root.findViewById(R.id.btn_fps).setOnClickListener(v -> {
            String[] options = {"30 FPS", "60 FPS", "90 FPS", "120 FPS"};
            String[] values = {"30", "60", "90", "120"};
            int checkedItem = 3;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(customFps)) {
                    checkedItem = i;
                    break;
                }
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Target FPS")
                    .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                        customFps = values[which];
                        tvFpsValue.setText(options[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        root.findViewById(R.id.btn_reset_config).setOnClickListener(v -> {
            customIconSize = prefs.getInt("pref_icon_size", 50);
            customSpeed = prefs.getInt("pref_rotation_speed", 100);
            customFps = prefs.getString("pref_target_fps", "120");

            sliderIconSize.setValue(customIconSize);
            sliderSpeed.setValue(customSpeed);
            tvFpsValue.setText(customFps + " FPS");
        });

        hideLogoSwitch.setChecked(isHideLogo);
        hideLogoSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isHideLogo = isChecked;
            updateLivePreview();
        });
        
        hideTextSwitch.setChecked(isHideText);
        hideTextSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isHideText = isChecked;
            updateLivePreview();
        });

        transparentSwitch.setChecked(isTransparent);
        transparentSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isTransparent = isChecked;
            updateLivePreview();
        });

        themeColorSwitch.setChecked(isUseThemeColor);
        themeColorSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isUseThemeColor = isChecked;
            updateLivePreview();
        });
        
        btnWidgetLogo.setOnClickListener(v -> {
            pickMedia.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
        });

        btnReplaceCustomLogo.setOnClickListener(v -> {
            pickMedia.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
        });

        btnRemoveCustomLogo.setOnClickListener(v -> {
            pendingLogoClear = true;
            pendingLogoUri = null;
            updateLivePreview();
        });
        
        nameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                updateLivePreview();
            }
        });

        // ─── Color circles ────────────────────────────────────────────────
        buildColorRow(colorRow);

        // ─── Pin Widget button ────────────────────────────────────────────
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(requireContext());
        if (originalWidgetName != null && appWidgetManager.isRequestPinAppWidgetSupported()) {
            btnPinWidget.setVisibility(View.VISIBLE);
            if (btnInfoPinWidget != null) {
                btnInfoPinWidget.setVisibility(View.VISIBLE);
                btnInfoPinWidget.setOnClickListener(v -> showInfoDialog("Pin Widget", "Adds a shortcut to this widget directly on your home screen."));
            }
            btnPinWidget.setOnClickListener(v -> {
                int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(requireContext(), SphereWidgetProvider.class));
                boolean alreadyPinned = false;
                for (int id : appWidgetIds) {
                    if (originalWidgetName.equals(prefs.getString("widget_group_" + id, null))) {
                        alreadyPinned = true;
                        break;
                    }
                }
                
                if (alreadyPinned) {
                    new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Widget Already Pinned")
                        .setMessage("A widget for this widget is already present on your home screen. Do you want to add another one?")
                        .setPositiveButton("Add Another", (dialog, which) -> requestPinWidget(originalWidgetName))
                        .setNegativeButton("Cancel", null)
                        .show();
                } else {
                    requestPinWidget(originalWidgetName);
                }
            });
            
            MaterialButton btnEditApps = root.findViewById(R.id.btn_edit_apps);
            View cardApps = root.findViewById(R.id.card_apps);
            View tvAppsTitle = root.findViewById(R.id.tv_apps_title);
            
            btnEditApps.setVisibility(View.VISIBLE);
            cardApps.setVisibility(View.GONE);
            tvAppsTitle.setVisibility(View.GONE);
            
            btnEditApps.setOnClickListener(v -> {
                ViewGroup parent = (ViewGroup) cardApps.getParent();
                if (parent != null) {
                    parent.removeView(cardApps);
                }
                cardApps.setVisibility(View.VISIBLE);
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Edit Apps")
                    .setView(cardApps)
                    .setPositiveButton("Done", null)
                    .setOnDismissListener(dialog -> {
                        ViewGroup dp = (ViewGroup) cardApps.getParent();
                        if (dp != null) dp.removeView(cardApps);
                        cardApps.setVisibility(View.GONE);
                        ViewGroup constraintLayout = (ViewGroup) tvAppsTitle.getParent();
                        if (constraintLayout != null) {
                            constraintLayout.addView(cardApps, constraintLayout.indexOfChild(tvAppsTitle) + 1);
                        }
                    })
                    .show();
            });
        }
        
        MaterialButton btnSaveNewGroup = root.findViewById(R.id.btn_save_new_widget);
        View actionsSpacer = root.findViewById(R.id.actions_spacer);
        if (originalWidgetName == null) {
            btnSaveNewGroup.setVisibility(View.VISIBLE);
            if (actionsSpacer != null) {
                actionsSpacer.setVisibility(View.GONE);
            }
            btnSaveNewGroup.setOnClickListener(v -> {
                if (saveData()) {
                    btnSaveNewGroup.setVisibility(View.GONE);
                    if (actionsSpacer != null) {
                        actionsSpacer.setVisibility(View.VISIBLE);
                    }
                }
            });
        } else {
            btnSaveNewGroup.setVisibility(View.GONE);
            if (actionsSpacer != null) {
                actionsSpacer.setVisibility(View.VISIBLE);
            }
        }

        // ─── Member search ────────────────────────────────────────────────
        memberSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                memberAdapter.filter(s == null ? "" : s.toString());
            }
        });

        // ─── Load members asynchronously ──────────────────────────────────
        loadMembersAsync(prefs, widgets);
        
        // --- Widget Customization UI Setup ---
        updateLivePreview();
        
        tvSpherePositionStatus = root.findViewById(R.id.tv_sphere_position_status);
        tvBlurStatus = root.findViewById(R.id.tv_blur_status);
        tvBackgroundStatus = root.findViewById(R.id.tv_background_status);
        
        updateSpherePositionStatus();
        updateBlurStatusText(prefs);
        updateBackgroundStatus();
        
        root.findViewById(R.id.btn_sphere_position).setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), dev.jaimin.auraorbit.SpherePositionEditorActivity.class);
            String name = originalWidgetName != null ? originalWidgetName : nameInput.getText().toString();
            intent.putExtra("widget_name", name);
            intent.putExtra("group_name", name);
            startActivity(intent);
        });
        
        root.findViewById(R.id.btn_sphere_blur).setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), dev.jaimin.auraorbit.SphereBlurEditorActivity.class);
            String name = originalWidgetName != null ? originalWidgetName : nameInput.getText().toString();
            intent.putExtra("widget_name", name);
            intent.putExtra("group_name", name);
            startActivity(intent);
        });
        
        root.findViewById(R.id.btn_app_background).setOnClickListener(v -> {
            String gName = originalWidgetName != null ? originalWidgetName : nameInput.getText().toString();
            boolean exists = pendingBackgroundUri != null || (pendingBackgroundClear == false && dev.jaimin.auraorbit.BackgroundStore.exists(requireContext(), gName));
            if (exists) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setItems(new CharSequence[]{"Choose new photo", "Remove photo", "Cancel"}, (dialog, which) -> {
                            if (which == 0) {
                                pickBackgroundMedia.launch(new PickVisualMediaRequest.Builder()
                                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                    .build());
                            } else if (which == 1) {
                                pendingBackgroundUri = null;
                                pendingBackgroundClear = true;
                                updateBackgroundStatus();
                            }
                        })
                        .show();
            } else {
                pickBackgroundMedia.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
            }
        });
    }
    
    private void updateLivePreview() {
        if (!isAdded()) return;
        
        TextInputEditText nameInput = requireView().findViewById(R.id.group_name_input);
        String name = nameInput.getText().toString();
        if (name.isEmpty()) name = "Widget Name";
        previewLabel.setText(name);
        previewLabel.setVisibility(isHideText ? View.GONE : View.VISIBLE);
        
        if (isTransparent || isHideLogo) {
            previewIconContainer.setBackground(null);
        } else {
            previewIconContainer.setBackgroundResource(R.drawable.rounded_bg_solid);
        }
        
        previewIconContainer.setVisibility(View.VISIBLE);

        boolean hasCustom = false;
        if (pendingLogoUri != null) {
            hasCustom = true;
        } else if (!pendingLogoClear && originalWidgetName != null && WidgetLogoStore.exists(requireContext(), originalWidgetName)) {
            hasCustom = true;
        }

        if (hasCustom) {
            defaultLogoOptionsContainer.setVisibility(View.GONE);
            customLogoOptionsContainer.setVisibility(View.VISIBLE);
            btnWidgetLogo.setVisibility(View.GONE);
            logoStatusLabel.setText("Custom Image");
        } else {
            defaultLogoOptionsContainer.setVisibility(View.VISIBLE);
            customLogoOptionsContainer.setVisibility(View.GONE);
            btnWidgetLogo.setVisibility(View.VISIBLE);
            btnWidgetLogo.setText("Upload");
            logoStatusLabel.setText("Default");
        }

        if (isHideLogo) {
            previewPlanet.setVisibility(View.GONE);
            previewRing.setVisibility(View.GONE);
            previewCustomLogo.setVisibility(View.GONE);
        } else {
            if (hasCustom) {
                previewPlanet.setVisibility(View.GONE);
                previewRing.setVisibility(View.GONE);
                previewCustomLogo.setVisibility(View.VISIBLE);
                if (pendingLogoUri != null) {
                    previewCustomLogo.setImageURI(null);
                    previewCustomLogo.setImageURI(pendingLogoUri);
                } else {
                    android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(WidgetLogoStore.file(requireContext(), originalWidgetName).getAbsolutePath());
                    if (b != null) {
                        previewCustomLogo.setImageBitmap(b);
                    }
                }
            } else {
                previewPlanet.setVisibility(View.VISIBLE);
                previewCustomLogo.setVisibility(View.GONE);
                try {
                    if (isUseThemeColor) {
                        previewRing.setColorFilter(requireContext().getColor(R.color.widget_theme_color));
                    } else {
                        previewRing.setColorFilter(Color.parseColor(selectedColor));
                    }
                    previewRing.setVisibility(View.VISIBLE);
                } catch (Exception e) {
                    previewRing.setColorFilter(Color.WHITE);
                    previewRing.setVisibility(View.VISIBLE);
                }
            }
        }

        View orbitColorHeader = requireView().findViewById(R.id.orbit_color_header);
        View colorRowScroll = (View) requireView().findViewById(R.id.color_row).getParent();
        if (orbitColorHeader != null) orbitColorHeader.setVisibility(isUseThemeColor ? View.GONE : View.VISIBLE);
        if (colorRowScroll != null) colorRowScroll.setVisibility(isUseThemeColor ? View.GONE : View.VISIBLE);
    }
    
    private void showInfoDialog(String title, String message) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Got it", null)
                .show();
    }
    
    private void updateSpherePositionStatus() {
        if (tvSpherePositionStatus == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String position = prefs.getString("pref_sphere_position_" + originalWidgetName, "center");
        if (originalWidgetName == null && !prefs.contains("pref_sphere_position_" + originalWidgetName)) {
             position = prefs.getString("pref_sphere_position", "center");
        }
        String display = "Center";
        if ("top".equals(position)) display = "Top";
        else if ("bottom".equals(position)) display = "Bottom";
        else if ("custom".equals(position)) display = "Custom";
        tvSpherePositionStatus.setText(display);
    }

    private void updateBlurStatusText(SharedPreferences prefs) {
        if (tvBlurStatus == null) return;
        int amount = prefs.getInt("pref_blur_radius_" + originalWidgetName, 50);
        if (originalWidgetName == null && !prefs.contains("pref_blur_radius_" + originalWidgetName)) {
            amount = prefs.getInt("pref_blur_radius", 50);
        }
        if (amount == 0) tvBlurStatus.setText("No Blur");
        else if (amount <= 33) tvBlurStatus.setText("Sphere Background Only");
        else if (amount <= 66) tvBlurStatus.setText("Nearby Area");
        else if (amount < 100) tvBlurStatus.setText("Almost Full Screen");
        else tvBlurStatus.setText("Full Screen Blur");
    }

    private void updateBackgroundStatus() {
        if (tvBackgroundStatus == null) return;
        boolean hasBackground = pendingBackgroundUri != null || (!pendingBackgroundClear && dev.jaimin.auraorbit.BackgroundStore.exists(requireContext(), originalWidgetName));
        if (hasBackground) {
            tvBackgroundStatus.setText("Custom Image");
        } else {
            tvBackgroundStatus.setText("Default");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Set the appropriate title.
        requireActivity().setTitle(originalWidgetName == null
                ? R.string.title_new_widget
                : R.string.title_edit_widget);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    private void requestPinWidget(String widgetName) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(requireContext());
        ComponentName myProvider = new ComponentName(requireContext(), SphereWidgetProvider.class);

        if (appWidgetManager.isRequestPinAppWidgetSupported()) {
            Intent callbackIntent = new Intent(requireContext(), WidgetPinnedReceiver.class);
            callbackIntent.putExtra(WidgetPinnedReceiver.EXTRA_GROUP_NAME, widgetName);
            
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
                updateLivePreview();
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
                updateLivePreview();
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
     * @param prefs   SharedPreferences for reading selected-app and widget data.
     * @param widgets  Full widget list, used for "in other widget" subtitle logic.
     */
    private void loadMembersAsync(@NonNull SharedPreferences prefs,
                                  @NonNull List<WidgetStore.Widget> widgets) {
        android.content.Context appCtx = requireContext().getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        // Build a package→widget reverse map to detect conflicting membership.
        Map<String, WidgetStore.Widget> pkgToWidget = WidgetStore.packageToWidget(widgets);

        executor.submit(() -> {
            PackageManager pm = appCtx.getPackageManager();
            java.util.List<android.content.pm.ResolveInfo> resolvedApps = AppFetcher.getAllLaunchableApps(appCtx);

            List<MemberRow> rows = new ArrayList<>();
            for (android.content.pm.ResolveInfo ri : resolvedApps) {
                String pkg = ri.activityInfo.packageName;
                String label = ri.loadLabel(pm).toString();
                Drawable icon = ri.loadIcon(pm);

                // Determine if this app already belongs to a DIFFERENT widget.
                WidgetStore.Widget owningWidget = pkgToWidget.get(pkg);
                String otherGroupName = null;
                if (owningWidget != null
                        && !owningWidget.name.equalsIgnoreCase(
                                originalWidgetName == null ? "" : originalWidgetName)) {
                    otherGroupName = owningWidget.name;
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
    //  Auto Save handler
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void onPause() {
        super.onPause();
        if (originalWidgetName != null) {
            saveData();
        }
    }

    private boolean saveData() {
        View root = getView();
        if (root == null) return false;
        TextInputEditText nameInput = root.findViewById(R.id.group_name_input);
        if (nameInput == null) return false;
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        Editable editable = nameInput.getText();
        String newName = (editable == null) ? "" : editable.toString().trim();

        // Validate: name must not be empty. If empty, don't save.
        if (newName.isEmpty()) {
            nameInput.setError("Widget name cannot be empty");
            nameInput.requestFocus();
            androidx.core.widget.NestedScrollView scrollView = root.findViewById(R.id.scroll_view);
            if (scrollView != null) {
                View cardGeneral = root.findViewById(R.id.card_general);
                if (cardGeneral != null) {
                    scrollView.smoothScrollTo(0, cardGeneral.getTop());
                }
            }
            return false;
        }

        // Reload latest widget list to prevent stale-data issues (another agent
        // may have saved widgets while this fragment was open, but in practice
        // the list is fresh because WidgetStore.load is side-effect-free here).
        List<WidgetStore.Widget> widgets = WidgetStore.load(prefs);

        boolean ok = WidgetStore.upsert(
                widgets,
                originalWidgetName, // null → create; non-null → edit
                newName,
                selectedColor,
                new HashSet<>(workingMembers) // pass a copy
        );

        if (!ok) {
            // upsert returns false on: name collision with different widget,
            // or empty name (already guarded above), or old-name-not-found.
            nameInput.setError(getString(R.string.toast_widget_exists));
            nameInput.requestFocus();
            androidx.core.widget.NestedScrollView scrollView = root.findViewById(R.id.scroll_view);
            if (scrollView != null) {
                View cardGeneral = root.findViewById(R.id.card_general);
                if (cardGeneral != null) {
                    scrollView.smoothScrollTo(0, cardGeneral.getTop());
                }
            }
            Toast.makeText(requireContext(),
                    R.string.toast_widget_exists,
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        // Persist the mutated list.
        WidgetStore.save(prefs, widgets);

        // Migrate widget preferences if name changed
        if (originalWidgetName != null && !originalWidgetName.equals(newName)) {
            // Rename hide logo preference
            boolean oldHide = prefs.getBoolean("pref_widget_hide_logo_" + originalWidgetName, false);
            boolean oldHideText = prefs.getBoolean("pref_widget_hide_text_" + originalWidgetName, false);
            boolean oldTransparent = prefs.getBoolean("pref_widget_transparent_" + originalWidgetName, false);
            boolean oldUseTheme = prefs.getBoolean("pref_widget_use_theme_color_" + originalWidgetName, true);
            int oldIconSize = prefs.getInt("pref_icon_size_" + originalWidgetName, prefs.getInt("pref_icon_size", 50));
            int oldSpeed = prefs.getInt("pref_rotation_speed_" + originalWidgetName, prefs.getInt("pref_rotation_speed", 100));
            String oldFps = prefs.getString("pref_target_fps_" + originalWidgetName, prefs.getString("pref_target_fps", "120"));
            
            String oldPos = prefs.getString("pref_sphere_position_" + originalWidgetName, prefs.getString("pref_sphere_position", "center"));
            float oldX = prefs.getFloat("pref_sphere_x_" + originalWidgetName, prefs.getFloat("pref_sphere_x", 0f));
            float oldY = prefs.getFloat("pref_sphere_y_" + originalWidgetName, prefs.getFloat("pref_sphere_y", 0f));
            float oldScale = prefs.getFloat("pref_sphere_scale_" + originalWidgetName, prefs.getFloat("pref_sphere_scale", 1f));
            int oldBlurRadius = prefs.getInt("pref_blur_radius_" + originalWidgetName, prefs.getInt("pref_blur_radius", 50));
            int oldBlurStrength = prefs.getInt("pref_blur_strength_" + originalWidgetName, prefs.getInt("pref_blur_strength", 50));
            
            // Migrate widget widget mappings to new name
            android.appwidget.AppWidgetManager appWidgetManager = android.appwidget.AppWidgetManager.getInstance(requireContext());
            android.content.ComponentName thisWidget = new android.content.ComponentName(requireContext(), SphereWidgetProvider.class);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
            for (int id : appWidgetIds) {
                if (originalWidgetName.equals(prefs.getString("widget_group_" + id, null))) {
                    prefs.edit().putString("widget_group_" + id, newName).apply();
                }
            }
            
            prefs.edit()
                .remove("pref_widget_hide_logo_" + originalWidgetName)
                .remove("pref_widget_hide_text_" + originalWidgetName)
                .remove("pref_widget_transparent_" + originalWidgetName)
                .remove("pref_widget_use_theme_color_" + originalWidgetName)
                .remove("pref_icon_size_" + originalWidgetName)
                .remove("pref_rotation_speed_" + originalWidgetName)
                .remove("pref_target_fps_" + originalWidgetName)
                .remove("pref_sphere_position_" + originalWidgetName)
                .remove("pref_sphere_x_" + originalWidgetName)
                .remove("pref_sphere_y_" + originalWidgetName)
                .remove("pref_sphere_scale_" + originalWidgetName)
                .remove("pref_blur_radius_" + originalWidgetName)
                .remove("pref_blur_strength_" + originalWidgetName)
                .putBoolean("pref_widget_hide_logo_" + newName, oldHide)
                .putBoolean("pref_widget_hide_text_" + newName, oldHideText)
                .putBoolean("pref_widget_transparent_" + newName, oldTransparent)
                .putBoolean("pref_widget_use_theme_color_" + newName, oldUseTheme)
                .putInt("pref_icon_size_" + newName, oldIconSize)
                .putInt("pref_rotation_speed_" + newName, oldSpeed)
                .putString("pref_target_fps_" + newName, oldFps)
                .putString("pref_sphere_position_" + newName, oldPos)
                .putFloat("pref_sphere_x_" + newName, oldX)
                .putFloat("pref_sphere_y_" + newName, oldY)
                .putFloat("pref_sphere_scale_" + newName, oldScale)
                .putInt("pref_blur_radius_" + newName, oldBlurRadius)
                .putInt("pref_blur_strength_" + newName, oldBlurStrength)
                .apply();
                
            // Rename logo file
            File oldFile = WidgetLogoStore.file(requireContext(), originalWidgetName);
            if (oldFile.exists()) {
                File newFile = WidgetLogoStore.file(requireContext(), newName);
                oldFile.renameTo(newFile);
            }
            
            // Rename background file
            File oldBgFile = dev.jaimin.auraorbit.BackgroundStore.file(requireContext(), originalWidgetName);
            if (oldBgFile.exists()) {
                File newBgFile = dev.jaimin.auraorbit.BackgroundStore.file(requireContext(), newName);
                oldBgFile.renameTo(newBgFile);
            }
        }
        
        // Apply pending widget logo changes
        SharedPreferences.Editor ed = prefs.edit()
            .putBoolean("pref_widget_hide_logo_" + newName, isHideLogo)
            .putBoolean("pref_widget_hide_text_" + newName, isHideText)
            .putBoolean("pref_widget_transparent_" + newName, isTransparent)
            .putBoolean("pref_widget_use_theme_color_" + newName, isUseThemeColor)
            .putInt("pref_icon_size_" + newName, customIconSize)
            .putInt("pref_rotation_speed_" + newName, customSpeed)
            .putString("pref_target_fps_" + newName, customFps);
            
        ed.apply();
        
        if (pendingBackgroundClear) {
            dev.jaimin.auraorbit.BackgroundStore.clear(requireContext(), newName);
        } else if (pendingBackgroundUri != null) {
            dev.jaimin.auraorbit.BackgroundStore.saveFromUri(requireContext(), pendingBackgroundUri, newName);
        }
        
        if (pendingLogoClear) {
            WidgetLogoStore.clear(requireContext(), newName);
        } else if (pendingLogoUri != null) {
            WidgetLogoStore.saveFromUri(requireContext(), pendingLogoUri, newName);
        }



        if (originalWidgetName == null) {
            // Automatically prompt the user to pin the widget to their home screen for new widgets
            requestPinWidget(newName);
        } else {
            // Update existing widgets when a widget is edited
            SphereWidgetProvider.updateAllWidgets(requireContext());
        }

        Toast.makeText(requireContext(), "Saved!", Toast.LENGTH_SHORT).show();

        // Update originalWidgetName so subsequent auto-saves (e.g. after config change)
        // know the new identity of this widget.
        originalWidgetName = newName;
        return true;
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
     * widget, the current widget, or no widget at all.
     */
    private static final class MemberRow {
        final String   packageName;
        final String   label;
        final Drawable icon;
        /**
         * Non-null only when the app is currently in a DIFFERENT widget,
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
     * Adapter for the member list inside WidgetEditFragment.
     *
     * <p>Maintains a full list and a filtered display list, just like
     * {@link AppPickerFragment}'s adapter. Toggling a row updates
     * {@link #workingMembers} in the enclosing fragment — the set is then
     * passed to {@link WidgetStore#upsert} on Save.</p>
     *
     * <p>The checkbox in each row is {@code clickable=false} (declared in
     * {@code row_widget_member.xml}), so only the row's root click fires.</p>
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
                    .inflate(R.layout.row_widget_member, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            MemberRow row = displayItems.get(position);

            holder.icon.setImageDrawable(row.icon);
            holder.label.setText(row.label);

            // ─── "Already in other widget" subtitle + disabled state ───────
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

            // App is always fully interactive, even if it belongs to another widget.
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
