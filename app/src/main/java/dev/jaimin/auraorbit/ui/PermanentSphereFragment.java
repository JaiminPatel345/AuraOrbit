











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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.Set;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jaimin.auraorbit.BackgroundStore;
import dev.jaimin.auraorbit.MyWallpaperService;
import dev.jaimin.auraorbit.R;
import dev.jaimin.auraorbit.SpherePositionEditorActivity;
import dev.jaimin.auraorbit.GestureRadiusEditorActivity;
import dev.jaimin.auraorbit.SphereBlurEditorActivity;
import dev.jaimin.auraorbit.AppFetcher;

public class PermanentSphereFragment extends Fragment {

    public static final String PREF_SPHERE_POSITION = "pref_sphere_position";
    public static final String PREF_PERMANENT_SPHERE_ENABLED = "pref_permanent_sphere_enabled";

    private SharedPreferences prefs;
    private ExecutorService executor;
    private TextView tvBackgroundStatus;
    private TextView tvSpherePositionStatus;
    private TextView tvAppsCount;
    private TextView tvGestureRadiusValue;
    private TextView tvBlurStatus;
    private View sectionSphereSettings;

    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();

        pickMedia = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                this::saveBackground
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_permanent_sphere, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        // Section visibility container
        sectionSphereSettings = view.findViewById(R.id.section_sphere_settings);

        // Toggle switch
        MaterialSwitch switchPermanentSphere = view.findViewById(R.id.switch_permanent_sphere);
        boolean enabled = prefs.getBoolean(PREF_PERMANENT_SPHERE_ENABLED, false);
        switchPermanentSphere.setChecked(enabled);
        sectionSphereSettings.setVisibility(enabled ? View.VISIBLE : View.GONE);

