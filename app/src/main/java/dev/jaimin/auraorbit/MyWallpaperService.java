package dev.jaimin.auraorbit;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
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
     * at initialization and updated live when the pref changes (spec §3.2).
     */
    private float targetFrameRate = 120f;

    /**
     * The most recently created AuraOrbitEngine instance.
     * Tracked here so the live FPS listener can reach its currentHolder.
     * libGDX keeps one app context, so one engine is active at a time.
     */
    private AuraOrbitEngine activeEngine;

    /**
     * STRONG reference to the FPS preference listener.
     * SharedPreferences uses a WeakHashMap internally, so a listener stored
     * only as a local variable would be silently GC'd.
     */
    private SharedPreferences.OnSharedPreferenceChangeListener fpsListener;

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

        // ─── Register live FPS listener (spec §3.2) ─────────────────────
        // When the user changes pref_target_fps in Settings, re-parse the
        // value and apply it to the active surface immediately — no need to
        // re-apply the wallpaper for the setting to take effect.
        fpsListener = (p, key) -> {
            if ("pref_target_fps".equals(key)) {
                String newFpsStr = p.getString("pref_target_fps", "120");
                try {
                    targetFrameRate = Float.parseFloat(newFpsStr);
                } catch (NumberFormatException e) {
                    targetFrameRate = 120f;
                }
                Log.i(TAG, "pref_target_fps changed → " + targetFrameRate + " FPS");
                if (activeEngine != null && activeEngine.currentHolder != null) {
                    activeEngine.applyFrameRate(activeEngine.currentHolder);
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(fpsListener);

        Log.i(TAG, "libGDX engine initialized successfully");
    }

    @Override
    public void onDestroy() {
        // Unregister the FPS listener to avoid leaking this service instance.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (fpsListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(fpsListener);
            fpsListener = null;
        }
        super.onDestroy();
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
     * 3. Gate app launching on the {@code android.wallpaper.tap} command
     *    via {@link #onCommand}. Launchers send this command only for taps
     *    on empty workspace — taps on the app drawer, icon grid, widgets,
     *    or search bar are never forwarded to the wallpaper as this command.
     *    This prevents sphere app launches from firing when drawer UI is open.
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

        /**
         * The active SurfaceHolder for this engine, set in onSurfaceCreated
         * and cleared in onSurfaceDestroyed. Exposed (package-private) so
         * the service's live FPS listener can re-apply the frame rate without
         * requiring the wallpaper to be re-applied (spec §3.2).
         */
        SurfaceHolder currentHolder;

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            currentHolder = holder;
            activeEngine = this; // track most-recent engine in the service

            // ─── Apply the user's frame rate preference to this surface ──
            applyFrameRate(holder);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            currentHolder = null;
            super.onSurfaceDestroyed(holder);
        }

        /**
         * ─────────────────────────────────────────────────────────────────
         * Wallpaper Command Handler — Tap-to-Launch Gate
         * ─────────────────────────────────────────────────────────────────
         *
         * Android launchers send the {@code android.wallpaper.tap} command
         * exclusively for taps on <em>empty workspace</em> — taps consumed by
         * the app drawer, icon grid, widgets, or the search bar never produce
         * this command.  By forwarding only this command to
         * {@link SphereEngine#onWallpaperTapCommand}, we ensure that app
         * launches cannot fire while the app drawer (or any other launcher UI)
         * is open and overlaying the wallpaper.
         *
         * @param action           The command action string (e.g.
         *                         {@code "android.wallpaper.tap"}).
         * @param x                Surface-relative X coordinate in pixels.
         * @param y                Surface-relative Y coordinate in pixels.
         * @param z                Unused (always 0 for tap commands).
         * @param extras           Optional command extras (may be null).
         * @param resultRequested  Whether the caller expects a result Bundle.
         * @return                 Result Bundle forwarded from super, or null.
         */
        @Override
        public Bundle onCommand(String action, int x, int y, int z,
                                Bundle extras, boolean resultRequested) {
            // Diagnostic: launcher command behavior varies by OEM (One UI vs Pixel).
            Log.d(TAG, "onCommand: " + action + " @(" + x + "," + y + ")");
            if ("android.wallpaper.tap".equals(action) && sphereEngine != null) {
                sphereEngine.onWallpaperTapCommand(x, y);
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        /**
         * Applies the current {@link #targetFrameRate} to the given surface.
         *
         * Extracted from onSurfaceCreated so the live FPS preference listener
         * in the service can call it whenever the user changes pref_target_fps,
         * making the FPS setting take effect immediately (spec §3.2).
         *
         * Guards:
         * - API 30+ (Surface.setFrameRate added in Android 11)
         * - Surface validity check before the call
         * - try/catch for OEM surfaces that throw UnsupportedOperationException
         *
         * @param holder The SurfaceHolder whose Surface should be reconfigured.
         */
        private void applyFrameRate(SurfaceHolder holder) {
            // ─── Frame Rate Unlock ──────────────────────────────────────
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

            // Diagnostic: OEM launchers differ wildly here — Pixel reports real
            // page offsets, Samsung One UI historically reports none/fixed values.
            Log.d(TAG, "onOffsetsChanged: x=" + xOffset + " step=" + xOffsetStep);

            if (sphereEngine != null) {
                sphereEngine.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep);
            }
        }

        /**
         * ─────────────────────────────────────────────────────────────
         * Wallpaper Zoom Handler — One UI drawer/recents signal
         * ─────────────────────────────────────────────────────────────
         *
         * Called by the Android system (API 30+) when the launcher
         * zooms the wallpaper surface in or out.  On Samsung One UI,
         * zoom increases toward 1.0 when the user opens the app drawer,
         * recents, or widget edit mode — and returns to 0.0 when back
         * on the plain home screen.
         *
         * Because One UI never sends {@code android.wallpaper.tap}
         * commands, this zoom callback is our only reliable signal that
         * the user has left the home screen view.  We forward the value
         * to {@link SphereEngine#onWallpaperZoom} so the direct-tap
         * fallback can suppress app launches while the drawer is open.
         *
         * The override is harmless below API 30 — the system will never
         * call it on older devices.  {@code minSdk = 30} means this
         * override is always reachable.
         *
         * @param zoom 0 = home screen (fully zoomed in),
         *             1 = fully zoomed out (drawer / recents / edit mode)
         */
        @Override
        public void onZoomChanged(float zoom) {
            // Diagnostic: log zoom values so owner can confirm One UI behavior on S25.
            Log.d(TAG, "onZoomChanged: " + zoom);
            if (sphereEngine != null) {
                sphereEngine.onWallpaperZoom(zoom);
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
