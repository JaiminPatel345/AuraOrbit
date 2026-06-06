package dev.jaimin.auraorbit;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * 2. **System Wallpaper Capture**: Uses WallpaperManager.peekDrawable()
 *    to non-destructively read the user's current static wallpaper,
 *    converts it to a full-screen libGDX Texture for background rendering.
 *
 * 3. **Group Configuration Parsing**: Reads group assignments from
 *    SharedPreferences and maps package names to group IDs/colors for
 *    the SphereEngine's visual clustering system.
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

    /**
     * SharedPreferences key prefix for group definitions.
     * Groups are stored as:
     *   - "groups_list" → Set<String> of group names
     *   - "group_<name>_color" → String hex color
     *   - "group_<name>_apps" → Set<String> of package names
     */
    public static final String PREF_GROUPS_LIST = "groups_list";
    public static final String PREF_GROUP_PREFIX = "group_";

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

        // ─── Read group assignments ─────────────────────────────────────
        Map<String, String> packageToGroup = new HashMap<>();
        Map<String, String> groupColors = new HashMap<>();
        loadGroupMappings(prefs, packageToGroup, groupColors);

        // ─── Build AppNode list ─────────────────────────────────────────
        List<AppNode> nodes = new ArrayList<>();

        for (String packageName : selectedPackages) {
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                String appName = pm.getApplicationLabel(appInfo).toString();

                AppNode node = new AppNode(packageName, appName);

                // ─── Extract and convert the app icon ───────────────────
                Drawable drawable = pm.getApplicationIcon(appInfo);
                Bitmap bitmap = drawableToBitmap(drawable, ICON_SIZE);

                if (bitmap != null) {
                    node.iconTexture = bitmapToTexture(bitmap);
                    node.iconRegion = new TextureRegion(node.iconTexture);

                    // Bitmap is now uploaded to GPU — recycle native memory
                    bitmap.recycle();
                }

                // ─── Assign group metadata ──────────────────────────────
                String groupId = packageToGroup.get(packageName);
                if (groupId != null) {
                    node.groupId = groupId;
                    node.groupColorHex = groupColors.get(groupId);
                }

                nodes.add(node);
                Log.d(TAG, "Loaded: " + appName + " (" + packageName + ")"
                        + (groupId != null ? " [Group: " + groupId + "]" : ""));

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
    //  Public API — System Wallpaper Background Texture
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Captures the current system wallpaper and converts it to a libGDX Texture.
     *
     * Uses WallpaperManager.peekDrawable() which is non-destructive — it reads
     * the wallpaper without claiming ownership or modifying it. Returns null if
     * the wallpaper cannot be read (e.g., live wallpaper, permission denied).
     *
     * The returned Texture is sized to the screen dimensions for a pixel-perfect
     * full-screen background render.
     *
     * MUST be called from the GL thread.
     *
     * @param context  Android context for WallpaperManager access
     * @return Texture of the system wallpaper, or null if unavailable
     */
    public static Texture fetchSystemWallpaper(Context context) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context);

            // peekDrawable() returns null if:
            //   - Current wallpaper is a Live Wallpaper (not a static image)
            //   - Permission not granted (shouldn't happen — we're a wallpaper service)
            Drawable wallpaperDrawable = wm.peekDrawable();

            if (wallpaperDrawable == null) {
                Log.w(TAG, "System wallpaper is null (live wallpaper or no permission)");
                return null;
            }

            // ─── Determine output size ──────────────────────────────────
            // Use the actual screen dimensions for pixel-perfect rendering.
            // On Galaxy S25 Ultra: 1440×3120 in portrait.
            int width = Gdx.graphics.getWidth();
            int height = Gdx.graphics.getHeight();

            // Clamp to power-of-two friendly sizes to avoid GPU waste
            // (most modern GPUs handle NPOT fine, but this is defensive)
            width = Math.min(width, 2048);
            height = Math.min(height, 2048);

            Log.i(TAG, "Capturing system wallpaper at " + width + "x" + height);

            // ─── Rasterize the wallpaper Drawable to a Bitmap ───────────
            Bitmap wallpaperBitmap = Bitmap.createBitmap(width, height,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(wallpaperBitmap);

            // Scale the drawable to fill the entire canvas
            wallpaperDrawable.setBounds(0, 0, width, height);
            wallpaperDrawable.draw(canvas);

            // ─── Convert to libGDX Texture ──────────────────────────────
            Texture texture = bitmapToTexture(wallpaperBitmap);
            wallpaperBitmap.recycle();

            Log.i(TAG, "System wallpaper captured successfully");
            return texture;

        } catch (Exception e) {
            Log.e(TAG, "Failed to capture system wallpaper", e);
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
    //  Private — Group Configuration Parsing
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Loads group→package and group→color mappings from SharedPreferences.
     *
     * Group data structure in SharedPreferences:
     *   - "groups_list"           → Set<String> {"Social", "Productivity", ...}
     *   - "group_Social_color"    → "#FF6B6B"
     *   - "group_Social_apps"     → Set<String> {"com.whatsapp", "com.instagram", ...}
     *
     * @param prefs           SharedPreferences to read from
     * @param packageToGroup  Output: maps package name → group name
     * @param groupColors     Output: maps group name → hex color string
     */
    private static void loadGroupMappings(SharedPreferences prefs,
                                          Map<String, String> packageToGroup,
                                          Map<String, String> groupColors) {

        Set<String> groupNames = prefs.getStringSet(PREF_GROUPS_LIST, new HashSet<>());

        for (String groupName : groupNames) {
            // Read this group's color
            String colorHex = prefs.getString(PREF_GROUP_PREFIX + groupName + "_color", "#FFFFFF");
            groupColors.put(groupName, colorHex);

            // Read this group's assigned apps
            Set<String> groupApps = prefs.getStringSet(
                    PREF_GROUP_PREFIX + groupName + "_apps", new HashSet<>());

            for (String packageName : groupApps) {
                packageToGroup.put(packageName, groupName);
            }

            Log.d(TAG, "Group '" + groupName + "' (" + colorHex + "): "
                    + groupApps.size() + " apps");
        }
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
                context.startActivity(launchIntent);
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
