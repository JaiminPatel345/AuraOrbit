package dev.jaimin.auraorbit;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import com.google.android.material.slider.Slider;

public class GestureRadiusEditorActivity extends AppCompatActivity {

    private View sphereMock;
    private View gestureZoneMock;
    private TextView tvPercentValue;
    private SharedPreferences prefs;

    private int screenWidth;
    private int screenHeight;
    private float baseDiameter;
    private float sphereDiameter;
    private float currentPercent = 100f;

    private float centerX;
    private float centerY;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gesture_radius_editor);

        // Immersive mode
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        sphereMock = findViewById(R.id.sphere_mock);
        gestureZoneMock = findViewById(R.id.gesture_zone_mock);
        tvPercentValue = findViewById(R.id.tv_percent_value);
        Slider sliderCaptureRadius = findViewById(R.id.slider_capture_radius);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        int radiusPref = prefs.getInt("pref_sphere_radius", 50);
        int iconPref = prefs.getInt("pref_icon_size", 50);
        float scale = prefs.getFloat("pref_sphere_scale", 1.0f);
        String posType = prefs.getString("pref_sphere_position", "center");
        currentPercent = Math.max(50f, Math.min(150f, (float) prefs.getInt("pref_gesture_capture_scale_percent", 100)));

        // Math to calculate visual sizes matching SphereEngine.java camera projection
        float worldRadius = 3.0f + 5.0f * (radiusPref / 100f);
        float worldIconSize = 0.6f + 1.4f * (iconPref / 100f);
        float effRadius = worldRadius + worldIconSize * 0.75f;
        
        // Base sizes scaled by user's sphere size multiplier
        // Base sizes matching SpherePositionEditorActivity
        float sphereVisualDiameter = screenWidth * scale;
        baseDiameter = (effRadius * 2f * (screenWidth / 16f)) * scale;

        // Position calculations
        if ("custom".equals(posType)) {
            float customX = prefs.getFloat("pref_sphere_x", 0f);
            float customY = prefs.getFloat("pref_sphere_y", (screenHeight - screenWidth) / 2f);
            // Center of custom view of size screenWidth * scale
            centerX = customX + (screenWidth * scale) / 2f;
            centerY = customY + (screenWidth * scale) / 2f;
        } else if ("top".equals(posType)) {
            centerX = screenWidth / 2f;
            centerY = screenHeight * 0.25f;
        } else if ("bottom".equals(posType)) {
            centerX = screenWidth / 2f;
            centerY = screenHeight * 0.75f;
        } else { // "center"
            centerX = screenWidth / 2f;
            centerY = screenHeight / 2f;
        }

        // Apply visual sizes and positions to Sphere Mock
        FrameLayout.LayoutParams sphereParams = (FrameLayout.LayoutParams) sphereMock.getLayoutParams();
        sphereParams.width = (int) sphereVisualDiameter;
        sphereParams.height = (int) sphereVisualDiameter;
        sphereMock.setLayoutParams(sphereParams);
        
        sphereMock.post(() -> {
            sphereMock.setX(centerX - sphereVisualDiameter / 2f);
            sphereMock.setY(centerY - sphereVisualDiameter / 2f);
        });

        updateGestureZone();

        sliderCaptureRadius.setValue(currentPercent);
        tvPercentValue.setText((int) currentPercent + "%");

        sliderCaptureRadius.addOnChangeListener((slider, value, fromUser) -> {
            currentPercent = value;
            tvPercentValue.setText((int) currentPercent + "%");
            updateGestureZone();
        });

        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save).setOnClickListener(v -> {
            prefs.edit()
                 .putInt("pref_gesture_capture_scale_percent", (int) currentPercent)
                 .apply();
            finish();
        });
    }

    private void updateGestureZone() {
        float zoneDiameter = baseDiameter * (currentPercent / 100f);
        FrameLayout.LayoutParams zoneParams = (FrameLayout.LayoutParams) gestureZoneMock.getLayoutParams();
        zoneParams.width = (int) zoneDiameter;
        zoneParams.height = (int) zoneDiameter;
        gestureZoneMock.setLayoutParams(zoneParams);
        
        gestureZoneMock.post(() -> {
            gestureZoneMock.setX(centerX - zoneDiameter / 2f);
            gestureZoneMock.setY(centerY - zoneDiameter / 2f);
        });
    }
}
