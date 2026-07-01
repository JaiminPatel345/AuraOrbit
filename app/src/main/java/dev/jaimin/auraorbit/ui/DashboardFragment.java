package dev.jaimin.auraorbit.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import android.widget.EditText;
import android.text.InputType;
import dev.jaimin.auraorbit.AppFetcher;
import dev.jaimin.auraorbit.WidgetLogoStore;
import dev.jaimin.auraorbit.SphereWidgetProvider;
import dev.jaimin.auraorbit.SpherePositionEditorActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jaimin.auraorbit.BackgroundStore;
import dev.jaimin.auraorbit.R;

public class DashboardFragment extends Fragment {

    private SharedPreferences prefs;
    private ExecutorService executor;
    private TextView tvBackgroundStatus;
    private TextView tvWidgetLogoStatus;
    private TextView tvWidgetName;
    private View defaultLogoOptionsContainer;
    private View customLogoOptionsContainer;
    private com.google.android.material.button.MaterialButton btnWidgetLogo;
    private String selectedColor;
    private boolean isPickingDeviceWallpaper = false;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    this::saveBackground
            );

    private final ActivityResultLauncher<PickVisualMediaRequest> pickWidgetLogoMedia =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    this::saveWidgetLogo
            );

    public static final String PREF_SPHERE_POSITION = "pref_sphere_position";
    private TextView tvSpherePositionStatus;

    private void updateSpherePositionStatus() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String position = prefs.getString(PREF_SPHERE_POSITION, "center");
        String display = "Center";
        if ("top".equals(position)) display = "Top";
        else if ("bottom".equals(position)) display = "Bottom";
        else if ("custom".equals(position)) display = "Custom";
        if (tvSpherePositionStatus != null) {
            tvSpherePositionStatus.setText(display);
        }
    }

    private void updateBlurStatusText(TextView tv, int amount) {
        if (tv == null) return;
        if (amount == 0) tv.setText("No Blur");
        else if (amount <= 33) tv.setText("Sphere Background Only");
        else if (amount <= 66) tv.setText("Nearby Area");
        else if (amount < 100) tv.setText("Almost Full Screen");
        else tv.setText("Full Screen Blur");
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Navigation Cards
        MaterialCardView cardApps = view.findViewById(R.id.card_apps);
        cardApps.setOnClickListener(v -> navigateTo(new AppPickerFragment()));

        MaterialCardView cardGroups = view.findViewById(R.id.card_groups);
        cardGroups.setOnClickListener(v -> navigateTo(new GroupListFragment()));


        Slider sliderIconSize = view.findViewById(R.id.slider_icon_size);
        sliderIconSize.setValue(prefs.getInt("pref_icon_size", 50));
        sliderIconSize.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) prefs.edit().putInt("pref_icon_size", (int) value).apply();
        });

        Slider sliderSpeed = view.findViewById(R.id.slider_speed);
        sliderSpeed.setValue(prefs.getInt("pref_rotation_speed", 100));
        sliderSpeed.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) prefs.edit().putInt("pref_rotation_speed", (int) value).apply();
        });

        // Blur Background
        View btnSphereBlur = view.findViewById(R.id.btn_sphere_blur);
        TextView tvBlurStatus = view.findViewById(R.id.tv_blur_status);
        if (btnSphereBlur != null) {
            updateBlurStatusText(tvBlurStatus, prefs.getInt("pref_blur_amount", 0));
            btnSphereBlur.setOnClickListener(v -> {
                startActivity(new android.content.Intent(requireContext(), dev.jaimin.auraorbit.SphereBlurEditorActivity.class));
            });
        }

        // FPS
        TextView tvFpsValue = view.findViewById(R.id.tv_fps_value);
        String fpsStr = prefs.getString("pref_target_fps", "120");
        tvFpsValue.setText(fpsStr + " FPS");

        view.findViewById(R.id.btn_fps).setOnClickListener(v -> {
            String[] options = {"30 FPS", "60 FPS", "90 FPS", "120 FPS"};
            String[] values = {"30", "60", "90", "120"};
            int checkedItem = 3;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(prefs.getString("pref_target_fps", "120"))) {
                    checkedItem = i;
                    break;
                }
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Target FPS")
                    .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                        prefs.edit().putString("pref_target_fps", values[which]).apply();
                        tvFpsValue.setText(options[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Sphere Position handler
        tvSpherePositionStatus = view.findViewById(R.id.tv_sphere_position_status);
        updateSpherePositionStatus();

        view.findViewById(R.id.btn_sphere_position).setOnClickListener(v -> {
            startActivity(new android.content.Intent(requireContext(), SpherePositionEditorActivity.class));
        });

        // Background handler
        tvBackgroundStatus = view.findViewById(R.id.tv_background_status);
        updateBackgroundStatus();

        view.findViewById(R.id.btn_app_background).setOnClickListener(v -> {
            if (BackgroundStore.exists(requireContext())) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setItems(new CharSequence[]{"Choose new photo", "Remove photo", "Cancel"}, (dialog, which) -> {
                            if (which == 0) {
                                isPickingDeviceWallpaper = false;
                                launchPicker();
                            } else if (which == 1) {
                                BackgroundStore.clear(requireContext());
                                updateBackgroundStatus();
                            }
                        })
                        .show();
            } else {
                isPickingDeviceWallpaper = false;
                launchPicker();
            }
        });

        view.findViewById(R.id.btn_set_device_wallpaper).setOnClickListener(v -> {
            isPickingDeviceWallpaper = true;
            launchPicker();
        });

        // GitHub link
        view.findViewById(R.id.btn_github).setOnClickListener(v -> {
            android.content.Intent browserIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/JaiminPatel345/AuraOrbit"));
            startActivity(browserIntent);
        });

        // Widget Settings
        tvWidgetName = view.findViewById(R.id.tv_widget_name);
        tvWidgetName.setText(prefs.getString("pref_widget_name", "All"));
        view.findViewById(R.id.btn_widget_name).setOnClickListener(v -> {
            final EditText input = new EditText(requireContext());
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setText(prefs.getString("pref_widget_name", "All"));
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Widget Name")
                    .setView(input)
                    .setPositiveButton("Save", (dialog, which) -> {
                        String newName = input.getText().toString();
                        prefs.edit().putString("pref_widget_name", newName).apply();
                        tvWidgetName.setText(newName);
                        SphereWidgetProvider.updateAllWidgets(requireContext());
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        tvWidgetLogoStatus = view.findViewById(R.id.tv_widget_logo_status);
        defaultLogoOptionsContainer = view.findViewById(R.id.default_logo_options_container);
        customLogoOptionsContainer = view.findViewById(R.id.custom_logo_options_container);
        btnWidgetLogo = view.findViewById(R.id.btn_widget_logo);

        updateWidgetLogoStatus();
        btnWidgetLogo.setOnClickListener(v -> launchWidgetLogoPicker());

        view.findViewById(R.id.btn_replace_custom_logo).setOnClickListener(v -> launchWidgetLogoPicker());
        view.findViewById(R.id.btn_remove_custom_logo).setOnClickListener(v -> {
            WidgetLogoStore.clear(requireContext());
            updateWidgetLogoStatus();
            SphereWidgetProvider.updateAllWidgets(requireContext());
            updateLivePreview();
        });

        // ─── Info Buttons ─────────────────────────────────────────────────
        view.findViewById(R.id.btn_info_orbit_color).setOnClickListener(v -> 
            showInfoDialog("Orbit Color", "Sets the color of the widget's ring.")
        );
        view.findViewById(R.id.btn_info_theme_color).setOnClickListener(v -> 
            showInfoDialog("System Theme Color", "Overrides the custom orbit color to match your Android system's Material You theme.")
        );
        view.findViewById(R.id.btn_info_transparent).setOnClickListener(v -> 
            showInfoDialog("Transparent Widget", "Removes the solid background from the widget so it blends seamlessly into your wallpaper.")
        );
        view.findViewById(R.id.btn_info_hide_logo).setOnClickListener(v -> 
            showInfoDialog("Hide Widget Logo", "Makes the widget fully transparent by hiding the icon. Only the text label will remain visible.")
        );
        view.findViewById(R.id.btn_info_hide_text).setOnClickListener(v -> 
            showInfoDialog("Hide Widget Text", "Removes the group name label displayed beneath the widget.")
        );

        selectedColor = prefs.getString("pref_widget_orbit_color", "#FFFFFF");
        buildColorRow((android.widget.LinearLayout) view.findViewById(R.id.color_row));

        MaterialSwitch switchTransparent = view.findViewById(R.id.switch_transparent_widget);
        switchTransparent.setChecked(prefs.getBoolean("pref_widget_transparent", false));
        switchTransparent.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("pref_widget_transparent", isChecked).apply();
            SphereWidgetProvider.updateAllWidgets(requireContext());
            updateLivePreview();
        });

        MaterialSwitch switchThemeColor = view.findViewById(R.id.switch_use_theme_color);
        switchThemeColor.setChecked(prefs.getBoolean("pref_widget_use_theme_color", true));
        switchThemeColor.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("pref_widget_use_theme_color", isChecked).apply();
            SphereWidgetProvider.updateAllWidgets(requireContext());
            updateLivePreview();
        });

        MaterialSwitch switchHideText = view.findViewById(R.id.switch_hide_widget_text);
        switchHideText.setChecked(prefs.getBoolean("pref_widget_hide_text", false));
        switchHideText.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("pref_widget_hide_text", isChecked).apply();
            SphereWidgetProvider.updateAllWidgets(requireContext());
            updateLivePreview();
        });

        MaterialSwitch switchHideLogo = view.findViewById(R.id.switch_hide_widget_logo);
        switchHideLogo.setChecked(prefs.getBoolean("pref_widget_hide_logo", false));
        switchHideLogo.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("pref_widget_hide_logo", isChecked).apply();
            SphereWidgetProvider.updateAllWidgets(requireContext());
            updateLivePreview();
        });
        
        // Initial preview update
        updateLivePreview();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.settings_title);
        updateBackgroundStatus();
        updateWidgetLogoStatus();
        updateSpherePositionStatus();
        
        View view = getView();
        if (view != null) {
            TextView tvBlurStatus = view.findViewById(R.id.tv_blur_status);
            if (tvBlurStatus != null) {
                updateBlurStatusText(tvBlurStatus, androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt("pref_blur_amount", 0));
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    private void updateBackgroundStatus() {
        if (tvBackgroundStatus != null) {
            tvBackgroundStatus.setText(BackgroundStore.exists(requireContext()) ? "Custom image set" : "Default");
        }
    }

    private void launchPicker() {
        pickMedia.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void launchWidgetLogoPicker() {
        pickWidgetLogoMedia.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void saveBackground(@Nullable Uri uri) {
        if (uri == null) return;
        if (isPickingDeviceWallpaper) {
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(requireContext());
                java.io.InputStream is = requireContext().getContentResolver().openInputStream(uri);
                wm.setStream(is);
                if (is != null) is.close();
                Toast.makeText(requireContext(), "Device wallpaper updated!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(requireContext(), "Failed to set wallpaper", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        
        Context appCtx = requireContext().getApplicationContext();
        Handler mainThread = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            boolean ok = BackgroundStore.saveFromUri(appCtx, uri);
            mainThread.post(() -> {
                if (!isAdded()) return;
                if (ok) {
                    updateBackgroundStatus();
                } else {
                    Toast.makeText(requireContext(), "Failed to save image. Please try another one.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void updateWidgetLogoStatus() {
        if (tvWidgetLogoStatus != null) {
            tvWidgetLogoStatus.setText(WidgetLogoStore.exists(requireContext()) ? "Custom image set" : "Default");
        }
    }

    private void saveWidgetLogo(@Nullable Uri uri) {
        if (uri == null) return;
        Context appCtx = requireContext().getApplicationContext();
        Handler mainThread = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            boolean ok = WidgetLogoStore.saveFromUri(appCtx, uri);
            mainThread.post(() -> {
                if (!isAdded()) return;
                if (ok) {
                    updateWidgetLogoStatus();
                    SphereWidgetProvider.updateAllWidgets(requireContext());
                    updateLivePreview();
                } else {
                    Toast.makeText(requireContext(), "Failed to save logo. Please try another one.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void navigateTo(@NonNull Fragment fragment) {
        getParentFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, fragment)
                .addToBackStack(null)
                .commit();
    }

    private void showInfoDialog(String title, String message) {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Got it", null)
            .show();
    }

    private void buildColorRow(@NonNull android.widget.LinearLayout colorRow) {
        colorRow.removeAllViews();

        int circleSizePx = (int)(40 * getResources().getDisplayMetrics().density);
        int marginEndPx  = (int)(8 * getResources().getDisplayMetrics().density);

        String[] colorHexValues = requireContext().getResources().getStringArray(R.array.group_color_hex);
        String[] colorNames = requireContext().getResources().getStringArray(R.array.group_color_names);

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
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(circleSizePx, circleSizePx);
            lp.setMarginEnd(marginEndPx);
            circle.setLayoutParams(lp);
            circle.setContentDescription(colorNames[i]);

            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            try { d.setColor(android.graphics.Color.parseColor(hex)); } catch (Exception e) {}
            if (hex.equalsIgnoreCase(selectedColor)) {
                d.setStroke((int)(3 * getResources().getDisplayMetrics().density), android.graphics.Color.WHITE);
            }
            circle.setBackground(d);

            circle.setOnClickListener(v -> {
                selectedColor = hex;
                prefs.edit().putString("pref_widget_orbit_color", selectedColor).apply();
                SphereWidgetProvider.updateAllWidgets(requireContext());
                buildColorRow(colorRow);
                updateLivePreview();
            });

            colorRow.addView(circle);
        }
        
        // Add Custom Color Circle
        View customCircle = new View(requireContext());
        android.widget.LinearLayout.LayoutParams customLp =
                new android.widget.LinearLayout.LayoutParams(circleSizePx, circleSizePx);
        customCircle.setLayoutParams(customLp);
        customCircle.setContentDescription("Custom Color");

        if (isCustomSelected) {
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            try { d.setColor(android.graphics.Color.parseColor(selectedColor)); } catch (Exception e) {}
            d.setStroke((int)(3 * getResources().getDisplayMetrics().density), android.graphics.Color.WHITE);
            customCircle.setBackground(d);
        } else {
            android.graphics.drawable.ShapeDrawable rainbow = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.OvalShape());
            rainbow.getPaint().setShader(new android.graphics.SweepGradient(
                    circleSizePx / 2f, circleSizePx / 2f,
                    new int[]{android.graphics.Color.RED, android.graphics.Color.YELLOW, android.graphics.Color.GREEN, android.graphics.Color.CYAN, android.graphics.Color.BLUE, android.graphics.Color.MAGENTA, android.graphics.Color.RED},
                    null));
            customCircle.setBackground(rainbow);
        }

        customCircle.setOnClickListener(v -> {
            final android.widget.EditText input = new android.widget.EditText(requireContext());
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setText(selectedColor);
            input.setHint("#RRGGBB");
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Custom Color (Hex)")
                    .setView(input)
                    .setPositiveButton("Save", (dialog, which) -> {
                        selectedColor = input.getText().toString();
                        prefs.edit().putString("pref_widget_orbit_color", selectedColor).apply();
                        SphereWidgetProvider.updateAllWidgets(requireContext());
                        buildColorRow(colorRow);
                        updateLivePreview();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        colorRow.addView(customCircle);
    }

    private void updateLivePreview() {
        if (!isAdded()) return;

        boolean transparent = prefs.getBoolean("pref_widget_transparent", false);
        boolean useThemeColor = prefs.getBoolean("pref_widget_use_theme_color", true);
        boolean hideLogo = prefs.getBoolean("pref_widget_hide_logo", false);
        boolean hideText = prefs.getBoolean("pref_widget_hide_text", false);
        String name = prefs.getString("pref_widget_name", "All");
        String orbitColor = prefs.getString("pref_widget_orbit_color", "#FFFFFF");

        View previewIconContainer = getView().findViewById(R.id.preview_icon_container);
        android.widget.ImageView previewIconPlanet = getView().findViewById(R.id.preview_icon_planet);
        android.widget.ImageView previewIconRing = getView().findViewById(R.id.preview_icon_ring);
        android.widget.ImageView previewCustomLogo = getView().findViewById(R.id.preview_custom_logo);
        TextView previewLabel = getView().findViewById(R.id.preview_label);

        if (previewIconContainer == null) return;

        // Transparent Widget Check
        if (transparent || hideLogo) {
            previewIconContainer.setBackground(null);
        } else {
            previewIconContainer.setBackgroundResource(R.drawable.rounded_bg_solid);
        }

        // Hide Text Check
        if (hideText) {
            previewLabel.setVisibility(View.GONE);
        } else {
            previewLabel.setVisibility(View.VISIBLE);
            previewLabel.setText(name);
        }

        previewIconContainer.setVisibility(View.VISIBLE);

        boolean hasCustom = WidgetLogoStore.exists(requireContext());

        if (hasCustom) {
            if (tvWidgetLogoStatus != null) tvWidgetLogoStatus.setText("Custom Image");
            if (defaultLogoOptionsContainer != null) defaultLogoOptionsContainer.setVisibility(View.GONE);
            if (customLogoOptionsContainer != null) customLogoOptionsContainer.setVisibility(View.VISIBLE);
            if (btnWidgetLogo != null) btnWidgetLogo.setVisibility(View.GONE);
        } else {
            if (tvWidgetLogoStatus != null) tvWidgetLogoStatus.setText("Default");
            if (defaultLogoOptionsContainer != null) defaultLogoOptionsContainer.setVisibility(View.VISIBLE);
            if (customLogoOptionsContainer != null) customLogoOptionsContainer.setVisibility(View.GONE);
            if (btnWidgetLogo != null) {
                btnWidgetLogo.setVisibility(View.VISIBLE);
                btnWidgetLogo.setText("Upload");
            }
        }

        if (hideLogo) {
            previewIconPlanet.setVisibility(View.GONE);
            previewIconRing.setVisibility(View.GONE);
            previewCustomLogo.setVisibility(View.GONE);
        } else {
            if (hasCustom) {
                previewIconPlanet.setVisibility(View.GONE);
                previewIconRing.setVisibility(View.GONE);
                previewCustomLogo.setVisibility(View.VISIBLE);
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(WidgetLogoStore.file(requireContext()).getAbsolutePath());
                if (bitmap != null) {
                    previewCustomLogo.setImageBitmap(bitmap);
                }
            } else {
                previewIconPlanet.setVisibility(View.VISIBLE);
                previewIconRing.setVisibility(View.VISIBLE);
                previewCustomLogo.setVisibility(View.GONE);
                try {
                    if (useThemeColor) {
                        previewIconRing.setColorFilter(requireContext().getColor(R.color.widget_theme_color));
                    } else {
                        previewIconRing.setColorFilter(android.graphics.Color.parseColor(orbitColor));
                    }
                } catch (Exception e) {}
            }
        }

        View orbitColorHeader = getView().findViewById(R.id.orbit_color_header);
        View colorRowScroll = getView().findViewById(R.id.color_row).getParent() instanceof View ? (View) getView().findViewById(R.id.color_row).getParent() : getView().findViewById(R.id.color_row);
        if (orbitColorHeader != null) orbitColorHeader.setVisibility(useThemeColor ? View.GONE : View.VISIBLE);
        if (colorRowScroll != null) colorRowScroll.setVisibility(useThemeColor ? View.GONE : View.VISIBLE);
    }
}
