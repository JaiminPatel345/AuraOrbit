package dev.jaimin.auraorbit;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        // Initialize libGDX with activityMode=true so the engine bypasses all
        // wallpaper-specific guards (page isolation, edge exclusion, zoom revert,
        // command gating). initialize() sets the content view internally.
        initialize(new SphereEngine(this, true), config);

        // ─── Overlay: floating gear button ─────────────────────────────────
        // The libGDX surface is now the content view. We overlay an Android
        // ImageButton on top via addContentView so it receives touch events
        // without interfering with libGDX's input processing on the GL surface.
        addSettingsButton();

        // ─── Hide system bars (immersive fullscreen) ─────────────────────
        // Must be called AFTER super.onCreate / initialize so the window is
        // fully decorated and the insets controller is available.
        hideSystemBars();
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

        // Hide both status bar and navigation bar immediately.
        controller.hide(WindowInsetsCompat.Type.statusBars()
                | WindowInsetsCompat.Type.navigationBars());
    }

    /**
     * Adds a small floating gear (settings) button in the top-right corner.
     *
     * The button is overlaid via {@link #addContentView} on top of the libGDX
     * GL surface using a {@link FrameLayout} wrapper with GRAVITY_TOP|END
     * placement. Alpha is 0.6f (subtle, non-intrusive).
     */
    private void addSettingsButton() {
        // Convert 40dp to pixels for the button size.
        int sizePx = (int) (40 * getResources().getDisplayMetrics().density);
        int marginPx = (int) (12 * getResources().getDisplayMetrics().density);

        ImageButton gearButton = new ImageButton(this);
        gearButton.setImageResource(android.R.drawable.ic_menu_preferences);
        gearButton.setBackgroundResource(android.R.drawable.btn_default);
        gearButton.setAlpha(0.6f);
        gearButton.setContentDescription(getString(R.string.sphere_mode_open_settings));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sizePx, sizePx);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = marginPx;
        lp.rightMargin = marginPx;

        gearButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LiveWallpaperSettings.class);
            startActivity(intent);
        });

        // addContentView places the FrameLayout on top of the existing content
        // (the libGDX surface) without replacing it.
        addContentView(gearButton, lp);
    }

    /**
     * Re-apply immersive mode when the activity window focus returns
     * (e.g. after returning from Settings or a launched app).
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }
}
