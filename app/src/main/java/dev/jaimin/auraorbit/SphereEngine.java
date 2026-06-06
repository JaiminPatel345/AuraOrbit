package dev.jaimin.auraorbit;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidWallpaperListener;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;

import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.graphics.g3d.decals.CameraGroupStrategy;
import com.badlogic.gdx.graphics.g3d.decals.Decal;
import com.badlogic.gdx.graphics.g3d.decals.DecalBatch;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * SphereEngine.java — The libGDX 3D Rendering Core
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This is the heart of AuraOrbit — a libGDX ApplicationListener that renders
 * a true 3D sphere populated by app icon decals. It implements:
 *
 * ─── Rendering Pipeline ────────────────────────────────────────────────────
 *
 * 1. **Background Layer** (SpriteBatch, 2D)
 *    Always rendered. Draws the user-selected background photo (via
 *    BackgroundStore/AppFetcher.loadBackgroundTexture) when one is set;
 *    falls back to a procedural vertical gradient texture otherwise.
 *
 * 2. **Group Backdrop Layer** (ModelBatch, 3D)
 *    Renders translucent colored spherical cap meshes behind each app
 *    group cluster. These caps are oriented so their +Y axis points toward
 *    the group's slot direction on the sphere, and they rotate rigidly with
 *    the sphere. IntAttribute.CullFace=GL_NONE makes caps visible from both
 *    the front and back of the sphere, so users can see where a group is
 *    even when it is on the far side.
 *
 * 3. **App Icon Layer** (DecalBatch, 3D billboarded)
 *    Renders app icons as 2D textured decals positioned in 3D space on
 *    the sphere surface. Each decal uses lookAt() billboarding to always
 *    face the camera. Icons on the far side of the sphere are scaled down
 *    and dimmed (depth-based scale/alpha) for a natural parallax effect.
 *
 * 4. **Empty-state hint** (SpriteBatch, 2D)
 *    When no apps are selected, a centered text hint is rendered on top of
 *    the background instructing the user to open AuraOrbit settings.
 *
 * ─── Physics ────────────────────────────────────────────────────────────────
 *
 * - Rotation uses Quaternions exclusively (no Euler angles → no Gimbal Lock)
 * - Angular velocity with exponential friction for smooth spin deceleration
 * - All math is delta-time dependent for frame-rate independence
 * - Drag and physics rotations use pre-multiplication (mulLeft) so they always
 *   operate in world space regardless of accumulated sphere orientation.
 *
 * ─── Live Settings ──────────────────────────────────────────────────────────
 *
 * A SharedPreferences listener (strongly referenced in a field, because
 * SharedPreferences uses a WeakHashMap and would silently GC a lambda)
 * posts applyConfig() to the GL thread whenever a relevant key changes.
 * applyConfig() uses a snapshot string for deduplication — the same config
 * is never applied twice (prevents double-fire on resume + pref change).
 *
 * ─── Input ──────────────────────────────────────────────────────────────────
 *
 * - GestureDetector handles pan (rotation), fling (momentum), and tap (launch)
 * - 3D ray picking via Camera.getPickRay() + Intersector for app selection
 */
public class SphereEngine implements ApplicationListener, AndroidWallpaperListener {

    private static final String TAG = "AuraOrbit.Engine";

    // ─── Android Context (passed from MyWallpaperService) ───────────────
    private final Context context;

    // ─── Camera & Rendering ─────────────────────────────────────────────
    private PerspectiveCamera camera;
    private SpriteBatch spriteBatch;       // For 2D background and empty-state hint
    private DecalBatch decalBatch;         // For 3D billboarded app icons
    private ModelBatch modelBatch;         // For 3D group backdrop meshes

    // ─── Background Textures ────────────────────────────────────────────
    /**
     * User-selected background photo loaded from BackgroundStore, or null
     * when no photo has been selected or showBackground is false.
     * Falls back to gradientTexture when null.
     */
    private Texture backgroundTexture;

    /**
     * Procedural 1×256 vertical gradient (dark navy top → slightly lighter navy bottom).
     * Always created in create() so there is always something to draw behind the sphere.
     * Disposed in dispose() and recreated in applyConfig() rebuilds.
     */
    private Texture gradientTexture;

    // ─── User-configurable settings (read by readConfig) ─────────────────
    /**
     * Whether to attempt loading a user photo background (pref_show_background).
     * When false, backgroundTexture stays null and only gradientTexture is drawn.
     */
    private boolean showBackground;

    // ─── Sphere State ───────────────────────────────────────────────────
    private float sphereRadius;            // Slider-defined maximum sphere radius (world units)
    private float iconSize;                // Configurable icon dimensions
    private float rotationSpeedFactor;     // Multiplier for auto-spin and fling

    /**
     * Adaptive layout radius — computed after apps are loaded in buildScene.
     * Grows with app count (0.48 × iconSize × √N) so sparse sets still look
     * like a sphere, never exceeds the user's sphereRadius slider value, and
     * never falls below 1.6 × iconSize. When groups exist, an additional
     * group-spread formula may push the floor higher. Used for ALL node
     * placement and depth-normalisation math.
     *
     * computeCameraDistance() continues to use sphereRadius (the slider) so
     * the camera is a fixed reference: a small effective sphere appears
     * proportionally small/dense; a full one fills screen width exactly.
     */
    private float effectiveRadius;

    private List<AppFetcher.AppNode> appNodes;  // The loaded app data
    private Array<Decal> decals;           // libGDX decals for each app
    /**
     * Parallel to {@link #decals}: maps decal index → nodePositions index.
     * Icons can fail to rasterize (iconRegion == null), so createDecals() skips
     * those nodes via {@code continue}. Without this map every later decal would
     * be paired with the wrong node position in renderDecals().
     */
    private IntArray decalNodeIndex;       // decal i → nodePositions[decalNodeIndex.get(i)]
    private Vector3[] nodePositions;       // Cluster-layout positions on effectiveRadius sphere

    /**
     * The master rotation quaternion for the entire sphere.
     * All node positions are transformed by this quaternion each frame.
     * Using quaternions prevents gimbal lock that would occur with
     * sequential Euler angle rotations (rotateX then rotateY).
     */
    private Quaternion sphereRotation;

    /**
     * Angular velocity vector. Each component represents rotation speed
     * (radians/sec) around that world axis. Applied to sphereRotation
     * each frame via quaternion pre-multiplication (mulLeft), which keeps
     * the rotation in world space regardless of accumulated orientation.
     */
    private Vector3 angularVelocity;

    /**
     * Friction coefficient — multiplied against angularVelocity each frame.
     * 0.97 gives a smooth ~1 second glide stop at 120 FPS.
     * (0.97^120 ≈ 0.026, so velocity drops to 2.6% after 1 second)
     */
    private static final float FRICTION = 0.97f;

    /**
     * Below this velocity magnitude, snap to zero to prevent eternal
     * micro-spinning that wastes GPU cycles.
     */
    private static final float VELOCITY_EPSILON = 0.001f;

    /**
     * Sensitivity multiplier for drag-to-rotate. Converts screen pixels
     * of drag distance into radians of sphere rotation.
     */
    private static final float ROTATION_SENSITIVITY = 0.005f;

    /**
     * Base sensitivity multiplier for fling-to-spin. Scaled by
     * rotationSpeedFactor at fling time. Converts fling velocity
     * (pixels/sec) into angular velocity (radians/sec).
     */
    private static final float FLING_SENSITIVITY = 0.002f;

    /**
     * Default auto-rotation speed when user isn't interacting.
     * A gentle Y-axis spin to keep the wallpaper alive. Scaled by
     * rotationSpeedFactor when applied.
     */
    private static final float IDLE_SPIN_SPEED = 0.15f;

    // ─── Group Backdrop Meshes ──────────────────────────────────────────
    private Array<ModelInstance> groupBackdrops;
    private Array<Model> groupModels;  // Must be disposed

    /**
     * Base orientation matrix for each group cap mesh (rotation that takes +Y
     * to the slot's unit-direction vector). Applied each frame combined with
     * the live sphereRotation so the cap stays glued under its icons.
     * Replaces the old groupCentroids (centroid-translation) approach.
     */
    private Array<Matrix4> groupCapOrientations;

