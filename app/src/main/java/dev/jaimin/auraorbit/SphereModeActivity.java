package dev.jaimin.auraorbit;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * SphereModeActivity — Fullscreen Sphere Mode Entry Point
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * A fullscreen {@link AndroidApplication} that renders the same 3D sphere as the
 * live wallpaper, but with the key advantage that the activity owns ALL input.
 * There is no launcher fighting for one-finger swipes; every gesture goes directly
 * to the sphere.
 *
 * ─── Key differences from wallpaper mode ────────────────────────────────────
 *
 * - activityMode=true in SphereEngine: page-visibility is always 1, tap-to-launch
 *   is always direct (no command gating, no zoom/drawer guards, no edge exclusion).
 * - Back gesture / button finishes the activity naturally (AndroidApplication
 *   default behavior — no override needed).
 * - A floating gear button (top-right) opens LiveWallpaperSettings.
 * - Launched apps: the sphere fires startActivity, the launched app comes to the
 *   foreground. This activity remains in the back stack behind it; the user presses
 *   Back to return to Sphere Mode or Home to leave both. This is the simplest UX
 *   and requires no callback coordination.
 *
 * ─── Immersive fullscreen ─────────────────────────────────────────────────────
 *
 * libGDX 1.13.0's AndroidApplicationConfiguration does not expose useImmersiveMode
 * as a public field (it was removed in earlier 1.1x releases). We use
 * WindowInsetsControllerCompat directly (targetSdk 35 pattern):
 *   - BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: swipe in from edge to temporarily
 *     reveal status/nav bars (they auto-hide after a moment).
 *   - hide(statusBars | navigationBars) immediately after the window is decorated.
 *
 * Edge-to-edge is enabled via WindowCompat.setDecorFitsSystemWindows(window, false)
 * so the libGDX surface fills the entire display including cutout areas.
 */
public class SphereModeActivity extends AndroidApplication {

    private static final String TAG = "AuraOrbit.SphereMode";
    
    private SphereEngine sphereEngine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        // No window-level FLAG_BLUR_BEHIND here.
        // We will apply blur to a specific View so it can dynamically resize.

        // ─── Fullscreen / edge-to-edge ──────────────────────────────────
        // Tell the decor not to fit system windows so the GL surface reaches
        // every pixel including display cutouts.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Keep screen on while Sphere Mode is open.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // ─── libGDX initialization ────────────────────────────────────────
        // Mirror MyWallpaperService's config: no sensors, depth 16, rgba8888,
        // no MSAA — identical rendering pipeline, just inside an activity.
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

        // Read group_name / widget_name extra if opened from a pinned widget
        String groupName = getIntent().getStringExtra("group_name");
        if (groupName == null) {
            groupName = getIntent().getStringExtra("widget_name");
        }

        // Initialize libGDX with activityMode=true so the engine bypasses all
        // wallpaper-specific guards (page isolation, edge exclusion, zoom revert,
        // command gating).
        sphereEngine = new SphereEngine(this, true, groupName);
        sphereEngine.applyPositionAndScale = true;
        View glView = initializeForView(sphereEngine, config);
        glView.setClickable(true); // Ensure glView consumes clicks
        if (graphics.getView() instanceof android.view.SurfaceView) {
            android.view.SurfaceView surfaceView = (android.view.SurfaceView) graphics.getView();
            surfaceView.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            surfaceView.setZOrderOnTop(true);
        }
        String scalePref = groupName != null ? "pref_sphere_scale_" + groupName : "pref_sphere_scale";
        String radiusPref = groupName != null ? "pref_blur_radius_" + groupName : "pref_blur_radius";
        String strengthPref = groupName != null ? "pref_blur_strength_" + groupName : "pref_blur_strength";
        String posPref = groupName != null ? "pref_sphere_position_" + groupName : "pref_sphere_position";
        String xPref = groupName != null ? "pref_sphere_x_" + groupName : "pref_sphere_x";
        String yPref = groupName != null ? "pref_sphere_y_" + groupName : "pref_sphere_y";

