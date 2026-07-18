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
import dev.jaimin.auraorbit.ui.InterceptingFrameLayout;

public class SpherePositionEditorActivity extends AndroidApplication {

    private InterceptingFrameLayout sphereMock;
    private SphereEngine sphereEngine;
    
    private float dX, dY;
    private float startX, startY;
    private float currentX, currentY;
    
    private float currentScale = 1.0f;
    private int screenWidth;
    private SharedPreferences prefs;

    private String xPref;
    private String yPref;
    private String scalePref;
    private String posPref;

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
        scalePref = groupName != null ? "pref_sphere_scale_" + groupName : "pref_sphere_scale";
        xPref = groupName != null ? "pref_sphere_x_" + groupName : "pref_sphere_x";
        yPref = groupName != null ? "pref_sphere_y_" + groupName : "pref_sphere_y";
        posPref = groupName != null ? "pref_sphere_position_" + groupName : "pref_sphere_position";

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentScale = prefs.getFloat(scalePref, 1.0f);
        currentX = prefs.getFloat(xPref, 0f);
        currentY = prefs.getFloat(yPref, (metrics.heightPixels - screenWidth)/2f);

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
        sphereEngine.applyPositionAndScale = true; // Tell engine to translate camera like wallpaper mode
        sphereEngine.setPreviewModeAuthoritative(true); // Ensure app decals are loaded and rendered immediately
        
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

        sliderScale.setValue(currentScale);
        sliderScale.addOnChangeListener((slider, value, fromUser) -> {
            currentScale = value;
            sphereEngine.updateCameraPositionAndScale(currentX, currentY, currentScale);
        });

        sphereMock.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX = event.getRawX();
                    dY = event.getRawY();
                    startX = currentX;
                    startY = currentY;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getRawX() - dX;
                    float deltaY = event.getRawY() - dY;
                    currentX = startX + deltaX;
                    currentY = startY + deltaY;
                    
                    sphereEngine.updateCameraPositionAndScale(currentX, currentY, currentScale);
                    break;
                default:
                    return false;
            }
            return true;
        });

        findViewById(R.id.btn_cancel).setOnClickListener(v -> finish());
        
        findViewById(R.id.btn_save).setOnClickListener(v -> {
            prefs.edit()
                 .putFloat(xPref, currentX)
                 .putFloat(yPref, currentY)
                 .putFloat(scalePref, currentScale)
                 .putString(posPref, "custom")
                 .apply();
            finish();
        });
    }
}
