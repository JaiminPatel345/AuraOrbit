package dev.jaimin.auraorbit.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import dev.jaimin.auraorbit.R;
import dev.jaimin.auraorbit.SpherePositionEditorActivity;

public class PermanentSphereFragment extends Fragment {
    
    private SharedPreferences prefs;
    private TextView tvSpherePositionStatus;
    private ExecutorService executor;
    
    private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    uri -> {
                        if (uri != null) {
                            saveBackground(uri);
                        }
                    }
            );

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
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
        
        MaterialSwitch switchPermanentSphere = view.findViewById(R.id.switch_permanent_sphere);
        switchPermanentSphere.setChecked(prefs.getBoolean("pref_permanent_sphere_enabled", false));
        switchPermanentSphere.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("pref_permanent_sphere_enabled", isChecked).apply();
            if (isChecked) {
                launchLiveWallpaperPreview();
            }
        });
        
        view.findViewById(R.id.btn_select_apps).setOnClickListener(v -> {
            // For permanent sphere we use a dedicated special key
            AppPickerFragment fragment = AppPickerFragment.newInstance("pref_permanent_sphere_apps");
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, fragment)
                    .addToBackStack(null)
                    .commit();
        });
        
        tvSpherePositionStatus = view.findViewById(R.id.tv_sphere_position_status);
        updateSpherePositionStatus();
        view.findViewById(R.id.btn_sphere_position).setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), SpherePositionEditorActivity.class));
        });
        
        view.findViewById(R.id.btn_device_wallpaper).setOnClickListener(v -> {
            pickMedia.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });
        
        Slider sliderIconSize = view.findViewById(R.id.slider_icon_size);
        sliderIconSize.setValue(prefs.getFloat("pref_permanent_icon_size", 1.0f));
        sliderIconSize.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) prefs.edit().putFloat("pref_permanent_icon_size", value).apply();
        });
        
        Slider sliderRotationSpeed = view.findViewById(R.id.slider_rotation_speed);
        sliderRotationSpeed.setValue(prefs.getFloat("pref_permanent_rotation_speed", 1.0f));
        sliderRotationSpeed.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) prefs.edit().putFloat("pref_permanent_rotation_speed", value).apply();
        });
    }

    private void launchLiveWallpaperPreview() {
        try {
            Intent intent = new Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new android.content.ComponentName(requireContext(), dev.jaimin.auraorbit.MyWallpaperService.class));
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            try {
                Intent fallback = new Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
                startActivity(fallback);
            } catch (android.content.ActivityNotFoundException e2) {
                Toast.makeText(requireContext(), "Live wallpaper chooser not found on this device.", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        updateSpherePositionStatus();
    }
    
    private void updateSpherePositionStatus() {
        String position = prefs.getString("pref_sphere_position", "center");
        String display = "Center";
        if ("top".equals(position)) display = "Top";
        else if ("bottom".equals(position)) display = "Bottom";
        else if ("custom".equals(position)) display = "Custom";
        if (tvSpherePositionStatus != null) {
            tvSpherePositionStatus.setText(display);
        }
    }
    
    private void saveBackground(android.net.Uri uri) {
        Context appCtx = requireContext().getApplicationContext();
        Toast.makeText(appCtx, "Saving wallpaper...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean ok = dev.jaimin.auraorbit.BackgroundStore.saveFromUri(appCtx, uri);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (isAdded()) {
                    Toast.makeText(requireContext(),
                            ok ? "Wallpaper updated!" : "Failed to save wallpaper.",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}
