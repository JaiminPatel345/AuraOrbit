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
import com.google.android.material.slider.Slider;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jaimin.auraorbit.BackgroundStore;
import dev.jaimin.auraorbit.R;

public class DashboardFragment extends Fragment {

    private SharedPreferences prefs;
    private ExecutorService executor;
    private TextView tvBackgroundStatus;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    this::saveBackground
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
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.settings_title);
        updateBackgroundStatus();
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

    private void navigateTo(@NonNull Fragment fragment) {
        getParentFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, fragment)
                .addToBackStack(null)
                .commit();
    }
}
