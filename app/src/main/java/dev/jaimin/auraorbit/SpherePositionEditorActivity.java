package dev.jaimin.auraorbit;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.google.android.material.slider.Slider;

public class SpherePositionEditorActivity extends AndroidApplication {

    private FrameLayout sphereMock;
    private SphereEngine sphereEngine;
    private float dX, dY;
    
    private float currentScale = 1.0f;
    private int screenWidth;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sphere_position_editor);

        // Immersive mode
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        sphereMock = findViewById(R.id.sphere_mock);
        Slider sliderScale = findViewById(R.id.slider_scale);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;

        String groupName = getIntent().getStringExtra("group_name");
        String scalePref = groupName != null ? "pref_sphere_scale_" + groupName : "pref_sphere_scale";
        String xPref = groupName != null ? "pref_sphere_x_" + groupName : "pref_sphere_x";
        String yPref = groupName != null ? "pref_sphere_y_" + groupName : "pref_sphere_y";

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentScale = prefs.getFloat(scalePref, 1.0f);
        float initX = prefs.getFloat(xPref, 0f);
        float initY = prefs.getFloat(yPref, (metrics.heightPixels - screenWidth)/2f);

        // ─── Initialize LibGDX 3D View ───────────────────────────────────
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
        View glView = initializeForView(sphereEngine, config);
        
        // Pass touches through the glView so dragging is handled by sphereMock container
        glView.setClickable(false);
        glView.setFocusable(false);
        glView.setOnTouchListener((v, event) -> false);

        if (graphics.getView() instanceof android.view.SurfaceView) {
            android.view.SurfaceView surfaceView = (android.view.SurfaceView) graphics.getView();
            surfaceView.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            surfaceView.setZOrderOnTop(true);
        }

        sphereMock.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        updateSphereSize();
        
        sphereMock.post(() -> {
            sphereMock.setX(initX);
            sphereMock.setY(initY);
        });

        sliderScale.setValue(currentScale);
        sliderScale.addOnChangeListener((slider, value, fromUser) -> {
            currentScale = value;
            updateSphereSize();
        });

        sphereMock.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX = view.getX() - event.getRawX();
                    dY = view.getY() - event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    view.setX(event.getRawX() + dX);
                    view.setY(event.getRawY() + dY);
                    break;
                default:
                    return false;
            }
            return true;
        });

        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save).setOnClickListener(v -> {
            String saveGroupName = getIntent().getStringExtra("group_name");
            String saveXPref = saveGroupName != null ? "pref_sphere_x_" + saveGroupName : "pref_sphere_x";
            String saveYPref = saveGroupName != null ? "pref_sphere_y_" + saveGroupName : "pref_sphere_y";
            String saveScalePref = saveGroupName != null ? "pref_sphere_scale_" + saveGroupName : "pref_sphere_scale";
            String savePosPref = saveGroupName != null ? "pref_sphere_position_" + saveGroupName : "pref_sphere_position";
            
            prefs.edit()
                 .putFloat(saveXPref, sphereMock.getX())
                 .putFloat(saveYPref, sphereMock.getY())
                 .putFloat(saveScalePref, currentScale)
                 .putString(savePosPref, "custom")
                 .apply();
            finish();
        });
    }
    
    private void updateSphereSize() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) sphereMock.getLayoutParams();
        params.width = (int) (screenWidth * currentScale);
        params.height = (int) (screenWidth * currentScale);
        sphereMock.setLayoutParams(params);
    }
}