    // ─── Empty-state hint rendering ──────────────────────────────────────
    /**
     * BitmapFont used to render the empty-state hint message. Scaled to
     * device density in create(). Must be disposed.
     */
    private BitmapFont hintFont;

    /**
     * Pre-measured layout for the hint string. Built with Align.center so
     * hintFont.draw() centers the text around the x coordinate passed to it.
     * Rebuilt after any scale change.
     */
    private GlyphLayout hintLayout;

    // ─── Page Isolation State ───────────────────────────────────────────
    private float currentXOffset = 0f;     // 0.0–1.0 from onOffsetsChanged
    private float xOffsetStep = 0f;        // Fraction per page
    private int activePage = 0;            // User-configured target page
    private float pageVisibility = 1f;     // 0.0 (hidden) → 1.0 (full render)

    // ─── Visibility ─────────────────────────────────────────────────────
    private boolean isVisible = true;

    // ─── Reusable math objects (avoid GC pressure) ──────────────────────
    private final Vector3 tmpVec = new Vector3();
    private final Vector3 tmpVec2 = new Vector3();
    private final Quaternion tmpQuat = new Quaternion();
    private final Matrix4 tmpMat = new Matrix4();

    // ─── Interaction tracking ───────────────────────────────────────────
    private boolean userInteracting = false;
    private float idleTimer = 0f;
    private static final float IDLE_DELAY = 3f; // Seconds before auto-spin resumes

    // ─── Live settings listener ──────────────────────────────────────────

    /**
     * Keys that SphereEngine cares about. When any of these change, applyConfig()
     * is posted to the GL thread to rebuild the scene.
     */
    private static final Set<String> RELEVANT_KEYS = Set.of(
            "selected_app_packages",
            GroupStore.PREF_GROUPS_JSON,
            "pref_show_background",
            BackgroundStore.PREF_BACKGROUND_VERSION,
            "pref_sphere_radius",
            "pref_icon_size",
            "pref_rotation_speed",
            "pref_active_page"
    );

    /**
     * STRONG reference to the preference change listener.
     *
     * SharedPreferences internally stores listeners in a WeakHashMap, so a
     * listener registered as an inline lambda or anonymous class with no other
     * strong reference will be silently garbage-collected, causing the engine
     * to stop reacting to settings changes without any warning or exception.
     * Holding the reference here prevents that.
     */
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    /**
     * Snapshot string of the last applied configuration, used to deduplicate
     * applyConfig() calls. SharedPreferences listeners fire on every write even
     * when the value is unchanged, and resume() also triggers a rebuild — this
     * snapshot comparison makes both idempotent at negligible cost.
     */
    private String lastConfigSnapshot = "";

