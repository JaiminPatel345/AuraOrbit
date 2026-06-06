package dev.jaimin.auraorbit;

import android.content.SharedPreferences;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidLiveWallpaperService;

import androidx.preference.PreferenceManager;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * MyWallpaperService.java — Android ↔ libGDX Bridge for AuraOrbit
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This class serves as the critical bridge between the Android OS wallpaper
 * system and the libGDX game engine. It extends {@link AndroidLiveWallpaperService},
 * which internally manages a GL surface and routes lifecycle events to our
 * {@link SphereEngine} (the libGDX ApplicationListener).
 *
 * ─── Key Responsibilities ───────────────────────────────────────────────────
 *
 * 1. **120Hz Frame Rate Unlock**
 *    By default, Android caps Live Wallpaper surfaces at 60 FPS even on 120Hz
 *    displays. We override {@code onSurfaceCreated()} to call the Android 11+
 *    {@link Surface#setFrameRate(float, int)} API, explicitly requesting 120 FPS
 *    from the compositor. This is essential for the Samsung Galaxy S25 Ultra's
 *    LTPO display to actually render at its full refresh rate.
 *
 * 2. **Page Isolation via onOffsetsChanged**
 *    The Android launcher broadcasts page-swipe offsets to live wallpapers.
 *    We intercept these to determine which home screen page is active and
 *    forward the offset to {@link SphereEngine}, which scales/fades the sphere
 *    based on proximity to the user-configured "active page." This saves
 *    significant GPU cycles when the sphere isn't visible.
 *
 * 3. **libGDX Initialization**
 *    Configures libGDX with hardware-optimized settings (no compass, no
 *    accelerometer, multisampling disabled for raw throughput) and instantiates
 *    the {@link SphereEngine} with the Android Context for PackageManager access.
 *
 * ─── Architecture Note ──────────────────────────────────────────────────────
 *
 * AndroidLiveWallpaperService creates its own Engine internally. We override
 * {@link #onCreateApplication()} (not onCreate) because libGDX initializes
 * its GL context there. The Engine inner class provides surface callbacks
 * which we use for the frame rate unlock.
 */
public class MyWallpaperService extends AndroidLiveWallpaperService {

    private static final String TAG = "AuraOrbit.Service";

    /**
     * The libGDX application listener that handles all 3D rendering.
     * Held as a field so we can forward offset changes to it.
     */
    private SphereEngine sphereEngine;

    /**
     * User-configured target frame rate (60/90/120). Read from SharedPreferences
     * at initialization and used for the setFrameRate() call.
     */
    private float targetFrameRate = 120f;

    // ═══════════════════════════════════════════════════════════════════════
    //  libGDX Lifecycle — Called when the wallpaper service is first created
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void onCreateApplication() {
        super.onCreateApplication();

        // ─── Read user preferences ──────────────────────────────────────
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String fpsStr = prefs.getString("pref_target_fps", "120");
        try {
            targetFrameRate = Float.parseFloat(fpsStr);
        } catch (NumberFormatException e) {
            targetFrameRate = 120f;
        }

        Log.i(TAG, "Initializing AuraOrbit with target FPS: " + targetFrameRate);

        // ─── Configure libGDX for maximum performance ───────────────────
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();

        // Disable sensors we don't use — saves CPU wake-locks and battery
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useGyroscope = false;

        // Enable depth buffer for proper 3D sorting of decals/meshes.
        // 16-bit depth is sufficient for our sphere radius range.
        config.depth = 16;

        // Stencil buffer not needed for our rendering pipeline
        config.stencil = 0;

        // MSAA: Disabled. We rely on texture filtering and high-res icons
        // for visual quality. MSAA at 120 FPS would be too expensive.
        config.numSamples = 0;

        // CRITICAL: Without this flag, the live wallpaper receives NO touch
        // events at all. This tells libGDX's AndroidWallpaperEngine to
        // forward MotionEvents from the WallpaperService.Engine to libGDX's
        // input system. Required for swipe-to-spin and tap-to-launch.
        config.getTouchEventsForLiveWallpaper = true;

        // Request RGBA8888 for accurate icon color reproduction
        config.r = 8;
        config.g = 8;
        config.b = 8;
        config.a = 8;

        // ─── Create the 3D engine ───────────────────────────────────────
        // Pass the Android Context so the engine can access PackageManager,
        // WallpaperManager, and SharedPreferences from within libGDX.
        sphereEngine = new SphereEngine(this);

        // ─── Initialize libGDX with our engine ──────────────────────────
        // This triggers GL context creation and calls sphereEngine.create()
        initialize(sphereEngine, config);

        Log.i(TAG, "libGDX engine initialized successfully");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Custom Engine — Overrides for 120Hz unlock and offset forwarding
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Override the Engine creation to return our custom Engine subclass
     * that handles 120Hz frame rate negotiation and page offset forwarding.
     */
    @Override
    public WallpaperService.Engine onCreateEngine() {
        return new AuraOrbitEngine();
    }

    /**
     * ─────────────────────────────────────────────────────────────────────
     * AuraOrbitEngine — Custom WallpaperService.Engine
     * ─────────────────────────────────────────────────────────────────────
     *
     * Extends the default Engine to:
     *
     * 1. Call Surface.setFrameRate(120, COMPATIBILITY_DEFAULT) when the
     *    surface is created. This tells the Android compositor to run
     *    this surface at the requested refresh rate rather than the
     *    default 60Hz cap that Live Wallpapers receive.
     *
     * 2. Forward onOffsetsChanged events to the SphereEngine so it can
     *    determine which home screen page is active and adjust rendering
     *    intensity accordingly.
     *
     * ─── Why setFrameRate and not setFrameRateCategory? ─────────────────
     *
     * setFrameRateCategory (Android 15+) is category-based and doesn't give
     * us precise control. setFrameRate (Android 11+) lets us request an
     * exact rate. On Samsung S25 Ultra with LTPO, the display can run at
     * 1-120Hz, and setFrameRate is the standard mechanism to request
     * the higher end of that range for a specific surface.
     */
    private class AuraOrbitEngine extends AndroidLiveWallpaperService.AndroidWallpaperEngine {

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);

            // ─── 120Hz Frame Rate Unlock ────────────────────────────────
            //
            // The critical call that breaks through the 60 FPS ceiling.
            //
            // Surface.setFrameRate() was added in API 30 (Android 11).
            // FRAME_RATE_COMPATIBILITY_DEFAULT tells the compositor to
            // switch to this rate if it can, but it won't force a mode
            // change that would affect other apps negatively.
            //
            // On Samsung's GameSDK-equipped devices, this works in
            // conjunction with the device's own refresh rate management
            // to ensure the display runs at the requested rate while
            // this surface is visible.
            //
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Surface surface = holder.getSurface();
                    if (surface != null && surface.isValid()) {
                        surface.setFrameRate(
                                targetFrameRate,
                                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                        );
                        Log.i(TAG, "Successfully requested " + targetFrameRate
                                + " FPS from compositor");
                    }
                } catch (Exception e) {
                    // Some OEM surfaces throw UnsupportedOperationException.
                    // Fall back gracefully to the system default rate.
                    Log.w(TAG, "setFrameRate failed, using system default", e);
                }
            } else {
                Log.w(TAG, "setFrameRate requires API 30+; running at system default");
            }
        }

        /**
         * ─────────────────────────────────────────────────────────────
         * Page Offset Handler
         * ─────────────────────────────────────────────────────────────
         *
         * Called by the Android launcher whenever the user swipes
         * between home screen pages.
         *
         * @param xOffset       Normalized 0.0–1.0 horizontal position.
         *                      0.0 = first page, 1.0 = last page.
         * @param yOffset       Vertical offset (usually 0.0 on most launchers).
         * @param xOffsetStep   The fraction of offset per page.
         *                      1/numPages when pages are equally spaced.
         * @param yOffsetStep   Vertical step (usually 0.0).
         * @param xPixelOffset  Raw pixel offset (launcher-dependent, less reliable).
         * @param yPixelOffset  Raw vertical pixel offset.
         *
         * We forward this to the SphereEngine which uses it to:
         * - Determine if the user is on the "active" home screen page
         * - Scale/fade the sphere when on non-active pages
         * - Apply a subtle parallax shift to the sphere based on swipe position
         */
        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                                     float xOffsetStep, float yOffsetStep,
                                     int xPixelOffset, int yPixelOffset) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep,
                    xPixelOffset, yPixelOffset);

            if (sphereEngine != null) {
                sphereEngine.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep);
            }
        }

        /**
         * Handle visibility changes to pause/resume expensive rendering.
         * When the wallpaper is not visible (e.g., app is in foreground),
         * libGDX automatically pauses, but we can do additional cleanup here.
         */
        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Log.d(TAG, "Visibility changed: " + visible);

            if (sphereEngine != null) {
                sphereEngine.setVisible(visible);
            }
        }
    }
}
