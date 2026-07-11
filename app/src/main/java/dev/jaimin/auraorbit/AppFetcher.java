package dev.jaimin.auraorbit;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.app.WallpaperManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * AppFetcher.java — Android ↔ libGDX Texture Pipeline
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Utility class that bridges Android's PackageManager system with libGDX's
 * texture loading pipeline. Handles three critical data paths:
 *
 * 1. **App Icon Extraction**: Reads user-selected package names from
 *    SharedPreferences, queries PackageManager for each app's high-res
 *    Drawable, rasterizes it to a Bitmap, and converts it to a libGDX
 *    Texture suitable for 3D DecalBatch rendering.
 *
 * 2. **Background Image Loading**: Reads the user-selected background JPEG
 *    (saved by BackgroundStore) and converts it to a libGDX Texture. This
 *    replaces the old WallpaperManager.peekDrawable() approach which threw
 *    SecurityException on Android 13+ when AuraOrbit itself was the active
 *    wallpaper.
 *
 * 3. **Group Configuration Parsing**: Delegates to GroupStore to map package
 *    names to group IDs/colors for the SphereEngine's visual clustering system.
 *
 * ─── Thread Safety ──────────────────────────────────────────────────────────
 *
 * All methods that create libGDX Textures MUST be called from the GL thread
 * (inside create() or render()). Android PackageManager queries are thread-safe.
 * The recommended pattern is:
 *   1. Call fetchAppInfo() from any thread to get Bitmaps
 *   2. Call convertToTexture() from the GL thread to upload to GPU
 *
 * ─── Memory Management ─────────────────────────────────────────────────────
 *
 * Bitmaps are recycled immediately after GPU upload. Textures must be disposed
 * by the caller (typically SphereEngine.dispose()).
 */
public class AppFetcher {

    private static final String TAG = "AuraOrbit.Fetcher";

    /**
     * Standard icon size in pixels. 192×192 provides crisp rendering on
     * xxxhdpi displays (Galaxy S25 Ultra is 510 DPI) while keeping VRAM
     * usage reasonable. Each RGBA8888 icon = 192² × 4 = ~144 KB on GPU.
     * For 30 icons, total icon VRAM ≈ 4.3 MB — well within budget.
     */
    private static final int ICON_SIZE = 192;

    /**
     * SharedPreferences key for the set of selected package names.
     * Stored as a StringSet.
     */
    public static final String PREF_SELECTED_APPS = "selected_app_packages";

    /** Cache of loaded app icon Bitmaps to make activity launches instantaneous. */
    private static final java.util.Map<String, Bitmap> sIconCache = new java.util.concurrent.ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════════
    //  Data Class — Holds app metadata + texture for a single sphere node
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Represents a single app that will be placed as a node on the 3D sphere.
     * Contains both Android metadata (for launching) and libGDX resources
     * (for rendering).
     */
    public static class AppNode {
        /** Android package name (e.g., "com.whatsapp") — used for startActivity */
        public final String packageName;

        /** Human-readable app name — used for accessibility/debugging */
        public final String appName;

        /** The app icon as a libGDX TextureRegion for DecalBatch rendering */
        public TextureRegion iconRegion;

        /** The underlying Texture that must be disposed */
        public Texture iconTexture;

        /**
         * Group ID this app belongs to, or null if ungrouped.
         * Used by SphereEngine to cluster apps and render colored backdrops.
         */
        public String groupId;

        /**
         * Group color as a hex string (e.g., "#FF6B6B"), or null if ungrouped.
         * Parsed by SphereEngine to color the translucent backdrop mesh.
         */
        public String groupColorHex;