        switchPermanentSphere.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PREF_PERMANENT_SPHERE_ENABLED, isChecked).apply();
            sectionSphereSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isChecked) {
                android.content.Intent intent = new android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                intent.putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new android.content.ComponentName(requireContext(), MyWallpaperService.class));
                startActivity(intent);
            }
        });

        // Select Apps
        tvAppsCount = view.findViewById(R.id.tv_apps_count);
        view.findViewById(R.id.btn_select_apps).setOnClickListener(v -> navigateTo(new AppPickerFragment()));

        // Sphere Position
        tvSpherePositionStatus = view.findViewById(R.id.tv_sphere_position_status);
        updateSpherePositionStatus();
        view.findViewById(R.id.btn_sphere_position).setOnClickListener(v -> {
            startActivity(new android.content.Intent(requireContext(), SpherePositionEditorActivity.class));
        });

        // Device Wallpaper
        tvBackgroundStatus = view.findViewById(R.id.tv_wallpaper_status);
        updateBackgroundStatus();
        view.findViewById(R.id.btn_set_device_wallpaper).setOnClickListener(v -> {
            if (BackgroundStore.exists(requireContext())) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setItems(new CharSequence[]{"Choose new wallpaper", "Remove wallpaper / Reset to Default", "Cancel"}, (dialog, which) -> {
                            if (which == 0) {
                                launchPicker();
                            } else if (which == 1) {
                                BackgroundStore.clear(requireContext());
                                try {
                                    android.app.WallpaperManager.getInstance(requireContext()).clear();
                                    Toast.makeText(requireContext(), "Device wallpaper reset to default!", Toast.LENGTH_SHORT).show();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                updateBackgroundStatus();
                            }
                        })
                        .show();
            } else {
                launchPicker();
            }
        });

        // Background Blur
        tvBlurStatus = view.findViewById(R.id.tv_blur_status);
        updateBlurStatusText(tvBlurStatus, prefs.getInt("pref_blur_radius", 50));
        view.findViewById(R.id.btn_sphere_blur).setOnClickListener(v -> {
            startActivity(new android.content.Intent(requireContext(), SphereBlurEditorActivity.class));
        });

        // Icon Size slider
        Slider sliderIconSize = view.findViewById(R.id.slider_icon_size);
        sliderIconSize.setValue(prefs.getInt("pref_icon_size", 50));
        sliderIconSize.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) prefs.edit().putInt("pref_icon_size", (int) value).apply();
        });

        // Rotation Speed slider
        Slider sliderSpeed = view.findViewById(R.id.slider_speed);
        sliderSpeed.setValue(prefs.getInt("pref_rotation_speed", 100));
        sliderSpeed.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) prefs.edit().putInt("pref_rotation_speed", (int) value).apply();
        });

        // Page Settings
        MaterialSwitch switchDynamicLastPage = view.findViewById(R.id.switch_dynamic_last_page);
        View layoutActivePage = view.findViewById(R.id.layout_active_page);
        TextView tvActivePageValue = view.findViewById(R.id.tv_active_page_value);
        com.google.android.material.button.MaterialButton btnDecrementActivePage = view.findViewById(R.id.btn_decrement_active_page);
        com.google.android.material.button.MaterialButton btnIncrementActivePage = view.findViewById(R.id.btn_increment_active_page);

        View layoutTotalPages = view.findViewById(R.id.layout_total_pages);
        TextView tvTotalPagesValue = view.findViewById(R.id.tv_total_pages_value);
        com.google.android.material.button.MaterialButton btnDecrementTotalPages = view.findViewById(R.id.btn_decrement_total_pages);
        com.google.android.material.button.MaterialButton btnIncrementTotalPages = view.findViewById(R.id.btn_increment_total_pages);

        boolean dynamicLastPage = prefs.getBoolean("pref_dynamic_last_page", false);
        switchDynamicLastPage.setChecked(dynamicLastPage);
        layoutActivePage.setVisibility(dynamicLastPage ? View.GONE : View.VISIBLE);

        switchDynamicLastPage.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("pref_dynamic_last_page", isChecked).apply();
            layoutActivePage.setVisibility(isChecked ? View.GONE : View.VISIBLE);
        });

        // Active Page Stepper
        int activePage = prefs.getInt("pref_active_page", 1);
        tvActivePageValue.setText(String.valueOf(activePage));
        btnDecrementActivePage.setOnClickListener(v -> {
            int val = prefs.getInt("pref_active_page", 1);
            int newVal = Math.max(1, val - 1);
            if (newVal != val) {
                prefs.edit().putInt("pref_active_page", newVal).apply();
                tvActivePageValue.setText(String.valueOf(newVal));
            }
        });
        btnIncrementActivePage.setOnClickListener(v -> {
            int val = prefs.getInt("pref_active_page", 1);
            int newVal = Math.min(9, val + 1);
            if (newVal != val) {
                prefs.edit().putInt("pref_active_page", newVal).apply();
                tvActivePageValue.setText(String.valueOf(newVal));
            }
        });

        // Total Pages Stepper
        int totalPages = prefs.getInt("pref_total_pages", 3);
        tvTotalPagesValue.setText(String.valueOf(totalPages));
        btnDecrementTotalPages.setOnClickListener(v -> {
            int val = prefs.getInt("pref_total_pages", 3);
            int newVal = Math.max(1, val - 1);
            if (newVal != val) {
                prefs.edit().putInt("pref_total_pages", newVal).apply();
                tvTotalPagesValue.setText(String.valueOf(newVal));
            }
        });
        btnIncrementTotalPages.setOnClickListener(v -> {
            int val = prefs.getInt("pref_total_pages", 3);
            int newVal = Math.min(9, val + 1);
            if (newVal != val) {
                prefs.edit().putInt("pref_total_pages", newVal).apply();
                tvTotalPagesValue.setText(String.valueOf(newVal));
            }
        });
        // Block Launcher Gestures switch and Clickable Radius Row
        View btnGestureCaptureRadius = view.findViewById(R.id.btn_gesture_capture_radius);
        tvGestureRadiusValue = view.findViewById(R.id.tv_gesture_radius_value);

        View layoutDebugGestureBounds = view.findViewById(R.id.layout_debug_gesture_bounds);
        com.google.android.material.materialswitch.MaterialSwitch switchDebugGestureBounds = view.findViewById(R.id.switch_debug_gesture_bounds);

        com.google.android.material.materialswitch.MaterialSwitch switchBlockLauncherGestures = view.findViewById(R.id.switch_block_launcher_gestures);
        if (switchBlockLauncherGestures != null) {
            boolean hasPerm = android.provider.Settings.canDrawOverlays(requireContext());
            boolean blockEnabled = prefs.getBoolean("pref_block_launcher_gestures", false);
            switchBlockLauncherGestures.setChecked(blockEnabled);
            if (btnGestureCaptureRadius != null) {
                btnGestureCaptureRadius.setVisibility((blockEnabled && hasPerm) ? View.VISIBLE : View.GONE);
            }
            if (layoutDebugGestureBounds != null) {
                // COMMENTED OUT: layoutDebugGestureBounds.setVisibility((blockEnabled && hasPerm) ? View.VISIBLE : View.GONE);
                layoutDebugGestureBounds.setVisibility(View.GONE);
            }

            switchBlockLauncherGestures.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    if (!android.provider.Settings.canDrawOverlays(requireContext())) {
                        Toast.makeText(requireContext(), "Please allow AuraOrbit to draw over other apps to enable gesture blocking.", Toast.LENGTH_LONG).show();
                        android.content.Intent intent = new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + requireContext().getPackageName())
                        );
                        startActivity(intent);
                        prefs.edit().putBoolean("pref_block_launcher_gestures", true).apply();
                    } else {
                        prefs.edit().putBoolean("pref_block_launcher_gestures", true).apply();
                        if (btnGestureCaptureRadius != null) btnGestureCaptureRadius.setVisibility(View.VISIBLE);
                        // COMMENTED OUT: if (layoutDebugGestureBounds != null) layoutDebugGestureBounds.setVisibility(View.VISIBLE);
                    }
                } else {
                    prefs.edit().putBoolean("pref_block_launcher_gestures", false).apply();
                    if (btnGestureCaptureRadius != null) btnGestureCaptureRadius.setVisibility(View.GONE);
                    // COMMENTED OUT: if (layoutDebugGestureBounds != null) layoutDebugGestureBounds.setVisibility(View.GONE);
                }
            });
        }

        if (switchDebugGestureBounds != null) {
            switchDebugGestureBounds.setChecked(prefs.getBoolean("pref_debug_gesture_bounds", false));
            switchDebugGestureBounds.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("pref_debug_gesture_bounds", isChecked).apply();
            });
        }

        if (btnGestureCaptureRadius != null) {
            btnGestureCaptureRadius.setOnClickListener(v -> {
                startActivity(new android.content.Intent(requireContext(), GestureRadiusEditorActivity.class));
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle("Permanent Sphere");
        updateBackgroundStatus();
        updateSpherePositionStatus();
        updateAppsCount();



        // Keep Block Launcher Gestures switch in sync with system settings
        boolean hasOverlayPerm = android.provider.Settings.canDrawOverlays(requireContext());
        boolean blockEnabled = prefs.getBoolean("pref_block_launcher_gestures", false);
        if (!hasOverlayPerm && blockEnabled) {
            prefs.edit().putBoolean("pref_block_launcher_gestures", false).apply();
            blockEnabled = false;
        }
        com.google.android.material.materialswitch.MaterialSwitch switchBlock = requireView().findViewById(R.id.switch_block_launcher_gestures);
        if (switchBlock != null) {
            switchBlock.setChecked(blockEnabled);
        }
        View btnGestureCaptureRadius = requireView().findViewById(R.id.btn_gesture_capture_radius);
        if (btnGestureCaptureRadius != null) {
            btnGestureCaptureRadius.setVisibility((blockEnabled && hasOverlayPerm) ? View.VISIBLE : View.GONE);
        }
        View layoutDebugGestureBounds = requireView().findViewById(R.id.layout_debug_gesture_bounds);
        if (layoutDebugGestureBounds != null) {
            // COMMENTED OUT: layoutDebugGestureBounds.setVisibility((blockEnabled && hasOverlayPerm) ? View.VISIBLE : View.GONE);
            layoutDebugGestureBounds.setVisibility(View.GONE);
        }
        com.google.android.material.materialswitch.MaterialSwitch switchDebugBounds = requireView().findViewById(R.id.switch_debug_gesture_bounds);
        if (switchDebugBounds != null) {
            switchDebugBounds.setChecked(prefs.getBoolean("pref_debug_gesture_bounds", false));
        }
        if (tvGestureRadiusValue != null) {
            int radiusVal = prefs.getInt("pref_gesture_capture_scale_percent", 100);
            tvGestureRadiusValue.setText(radiusVal + "%");
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

    private void updateSpherePositionStatus() {
        if (tvSpherePositionStatus == null) return;
        String position = prefs.getString(PREF_SPHERE_POSITION, "center");
        String display = "Center";
        if ("top".equals(position)) display = "Top";
        else if ("bottom".equals(position)) display = "Bottom";
        else if ("custom".equals(position)) display = "Custom";
        tvSpherePositionStatus.setText(display);
    }



    private void updateAppsCount() {
        if (tvAppsCount == null) return;
        Set<String> apps = prefs.getStringSet("selected_app_packages", null);
        if (apps == null || apps.isEmpty()) {
            List<android.content.pm.ResolveInfo> launchable = AppFetcher.getAllLaunchableApps(requireContext());
            tvAppsCount.setText(launchable.size() + " apps selected (default)");
        } else {
            tvAppsCount.setText(apps.size() + " apps selected");
        }
    }

    private void launchPicker() {
        pickMedia.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void saveBackground(@Nullable Uri uri) {
        if (uri == null) return;

        Context appCtx = requireContext().getApplicationContext();
        Handler mainThread = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            boolean ok = BackgroundStore.saveFromUri(appCtx, uri);
            mainThread.post(() -> {
                if (!isAdded()) return;
                if (ok) {
                    updateBackgroundStatus();
                } else {
                    Toast.makeText(requireContext(), "Failed to save wallpaper background image internally.", Toast.LENGTH_LONG).show();
                }
            });
        });

        // 2. Set as system static wallpaper
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(requireContext());
            java.io.InputStream is = requireContext().getContentResolver().openInputStream(uri);
            wm.setStream(is);
            if (is != null) is.close();
            Toast.makeText(requireContext(), "Device wallpaper set! Re-enable AuraOrbit to see the 3D sphere.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(requireContext(), "Failed to set system wallpaper.", Toast.LENGTH_SHORT).show();
        }

        // 3. Prompt user to re-enable AuraOrbit Live Wallpaper
        try {
            android.content.Intent intent = new android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new android.content.ComponentName(requireContext(), MyWallpaperService.class));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
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

    private void navigateTo(@NonNull Fragment fragment) {
        getParentFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, fragment)
                .addToBackStack(null)
                .commit();
    }
}
