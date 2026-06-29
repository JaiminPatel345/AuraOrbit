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
import dev.jaimin.auraorbit.WidgetLogoStore;
import dev.jaimin.auraorbit.SphereWidgetProvider;

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

        // Sliders
        Slider sliderRadius = view.findViewById(R.id.slider_radius);
        sliderRadius.setValue(prefs.getInt("pref_sphere_radius", 50));
        sliderRadius.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) prefs.edit().putInt("pref_sphere_radius", (int) value).apply();
        });

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

        // Background handler
        tvBackgroundStatus = view.findViewById(R.id.tv_background_status);
        updateBackgroundStatus();

        view.findViewById(R.id.btn_background).setOnClickListener(v -> {
            if (BackgroundStore.exists(requireContext())) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setItems(new CharSequence[]{"Choose new photo", "Remove photo", "Cancel"}, (dialog, which) -> {
                            if (which == 0) {
                                launchPicker();
                            } else if (which == 1) {
                                BackgroundStore.clear(requireContext());
                                updateBackgroundStatus();
                            }
                        })
                        .show();
            } else {
                launchPicker();
            }
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
        updateWidgetLogoStatus();
        view.findViewById(R.id.btn_widget_logo).setOnClickListener(v -> {
            showWidgetLogoDialog();
        });

        MaterialSwitch switchTransparent = view.findViewById(R.id.switch_transparent_widget);
        switchTransparent.setChecked(prefs.getBoolean("pref_widget_transparent", false));
        switchTransparent.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("pref_widget_transparent", isChecked).apply();
            SphereWidgetProvider.updateAllWidgets(requireContext());
        });

        MaterialSwitch switchHideText = view.findViewById(R.id.switch_hide_widget_text);
        switchHideText.setChecked(prefs.getBoolean("pref_widget_hide_text", false));
        switchHideText.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("pref_widget_hide_text", isChecked).apply();
            SphereWidgetProvider.updateAllWidgets(requireContext());
        });

        MaterialSwitch switchHideLogo = view.findViewById(R.id.switch_hide_widget_logo);
        switchHideLogo.setChecked(prefs.getBoolean("pref_widget_hide_logo", false));
        switchHideLogo.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("pref_widget_hide_logo", isChecked).apply();
            SphereWidgetProvider.updateAllWidgets(requireContext());
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.settings_title);
        updateBackgroundStatus();
        updateWidgetLogoStatus();
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

    private void showWidgetLogoDialog() {
        android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.setContentView(R.layout.dialog_widget_logo);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        android.widget.ImageView previewPlanet = dialog.findViewById(R.id.dialog_preview_planet);
        android.widget.ImageView previewRing = dialog.findViewById(R.id.dialog_preview_ring);
        android.widget.ImageView previewCustom = dialog.findViewById(R.id.dialog_preview_custom);
        
        final String[] selectedColor = {prefs.getString("pref_widget_orbit_color", "#FFFFFF")};

        Runnable updatePreview = () -> {
            if (WidgetLogoStore.exists(requireContext())) {
                previewPlanet.setVisibility(View.GONE);
                previewRing.setVisibility(View.GONE);
                previewCustom.setVisibility(View.VISIBLE);
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(WidgetLogoStore.file(requireContext()).getAbsolutePath());
                if (bitmap != null) {
                    previewCustom.setImageBitmap(bitmap);
                }
            } else {
                previewPlanet.setVisibility(View.VISIBLE);
                previewRing.setVisibility(View.VISIBLE);
                previewCustom.setVisibility(View.GONE);
                try {
                    previewRing.setColorFilter(android.graphics.Color.parseColor(selectedColor[0]));
                } catch (Exception e) {}
            }
        };
        updatePreview.run();

        android.widget.LinearLayout colorContainer = dialog.findViewById(R.id.dialog_color_container);
        String[] colors = {"#FFFFFF", "#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4", "#009688", "#4CAF50", "#8BC34A", "#CDDC39", "#FFEB3B", "#FFC107", "#FF9800", "#FF5722"};
        for (String colorHex : colors) {
            View colorView = new View(requireContext());
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                    (int)(40 * getResources().getDisplayMetrics().density),
                    (int)(40 * getResources().getDisplayMetrics().density)
            );
            params.setMargins((int)(4 * getResources().getDisplayMetrics().density), 0, (int)(4 * getResources().getDisplayMetrics().density), 0);
            colorView.setLayoutParams(params);
            
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            try {
                gd.setColor(android.graphics.Color.parseColor(colorHex));
            } catch(Exception e){}
            colorView.setBackground(gd);
            
            colorView.setOnClickListener(v -> {
                selectedColor[0] = colorHex;
                updatePreview.run();
            });
            colorContainer.addView(colorView);
        }

        android.widget.Button btnUpload = dialog.findViewById(R.id.dialog_btn_upload);
        btnUpload.setOnClickListener(v -> {
            dialog.dismiss();
            pickWidgetLogoMedia.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });

        android.widget.Button btnRemove = dialog.findViewById(R.id.dialog_btn_remove_custom);
        if (WidgetLogoStore.exists(requireContext())) {
            btnRemove.setVisibility(View.VISIBLE);
        }
        btnRemove.setOnClickListener(v -> {
            WidgetLogoStore.clear(requireContext());
            updateWidgetLogoStatus();
            SphereWidgetProvider.updateAllWidgets(requireContext());
            updatePreview.run();
            btnRemove.setVisibility(View.GONE);
        });

        dialog.findViewById(R.id.dialog_btn_cancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.dialog_btn_save).setOnClickListener(v -> {
            prefs.edit().putString("pref_widget_orbit_color", selectedColor[0]).apply();
            SphereWidgetProvider.updateAllWidgets(requireContext());
            dialog.dismiss();
        });

        dialog.show();
    }
}