    // ═══════════════════════════════════════════════════════════════════════
    //  Constructor
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * @param context The Android context (WallpaperService). Used for
     *                PackageManager, BackgroundStore, SharedPreferences.
     *                Note: AndroidLiveWallpaperService extends Service
     *                extends Context, so the service itself IS a Context.
     */
    public SphereEngine(Context context) {
        this.context = context;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ApplicationListener — create()
    //  Called once when the GL context is ready
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void create() {
        Log.i(TAG, "Creating SphereEngine...");

        // ─── Setup 3D camera ────────────────────────────────────────────
        // PerspectiveCamera with 67° FOV — standard for immersive 3D.
        // Position is set later in buildScene() based on sphereRadius.
        camera = new PerspectiveCamera(67f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.lookAt(0f, 0f, 0f);
        camera.near = 0.1f;
        camera.far = 100f;

        // ─── Initialize rendering systems ───────────────────────────────
        spriteBatch = new SpriteBatch();
        decalBatch = new DecalBatch(new CameraGroupStrategy(camera));
        modelBatch = new ModelBatch();

        // ─── Initialize physics state ───────────────────────────────────
        sphereRotation = new Quaternion().idt(); // Identity = no rotation
        angularVelocity = new Vector3(0f, IDLE_SPIN_SPEED, 0f); // Gentle initial spin

        // ─── Build the gradient fallback background ──────────────────────
        // Always built so there is always something behind the sphere even
        // when no photo is selected. A 1×256 pixel tall texture is sufficient;
        // SpriteBatch stretches it full-screen.
        gradientTexture = buildGradientTexture();

        // ─── Initialize hint font for empty-state rendering ──────────────
        hintFont = new BitmapFont();
        hintFont.getData().setScale(Gdx.graphics.getDensity() * 1.1f);
        hintLayout = new GlyphLayout(hintFont,
                context.getString(R.string.wallpaper_hint_no_apps),
                Color.WHITE, 0, Align.center, false);

        // ─── Register live settings listener ────────────────────────────
        // We keep a strong reference in the field (prefListener) to prevent
        // the WeakHashMap inside SharedPreferences from GC-ing the listener.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefListener = (p, key) -> {
            if (key != null && RELEVANT_KEYS.contains(key)) {
                Gdx.app.postRunnable(this::applyConfig);
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        // ─── Setup input handling ───────────────────────────────────────
        setupInput();

        // ─── Initial scene build ────────────────────────────────────────
        // applyConfig() reads prefs, builds the scene, and sets lastConfigSnapshot.
        applyConfig();

        Log.i(TAG, "SphereEngine created with " + (appNodes != null ? appNodes.size() : 0) + " apps");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Config Reading — Extracts all relevant prefs into engine fields
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reads all user-configurable preferences into engine fields.
     *
     * Centralizing pref reads here ensures create(), resume(), and the live
     * listener all apply the same set of keys in the same way, with no
     * accidental omissions.
     *
     * @param prefs  SharedPreferences to read from
     */
    private void readConfig(SharedPreferences prefs) {
        // Background visibility (replaces old pref_keep_wallpaper)
        showBackground = prefs.getBoolean("pref_show_background", true);

        // Active home-screen page for visibility fade
        activePage = prefs.getInt("pref_active_page", 0);

        // Sphere radius: pref value 20–100 mapped to world units 3.0–8.0
        int radiusPref = prefs.getInt("pref_sphere_radius", 50);
        sphereRadius = MathUtils.lerp(3.0f, 8.0f, radiusPref / 100f);

        // Icon size: pref value 20–100 mapped to world units 0.6–2.0
        int iconPref = prefs.getInt("pref_icon_size", 50);
        iconSize = MathUtils.lerp(0.6f, 2.0f, iconPref / 100f);

        // Rotation speed: pref value 10–300, divide by 100 to get factor, clamp to [0.1, 3.0]
        rotationSpeedFactor = MathUtils.clamp(prefs.getInt("pref_rotation_speed", 100) / 100f, 0.1f, 3.0f);

        Log.i(TAG, "Config — radius: " + sphereRadius + ", iconSize: " + iconSize
                + ", showBackground: " + showBackground + ", activePage: " + activePage
                + ", rotationSpeedFactor: " + rotationSpeedFactor);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Snapshot — Deduplication key for applyConfig()
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns a string snapshot of all RELEVANT_KEYS values from SharedPreferences.
     *
     * The snapshot is compared against {@link #lastConfigSnapshot} in applyConfig()
     * to skip redundant rebuilds. StringSets are sorted before concatenation so
     * the snapshot is order-independent (SharedPreferences StringSets have no
     * guaranteed iteration order).
     *
     * @return Pipe-delimited concatenation of current values for all RELEVANT_KEYS
     */
    private String configSnapshot() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        StringBuilder sb = new StringBuilder();

        // selected_app_packages — sort for deterministic ordering
        Set<String> selectedApps = prefs.getStringSet("selected_app_packages", new java.util.HashSet<>());
        sb.append(new TreeSet<>(selectedApps)).append('|');

        sb.append(prefs.getString(GroupStore.PREF_GROUPS_JSON, "")).append('|');
        sb.append(prefs.getBoolean("pref_show_background", true)).append('|');
        sb.append(prefs.getInt(BackgroundStore.PREF_BACKGROUND_VERSION, 0)).append('|');
        sb.append(prefs.getInt("pref_sphere_radius", 50)).append('|');
        sb.append(prefs.getInt("pref_icon_size", 50)).append('|');
        sb.append(prefs.getInt("pref_rotation_speed", 100)).append('|');
        sb.append(prefs.getInt("pref_active_page", 0));

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  applyConfig() — GL-thread scene rebuild with snapshot deduplication
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reads the current configuration and rebuilds the scene if anything changed.
     *
     * Must be called on the GL thread. Safe to call redundantly — the snapshot
     * comparison makes it a no-op when nothing has changed (e.g., resume() fires
     * twice, or a pref listener fires for an unrelated key that slipped through).
     *
     * ─── What is rebuilt ─────────────────────────────────────────────────
     *
     * - All app icon textures (disposed then re-fetched from PackageManager)
     * - Group backdrop 3D models (disposed then rebuilt from new GroupStore data)
     * - Background texture (disposed then reloaded from BackgroundStore)
     * - Slot-clustered node distribution (recalculated with new effectiveRadius)
     * - Decals (recreated with new iconSize)
     * - Camera position (repositioned via computeCameraDistance using sphereRadius)
     *
     * ─── What is NOT rebuilt ─────────────────────────────────────────────
     *
     * - Camera projection (update() is called but FOV and near/far stay)
     * - SpriteBatch, DecalBatch, ModelBatch (renderer lifetime = GL context)
     * - sphereRotation, angularVelocity (physics state is preserved)
     * - gradientTexture (never changes — only depends on color constants)
     * - hintFont / hintLayout (only rebuilt if density changes, which is rare)
     */
    private void applyConfig() {
        String snapshot = configSnapshot();
        if (snapshot.equals(lastConfigSnapshot)) {
            Log.d(TAG, "applyConfig: snapshot unchanged, skipping rebuild");
            return;
        }
        // NOTE: lastConfigSnapshot is intentionally NOT assigned here.
        // It is assigned at the END of the method after a successful rebuild
        // so that a failed/interrupted rebuild can be retried on the next
        // resume() or preference-change listener fire.

        Log.i(TAG, "applyConfig: rebuilding scene...");

        // ─── Dispose old per-app icon textures ──────────────────────────
        if (appNodes != null) {
            for (AppFetcher.AppNode node : appNodes) {
                if (node.iconTexture != null) node.iconTexture.dispose();
            }
        }

        // ─── Dispose old group models ────────────────────────────────────
        if (groupModels != null) {
            for (Model model : groupModels) model.dispose();
        }

        // ─── Dispose old background texture ─────────────────────────────
        if (backgroundTexture != null) {
            backgroundTexture.dispose();
            backgroundTexture = null;
        }

        // ─── Read fresh configuration ────────────────────────────────────
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        readConfig(prefs);

        // ─── Update camera position for new radius ───────────────────────
        // Camera uses sphereRadius (the slider) as its reference so that the
        // maximum sphere always fills screen width exactly; effectiveRadius is
        // computed inside distributeNodesOnSphere() after app count is known.
        float vpW = camera.viewportWidth  > 0 ? camera.viewportWidth  : Gdx.graphics.getWidth();
        float vpH = camera.viewportHeight > 0 ? camera.viewportHeight : Gdx.graphics.getHeight();
        camera.position.set(0f, 0f, computeCameraDistance(vpW, vpH));
        camera.update();

        // ─── Re-fetch apps, redistribute, recreate decals and backdrops ──
        appNodes = AppFetcher.fetchSelectedApps(context);
        distributeNodesOnSphere();
        createDecals();
        buildGroupBackdrops();

        // ─── Reload background photo if enabled ──────────────────────────
        if (showBackground) {
            backgroundTexture = AppFetcher.loadBackgroundTexture(context);
        }

        // Snapshot recorded AFTER successful rebuild so a failed rebuild can
        // be retried on the next resume() or preference-change listener fire.
        lastConfigSnapshot = snapshot;

        Log.i(TAG, "applyConfig: done — " + appNodes.size() + " apps, effectiveRadius=" + effectiveRadius);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Gradient Texture Builder
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds a 1×256 RGBA8888 gradient texture that transitions from a very dark
     * navy (#05050F) at the top to a slightly lighter navy (#1A1A33) at the bottom.
     *
     * The texture is only 1 pixel wide; SpriteBatch stretches it to fill the screen.
     * Using a 256-pixel height gives smooth gradient steps. Linear filtering on the
     * texture ensures no banding when it is upscaled.
     *
     * @return New Texture (caller must dispose when done)
     */
    private Texture buildGradientTexture() {
        // Top color: #05050F  →  r=0.0196, g=0.0196, b=0.0588
        // Bottom color: #1A1A33  →  r=0.1020, g=0.1020, b=0.2000
        final float topR = 0x05 / 255f, topG = 0x05 / 255f, topB = 0x0F / 255f;
        final float botR = 0x1A / 255f, botG = 0x1A / 255f, botB = 0x33 / 255f;

        Pixmap pixmap = new Pixmap(1, 256, Pixmap.Format.RGBA8888);

        for (int y = 0; y < 256; y++) {
            // t=0 at top (y=0), t=1 at bottom (y=255)
            float t = y / 255f;
            int r = (int) (MathUtils.lerp(topR, botR, t) * 255f);
            int g = (int) (MathUtils.lerp(topG, botG, t) * 255f);
            int b = (int) (MathUtils.lerp(topB, botB, t) * 255f);
            // Pixmap.drawPixel expects RGBA packed int: 0xRRGGBBAA
            pixmap.drawPixel(0, y, (r << 24) | (g << 16) | (b << 8) | 0xFF);
        }

        Texture texture = new Texture(pixmap);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pixmap.dispose();
        return texture;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Slot-Clustered Sphere Distribution
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Distributes app nodes on the sphere using a two-level slot layout that
     * keeps grouped apps spatially together and adapts the layout radius to
     * the actual app count.
     *
     * ─── Adaptive Radius ─────────────────────────────────────────────────
     *
     * With very few apps on a large slider radius the icons would float as
     * sparse dots with no sense of a sphere. effectiveRadius is computed so
     * icons are naturally dense:
     *
     *   rIcons  = 0.48 × iconSize × √N          (density-based baseline)
     *   rGroups = 0.345 × iconSize × √(maxGroupSize × S)  (group-spread floor, if G>0)
     *   effectiveRadius = clamp(max(rIcons, rGroups), 1.6×iconSize, sphereRadius)
     *
     * computeCameraDistance() still uses sphereRadius (the slider) as its
     * reference, so the camera is a fixed envelope — a small effective sphere
     * appears proportionally small/dense.
     *
     * ─── Slot Layout ─────────────────────────────────────────────────────
     *
     * A "slot" is the unit of sphere surface area occupied by one group (all
     * M members together) or one ungrouped app. The S slot centers are placed
     * via the Fibonacci sphere at effectiveRadius, giving even spacing.
     *
     * ─── Sunflower Sub-layout Inside Group Slots ─────────────────────────
     *
     * Members within a group slot are arranged in a Fermat (sunflower) spiral
     * inside a spherical cap around the slot direction. Each member is
     * projected back onto the sphere surface at effectiveRadius.
     *
     * ─── Why Golden Angle? ───────────────────────────────────────────────
     *
     * The golden angle (≈137.5° or ≈2.3999… rad) ensures successive points
     * are rotated by the most irrational angle possible, preventing alignment
     * patterns that would leave visible gaps or clusters.
     */
    private void distributeNodesOnSphere() {
        int N = appNodes.size();
        nodePositions = new Vector3[N];

        if (N == 0) return;

        // ─── Build slot list (ordered by first appearance of each groupId) ──
        // Each slot holds the appNodes indices of its members.
        // LinkedHashMap preserves first-encounter order of group IDs.
        LinkedHashMap<String, List<Integer>> slots = new LinkedHashMap<>();
        for (int i = 0; i < N; i++) {
            AppFetcher.AppNode node = appNodes.get(i);
            // Ungrouped apps get their own single-member slot keyed by index.
            String key = (node.groupId != null) ? node.groupId : ("__single__" + i);
            slots.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        int S = slots.size(); // Total slot count
        int G = 0;            // Group-slot count (M >= 2)
        int maxGroupSize = 0;
        for (List<Integer> members : slots.values()) {
            if (members.size() >= 2) {
                G++;
                maxGroupSize = Math.max(maxGroupSize, members.size());
            }
        }

        // ─── Compute effectiveRadius ─────────────────────────────────────
        float rIcons  = 0.48f * iconSize * (float) Math.sqrt(N);
        float rGroups = (G > 0)
                ? 0.345f * iconSize * (float) Math.sqrt((float) maxGroupSize * S)
                : 0f;
        effectiveRadius = MathUtils.clamp(
                Math.max(rIcons, rGroups),
                1.6f * iconSize,
                sphereRadius);

        Log.d(TAG, "distributeNodesOnSphere: N=" + N + " S=" + S + " G=" + G
                + " effectiveRadius=" + effectiveRadius);

        if (N == 1) {
            // Edge case: single app → place at sphere front facing the camera.
            nodePositions[0] = new Vector3(0f, 0f, effectiveRadius);
            return;
        }

        // ─── Place slot centers via Fibonacci sphere at effectiveRadius ──
        // The golden angle gives the irrational longitude step.
        float phi = (float) (Math.PI * (3f - Math.sqrt(5f)));

        // Pre-compute slot center directions (unit vectors).
        List<Vector3> slotDirs = new ArrayList<>(S);
        {
            int si = 0;
            for (List<Integer> ignored : slots.values()) {
                float y = (S == 1) ? 0f : (1f - (si / (float) (S - 1)) * 2f);
                float radiusAtY = (float) Math.sqrt(Math.max(0f, 1f - y * y));
                float theta = phi * si;
                slotDirs.add(new Vector3(
                        (float) Math.cos(theta) * radiusAtY,
                        y,
                        (float) Math.sin(theta) * radiusAtY
                )); // This is already a unit vector (radius=1) from Fibonacci
                si++;
            }
        }

        // ─── Approximate half-angular-separation between slot centers ───
        // slotAngle ≈ 2/√S radians — the half-opening angle between Fibonacci
        // neighbours at this density. Used to bound the cap arc radius.
        float slotAngle = (S > 1) ? (2f / (float) Math.sqrt(S)) : MathUtils.PI;

        // ─── Assign positions for each slot ─────────────────────────────
        int slotIndex = 0;
        for (List<Integer> members : slots.values()) {
            Vector3 slotDir = slotDirs.get(slotIndex);
            int M = members.size();

            if (M == 1) {
                // ── Single-app slot: place node directly at the slot center ─
                int ni = members.get(0);
                nodePositions[ni] = new Vector3(slotDir).scl(effectiveRadius);
            } else {
                // ── Group slot: Fermat spiral inside a spherical cap ─────────
                //
                // capArc: world-unit arc radius on the sphere surface.
                //   • 0.62 × iconSize × √M gives enough room for M icons.
                //   • Capped by 0.9 × effectiveRadius × slotAngle (half the
                //     angular gap between slots) to prevent neighbor overlap.
                float capArc = Math.min(
                        0.62f * iconSize * (float) Math.sqrt(M),
                        0.9f * effectiveRadius * slotAngle);

                // ── Build two tangent basis vectors orthogonal to slotDir ─────
                // Avoid the degenerate case when slotDir is nearly parallel to Y.
                Vector3 n   = new Vector3(slotDir);    // already unit length from Fibonacci
                Vector3 t1  = new Vector3();
                Vector3 t2  = new Vector3();

                if (Math.abs(n.y) < 0.9f) {
                    // Cross with world-Y to get first tangent
                    t1.set(Vector3.Y).crs(n).nor();
                } else {
                    // n ≈ ±Y — cross with world-X instead
                    t1.set(Vector3.X).crs(n).nor();
                }
                t2.set(n).crs(t1).nor();

                for (int k = 0; k < M; k++) {
                    int ni = members.get(k);

                    // Fermat spiral: radial distance grows with √(k+0.5)/M
                    float rk    = capArc * (float) Math.sqrt((k + 0.5f) / M);
                    float theta = k * 2.39996f; // golden angle in radians

                    float cosT = MathUtils.cos(theta);
                    float sinT = MathUtils.sin(theta);

                    // Offset = slot-center-point + tangent-plane displacement
                    // then renormalize to sphere surface.
                    tmpVec.set(n)
                          .scl(effectiveRadius)
                          .add(t1.x * rk * cosT + t2.x * rk * sinT,
                               t1.y * rk * cosT + t2.y * rk * sinT,
                               t1.z * rk * cosT + t2.z * rk * sinT);
                    tmpVec.nor().scl(effectiveRadius);

                    nodePositions[ni] = new Vector3(tmpVec);
                }
            }

            slotIndex++;
        }

        Log.d(TAG, "Distributed " + N + " nodes in " + S + " slots via Fibonacci+sunflower");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Decal Creation — One billboard per app icon
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Creates a libGDX Decal for each app node. Decals are textured 2D
     * quads positioned in 3D space that can be billboarded to always
     * face the camera.
     *
     * Each Decal is created with hasTransparency=true to support app
     * icons with alpha channels (rounded corners, etc.). The DecalBatch
     * with CameraGroupStrategy handles depth-sorted rendering.
     */
    private void createDecals() {
        decals = new Array<>();
        // Parallel index map: icons can fail to rasterize (iconRegion == null), so we
        // skip those nodes via `continue`. Without this map every later decal would be
        // paired with the wrong node position in renderDecals().
        decalNodeIndex = new IntArray();

        for (int i = 0; i < appNodes.size(); i++) {
            AppFetcher.AppNode node = appNodes.get(i);

            if (node.iconRegion == null) continue;

            // Create a 2D decal from the app icon texture
            // hasTransparency=true enables alpha blending for round icons
            Decal decal = Decal.newDecal(iconSize, iconSize, node.iconRegion, true);

            // Position at the cluster-distributed point on the sphere
            decal.setPosition(nodePositions[i].x, nodePositions[i].y, nodePositions[i].z);

            decals.add(decal);
            decalNodeIndex.add(i); // record which node this decal belongs to
        }

        Log.d(TAG, "Created " + decals.size + " decals");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Group Cloth-Cap Mesh Generation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds translucent colored spherical-cap "cloth" meshes behind each app group.
     *
     * ─── Visual Design ───────────────────────────────────────────────────
     *
     * Each group gets a semi-transparent spherical cap positioned at radius
     * 0.88 × effectiveRadius — slightly inside the icon sphere so caps appear
     * as colored cloth draped under the group's icons. The cap's angular radius
     * is 25%-padded beyond the sunflower sub-layout.
     *
     * IntAttribute.CullFace=GL_NONE disables back-face culling so caps are
     * visible from both sides of the sphere — users can see where a group is
     * even when it is on the far side and can rotate toward it.
     *
     * ─── Orientation Strategy ────────────────────────────────────────────
     *
     * The libGDX sphere-sweep overload builds a cap around +Y; we store the
     * base orientation (a rotation that takes +Y to the slot direction) as a
     * Matrix4 and apply it combined with sphereRotation every frame, so the
     * cap stays glued under its icons and rotates rigidly with the sphere.
     *
     * ─── Why not the old centroid-blob approach? ──────────────────────────
     *
     * Fibonacci consecutive indices are NOT spatially adjacent (the golden
     * angle jumps 137.5° in longitude each step), so group members assigned to
     * consecutive indices scatter around the sphere. The centroid of scattered
     * members collapses toward the sphere center, and a sphere blob placed
     * there becomes a huge washed-out circle that covers unrelated apps. The
     * slot layout above pre-clusters members before Fibonacci placement, so
     * the cap center is always accurate.
     */
    private void buildGroupBackdrops() {
        groupBackdrops      = new Array<>();
        groupModels         = new Array<>();
        groupCapOrientations = new Array<>();

        if (appNodes == null || appNodes.isEmpty()) return;

        // ─── Identify group slots from appNodes (preserve first-appearance order) ──
        LinkedHashMap<String, List<Integer>> groupSlots = new LinkedHashMap<>();
        Map<String, String> groupColorMap = new HashMap<>();

        for (int i = 0; i < appNodes.size(); i++) {
            AppFetcher.AppNode node = appNodes.get(i);
            if (node.groupId != null) {
                groupSlots.computeIfAbsent(node.groupId, k -> new ArrayList<>()).add(i);
                if (node.groupColorHex != null) {
                    groupColorMap.put(node.groupId, node.groupColorHex);
                }
            }
        }

        int S = countTotalSlots(); // Total slot count (groups + singles) for slotAngle

        // slotAngle ≈ half angular separation between Fibonacci slot centres.
        float slotAngle = (S > 1) ? (2f / (float) Math.sqrt(S)) : MathUtils.PI;

        ModelBuilder modelBuilder = new ModelBuilder();

        for (Map.Entry<String, List<Integer>> entry : groupSlots.entrySet()) {
            String groupId  = entry.getKey();
            List<Integer> indices = entry.getValue();
            String colorHex = groupColorMap.getOrDefault(groupId, "#FFFFFF");

            if (indices.size() < 2) continue; // Skip singleton groups

            int M = indices.size();

            // ─── Find the slot direction for this group ───────────────────
            // The slot direction is the unit-vector average (centroid on unit sphere)
            // of the member positions at effectiveRadius. Because all members were
            // projected back onto the sphere by distributeNodesOnSphere(), averaging
            // their unit-vectors and renormalizing gives the correct slot center
            // direction without re-running the Fibonacci layout.
            Vector3 slotDir = new Vector3();
            for (int idx : indices) {
                slotDir.add(nodePositions[idx]);
            }
            slotDir.nor(); // renormalize → unit direction of slot center

            // ─── Compute cap angular radius ───────────────────────────────
            // Mirror the capArc from distributeNodesOnSphere with 25% padding,
            // clamped to 1.2 radians so no cap wraps around more than ~70°.
            float capArc = Math.min(
                    0.62f * iconSize * (float) Math.sqrt(M),
                    0.9f * effectiveRadius * slotAngle);
            float alpha = Math.min(capArc / effectiveRadius * 1.25f, 1.2f); // radians
            float alphaDeg = (float) Math.toDegrees(alpha);

            // ─── Parse the group color at 30% opacity ────────────────────
            Color gdxColor = parseHexColor(colorHex, 0.30f);

            // ─── Create cap material ──────────────────────────────────────
            // BlendingAttribute: standard SRC_ALPHA / ONE_MINUS_SRC_ALPHA.
            // DepthTestAttribute(GL_LEQUAL, false): depth TEST on for correct
            //   sorting, depthMask=false so caps never z-reject icon decals
            //   at nearly the same depth.
            // IntAttribute.CullFace=GL_NONE: render both faces so the cap is
            //   visible from the back of the sphere (owner requirement).
            Material material = new Material(
                    ColorAttribute.createDiffuse(gdxColor),
                    new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 0.30f),
                    new DepthTestAttribute(GL20.GL_LEQUAL, false),
                    IntAttribute.createCullFace(GL20.GL_NONE)
            );

            // ─── Build the spherical-cap mesh ─────────────────────────────
            // MeshPartBuilder.sphere(w,h,d, uDiv, vDiv, uFrom, uTo, vFrom, vTo)
            //   v angles are latitude: 0° = +Y pole, 180° = −Y pole.
            //   We build a cap of half-angle alphaDeg degrees around +Y.
            //   Diameter = 2 × 0.88 × effectiveRadius; cap geometry is centred
            //   at the mesh origin with the pole at +Y at that radius.
            float capDiameter = 2f * 0.88f * effectiveRadius;

            int attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;

            modelBuilder.begin();
            MeshPartBuilder partBuilder = modelBuilder.part(
                    "cap_" + groupId,
                    GL20.GL_TRIANGLES,
                    attributes,
                    material
            );
            // Build cap: full 360° longitude sweep, 0° to alphaDeg latitude.
            // 24 longitude divisions × 8 latitude rings gives smooth edges.
            partBuilder.sphere(capDiameter, capDiameter, capDiameter,
                    24, 8,
                    0f, 360f, 0f, alphaDeg);

            Model model = modelBuilder.end();
            groupModels.add(model);

            ModelInstance instance = new ModelInstance(model);
            groupBackdrops.add(instance);

            // ─── Store base orientation: rotation that takes +Y → slotDir ─
            // setFromCross(a, b) produces the shortest-arc rotation from a to b.
            // When slotDir is exactly +Y, setFromCross returns identity (correct).
            // When slotDir is exactly −Y, setFromCross may degenerate — handle
            // that by using a 180° rotation around X instead.
            Matrix4 baseOrient = new Matrix4();
            if (slotDir.dot(Vector3.Y) > -0.9999f) {
                tmpQuat.setFromCross(Vector3.Y, slotDir);
                baseOrient.set(tmpQuat);
            } else {
                // slotDir ≈ −Y: 180° flip around X axis
                baseOrient.setToRotation(Vector3.X, 180f);
            }
            groupCapOrientations.add(baseOrient);

            Log.d(TAG, "Group '" + groupId + "': " + M + " apps, slotDir=" + slotDir
                    + " alphaDeg=" + alphaDeg);
        }
    }

    /**
     * Counts total slot count S (groups with M>=2 count as 1 slot each; each
     * ungrouped app is 1 slot). Used for the slotAngle calculation in
     * buildGroupBackdrops, mirroring the logic in distributeNodesOnSphere.
     */
    private int countTotalSlots() {
        if (appNodes == null || appNodes.isEmpty()) return 0;
        Set<String> seenGroups = new java.util.HashSet<>();
        int count = 0;
        for (AppFetcher.AppNode node : appNodes) {
            if (node.groupId != null) {
                if (seenGroups.add(node.groupId)) count++;
            } else {
                count++;
            }
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Input Setup — GestureDetector for spin, tap, fling
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Configures input handling with a GestureDetector for:
     * - **Pan**: Converts 2D screen drag into 3D sphere rotation
     * - **Fling**: Applies angular momentum for inertial spinning
     * - **Tap**: Raycasts to detect which app was tapped and launches it
     */
    private void setupInput() {
        GestureDetector gestureDetector = new GestureDetector(new GestureDetector.GestureAdapter() {

            /**
             * ─── TAP → App Launch ────────────────────────────────────────
             *
             * On tap, we cast a 3D ray from the camera through the tap
             * point and check intersection with each app node's bounding
             * sphere. The closest intersected node's app is launched.
             *
             * Taps are ignored when pageVisibility < 0.5 so apps on other
             * home-screen pages (where the sphere is invisible) cannot be
             * accidentally launched.
             */
            @Override
            public boolean tap(float x, float y, int count, int button) {
                if (appNodes == null || appNodes.isEmpty()) return false;

                // ─── Ignore taps when sphere is mostly invisible ────────
                // The sphere fully fades to 0 on non-active pages; tapping
                // through it would silently launch apps the user cannot see.
                if (pageVisibility < 0.5f) return false;

                // ─── Cast a pick ray from camera through screen point ───
                Ray pickRay = camera.getPickRay(x, y);

                int closestIdx = -1;
                float closestDist = Float.MAX_VALUE;

                // ─── Test intersection with each app node ───────────────
                // Each node is treated as a sphere with radius = iconSize/2
                // positioned at the rotated node position.
                float hitRadius = iconSize * 0.6f; // Slightly larger than visual for forgiving taps

                for (int i = 0; i < appNodes.size(); i++) {
                    // Get the current rotated position of this node
                    Vector3 rotatedPos = getRotatedPosition(i);

                    // Sphere intersection test
                    if (Intersector.intersectRaySphere(pickRay, rotatedPos, hitRadius, tmpVec)) {
                        // Use squared distance to avoid sqrt per node
                        float dist = pickRay.origin.dst2(tmpVec);
                        if (dist < closestDist) {
                            closestDist = dist;
                            closestIdx = i;
                        }
                    }
                }

                // ─── Launch the tapped app ──────────────────────────────
                if (closestIdx >= 0) {
                    AppFetcher.AppNode tappedNode = appNodes.get(closestIdx);
                    Log.i(TAG, "Tapped app: " + tappedNode.appName
                            + " (" + tappedNode.packageName + ")");

                    // Launch on the main (UI) thread since we're in GL thread
                    final String pkg = tappedNode.packageName;
                    Gdx.app.postRunnable(() -> AppFetcher.launchApp(context, pkg));

                    return true;
                }

                return false;
            }

            /**
             * ─── PAN → Sphere Rotation ───────────────────────────────────
             *
             * Converts 2D screen drag deltas into 3D rotation around the
             * appropriate axes. Dragging horizontally rotates around Y axis,
             * dragging vertically rotates around X axis.
             *
             * ─── Why mulLeft (pre-multiply)? ─────────────────────────────
             *
             * Post-multiplying (sphereRotation.mul(tmpQuat)) applies the new
             * rotation around the sphere's LOCAL axes. After the sphere has
             * accumulated some orientation, those local axes no longer align
             * with the screen axes, so horizontal drags start spinning the
             * sphere diagonally — a disorienting skew/inversion effect.
             *
             * Pre-multiplying (sphereRotation.mulLeft(tmpQuat)) applies the
             * rotation in WORLD space instead. The Y axis is always the
             * screen-vertical world axis, so horizontal drags always produce
             * a horizontal spin regardless of the accumulated orientation.
             */
            @Override
            public boolean pan(float x, float y, float deltaX, float deltaY) {
                userInteracting = true;
                idleTimer = 0f;

                // Convert screen-space drag to rotation angles
                // deltaX → rotate around Y axis (horizontal drag = horizontal spin)
                // deltaY → rotate around X axis (vertical drag = vertical spin)
                float angleY = -deltaX * ROTATION_SENSITIVITY;
                float angleX = deltaY * ROTATION_SENSITIVITY;

                // Pre-multiply: apply rotation in WORLD space so horizontal drags
                // always spin around the screen-vertical Y axis.
                tmpQuat.setFromAxis(Vector3.Y, (float) Math.toDegrees(angleY));
                sphereRotation.mulLeft(tmpQuat);

                tmpQuat.setFromAxis(Vector3.X, (float) Math.toDegrees(angleX));
                sphereRotation.mulLeft(tmpQuat);

                // Kill any existing momentum while dragging
                angularVelocity.setZero();

                return true;
            }

            /**
             * ─── FLING → Momentum Spin ───────────────────────────────────
             *
             * When the user releases a swipe, apply the fling velocity as
             * angular momentum. The friction system in render() will
             * smoothly decelerate the spin. Fling angular velocity is scaled
             * by rotationSpeedFactor so the user's speed preference applies.
             */
            @Override
            public boolean fling(float velocityX, float velocityY, int button) {
                userInteracting = false;

                // Map screen-space fling velocity to angular velocity, scaled
                // by the user's rotation speed preference.
                angularVelocity.set(
                        velocityY * FLING_SENSITIVITY * rotationSpeedFactor,   // X axis
                        -velocityX * FLING_SENSITIVITY * rotationSpeedFactor,  // Y axis
                        0f                                                       // No Z
                );

                // Clamp maximum angular velocity to prevent seizure-inducing spins
                float maxVelocity = 8f; // radians/sec
                if (angularVelocity.len() > maxVelocity) {
                    angularVelocity.nor().scl(maxVelocity);
                }

                return true;
            }

            @Override
            public boolean panStop(float x, float y, int pointer, int button) {
                userInteracting = false;
                return true;
            }
        });

        Gdx.input.setInputProcessor(gestureDetector);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ApplicationListener — render()
    //  Called every frame (target: 120 FPS)
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void render() {
        // ─── Skip rendering when not visible (major battery savings) ────
        if (!isVisible) return;

        float delta = Gdx.graphics.getDeltaTime();

        // ─── Update physics ─────────────────────────────────────────────
        updatePhysics(delta);

        // ─── Update page visibility ─────────────────────────────────────
        updatePageVisibility(delta);

        // ─── Clear screen ───────────────────────────────────────────────
        // Fixed deep-navy clear color provides a consistent base for the
        // gradient fallback. Alpha=1 so we always own our pixel.
        Gdx.gl.glClearColor(0.02f, 0.02f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        // ─── Enable depth testing for proper 3D sorting ─────────────────
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        // ─── Layer 1: Background ─────────────────────────────────────────
        // Always draws: user photo if available; gradient texture otherwise.
        renderBackground();

        // ─── Layer 2: Group Backdrop Meshes ─────────────────────────────
        if (groupBackdrops != null && groupBackdrops.size > 0 && pageVisibility > 0.01f) {
            renderGroupBackdrops();
        }

        // ─── Layer 3: App Icon Decals (Billboarded) ─────────────────────
        if (decals != null && decals.size > 0 && pageVisibility > 0.01f) {
            renderDecals();
        }

        // ─── Layer 4: Empty-state hint ───────────────────────────────────
        // Drawn on top of everything when no apps are configured.
        if (appNodes == null || appNodes.isEmpty()) {
            renderEmptyHint();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Physics Update — Quaternion rotation with friction
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Updates the sphere's rotation state each frame.
     *
     * ─── Rotation Pipeline ───────────────────────────────────────────────
     *
     * 1. If user isn't interacting and idle timer expired, apply gentle auto-spin
     * 2. Apply angular velocity to the rotation quaternion
     * 3. Apply friction to decelerate angular velocity
     * 4. Normalize the quaternion to prevent drift accumulation
     *
     * ─── Why mulLeft (pre-multiply)? ─────────────────────────────────────
     *
     * The angular velocity vector is defined in world space. Pre-multiplying
     * ensures the rotation always acts on world axes, consistent with the pan
     * handler. Post-multiplying would rotate in the sphere's local space,
     * causing the auto-spin axis to drift as orientation accumulates.
     *
     * @param delta Frame time in seconds (1/120 at 120 FPS)
     */
    private void updatePhysics(float delta) {
        // ─── Idle auto-spin ─────────────────────────────────────────────
        if (!userInteracting) {
            idleTimer += delta;
            if (idleTimer > IDLE_DELAY && angularVelocity.len2() < VELOCITY_EPSILON * VELOCITY_EPSILON) {
                // Resume gentle auto-rotation around world Y axis, scaled by speed preference
                angularVelocity.y = IDLE_SPIN_SPEED * rotationSpeedFactor;
            }
        }

        // ─── Apply angular velocity to rotation quaternion ──────────────
        float speed = angularVelocity.len();
        if (speed > VELOCITY_EPSILON) {
            // Create a delta rotation quaternion:
            // angle = speed * delta (radians this frame)
            // axis = normalized angular velocity direction
            float angle = speed * delta;

            tmpVec.set(angularVelocity).nor();
            tmpQuat.setFromAxis(tmpVec, (float) Math.toDegrees(angle));

            // Pre-multiply: apply rotation in WORLD space so the auto-spin
            // axis remains the world Y axis regardless of accumulated orientation.
            sphereRotation.mulLeft(tmpQuat);

            // Normalize to prevent floating-point drift
            // After many multiplications, quaternion magnitude can drift from 1.0
            sphereRotation.nor();

            // ─── Apply friction ─────────────────────────────────────────
            // Exponential decay: v *= friction^(delta * 120)
            // The exponent normalizes friction to feel consistent regardless
            // of frame rate. At 120 FPS, friction is applied 120 times/sec.
            float frictionThisFrame = (float) Math.pow(FRICTION, delta * 120.0);
            angularVelocity.scl(frictionThisFrame);

            // ─── Snap to zero below epsilon ─────────────────────────────
            if (angularVelocity.len2() < VELOCITY_EPSILON * VELOCITY_EPSILON) {
                angularVelocity.setZero();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Page Visibility — Animate sphere based on home screen position
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Smoothly transitions sphere visibility based on which home screen
     * page the user is on.
     *
     * When the user is on the configured "active page", pageVisibility
     * lerps toward 1.0 (full render). On other pages, it lerps toward
     * 0.0 (fully hidden) so the sphere completely disappears — saving GPU
     * cycles and ensuring the wallpaper only occupies the designated page.
     *
     * The lerp speed (8.0) gives a snappy ~150ms transition at 120 FPS.
     */
    private void updatePageVisibility(float delta) {
        float targetVisibility;

        if (xOffsetStep <= 0f) {
            // Can't determine page (some launchers don't report step) — always visible
            targetVisibility = 1f;
        } else {
            // Calculate current page number from continuous offset
            float currentPage = currentXOffset / xOffsetStep;
            float pageDistance = Math.abs(currentPage - activePage);

            // Full visibility when within 0.3 pages, fading to 0 beyond 1 page.
            // Clamp floor is 0f (not 0.1f) so the sphere fully disappears on
            // non-active pages — the user configured this sphere for one page only.
            // Falloff slope 1.5 (not 1.4): at exactly pageDistance = 1.0 the
            // target must reach 0 — with 1.4 it left a 2% ghost (1−0.7×1.4=0.02)
            // visible on the adjacent page.
            targetVisibility = MathUtils.clamp(1f - (pageDistance - 0.3f) * 1.5f, 0f, 1f);
        }

        // Smooth lerp to target
        pageVisibility = MathUtils.lerp(pageVisibility, targetVisibility, delta * 8f);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Render Layer 1 — Background (photo or gradient)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Renders the background as a full-screen 2D sprite. Always draws
     * something — the user photo if one has been loaded, otherwise the
     * procedural gradient texture.
     *
     * Uses SpriteBatch which internally sets up an orthographic projection,
     * unaffected by our 3D PerspectiveCamera. The depth buffer is temporarily
     * disabled so the background is always behind everything.
     *
     * The gradient texture (1×256) is always stretched full-screen — that is
     * correct since it is a uniform gradient with no meaningful aspect ratio.
     * The user photo (backgroundTexture) is center-cropped (aspect-fill) so
     * it fills the screen without distortion regardless of photo dimensions.
     */
    private void renderBackground() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);

        float screenW = Gdx.graphics.getWidth();
        float screenH = Gdx.graphics.getHeight();

        spriteBatch.begin();

        if (backgroundTexture != null) {
            // ─── Center-crop (aspect-fill) for user photo ────────────────
            // Scale uniformly so the photo covers the entire screen, then
            // center it. This avoids the stretch/distortion that would occur
            // when the photo's aspect ratio differs from the screen's.
            float texW = backgroundTexture.getWidth();
            float texH = backgroundTexture.getHeight();

            float scale  = Math.max(screenW / texW, screenH / texH);
            float drawW  = texW * scale;
            float drawH  = texH * scale;
            float drawX  = (screenW - drawW) / 2f;
            float drawY  = (screenH - drawH) / 2f;

            spriteBatch.draw(backgroundTexture, drawX, drawY, drawW, drawH);
        } else {
            // Gradient is a 1×256 stripe — stretching is correct here.
            spriteBatch.draw(gradientTexture, 0, 0, screenW, screenH);
        }

        spriteBatch.end();

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Render Layer 2 — Group Cloth-Cap Meshes
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Renders the translucent colored cloth-cap meshes behind grouped app clusters.
     *
     * Each cap's transform is:
     *   sphereRotationMatrix × baseOrientationMatrix (then scaled by pageVisibility)
     *
     * - baseOrientationMatrix: rotation taking +Y to the slot direction (built once
     *   in buildGroupBackdrops, stored in groupCapOrientations).
     * - sphereRotationMatrix: the live sphere rotation quaternion converted to Matrix4.
     *
     * This makes caps rotate rigidly with the sphere. No translation is needed —
     * the cap geometry is already centred at the mesh origin with its pole at
     * radius 0.88 × effectiveRadius from origin (from the sphere sweep).
     */
    private void renderGroupBackdrops() {
        // Build the sphere-rotation matrix once per frame (avoids per-cap alloc).
        tmpMat.set(sphereRotation);

        modelBatch.begin(camera);

        for (int i = 0; i < groupBackdrops.size; i++) {
            ModelInstance instance = groupBackdrops.get(i);
            Matrix4 baseOrient    = groupCapOrientations.get(i);

            // Compose: sphereRotation × baseOrientation
            instance.transform.set(tmpMat).mul(baseOrient);

            // Scale by pageVisibility for the page-fade shrink/grow animation.
            // Scaling uniformly toward origin keeps the cap centred on the sphere.
            instance.transform.scl(pageVisibility);

            modelBatch.render(instance);
        }

        modelBatch.end();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Render Layer 3 — Billboarded App Icon Decals with depth scale/alpha
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Renders all app icon decals with billboarding (face-the-camera) and
     * depth-based scale/alpha so icons on the far side of the sphere appear
     * smaller and dimmer, creating a natural parallax depth illusion.
     *
     * ─── Depth Scaling Math ───────────────────────────────────────────────
     *
     * After applying sphereRotation, each node's Z coordinate in
     * camera-relative space lies in [-effectiveRadius, +effectiveRadius].
     * The camera sits at +Z, so a high rotatedPos.z means "facing the camera".
     *
     * nd (normalized depth) maps that Z range to [0, 1]:
     *   nd = clamp((z/effectiveRadius + 1) * 0.5, 0, 1)
     *
     * depthScale ∈ [0.5, 1.0] — far icons at half visual size.
     * depthAlpha ∈ [0.35, 1.0] — far icons at 35% opacity.
     *
     * ─── Depth Sorting ───────────────────────────────────────────────────
     *
     * CameraGroupStrategy (set in create()) automatically sorts decals
     * by distance from the camera. This is critical for correct alpha
     * blending — transparent decals must be rendered back-to-front.
     *
     * ─── Billboarding ────────────────────────────────────────────────────
     *
     * Decal.lookAt() rotates the decal quad to face the camera position.
     * The camera.up vector prevents the decal from rolling. This creates
     * the illusion of flat 2D icons floating in 3D space.
     */
    private void renderDecals() {
        for (int i = 0; i < decals.size; i++) {
            Decal decal = decals.get(i);

            // ─── Apply sphere rotation to this node's position ──────────
            // Use decalNodeIndex to get the correct nodePositions entry; icons can
            // fail to rasterize so decals[] may be shorter than nodePositions[].
            int nodeIdx = decalNodeIndex.get(i);
            Vector3 rotatedPos = getRotatedPosition(nodeIdx);

            // ─── Depth-based scale and alpha ─────────────────────────────
            // Compute BEFORE scaling by pageVisibility (rotatedPos.z must be
            // in the raw sphere-space range [-R, +R] for the formula to hold).
            // Camera is at +Z; higher z = closer to camera = larger/brighter.
            // Uses effectiveRadius so the depth formula matches actual node placement.
            float nd = MathUtils.clamp((rotatedPos.z / effectiveRadius + 1f) * 0.5f, 0f, 1f);
            float depthScale = 0.5f + 0.5f * nd;    // far icons half size
            float depthAlpha = 0.35f + 0.65f * nd;  // far icons dimmed

            // ─── Apply page visibility scale ────────────────────────────
            // Scale position toward origin when page visibility < 1
            rotatedPos.scl(pageVisibility);

            // ─── Update decal transform ─────────────────────────────────
            decal.setPosition(rotatedPos.x, rotatedPos.y, rotatedPos.z);

            // Icon size combines depth perspective and page visibility
            decal.setDimensions(
                    iconSize * depthScale * pageVisibility,
                    iconSize * depthScale * pageVisibility
            );

            // Apply depth alpha combined with page visibility
            decal.setColor(1f, 1f, 1f, depthAlpha * pageVisibility);

            // ─── Billboard: always face the camera ──────────────────────
            decal.lookAt(camera.position, camera.up);

            // ─── Add to batch (CameraGroupStrategy handles depth sort) ──
            decalBatch.add(decal);
        }

        // ─── Flush all decals to GPU in one draw call ───────────────────
        decalBatch.flush();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Render Layer 4 — Empty-state hint text
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Renders a centered multi-line hint message when no apps have been
     * selected by the user.
     *
     * The GlyphLayout was built with Align.center in create(), so passing
     * screen-center X to hintFont.draw() produces a horizontally centered
     * result. Vertical centering positions the text mid-screen accounting
     * for the layout's measured height.
     *
     * The font color is white at 85% opacity for a subtle, non-intrusive look.
     */
    private void renderEmptyHint() {
        spriteBatch.begin();
        hintFont.setColor(1f, 1f, 1f, 0.85f);
        hintFont.draw(spriteBatch, hintLayout,
                Gdx.graphics.getWidth() / 2f,
                (Gdx.graphics.getHeight() + hintLayout.height) / 2f);
        spriteBatch.end();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Utility — Get rotated position of a node
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns the current world-space position of the node at the given index,
     * after applying the sphere's rotation quaternion.
     *
     * The rotation is applied by libGDX's Quaternion.transform(), which
     * computes v' = q * v * q⁻¹ efficiently without constructing a matrix.
     *
     * This preserves the original positions (important for group cap
     * calculations) while giving us the visually correct rotated positions.
     *
     * @param index Index into nodePositions and appNodes
     * @return The rotated position (reuses tmpVec2, not safe to store)
     */
    private Vector3 getRotatedPosition(int index) {
        tmpVec2.set(nodePositions[index]);

        // Apply quaternion rotation: v' = q * v * q^-1
        // libGDX's Quaternion.transform() does this efficiently
        sphereRotation.transform(tmpVec2);

        return tmpVec2;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Camera Distance — fits sphere + icon overhang inside both dimensions
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Computes the camera Z distance so the entire sphere — including the
     * billboarded icon quads that overhang the surface tangentially — projects
     * inside both screen dimensions. Owner requirement: the sphere's projected
     * diameter must never exceed the screen width.
     *
     * Geometry: a sphere of effective radius R' viewed from distance D has a
     * silhouette half-angle of asin(R'/D). It fits inside a view cone of
     * half-angle θ when D ≥ R'/sin(θ). We evaluate the horizontal cone
     * (θ_h = atan(tan(θ_v) × aspect), the narrow cone in portrait) and the
     * vertical cone, then take the larger distance, plus a 5 % safety margin.
     *
     * Billboards extend up to ~iconSize × 0.75 beyond the sphere surface
     * diagonally; they are enclosed in a slightly larger effective sphere.
     *
     * NOTE: This method intentionally uses sphereRadius (the slider value),
     * NOT effectiveRadius. The camera is a fixed reference — a small effective
     * sphere appears proportionally small/dense inside the camera envelope;
     * a full sphere fills screen width exactly. This is the intended UX.
     *
     * @param viewportW  Current viewport width  in pixels (must be > 0)
     * @param viewportH  Current viewport height in pixels (must be > 0)
     * @return Camera Z coordinate that keeps sphere + icons on-screen
     */
    private float computeCameraDistance(float viewportW, float viewportH) {
        // Effective radius encloses the sphere plus billboarded icon overhang.
        float effRadius = sphereRadius + iconSize * 0.75f;

        // camera.fieldOfView is the VERTICAL fov in libGDX PerspectiveCamera.
        double halfV = Math.toRadians(camera.fieldOfView / 2.0);

        // Derive horizontal half-fov from vertical half-fov and aspect ratio.
        float aspect = viewportW / viewportH;
        double halfH = Math.atan(Math.tan(halfV) * aspect);

        // Minimum distance so the sphere's silhouette fits inside each cone.
        // D ≥ R' / sin(θ) ensures the silhouette subtends at most θ half-angle.
        float distH = (float) (effRadius / Math.sin(halfH));
        float distV = (float) (effRadius / Math.sin(halfV));

        // Take the larger of the two (portrait → distH dominates),
        // then add a 5 % safety margin.
        return Math.max(distH, distV) * 1.05f;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Color Parsing Utility
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Parses a hex color string (e.g., "#FF6B6B") into a libGDX Color
     * with the specified alpha.
     *
     * @param hex   Hex color string with # prefix
     * @param alpha Alpha value 0.0–1.0
     * @return libGDX Color
     */
    private Color parseHexColor(String hex, float alpha) {
        try {
            hex = hex.replace("#", "");
            float r = Integer.parseInt(hex.substring(0, 2), 16) / 255f;
            float g = Integer.parseInt(hex.substring(2, 4), 16) / 255f;
            float b = Integer.parseInt(hex.substring(4, 6), 16) / 255f;
            return new Color(r, g, b, alpha);
        } catch (Exception e) {
            return new Color(1f, 1f, 1f, alpha); // Fallback: white
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AndroidWallpaperListener — Home screen offset callbacks
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called by libGDX's AndroidWallpaperEngine when the launcher broadcasts
     * page offset changes. This is the AndroidWallpaperListener interface
     * method — note it has a different signature than WallpaperService.Engine's
     * onOffsetsChanged.
     */
    @Override
    public void offsetChange(float xOffset, float yOffset,
                             float xOffsetStep, float yOffsetStep,
                             int xPixelOffset, int yPixelOffset) {
        this.currentXOffset = xOffset;
        this.xOffsetStep = xOffsetStep;
    }

    @Override
    public void previewStateChange(boolean isPreview) {
        // In preview mode (wallpaper picker), always render at full visibility
        if (isPreview) {
            pageVisibility = 1f;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Public callbacks from MyWallpaperService
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called from MyWallpaperService.AuraOrbitEngine.onOffsetsChanged().
     * This provides the raw WallpaperService.Engine offset values before
     * libGDX's AndroidWallpaperListener processes them.
     */
    public void onOffsetsChanged(float xOffset, float yOffset,
                                 float xOffsetStep, float yOffsetStep) {
        this.currentXOffset = xOffset;
        this.xOffsetStep = xOffsetStep;
    }

    /**
     * Called from MyWallpaperService when wallpaper visibility changes.
     * When not visible, we skip the render loop entirely.
     */
    public void setVisible(boolean visible) {
        this.isVisible = visible;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ApplicationListener — Lifecycle methods
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void resize(int width, int height) {
        // Update camera viewport when screen dimensions change
        // (e.g., orientation change, though wallpapers usually stay portrait)
        if (camera != null) {
            camera.viewportWidth = width;
            camera.viewportHeight = height;
            // Recompute the camera Z distance with the new aspect ratio.
            // In portrait (narrow), the horizontal half-fov is the binding cone;
            // after an orientation change the binding dimension may flip, so we
            // always recalculate to keep the sphere+icons inside the screen.
            if (width > 0 && height > 0) {
                camera.position.set(0f, 0f, computeCameraDistance(width, height));
            }
            camera.update();
        }

        Log.i(TAG, "Resized to " + width + "x" + height);
    }

    @Override
    public void pause() {
        // Live wallpaper is going to background — reduce state
        Log.d(TAG, "Paused");
    }

    @Override
    public void resume() {
        // Live wallpaper returning to foreground — rebuild if settings changed.
        // The snapshot deduplication in applyConfig() makes this a no-op when
        // nothing has changed, so it is safe to call on every resume.
        Log.d(TAG, "Resumed");
        Gdx.app.postRunnable(this::applyConfig);
    }

    @Override
    public void dispose() {
        Log.i(TAG, "Disposing SphereEngine...");

        // ─── Unregister preference listener ────────────────────────────
        // Must be unregistered explicitly — SharedPreferences.WeakHashMap will
        // eventually GC the entry, but we want deterministic unregistration
        // and the field keeps the listener alive until dispose().
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            prefListener = null;
        }

        // ─── Dispose rendering systems ──────────────────────────────────
        if (spriteBatch != null) spriteBatch.dispose();
        if (decalBatch != null) decalBatch.dispose();
        if (modelBatch != null) modelBatch.dispose();

        // ─── Dispose textures ───────────────────────────────────────────
        if (backgroundTexture != null) backgroundTexture.dispose();
        if (gradientTexture != null) gradientTexture.dispose();

        // Dispose all app icon textures
        if (appNodes != null) {
            for (AppFetcher.AppNode node : appNodes) {
                if (node.iconTexture != null) {
                    node.iconTexture.dispose();
                }
            }
        }

        // ─── Dispose group models ───────────────────────────────────────
        if (groupModels != null) {
            for (Model model : groupModels) {
                model.dispose();
            }
        }

        // ─── Dispose hint font ──────────────────────────────────────────
        if (hintFont != null) hintFont.dispose();

        Log.i(TAG, "SphereEngine disposed");
    }

    @Override
    public void iconDropped(int x, int y) {
        // Required by AndroidWallpaperListener but not used
    }
}
