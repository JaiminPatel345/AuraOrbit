package dev.jaimin.auraorbit;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.google.android.material.slider.Slider;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

public class SphereBlurEditorActivity extends com.badlogic.gdx.backends.android.AndroidApplication {

    private SphereEngine sphereEngine;
    private Dialog controlDialog;

    private float currentScale = 1.0f;
    private int currentBlurRadius = 0;
    private int currentBlurStrength = 0;

    private int screenWidth;
    private int screenHeight;
    private float sphereCenterX, sphereCenterY;
    private float actualVisualSphereRadius;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Make the window transparent
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        
        // Immersive mode for the activity
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.layout_blur_preview);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        String groupName = getIntent().getStringExtra("group_name");
        String scalePref = groupName != null ? "pref_sphere_scale_" + groupName : "pref_sphere_scale";
        String radiusPref = groupName != null ? "pref_blur_radius_" + groupName : "pref_blur_radius";
        String strengthPref = groupName != null ? "pref_blur_strength_" + groupName : "pref_blur_strength";
        String posPref = groupName != null ? "pref_sphere_position_" + groupName : "pref_sphere_position";
        String xPref = groupName != null ? "pref_sphere_x_" + groupName : "pref_sphere_x";
        String yPref = groupName != null ? "pref_sphere_y_" + groupName : "pref_sphere_y";

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentScale = prefs.getFloat(scalePref, 1.0f);
        currentBlurRadius = prefs.getInt(radiusPref, 10);
        currentBlurStrength = prefs.getInt(strengthPref, 50);

        // Migrate old pref_blur_amount if the new ones don't exist
        if (!prefs.contains(radiusPref) && groupName == null && prefs.contains("pref_blur_amount")) {
            int oldAmount = prefs.getInt("pref_blur_amount", 0);
            currentBlurRadius = oldAmount;
            currentBlurStrength = oldAmount > 0 ? 50 : 0;
        }

        // Calculate the exact 3D visual sphere center coordinates
        String posType = prefs.getString(posPref, "center");
        if ("custom".equals(posType)) {
            float defaultX = (screenWidth - (screenWidth * currentScale)) / 2f;
            float defaultY = (screenHeight - (screenWidth * currentScale)) / 2f;
            float sphereX = prefs.getFloat(xPref, defaultX);
            float sphereY = prefs.getFloat(yPref, defaultY);
            sphereCenterX = sphereX + (screenWidth * currentScale) / 2f;
            sphereCenterY = sphereY + (screenWidth * currentScale) / 2f;
        } else if ("top".equals(posType)) {
            sphereCenterX = screenWidth / 2f;
            sphereCenterY = screenHeight * 0.25f;
        } else if ("bottom".equals(posType)) {
            sphereCenterX = screenWidth / 2f;
            sphereCenterY = screenHeight * 0.75f;
        } else { // "center"
            sphereCenterX = screenWidth / 2f;
            sphereCenterY = screenHeight / 2f;
        }

        // Calculate the exact on-screen pixel radius of the 3D sphere using the
        // same perspective math as SphereEngine.computeCameraDistance().
        // Engine: FOV=67° (vertical), camera placed at effRadius/sin(halfH)*1.05f back.
        // We project worldRadius through that same view frustum to get screen pixels.
        int sphereRadiusPref = prefs.getInt("pref_sphere_radius", 50);
        int iconPref = prefs.getInt("pref_icon_size", 50);
        if (groupName != null) {
            iconPref = prefs.getInt("pref_icon_size_" + groupName, iconPref);
        }
        float worldRadius = 3.0f + 5.0f * (sphereRadiusPref / 100f);
        float worldIconSize = 0.6f + 1.4f * (iconPref / 100f);
        float effRadius = worldRadius + worldIconSize * 0.75f;
        actualVisualSphereRadius = SphereEngine.computeVisualSpherePixelRadius(
                worldRadius, effRadius, 67f, screenWidth, screenHeight, currentScale);

        // Render the 3D sphere natively in preview container
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;
        config.depth = 16;
        config.stencil = 0;
        config.numSamples = 0;
        config.r = 8;
        config.g = 8;
        config.b = 8;
        config.a = 8;

        sphereEngine = new SphereEngine(this, true, groupName);
        sphereEngine.applyPositionAndScale = true;
        sphereEngine.setPreviewModeAuthoritative(true);

        View glView = initializeForView(sphereEngine, config);
        glView.setClickable(false);
        glView.setFocusable(false);
        glView.setOnTouchListener((v, event) -> false);

        if (graphics.getView() instanceof android.view.SurfaceView) {
            android.view.SurfaceView surfaceView = (android.view.SurfaceView) graphics.getView();
            surfaceView.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            surfaceView.setZOrderOnTop(true); // Keep 3D view on top of the window background blur
        }

        FrameLayout sphereMock = findViewById(R.id.sphere_mock);
        if (sphereMock != null) {
            sphereMock.addView(glView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
        }

        setupControlDialog(radiusPref, strengthPref);
        updateBlurPreview();
    }

    private void setupControlDialog(String radiusPref, String strengthPref) {
        controlDialog = new Dialog(this, R.style.Theme_AuraOrbit_TransparentFullscreen);
        controlDialog.setContentView(R.layout.layout_blur_controls);
        
        Window window = controlDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = android.view.Gravity.BOTTOM;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            window.setAttributes(params);
        }
        
        Slider sliderRadius = controlDialog.findViewById(R.id.slider_blur_radius);
        Slider sliderStrength = controlDialog.findViewById(R.id.slider_blur_strength);
        
        sliderRadius.setLabelFormatter(value -> String.format(java.util.Locale.US, "%.1f", value / 10f));
        sliderRadius.setValue(currentBlurRadius);
        sliderStrength.setValue(currentBlurStrength);
        
        sliderRadius.addOnChangeListener((slider, value, fromUser) -> {
            currentBlurRadius = (int) value;
            updateBlurPreview();
        });
        
        sliderStrength.addOnChangeListener((slider, value, fromUser) -> {
            currentBlurStrength = (int) value;
            updateBlurPreview();
        });

        controlDialog.findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            controlDialog.dismiss();
            finish();
        });
        
        controlDialog.findViewById(R.id.btn_save).setOnClickListener(v -> {
            prefs.edit()
                .putInt(radiusPref, currentBlurRadius)
                .putInt(strengthPref, currentBlurStrength)
                .apply();
            controlDialog.dismiss();
            finish();
        });
        
        controlDialog.setOnCancelListener(dialog -> finish());
        controlDialog.show();
    }

    @Override
    protected void onDestroy() {
        if (controlDialog != null && controlDialog.isShowing()) {
            controlDialog.dismiss();
        }
        super.onDestroy();
    }

    private void updateBlurPreview() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (currentBlurRadius == 0 || currentBlurStrength == 0) {
                getWindow().setBackgroundBlurRadius(0);
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            } else {
                int radius = Math.min(currentBlurStrength * 2, 150);
                if (radius == 0) radius = 1;
                getWindow().setBackgroundBlurRadius(radius);

                // ─── Blur oval radius calculation ─────────────────────────────
                // Slider range 0–20 maps to display "0.0"–"2.0" (divided by 10).
                //
                // • value  1–10 (display 0.1–1.0):
                //     Oval grows from 10% → 100% of the sphere circle radius.
                //     At value 10 the oval exactly covers the visible sphere.
                //     Values below 1.0 blur only the inner portion of the sphere.
                //
                // • value 11–20 (display 1.1–2.0):
                //     Oval expands gently beyond the sphere up to full-screen
                //     corner coverage (t goes 0→1 over this range).
                float ovalRadius;
                if (currentBlurRadius <= 10) {
                    // Linear scale within the sphere: 10% per step
                    ovalRadius = actualVisualSphereRadius * (currentBlurRadius / 10f);
                } else {
                    // Distance from sphere edge to the farthest screen corner
                    float distTopLeft     = (float) Math.hypot(sphereCenterX, sphereCenterY);
                    float distTopRight    = (float) Math.hypot(screenWidth - sphereCenterX, sphereCenterY);
                    float distBottomLeft  = (float) Math.hypot(sphereCenterX, screenHeight - sphereCenterY);
                    float distBottomRight = (float) Math.hypot(screenWidth - sphereCenterX, screenHeight - sphereCenterY);
                    float maxCornerRadius = Math.max(Math.max(distTopLeft, distTopRight),
                                                     Math.max(distBottomLeft, distBottomRight));

                    float t = (currentBlurRadius - 10f) / 10f; // 0 at value=11, 1 at value=20
                    ovalRadius = actualVisualSphereRadius
                               + (maxCornerRadius - actualVisualSphereRadius) * t;
                }

                int left   = (int) (sphereCenterX - ovalRadius);
                int top    = (int) (sphereCenterY - ovalRadius);
                int right  = (int) (sphereCenterX + ovalRadius);
                int bottom = (int) (sphereCenterY + ovalRadius);

                getWindow().setBackgroundDrawable(new BlurBackgroundDrawable(left, top, right, bottom));
            }
        }
    }

    private static class BlurBackgroundDrawable extends android.graphics.drawable.GradientDrawable {
        private final int customLeft;
        private final int customTop;
        private final int customRight;
        private final int customBottom;

        public BlurBackgroundDrawable(int left, int top, int right, int bottom) {
            super();
            setShape(OVAL);
            setColor(android.graphics.Color.TRANSPARENT);
            this.customLeft = left;
            this.customTop = top;
            this.customRight = right;
            this.customBottom = bottom;
            super.setBounds(left, top, right, bottom);
        }

        @Override
        public void setBounds(int left, int top, int right, int bottom) {
            super.setBounds(customLeft, customTop, customRight, customBottom);
        }

        @Override
        public void setBounds(@NonNull android.graphics.Rect bounds) {
            super.setBounds(customLeft, customTop, customRight, customBottom);
        }
    }
}