        float scale = prefs.getFloat(scalePref, 1.0f);
        String pos = prefs.getString(posPref, "center");
        int blurRadiusPref = prefs.getInt(radiusPref, 10);
        int blurStrengthPref = prefs.getInt(strengthPref, 50);
        // Migrate old pref_blur_amount if the new ones don't exist
        if (!prefs.contains(radiusPref) && groupName == null && prefs.contains("pref_blur_amount")) {
            int oldAmount = prefs.getInt("pref_blur_amount", 0);
            blurRadiusPref = oldAmount;
            blurStrengthPref = oldAmount > 0 ? 50 : 0;
        }

        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int sphereSize = (int) (screenWidth * scale);

        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        
        // ─── Window Bounds ────────────────────────────────────────────────
        int sphereCenterX, sphereCenterY;
        if ("custom".equals(pos)) {
            float defaultX = (screenWidth - (screenWidth * scale)) / 2f;
            float defaultY = (screenHeight - (screenWidth * scale)) / 2f;
            float sphereX = prefs.getFloat(xPref, defaultX);
            float sphereY = prefs.getFloat(yPref, defaultY);
            sphereCenterX = (int) (sphereX + (screenWidth * scale) / 2f);
            sphereCenterY = (int) (sphereY + (screenWidth * scale) / 2f);
        } else if ("top".equals(pos)) {
            sphereCenterX = screenWidth / 2;
            sphereCenterY = (int) (screenHeight * 0.25f);
        } else if ("bottom".equals(pos)) {
            sphereCenterX = screenWidth / 2;
            sphereCenterY = (int) (screenHeight * 0.75f);
        } else { // "center"
            sphereCenterX = screenWidth / 2;
            sphereCenterY = screenHeight / 2;
        }
        
        // Position glView to cover the full screen so that the engine renders matching the preview and wallpaper
        android.widget.FrameLayout.LayoutParams glParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        container.addView(glView, glParams);
        
        // Close the activity if the user touches the blurred background outside the sphere
        container.setOnClickListener(v -> {
            if (sphereEngine != null) {
                sphereEngine.fanOutAndFinish();
            } else {
                finish();
            }
        });
        
        setContentView(container);

        // Calculate the exact on-screen pixel radius of the 3D sphere using the
        // same perspective math as SphereEngine.computeCameraDistance().
        int sphereRadiusPref = prefs.getInt("pref_sphere_radius", 50);
        int iconPref = prefs.getInt("pref_icon_size", 50);
        if (groupName != null) {
            iconPref = prefs.getInt("pref_icon_size_" + groupName, iconPref);
        }
        float worldRadius = 3.0f + 5.0f * (sphereRadiusPref / 100f);
        float worldIconSize = 0.6f + 1.4f * (iconPref / 100f);
        float effRadius = worldRadius + worldIconSize * 0.75f;
        float actualVisualSphereRadius = SphereEngine.computeVisualSpherePixelRadius(
                worldRadius, effRadius, 67f, screenWidth, screenHeight, scale);

