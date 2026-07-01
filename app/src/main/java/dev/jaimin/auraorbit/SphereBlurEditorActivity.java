package dev.jaimin.auraorbit;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import com.google.android.material.slider.Slider;

public class SphereBlurEditorActivity extends AppCompatActivity {

    private Dialog blurDialog;
    private Dialog controlDialog;
    
    private float currentScale = 1.0f;
    private int currentBlurRadius = 0;
    private int currentBlurStrength = 0;
    
    private int screenWidth;
    private int screenHeight;
    private int sphereX, sphereY;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Immersive mode for the activity
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                        
        setContentView(new View(this)); // Transparent and empty

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
        // We now have two preferences
        currentBlurRadius = prefs.getInt(radiusPref, 0);
        currentBlurStrength = prefs.getInt(strengthPref, 0);
        
        // Migrate old pref_blur_amount if the new ones don't exist
        if (!prefs.contains(radiusPref) && groupName == null && prefs.contains("pref_blur_amount")) {
            int oldAmount = prefs.getInt("pref_blur_amount", 0);
            currentBlurRadius = oldAmount;
            currentBlurStrength = oldAmount > 0 ? 50 : 0;
        }

        String pos = prefs.getString(posPref, "center");

        int sphereSize = (int) (screenWidth * currentScale);
        sphereX = (screenWidth - sphereSize) / 2;
        sphereY = (screenHeight - sphereSize) / 2;
        
        if ("top".equals(pos)) {
            sphereY = 100;
        } else if ("bottom".equals(pos)) {
            sphereY = screenHeight - sphereSize - 100;
        } else if ("custom".equals(pos)) {
            sphereX = (int) prefs.getFloat(xPref, sphereX);
            sphereY = (int) prefs.getFloat(yPref, sphereY);
        }

        setupBlurDialog();
        setupControlDialog();
    }
    
    private void setupBlurDialog() {
        // Create a Dialog using a translucent theme so it respects bounds
        blurDialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        blurDialog.setContentView(R.layout.layout_blur_preview);
        
        View sphereMock = blurDialog.findViewById(R.id.sphere_mock);
        FrameLayout.LayoutParams mockParams = (FrameLayout.LayoutParams) sphereMock.getLayoutParams();
        mockParams.width = (int) (screenWidth * currentScale);
        mockParams.height = (int) (screenWidth * currentScale);
        mockParams.gravity = android.view.Gravity.CENTER;
        sphereMock.setLayoutParams(mockParams);
        
        Window window = blurDialog.getWindow();
        if (window != null) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.TRANSPARENT);
            window.setBackgroundDrawable(circle);
            
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
        
        blurDialog.show();
        updateBlurPreview();
    }
    
    private void setupControlDialog() {
        controlDialog = new Dialog(this, R.style.Theme_AuraOrbit_TransparentFullscreen);
        controlDialog.setContentView(R.layout.layout_blur_controls);
        
        Window window = controlDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = android.view.Gravity.BOTTOM;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            // Ensure immersive mode for control dialog so it draws under nav bar
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            window.setAttributes(params);
        }
        
        Slider sliderRadius = controlDialog.findViewById(R.id.slider_blur_radius);
        Slider sliderStrength = controlDialog.findViewById(R.id.slider_blur_strength);
        
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
            String groupName = getIntent().getStringExtra("group_name");
            String saveRadiusPref = groupName != null ? "pref_blur_radius_" + groupName : "pref_blur_radius";
            String saveStrengthPref = groupName != null ? "pref_blur_strength_" + groupName : "pref_blur_strength";
            prefs.edit()
                .putInt(saveRadiusPref, currentBlurRadius)
                .putInt(saveStrengthPref, currentBlurStrength)
                .apply();
            controlDialog.dismiss();
            finish();
        });
        
        controlDialog.setOnCancelListener(dialog -> finish());
        
        controlDialog.show();
    }
    
    @Override
    protected void onDestroy() {
        if (blurDialog != null && blurDialog.isShowing()) blurDialog.dismiss();
        if (controlDialog != null && controlDialog.isShowing()) controlDialog.dismiss();
        super.onDestroy();
    }
    
    private void updateBlurPreview() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && blurDialog != null) {
            Window window = blurDialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams params = window.getAttributes();
                
                int sphereSize = (int) (screenWidth * currentScale);
                int maxDim = Math.max(screenWidth, screenHeight) * 2;
                
                // blurSize grows from sphereSize to maxDim
                int blurSize = (int) (sphereSize + (maxDim - sphereSize) * (currentBlurRadius / 100.0f));
                
                if (currentBlurRadius == 0 || currentBlurStrength == 0) {
                    window.setBackgroundBlurRadius(0);
                    // Still need to keep it at least sphereSize so sphereMock isn't clipped
                    params.width = sphereSize;
                    params.height = sphereSize;
                } else {
                    int radius = Math.min(currentBlurStrength * 2, 150); // Scale up to max blur radius
                    if (radius == 0) radius = 1;
                    window.setBackgroundBlurRadius(radius);
                    
                    params.width = blurSize;
                    params.height = blurSize;
                }
                
                params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                
                float sphereCenterX = sphereX + sphereSize / 2f;
                float sphereCenterY = sphereY + sphereSize / 2f;
                
                params.x = (int) (sphereCenterX - params.width / 2f);
                params.y = (int) (sphereCenterY - params.height / 2f);
                
                window.setAttributes(params);
            }
        }
    }
}