        public AppNode(String packageName, String appName) {
            this.packageName = packageName;
            this.appName = appName;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Public API — Fetch all selected apps and convert to renderable nodes
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fetches all user-selected apps and creates AppNode objects with GPU textures.
     *
     * MUST be called from the GL thread (e.g., inside SphereEngine.create()).
     *
     * ─── Group Assignment ────────────────────────────────────────────────────
     *
     * Group data is read via {@link GroupStore#load(SharedPreferences)} and then
     * inverted into a package→Group map by {@link GroupStore#packageToGroup(List)}.
     * This replaces the old per-key schema (groups_list / group_*_color / group_*_apps)
     * which required N+1 pref reads and two Map allocations; the new approach uses
     * a single JSON read and a single pass over the group list.
     *
     * @param context  Android context for PackageManager access
     * @return List of AppNode objects ready for sphere placement, sorted by group
     */
    public static List<AppNode> fetchSelectedApps(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        PackageManager pm = context.getPackageManager();

        // ─── Read selected package names ────────────────────────────────
        Set<String> selectedPackages = prefs.getStringSet(PREF_SELECTED_APPS, new HashSet<>());

        if (selectedPackages.isEmpty()) {
            Log.w(TAG, "No apps selected — returning empty list");
            return new ArrayList<>();
        }

        Log.i(TAG, "Fetching " + selectedPackages.size() + " selected apps");

        // ─── Read group assignments via GroupStore ───────────────────────
        // GroupStore.load() handles both the new JSON schema and legacy key
        // migration transparently. packageToGroup() inverts the list into a
        // fast O(1) lookup map keyed by package name.
        Map<String, GroupStore.Group> packageToGroup =
                GroupStore.packageToGroup(GroupStore.load(prefs));

        // ─── Build AppNode list ─────────────────────────────────────────
        List<AppNode> nodes = new ArrayList<>();

        for (String packageName : selectedPackages) {
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                String appName = pm.getApplicationLabel(appInfo).toString();

                AppNode node = new AppNode(packageName, appName);

                // ─── Extract and convert the app icon ───────────────────
                Bitmap bitmap = sIconCache.get(packageName);
                if (bitmap == null) {
                    IconPackManager iconPackManager = IconPackManager.getInstance(context);
                    Drawable drawable = iconPackManager.getIcon("ComponentInfo{" + packageName + "/" + pm.getLaunchIntentForPackage(packageName).getComponent().getClassName() + "}");
                    
                    if (drawable == null) {
                        drawable = pm.getApplicationIcon(appInfo);
                    }
                    bitmap = drawableToBitmap(drawable, ICON_SIZE);
                    if (bitmap != null) {
                        sIconCache.put(packageName, bitmap);
                    }
                }

                if (bitmap != null) {
                    node.iconTexture = bitmapToTexture(bitmap);
                    node.iconRegion = new TextureRegion(node.iconTexture);
                    // Do NOT recycle the bitmap here anymore! It is cached.
                }

                // ─── Assign group metadata via GroupStore ────────────────
                GroupStore.Group g = packageToGroup.get(packageName);
                if (g != null) {
                    node.groupId = g.name;
                    node.groupColorHex = g.color;
                }

                nodes.add(node);
                Log.d(TAG, "Loaded: " + appName + " (" + packageName + ")"
                        + (node.groupId != null ? " [Group: " + node.groupId + "]" : ""));

            } catch (PackageManager.NameNotFoundException e) {
                // App was uninstalled since selection — skip silently
                Log.w(TAG, "Package not found (uninstalled?): " + packageName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load app: " + packageName, e);
            }
        }

        // ─── Sort by group for clustering on the sphere ─────────────────
        // Ungrouped apps go to the end. Within a group, sort alphabetically.
        Collections.sort(nodes, (a, b) -> {
            // Both ungrouped — sort by name
            if (a.groupId == null && b.groupId == null) {
                return a.appName.compareToIgnoreCase(b.appName);
            }
            // One ungrouped — push to end
            if (a.groupId == null) return 1;
            if (b.groupId == null) return -1;
            // Same group — sort by name
            if (a.groupId.equals(b.groupId)) {
                return a.appName.compareToIgnoreCase(b.appName);
            }
            // Different groups — sort by group name
            return a.groupId.compareToIgnoreCase(b.groupId);
        });

        Log.i(TAG, "Successfully loaded " + nodes.size() + " app nodes");
        return nodes;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Public API — Enumerate all launchable apps for the settings screen
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns all installed apps that have a launcher intent (i.e., apps
     * the user can actually launch). Used by LiveWallpaperSettings for
     * the app selection dialog.
     *
     * Thread-safe — can be called from any thread.
     *
     * @param context  Android context for PackageManager access
     * @return List of ResolveInfo for all launchable apps, sorted by label
     */
    public static List<ResolveInfo> getAllLaunchableApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);

        // Sort alphabetically by app label for the selection UI
        Collections.sort(apps, (a, b) -> {
            String labelA = a.loadLabel(pm).toString();
            String labelB = b.loadLabel(pm).toString();
            return labelA.compareToIgnoreCase(labelB);
        });

        return apps;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Public API — User-Selected Background Image Texture
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Loads the user-selected background image (saved by BackgroundStore)
     * as a libGDX Texture. MUST be called on the GL thread.
     *
     * ─── Why not WallpaperManager.peekDrawable()? ────────────────────────
     *
     * The old fetchSystemWallpaper() used WallpaperManager.peekDrawable(),
     * which throws a SecurityException on Android 13+ when the calling app
     * IS itself the active wallpaper (READ_EXTERNAL_STORAGE is no longer
     * sufficient). BackgroundStore stores the user's chosen image in the
     * app's private filesDir, which requires no additional permissions.
     *
     * @param context  Android context for BackgroundStore file access
     * @return Texture or null when no image is set / decode fails.
     */
    public static Texture loadBackgroundTexture(Context context) {
        java.io.File f = BackgroundStore.file(context);
        if (!f.exists()) return null;
        try {
            Texture t = new Texture(Gdx.files.absolute(f.getAbsolutePath()));
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            return t;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load background texture", e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Public API — System Wallpaper Mirror (API 24+)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns true if the app has the permission required to read the system
     * wallpaper via {@link WallpaperManager#getDrawable()}.
     *
     * On API >= 30 this requires MANAGE_EXTERNAL_STORAGE ("All files access").
     * Below API 30, the attempt is always made (with try/catch as protection).
     *
     * @param context  Android context
     * @return true if the permission is granted (or API < 30 where it is not needed)
     */
    public static boolean canReadSystemWallpaper(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30
            return Environment.isExternalStorageManager();
        }
        return true; // Below API 30 we attempt anyway; SecurityException is caught
    }

    /**
     * Returns the WallpaperManager ID of the current system (static) wallpaper.
     *
     * Used by SphereEngine's configSnapshot() to detect when the user changes
     * their system wallpaper so the background can be reloaded on the next resume().
     *
     * Requires no special permission (getWallpaperId is available from API 24).
     * Returns -1 on any failure.
     *
     * @param context  Android context
     * @return Wallpaper ID, or -1 if unavailable
     */
    public static int systemWallpaperId(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { // API 24
            return -1;
        }
        try {
            return WallpaperManager.getInstance(context)
                    .getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Loads the current system (static) wallpaper as a libGDX Texture by
     * mirroring it: when AuraOrbit IS the active live wallpaper,
     * {@link WallpaperManager#getDrawable()} returns the last static wallpaper
     * that was set — exactly what we want as the background layer.
     *
     * MUST be called on the GL thread (same contract as loadBackgroundTexture).
     *
     * ─── Permission ─────────────────────────────────────────────────────────
     *
     * On API >= 30, MANAGE_EXTERNAL_STORAGE is required; callers should first
     * invoke {@link #canReadSystemWallpaper(Context)} and skip this method if
     * false. Below API 30 we attempt anyway; SecurityException is caught.
     * A one-time INFO log is emitted when permission is absent.
     *
     * ─── Bitmap pipeline ────────────────────────────────────────────────────
     *
     * The system wallpaper Drawable is rasterized to a Bitmap, downscaled so
     * the max dimension is ≤ 2048 (same policy as BackgroundStore), compressed
     * to a cache JPEG, then loaded as a libGDX Texture — reusing the same
     * file-path loading pattern as loadBackgroundTexture for consistency.
     *
     * @param context  Android context
     * @return Texture with linear filtering, or null if unavailable/failed
     */
    public static Texture loadSystemWallpaperTexture(Context context) {
        // ─── Permission gate ────────────────────────────────────────────────
        if (!canReadSystemWallpaper(context)) {
            Log.i(TAG, "loadSystemWallpaperTexture: MANAGE_EXTERNAL_STORAGE not granted; skipping");
            return null;
        }

        try {
            // ─── Obtain the system wallpaper Drawable ───────────────────────
            WallpaperManager wm = WallpaperManager.getInstance(context);
            Drawable d = wm.getDrawable();
            if (d == null) {
                Log.i(TAG, "loadSystemWallpaperTexture: WallpaperManager returned null Drawable");
                return null;
            }

            // ─── Drawable → Bitmap ─────────────────────────────────────────
            // Ownership flag: true only for bitmaps WE create (must recycle);
            // false for the BitmapDrawable-owned bitmap. Tracked explicitly
            // because after a downscale the current bitmap is OURS even when
            // the source Drawable was a BitmapDrawable — keying recycling off
            // `d instanceof BitmapDrawable` leaked the scaled copy (QA finding).
            Bitmap bitmap;
            boolean weOwnBitmap;
            if (d instanceof BitmapDrawable) {
                // Fast path: extract the underlying Bitmap directly (no rasterization).
                Bitmap src = ((BitmapDrawable) d).getBitmap();
                if (src == null) return null;
                bitmap = src; // Do NOT recycle — owned by the Drawable
                weOwnBitmap = false;
            } else {
                // General path: rasterize the Drawable onto a canvas.
                int w = d.getIntrinsicWidth();
                int h = d.getIntrinsicHeight();
                // Guard against Drawables with no intrinsic size (use screen size).
                if (w <= 0) w = context.getResources().getDisplayMetrics().widthPixels;
                if (h <= 0) h = context.getResources().getDisplayMetrics().heightPixels;
                // Clamp to reasonable bounds to avoid OOM.
                if (w > 4096) w = 4096;
                if (h > 4096) h = 4096;

                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                d.setBounds(0, 0, w, h);
                d.draw(canvas);
                weOwnBitmap = true;
            }

            // ─── Downscale so max dimension ≤ 2048 (same policy as BackgroundStore) ──
            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            final int MAX_DIM = 2048;
            if (Math.max(bw, bh) > MAX_DIM) {
                float scale = MAX_DIM / (float) Math.max(bw, bh);
                int nw = Math.max(1, Math.round(bw * scale));
                int nh = Math.max(1, Math.round(bh * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true);
                // Recycle the intermediate only if WE created it; the scaled
                // copy is always ours from here on. (createScaledBitmap can
                // theoretically return the source — guard with !=.)
                if (weOwnBitmap && scaled != bitmap) {
                    bitmap.recycle();
                }
                bitmap = scaled;
                weOwnBitmap = true;
            }

            // ─── Write bitmap to cache file as JPEG ────────────────────────
            // Using a cache file + Gdx.files.absolute() follows the exact same
            // loading path as loadBackgroundTexture so format/filtering match.
            File cacheFile = new File(context.getCacheDir(), "system_wallpaper.jpg");
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            } finally {
                // Only recycle bitmaps we own (never the BitmapDrawable-owned one).
                if (weOwnBitmap && !bitmap.isRecycled()) bitmap.recycle();
            }

            // ─── Load as libGDX Texture ─────────────────────────────────────
            Texture t = new Texture(Gdx.files.absolute(cacheFile.getAbsolutePath()));
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            return t;

        } catch (SecurityException se) {
            Log.i(TAG, "loadSystemWallpaperTexture: SecurityException — permission not granted");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "loadSystemWallpaperTexture: failed", e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Private — Drawable → Bitmap conversion
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Converts any Android Drawable to a square ARGB_8888 Bitmap of the
     * specified size.
     *
     * Handles all Drawable types including AdaptiveIconDrawable (Android 8+),
     * VectorDrawable, and BitmapDrawable. The Drawable is centered and scaled
     * to fit within the target dimensions with aspect ratio preserved.
     *
     * @param drawable  The source Drawable (app icon, wallpaper, etc.)
     * @param size      Target width and height in pixels
     * @return ARGB_8888 Bitmap, or null if conversion fails
     */
    private static Bitmap drawableToBitmap(Drawable drawable, int size) {
        if (drawable == null) return null;

        try {
            // Create a transparent canvas
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            // Center the drawable within the bitmap with padding for roundrect icons
            int padding = size / 10; // 10% padding for visual breathing room
            drawable.setBounds(padding, padding, size - padding, size - padding);
            drawable.draw(canvas);

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "drawableToBitmap failed", e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Private — Bitmap → libGDX Texture conversion
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Converts an Android Bitmap to a libGDX Texture.
     *
     * ─── Why not use Gdx.graphics.newTexture(Bitmap)? ───────────────────
     *
     * libGDX doesn't have a direct Bitmap→Texture API. We must:
     * 1. Extract raw RGBA pixels from the Bitmap into a ByteBuffer
     * 2. Wrap the buffer in a libGDX Pixmap
     * 3. Create a Texture from the Pixmap
     *
     * ─── ARGB → RGBA Color Channel Reordering ──────────────────────────
     *
     * Android Bitmaps use ARGB_8888 byte order: [A][R][G][B]
     * libGDX Pixmaps use RGBA8888 byte order:   [R][G][B][A]
     * We must swizzle each pixel's channels during the copy.
     *
     * @param bitmap  Source Android Bitmap (ARGB_8888)
     * @return libGDX Texture with linear filtering enabled
     */
    private static Texture bitmapToTexture(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // ─── Extract pixels and reorder ARGB → RGBA ────────────────────
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);

        for (int pixel : pixels) {
            // Android ARGB_8888 layout: 0xAARRGGBB
            // Extract each channel and repack as RGBA
            buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
            buffer.put((byte) ((pixel >> 8) & 0xFF));  // G
            buffer.put((byte) (pixel & 0xFF));          // B
            buffer.put((byte) ((pixel >> 24) & 0xFF)); // A
        }

        buffer.flip(); // Reset position to 0 for Pixmap read

        // ─── Create Pixmap from raw RGBA buffer ─────────────────────────
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);

        // Copy our reordered buffer into the Pixmap's native buffer
        ByteBuffer pixmapBuffer = pixmap.getPixels();
        pixmapBuffer.clear();
        pixmapBuffer.put(buffer);
        pixmapBuffer.flip();

        // ─── Create GPU Texture ─────────────────────────────────────────
        Texture texture = new Texture(pixmap);

        // Use linear filtering for smooth icon appearance when the sphere
        // rotates and icons are viewed at various angles/distances
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);

        // Clamp to edge to prevent texture bleeding at icon borders
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);

        // Pixmap native memory is no longer needed — texture now owns the GPU data
        pixmap.dispose();

        return texture;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Public Utility — Launch an app by package name
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Launches the app identified by the given package name.
     *
     * Uses FLAG_ACTIVITY_NEW_TASK because we're launching from a Service
     * context (WallpaperService), not an Activity. Without this flag,
     * Android would throw an exception.
     *
     * ─── One UI-style enter transition ─────────────────────────────────────
     *
     * Wraps startActivity with ActivityOptions.makeCustomAnimation so the
     * launched app enters with a scale-up + fade-in (sphere_launch_enter.xml,
     * 250ms decelerate) and the wallpaper exits with a subtle fade
     * (sphere_launch_exit.xml, 200ms). This matches the polished open-feel of
     * Samsung One UI's native launcher icon-tap animation.
     *
     * The ActivityOptions path is wrapped in its own try/catch so that OEMs
     * that throw from non-Activity contexts (rare but observed) degrade
     * silently to a plain startActivity — the launch always succeeds.
     *
     * @param context      Android context (the WallpaperService)
     * @param packageName  Package to launch (e.g., "com.whatsapp")
     * @return true if the app was launched successfully
     */
    public static boolean launchApp(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);

            if (launchIntent != null) {
                // FLAG_ACTIVITY_NEW_TASK: Required when starting Activity from non-Activity
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // ─── One UI-style enter transition via ActivityOptions ──────────
                // Try the animated path first; degrade to plain startActivity on
                // any exception (some OEMs restrict ActivityOptions from Service).
                boolean launched = false;
                try {
                    ActivityOptions options = ActivityOptions.makeCustomAnimation(
                            context,
                            R.anim.sphere_launch_enter,
                            R.anim.sphere_launch_exit);
                    Bundle bundle = options.toBundle();
                    context.startActivity(launchIntent, bundle);
                    launched = true;
                    Log.d(TAG, "Launched app with animation: " + packageName);
                } catch (Exception animEx) {
                    Log.d(TAG, "ActivityOptions animation failed, falling back to plain launch: " + animEx.getMessage());
                }

                if (!launched) {
                    context.startActivity(launchIntent);
                }

                Log.i(TAG, "Launched app: " + packageName);
                return true;
            } else {
                Log.w(TAG, "No launch intent for: " + packageName);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch app: " + packageName, e);
            return false;
        }
    }
}