        // ─── Blur oval radius calculation ────────────────────────────
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
        float ovalRadius = 0f;
        if (blurRadiusPref > 0) {
            if (blurRadiusPref <= 10) {
                ovalRadius = actualVisualSphereRadius * (blurRadiusPref / 10f);
            } else {
                float distTopLeft     = (float) Math.hypot(sphereCenterX, sphereCenterY);
                float distTopRight    = (float) Math.hypot(screenWidth - sphereCenterX, sphereCenterY);
                float distBottomLeft  = (float) Math.hypot(sphereCenterX, screenHeight - sphereCenterY);
                float distBottomRight = (float) Math.hypot(screenWidth - sphereCenterX, screenHeight - sphereCenterY);
                float maxCornerRadius = Math.max(Math.max(distTopLeft, distTopRight),
                                                 Math.max(distBottomLeft, distBottomRight));

                float t = (blurRadiusPref - 10f) / 10f; // 0 at value=11, 1 at value=20
                ovalRadius = actualVisualSphereRadius
                           + (maxCornerRadius - actualVisualSphereRadius) * t;
            }
        }
        
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        params.x = 0;
        params.y = 0;
        
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        params.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        params.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && blurRadiusPref > 0 && blurStrengthPref > 0) {
            int radius = Math.min(blurStrengthPref * 2, 150);
            if (radius == 0) radius = 1;
            getWindow().setBackgroundBlurRadius(radius);
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            getWindow().setBackgroundBlurRadius(0);
        }
        
        if (blurRadiusPref > 0 && blurStrengthPref > 0) {
            int left   = (int) (sphereCenterX - ovalRadius);
            int top    = (int) (sphereCenterY - ovalRadius);
            int right  = (int) (sphereCenterX + ovalRadius);
            int bottom = (int) (sphereCenterY + ovalRadius);
            getWindow().setBackgroundDrawable(new BlurBackgroundDrawable(left, top, right, bottom));
        } else {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        
        getWindow().setAttributes(params);

        // ─── Hide system bars (immersive fullscreen) ─────────────────────
        // Must be called AFTER super.onCreate / initialize so the window is
        // fully decorated and the insets controller is available.
        hideSystemBars();

        // ─── Empty state popup ────────────────────────────────────────────
        boolean isEmpty = false;
        if (groupName != null) {
            java.util.List<WidgetStore.Widget> widgets = WidgetStore.load(prefs);
            WidgetStore.Widget w = WidgetStore.find(widgets, groupName);
            if (w == null || w.packages.isEmpty()) {
                isEmpty = true;
            }
        } else {
            java.util.Set<String> selectedApps = prefs.getStringSet(AppFetcher.PREF_SELECTED_APPS, new java.util.HashSet<>());
            if (selectedApps.isEmpty()) {
                isEmpty = true;
            }
        }

        if (isEmpty) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("No Apps Selected")
                .setMessage("You need to select at least one app to see it in the AuraOrbit sphere.")
                .setPositiveButton("Go to Apps", (dialog, which) -> {
                    Intent intent = new Intent(this, LiveWallpaperSettings.class);
                    intent.putExtra("open_fragment", "apps");
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("Close", (dialog, which) -> {
                    finish();
                })
                .setCancelable(false)
                .show();
        }
    }

    /**
     * Hides status and navigation bars for a true fullscreen experience.
     *
     * Uses WindowInsetsControllerCompat (targetSdk 35 / AndroidX pattern).
     * BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE lets the user temporarily reveal
     * the bars by swiping from the edge — they auto-hide after ~2 s.
     */
    private void hideSystemBars() {
        View decorView = getWindow().getDecorView();
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), decorView);

        // Swipe-to-reveal: transient bars appear on edge swipe then auto-hide.
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }


    /**
     * Re-apply immersive mode when the activity window focus returns
     * (e.g. after returning from Settings or a launched app).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        
        // If the activity was already running and another widget was clicked,
        // update the engine with the new group name!
        if (sphereEngine != null) {
            String groupName = intent.getStringExtra("group_name");
            if (groupName == null) {
                groupName = intent.getStringExtra("widget_name");
            }
            sphereEngine.setPinnedGroupName(groupName);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        Intent hideIntent = new Intent(this, SphereWidgetProvider.class);
        hideIntent.setAction("dev.jaimin.auraorbit.WIDGET_HIDE");
        sendBroadcast(hideIntent);

        // Always hide system bars on resume to ensure the activity stays immersive
        // if the user pulled down the notification shade.
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Intent showIntent = new Intent(this, SphereWidgetProvider.class);
        showIntent.setAction("dev.jaimin.auraorbit.WIDGET_SHOW");
        sendBroadcast(showIntent);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_OUTSIDE) {
            if (sphereEngine != null) {
                sphereEngine.fanOutAndFinish();
            } else {
                finish();
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (sphereEngine != null) {
            sphereEngine.fanOutAndFinish();
        } else {
            super.onBackPressed();
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
