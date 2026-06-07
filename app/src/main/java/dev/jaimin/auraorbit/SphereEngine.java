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
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.graphics.g3d.decals.CameraGroupStrategy;
import com.badlogic.gdx.graphics.g3d.decals.Decal;
import com.badlogic.gdx.graphics.g3d.decals.DecalBatch;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
 *    Always rendered. Priority chain: custom photo (BackgroundStore /
 *    AppFetcher.loadBackgroundTexture) when one is set; else the current
 *    system (static) wallpaper mirrored via WallpaperManager.getDrawable()
 *    so the live wallpaper appears transparent; else a procedural vertical
 *    gradient texture as a final fallback.
 *
 * 2. **Group Backdrop Layer** (ModelBatch, 3D)
 *    Renders translucent colored convex-hull polygon patches behind each app
 *    group cluster. These patches are built as padded spherical polygons that
 *    precisely cover the group's icons, and they rotate rigidly with the sphere.
 *    IntAttribute.CullFace=GL_NONE makes patches visible from both the front
 *    and back of the sphere, so users can see where a group is even when it is
 *    on the far side.
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
 * - User flings apply angular velocity with exponential friction (decays naturally)
 * - Idle spin uses a SEPARATE friction-free constant rotation (no pulse/stop cycle):
 *   idleBlend ramps from 0→1 over 1.5s after IDLE_DELAY seconds without interaction.
 *   Both idle rotation and decaying fling momentum can coexist in the same frame.
 * - All math is delta-time dependent for frame-rate independence
 * - Drag and physics rotations use pre-multiplication (mulLeft) so they always
 *   operate in world space regardless of accumulated sphere orientation.
 *
 * ─── Layout ─────────────────────────────────────────────────────────────────
 *
 * ALL N apps are placed on a single plain Fibonacci sphere for perfectly uniform
 * spacing. Every icon has the same inter-icon distance — grouped or not. Groups
 * stay spatially contiguous by ASSIGNING which Fibonacci lattice point each app
 * gets (a permutation), never by distorting the lattice.
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
 * - GestureDetector handles pan (one-finger rotation), fling (momentum),
 *   tap (preview-only launch), and pinch/two-finger-drag (priority rotation channel)
 * - Two-finger drag is the "priority channel": the launcher ignores two-finger
 *   drags on the wallpaper surface, so the sphere rotates without fighting the
 *   launcher's swipe-up / swipe-left one-finger gesture claims.
 * - 3D ray picking via Camera.getPickRay() + Intersector for app selection
 *
 * ─── Command-Gated Launching ─────────────────────────────────────────────────
 *
 * On the real home screen, apps launch ONLY via {@link #onWallpaperTapCommand},
 * which is called by {@code AuraOrbitEngine.onCommand} when the launcher sends an
 * {@code android.wallpaper.tap} command.  Launchers send this command exclusively
 * for taps on empty workspace — taps consumed by the app drawer, icon grid, widgets,
 * or search bar never produce a wallpaper tap command.  This prevents the sphere from
 * launching apps when the user taps on drawer UI layered over the wallpaper.
 *
 * In the wallpaper-picker preview there is no launcher, so no commands arrive.
 * The GestureDetector tap() path is therefore active only in preview mode, allowing
 * tap-to-launch to be tested without going to the real home screen.
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
     * Grows with app count (0.52 × iconSize × √N) so sparse sets still look
     * like a sphere, never exceeds the user's sphereRadius slider value, and
     * never falls below 1.6 × iconSize. Used for ALL node placement and
     * depth-normalisation math.
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
    private Vector3[] nodePositions;       // Uniform-Fibonacci positions on effectiveRadius sphere

    /**
     * The master rotation quaternion for the entire sphere.
     * All node positions are transformed by this quaternion each frame.
     * Using quaternions prevents gimbal lock that would occur with
     * sequential Euler angle rotations (rotateX then rotateY).
     */
    private Quaternion sphereRotation;

    /**
     * Angular velocity vector for USER FLINGS ONLY. Each component represents
     * rotation speed (radians/sec) around that world axis. Applied to
     * sphereRotation each frame via quaternion pre-multiplication (mulLeft),
     * which keeps the rotation in world space regardless of accumulated
     * sphere orientation. Decays to zero via exponential friction after a fling.
     *
     * NOTE: idle auto-spin is NOT implemented through this vector. It uses a
     * separate idleBlend field and direct per-frame rotation to avoid the
     * pulse/stop/pulse artifact that impulse + friction creates.
     */
    private Vector3 angularVelocity;

    /**
     * Friction coefficient — multiplied against angularVelocity each frame.
     * 0.97 gives a smooth ~1 second glide stop at 120 FPS.
     * (0.97^120 ≈ 0.026, so velocity drops to 2.6% after 1 second)
     * Only applied to fling momentum; idle spin bypasses friction entirely.
     */
    private static final float FRICTION = 0.97f;

    /**
     * Below this velocity magnitude, snap fling momentum to zero to prevent
     * eternal micro-spinning that wastes GPU cycles.
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
     * Idle auto-rotation speed in radians/sec (world Y axis).
     * Applied as a friction-free constant rotation (separate from fling physics)
     * so the sphere spins continuously without any pulse/stop cycle.
     * Scaled by rotationSpeedFactor when applied.
     */
    private static final float IDLE_SPIN_SPEED = 0.15f;

    /**
     * Smooth blend factor for idle spin ramp-in (0..1).
     * Ramps from 0 to 1 over 1.5 seconds after IDLE_DELAY expires.
     * Prevents a visible jump when idle spin first engages after a fling stops.
     * Reset to 0 immediately on user touch so the ramp restarts cleanly.
     */
    private float idleBlend = 0f;

    // ─── Group Backdrop Meshes ──────────────────────────────────────────
    private Array<ModelInstance> groupBackdrops;
    private Array<Model> groupModels;  // Must be disposed

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

    /**
     * Whether the wallpaper is currently shown in the wallpaper-picker preview
     * (as opposed to the live home screen). Set by {@link #previewStateChange}.
     *
     * In preview mode, launcher tap commands are never sent (there is no launcher),
     * so the GestureDetector tap() path is the only way to test app launching.
     * On the real home screen, the command-gated path {@link #onWallpaperTapCommand}
     * is used instead and GestureDetector tap() is silenced.
     *
     * Default: false (assume home screen until told otherwise).
     */
    private boolean isPreviewMode = false;

    /**
     * Timestamp of the last successful app launch (from {@link System#currentTimeMillis()}).
     * Used to debounce double-fires: if a launch is requested within
     * {@link #LAUNCH_DEBOUNCE_MS} of the previous one it is silently ignored.
     * 0 means no previous launch this session.
     */
    private long lastLaunchTime = 0L;

    /**
     * Minimum milliseconds that must elapse between consecutive app launches.
     * Guards against the rare case where both the wallpaper tap command and
     * the GestureDetector tap() fire for the same physical tap (e.g. in preview).
     */
    private static final long LAUNCH_DEBOUNCE_MS = 500L;

    // ─── Reusable math objects (avoid GC pressure) ──────────────────────
    private final Vector3 tmpVec = new Vector3();
    private final Vector3 tmpVec2 = new Vector3();
    private final Quaternion tmpQuat = new Quaternion();
    private final Matrix4 tmpMat = new Matrix4();

    // ─── Interaction tracking ───────────────────────────────────────────
    private boolean userInteracting = false;
    private float idleTimer = 0f;
    private static final float IDLE_DELAY = 3f; // Seconds before auto-spin resumes

    // ─── Two-finger drag state ──────────────────────────────────────────
    /**
     * WHY TWO-FINGER DRAG EXISTS — Launcher gesture conflict:
     *
     * Android live wallpapers cannot consume single-pointer (one-finger) touch
     * events because the home screen launcher always claims them first:
     * swipe-up opens the app drawer, swipe-left opens Discover, etc. This makes
     * one-finger sphere rotation unreliable — the launcher fights the wallpaper
     * for every gesture.
     *
     * The launcher ignores two-finger drags on the home screen (it only acts on
     * them as pinch-zoom on its own views, which are not the wallpaper). The
     * AndroidLiveWallpaper backend forwards ALL pointer events to libGDX when
     * touch is enabled, so the wallpaper reliably receives two-finger drags.
     *
     * This makes two-finger drag the wallpaper's "priority channel" — a
     * dedicated gesture the launcher won't fight over.
     *
     * pinchActive: true while two pointers are down (between first pinch() and
     * pinchStop()). Used to gate out one-finger pan() so the two gestures
     * never double-apply rotation in the same frame.
     *
     * lastPinchMidX/Y: screen-space midpoint from the previous pinch() call,
     * used to compute the per-frame delta that drives rotation.
     */
    private boolean pinchActive = false;
    private float lastPinchMidX = 0f;
    private float lastPinchMidY = 0f;

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
        // angularVelocity is for fling momentum only; idle spin uses idleBlend
        angularVelocity = new Vector3(0f, 0f, 0f);

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

        // Active home-screen page for visibility fade.
        // User-facing value is 1-based (UI: 1 = first page, SeekBar range 1..9).
        // Internally we use 0-based index for all page-visibility math.
        // Default raw value 1 → internal 0 (first page).
        activePage = Math.max(0, prefs.getInt("pref_active_page", 1) - 1);

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
        sb.append(prefs.getInt("pref_active_page", 1)).append('|'); // raw 1-based value (UI default)

        // System-wallpaper mirror: changing the system wallpaper or granting
        // MANAGE_EXTERNAL_STORAGE must both trigger a background rebuild on next
        // resume(). getWallpaperId() needs no permission and is stable per-wallpaper.
        sb.append(AppFetcher.systemWallpaperId(context)).append('|');
        sb.append(AppFetcher.canReadSystemWallpaper(context));

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
     * - Uniform Fibonacci node distribution (recalculated with new effectiveRadius)
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

        // ─── Reload background: custom photo > system wallpaper mirror > gradient ──
        //
        // Priority chain:
        //   1. Custom photo (BackgroundStore.exists → loadBackgroundTexture)
        //   2. System wallpaper mirror (loadSystemWallpaperTexture) — requires
        //      MANAGE_EXTERNAL_STORAGE on API 30+; no-ops silently if absent.
        //   3. Procedural gradient (gradientTexture) — always available.
        //
        // showBackground pref controls whether ANY background photo is drawn.
        // When false, backgroundTexture stays null and only the gradient is used.
        if (showBackground) {
            if (BackgroundStore.exists(context)) {
                // User has uploaded a custom photo — use it exclusively.
                backgroundTexture = AppFetcher.loadBackgroundTexture(context);
            } else {
                // No custom photo — mirror the current system (static) wallpaper
                // so the live wallpaper appears transparent to the user.
                backgroundTexture = AppFetcher.loadSystemWallpaperTexture(context);
                // backgroundTexture may be null here (e.g. permission not granted);
                // renderBackground() falls through to the gradient automatically.
            }
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
    //  Uniform Fibonacci Sphere Distribution with Contiguous Group Assignment
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Distributes all N app nodes uniformly on the sphere using a single plain
     * Fibonacci sphere lattice, then assigns positions to nodes so that group
     * members occupy a contiguous patch of the lattice.
     *
     * ─── Why Plain Fibonacci (not slot+sunflower)? ────────────────────────
     *
     * The old slot+sunflower layout squeezed group members into spherical caps
     * whose radius was bounded by the slot-separation angle — causing members
     * to collide with neighboring slots' icons when groups were large. More
     * fundamentally, the sunflower sub-layout used different inter-icon spacing
     * inside a group than the global Fibonacci spacing, so apps had unequal
     * distances to their neighbors depending on whether they were grouped.
     *
     * The new approach: ALL N icons live on the SAME Fibonacci lattice, so
     * every icon has the same minimum angular separation to its nearest neighbor,
     * grouped or not.
     *
     * ─── Contiguous Group Assignment ─────────────────────────────────────
     *
     * We permute which lattice point each app gets (never moving the points):
     *
     *   1. Compute N Fibonacci unit directions as candidate positions.
     *   2. Sort groups in descending member-count order so large groups get
     *      first pick of the best-separated seed points.
     *   3. For each group: pick a seed = the unassigned lattice point with the
     *      maximum min-angular-distance to ALL already-assigned points (i.e.,
     *      the point that is farthest from everything assigned so far). For
     *      the very first group (nothing assigned yet) any point works — use
     *      index 0. Then greedily assign the (M−1) nearest unassigned points
     *      to the seed direction (by dot-product to seed). This yields a
     *      spatially contiguous patch of pristine lattice points.
     *   4. Ungrouped apps fill remaining lattice points in original index order.
     *   5. nodePositions[i] holds the position for appNodes.get(i)
     *      — the appNodes list order is never changed.
     *
     * O(N²·G) brute-force complexity is fine for N ≤ a few hundred.
     *
     * ─── Adaptive Radius ─────────────────────────────────────────────────
     *
     *   effectiveRadius = clamp(0.52 × iconSize × √N, 1.6×iconSize, sphereRadius)
     *
     * 0.52 gives more breathing room than the old 0.48 so icons are comfortably
     * spaced. The group-spread floor term is gone — no caps or sub-layouts any more.
     * computeCameraDistance() still uses sphereRadius (the slider) as its
     * reference, so the camera envelope is fixed.
     *
     * @param delta Frame time in seconds (1/120 at 120 FPS)
     */
    private void distributeNodesOnSphere() {
        int N = appNodes.size();
        nodePositions = new Vector3[N];

        if (N == 0) return;

        // ─── Compute effectiveRadius ──────────────────────────────────────
        // 0.52 (vs old 0.48) gives more breathing room per icon.
        // No group-spread floor — all icons are on the same lattice now.
        effectiveRadius = MathUtils.clamp(
                0.52f * iconSize * (float) Math.sqrt(N),
                1.6f * iconSize,
                sphereRadius);

        Log.d(TAG, "distributeNodesOnSphere: N=" + N + " effectiveRadius=" + effectiveRadius);

        if (N == 1) {
            // Edge case: single app → place at sphere front facing the camera.
            nodePositions[0] = new Vector3(0f, 0f, effectiveRadius);
            return;
        }

        // ─── Build the N Fibonacci unit directions ─────────────────────────
        // The golden angle (≈137.5°) ensures successive points are rotated by
        // the most irrational angle possible, preventing alignment patterns.
        float phi = (float) (Math.PI * (3f - Math.sqrt(5f)));
        Vector3[] fibDirs = new Vector3[N];
        for (int i = 0; i < N; i++) {
            float y = (N == 1) ? 0f : (1f - (i / (float) (N - 1)) * 2f);
            float radiusAtY = (float) Math.sqrt(Math.max(0f, 1f - y * y));
            float theta = phi * i;
            fibDirs[i] = new Vector3(
                    (float) Math.cos(theta) * radiusAtY,
                    y,
                    (float) Math.sin(theta) * radiusAtY
            );
        }

        // ─── Collect group memberships (descending by size for seed priority) ─
        // groupEntries: list of (groupId, [nodeIndices]) sorted largest first.
        LinkedHashMap<String, List<Integer>> groupMap = new LinkedHashMap<>();
        for (int i = 0; i < N; i++) {
            AppFetcher.AppNode node = appNodes.get(i);
            if (node.groupId != null) {
                groupMap.computeIfAbsent(node.groupId, k -> new ArrayList<>()).add(i);
            }
        }
        // Filter to groups with M >= 2 (singletons behave like ungrouped apps).
        List<Map.Entry<String, List<Integer>>> groupEntries = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : groupMap.entrySet()) {
            if (e.getValue().size() >= 2) groupEntries.add(e);
        }
        // Descending size so large groups get first pick of best-separated seeds.
        groupEntries.sort((a, b) -> b.getValue().size() - a.getValue().size());

        // ─── Permutation: assign a lattice index to each node index ──────────
        // positionFor[nodeIndex] = fibDirs index to use.
        int[] positionFor = new int[N];
        Arrays.fill(positionFor, -1);  // -1 = unassigned

        // Parallel: is lattice point i already claimed?
        boolean[] latticeClaimed = new boolean[N];

        // ─── Assign groups contiguously ───────────────────────────────────
        int totalAssigned = 0;
        for (Map.Entry<String, List<Integer>> entry : groupEntries) {
            List<Integer> members = entry.getValue();
            int M = members.size();

            // ── Choose seed: unassigned lattice point farthest from assigned ──
            // "Farthest" = maximizes min-dot-product distance to all assigned pts.
            // For the very first group (totalAssigned==0) no assigned pts exist;
            // use lattice index 0 as the seed (any choice is valid).
            int seedLattice;
            if (totalAssigned == 0) {
                seedLattice = 0;
            } else {
                // For each unassigned lattice point, compute its minimum dot
                // product to ALL already-assigned lattice directions. Choose the
                // unassigned point with the SMALLEST such dot product (maximum
                // angular separation from existing assignments = best isolation).
                seedLattice = -1;
                float bestMinDot = Float.MAX_VALUE; // smallest dot = most distant
                for (int li = 0; li < N; li++) {
                    if (latticeClaimed[li]) continue;
                    float minDot = Float.MAX_VALUE;
                    for (int lj = 0; lj < N; lj++) {
                        if (!latticeClaimed[lj]) continue;
                        float d = fibDirs[li].dot(fibDirs[lj]);
                        if (d < minDot) minDot = d;
                    }
                    // We want the unassigned point with the smallest minDot
                    // (smallest dot = largest angular distance from assigned set).
                    if (minDot < bestMinDot) {
                        bestMinDot = minDot;
                        seedLattice = li;
                    }
                }
                if (seedLattice < 0) seedLattice = 0; // fallback (shouldn't happen)
            }

            // ── Assign M nearest unassigned lattice points to the seed ────────
            // Sort all unassigned points by dot product to seed (largest = nearest).
            Vector3 seedDir = fibDirs[seedLattice];
            // Build a sorted list of (dot, latticeIndex) for all unassigned points.
            List<int[]> candidates = new ArrayList<>(N - totalAssigned);
            for (int li = 0; li < N; li++) {
                if (!latticeClaimed[li]) {
                    // Store as int[2]: [latticeIndex, Float.floatToIntBits(dot)]
                    // Use negative dot for ascending sort (largest dot first).
                    candidates.add(new int[]{li, Float.floatToIntBits(fibDirs[li].dot(seedDir))});
                }
            }
            // Sort descending by dot (nearest to seed first).
            candidates.sort((a, b) -> {
                float da = Float.intBitsToFloat(a[1]);
                float db = Float.intBitsToFloat(b[1]);
                return Float.compare(db, da);
            });

            // Assign first M candidates to this group's members.
            for (int k = 0; k < M && k < candidates.size(); k++) {
                int li = candidates.get(k)[0];
                int nodeIdx = members.get(k);
                positionFor[nodeIdx] = li;
                latticeClaimed[li] = true;
                totalAssigned++;
            }
        }

        // ─── Assign ungrouped apps to remaining lattice points ───────────────
        // Walk lattice indices in order; assign to ungrouped nodes in node order.
        int nextLattice = 0;
        for (int i = 0; i < N; i++) {
            if (positionFor[i] >= 0) continue; // already assigned by a group
            // Advance to next unclaimed lattice point.
            while (nextLattice < N && latticeClaimed[nextLattice]) nextLattice++;
            if (nextLattice < N) {
                positionFor[i] = nextLattice;
                latticeClaimed[nextLattice] = true;
                nextLattice++;
            }
        }

        // ─── Build nodePositions from the assignment ──────────────────────────
        for (int i = 0; i < N; i++) {
            int li = positionFor[i];
            if (li < 0) li = 0; // safety fallback
            nodePositions[i] = new Vector3(fibDirs[li]).scl(effectiveRadius);
        }

        Log.d(TAG, "Distributed " + N + " nodes uniformly (Fibonacci+contiguous group assignment),"
                + " groups=" + groupEntries.size());
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

            // Position at the uniform-Fibonacci point on the sphere
            decal.setPosition(nodePositions[i].x, nodePositions[i].y, nodePositions[i].z);

            decals.add(decal);
            decalNodeIndex.add(i); // record which node this decal belongs to
        }

        Log.d(TAG, "Created " + decals.size + " decals");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Group Convex-Hull Polygon Mesh Generation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds translucent colored convex-hull polygon patches behind each app group.
     *
     * ─── Visual Design ───────────────────────────────────────────────────
     *
     * Each group gets a semi-transparent polygon positioned at radius
     * 0.90 × effectiveRadius — slightly inside the icon sphere so patches appear
     * as colored cloth draped under the group's icons. The polygon precisely
     * covers the group (padded by 0.75×iconSize beyond the outermost icons).
     *
     * IntAttribute.CullFace=GL_NONE disables back-face culling so patches are
     * visible from both sides of the sphere — users can see where a group is
     * even when it is on the far side and can rotate toward it.
     *
     * ─── Why Gnomonic Projection? ─────────────────────────────────────────
     *
     * The convex hull of the group's icons must be computed in a flat 2D space.
     * Gnomonic projection maps geodesics (great-circle arcs on the sphere) to
     * straight lines in the plane, so the 2D convex hull of the projected
     * points IS the spherical convex hull of the original directions. For the
     * small angular extents of typical groups (≪ hemisphere), gnomonic
     * coordinates are nearly identical to simple tangent-plane projection,
     * and d·c > 0 always holds.
     *
     * ─── Algorithm ───────────────────────────────────────────────────────
     *
     *   1. Centroid direction c = normalized sum of member unit directions.
     *   2. Build tangent basis (t1, t2) at c.
     *   3. Gnomonic project each member d → (u, v): s = 1/(d·c), u=s(d·t1), v=s(d·t2).
     *   4. Andrew's monotone chain 2D convex hull on (u,v) points.
     *   5. Pad hull outward by pad = 0.75×iconSize/effectiveRadius.
     *   6. Subdivide each hull edge so no segment spans > 0.15 gnomonic units.
     *   7. Inverse-project boundary vertices back to sphere at 0.90R.
     *   8. Fan-triangulate from centroid vertex; add mid-ring for sphere-curvature.
     *   9. Material: group color at 32% alpha, GL_NONE cull face.
     *
     * ─── Geometry in sphere-local space ──────────────────────────────────
     *
     * All positions are in sphere-local coordinates (actual 3D positions), so
     * the per-frame transform is simply sphereRotation + pageVisibility scale
     * (identity base — no per-cap orientation matrix needed).
     */
    private void buildGroupBackdrops() {
        groupBackdrops = new Array<>();
        groupModels    = new Array<>();

        if (appNodes == null || appNodes.isEmpty()) return;

        // ─── Collect groups (M >= 2 only) ─────────────────────────────────
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

        ModelBuilder modelBuilder = new ModelBuilder();

        for (Map.Entry<String, List<Integer>> entry : groupSlots.entrySet()) {
            String groupId        = entry.getKey();
            List<Integer> indices = entry.getValue();
            int M = indices.size();
            if (M < 2) continue;

            String colorHex = groupColorMap.getOrDefault(groupId, "#FFFFFF");
            Color gdxColor  = parseHexColor(colorHex, 0.32f);

            // ── 1. Centroid direction c ────────────────────────────────────
            Vector3 c = new Vector3();
            for (int idx : indices) {
                c.add(nodePositions[idx]);
            }
            c.nor(); // unit direction toward group centroid on sphere

            // ── 2. Tangent basis (t1, t2) at c ────────────────────────────
            // Degenerate-safe: cross with Y unless c ≈ ±Y.
            Vector3 t1 = new Vector3();
            Vector3 t2 = new Vector3();
            if (Math.abs(c.y) < 0.9f) {
                t1.set(Vector3.Y).crs(c).nor();
            } else {
                t1.set(Vector3.X).crs(c).nor();
            }
            t2.set(c).crs(t1).nor();

            // ── 3. Gnomonic projection: member directions → (u, v) ──────────
            // For direction d: scale s = 1/(d·c); u = s*(d·t1); v = s*(d·t2).
            // Guard d·c < 0.1 to avoid near-zero division (should never trigger
            // for compact groups, but be defensive).
            float[] us = new float[M];
            float[] vs = new float[M];
            for (int k = 0; k < M; k++) {
                Vector3 d = new Vector3(nodePositions[indices.get(k)]).nor();
                float dot = d.dot(c);
                if (dot < 0.1f) dot = 0.1f;
                float s = 1f / dot;
                us[k] = s * d.dot(t1);
                vs[k] = s * d.dot(t2);
            }

            // ── 4. 2D convex hull via Andrew's monotone chain ─────────────
            // Returns indices into us[]/vs[] in CCW order.
            int[] hullIdx = convexHull2D(us, vs, M);

            // ── 5. Pad hull outward from 2D centroid ──────────────────────
            // pad in gnomonic units ≈ radians for small angles.
            float pad = (0.75f * iconSize) / effectiveRadius;

            // Compute 2D hull centroid.
            float cu2d = 0f, cv2d = 0f;
            for (int hi : hullIdx) { cu2d += us[hi]; cv2d += vs[hi]; }
            cu2d /= hullIdx.length;
            cv2d /= hullIdx.length;

            // Degenerate capsule path: M==2 or collinear (hull has < 3 points).
            float[] hullU, hullV;
            if (hullIdx.length < 3) {
                // Generate stadium/capsule: 8 circle points around each endpoint.
                int nCap = 8;
                hullU = new float[nCap * M];
                hullV = new float[nCap * M];
                int out = 0;
                for (int k = 0; k < M; k++) {
                    for (int a = 0; a < nCap; a++) {
                        float ang = a * MathUtils.PI2 / nCap;
                        hullU[out]   = us[k] + pad * MathUtils.cos(ang);
                        hullV[out++] = vs[k] + pad * MathUtils.sin(ang);
                    }
                }
                // Re-hull the circle points.
                int[] rehull = convexHull2D(hullU, hullV, out);
                float[] hu2 = new float[rehull.length];
                float[] hv2 = new float[rehull.length];
                for (int k = 0; k < rehull.length; k++) {
                    hu2[k] = hullU[rehull[k]];
                    hv2[k] = hullV[rehull[k]];
                }
                hullU = hu2; hullV = hv2;
            } else {
                // Pad normal hull outward.
                hullU = new float[hullIdx.length];
                hullV = new float[hullIdx.length];
                for (int k = 0; k < hullIdx.length; k++) {
                    float pu = us[hullIdx[k]] - cu2d;
                    float pv = vs[hullIdx[k]] - cv2d;
                    float len = (float) Math.sqrt(pu * pu + pv * pv);
                    if (len < 1e-6f) { pu = 1f; pv = 0f; len = 1f; }
                    hullU[k] = us[hullIdx[k]] + pad * pu / len;
                    hullV[k] = vs[hullIdx[k]] + pad * pv / len;
                }
            }

            // ── 6. Subdivide hull edges so no segment > 0.15 gnomonic units ─
            List<Float> bdryU = new ArrayList<>();
            List<Float> bdryV = new ArrayList<>();
            int Hlen = hullU.length;
            float maxSegment = 0.15f;
            for (int k = 0; k < Hlen; k++) {
                float au = hullU[k], av = hullV[k];
                float bu = hullU[(k + 1) % Hlen], bv = hullV[(k + 1) % Hlen];
                float segLen = (float) Math.sqrt((bu - au) * (bu - au) + (bv - av) * (bv - av));
                int segs = Math.max(1, (int) Math.ceil(segLen / maxSegment));
                for (int s = 0; s < segs; s++) {
                    float t = s / (float) segs;
                    bdryU.add(au + t * (bu - au));
                    bdryV.add(av + t * (bv - av));
                }
            }
            int B = bdryU.size(); // boundary vertex count after subdivision

            // ── 7. Inverse-project boundary + centroid to sphere ──────────
            // dir = normalize(c + u*t1 + v*t2), placed at 0.90*effectiveRadius.
            float patchR = 0.90f * effectiveRadius;

            // Boundary ring (outer) at patchR.
            Vector3[] outerRing = new Vector3[B];
            for (int k = 0; k < B; k++) {
                float u = bdryU.get(k), v = bdryV.get(k);
                outerRing[k] = new Vector3(
                        c.x + u * t1.x + v * t2.x,
                        c.y + u * t1.y + v * t2.y,
                        c.z + u * t1.z + v * t2.z
                ).nor().scl(patchR);
            }

            // Mid-ring: half the gnomonic radius of each boundary point,
            // to follow sphere curvature instead of sagging flat.
            Vector3[] midRing = new Vector3[B];
            for (int k = 0; k < B; k++) {
                float u = bdryU.get(k) * 0.5f, v = bdryV.get(k) * 0.5f;
                midRing[k] = new Vector3(
                        c.x + u * t1.x + v * t2.x,
                        c.y + u * t1.y + v * t2.y,
                        c.z + u * t1.z + v * t2.z
                ).nor().scl(patchR);
            }

            // Centre vertex at patchR along the centroid direction.
            Vector3 centreVert = new Vector3(c).scl(patchR);

            // ── 8. Build mesh: fan from centre through mid-ring to outer ring ─
            // Usage.Position | Usage.Normal.
            int attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;

            // Total vertices: 1 (centre) + B (mid) + B (outer) = 2B+1
            // Triangles:
            //   Inner fan (centre → mid):   B triangles
            //   Annular band (mid → outer): B*2 triangles (2 per quad)
            // Total: 3B triangles = 9B indices.

            Material material = new Material(
                    ColorAttribute.createDiffuse(gdxColor),
                    new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 0.32f),
                    new DepthTestAttribute(GL20.GL_LEQUAL, false),
                    IntAttribute.createCullFace(GL20.GL_NONE)
            );

            modelBuilder.begin();
            MeshPartBuilder pb = modelBuilder.part(
                    "hull_" + groupId,
                    GL20.GL_TRIANGLES,
                    attributes,
                    material
            );

            // Helper: normal = radial (outward from sphere centre).
            // Vertex layout: index 0 = centre; 1..B = mid-ring; B+1..2B = outer ring.
            short vCentre = addVertex(pb, centreVert);

            short[] vMid   = new short[B];
            short[] vOuter = new short[B];
            for (int k = 0; k < B; k++) {
                vMid[k]   = addVertex(pb, midRing[k]);
                vOuter[k] = addVertex(pb, outerRing[k]);
            }

            // Inner fan: centre → mid-ring triangles.
            for (int k = 0; k < B; k++) {
                int next = (k + 1) % B;
                pb.triangle(vCentre, vMid[k], vMid[next]);
            }

            // Annular band: mid-ring quads (2 triangles each).
            for (int k = 0; k < B; k++) {
                int next = (k + 1) % B;
                pb.triangle(vMid[k],   vOuter[k],    vOuter[next]);
                pb.triangle(vMid[k],   vOuter[next], vMid[next]);
            }

            Model model = modelBuilder.end();
            groupModels.add(model);
            groupBackdrops.add(new ModelInstance(model));

            Log.d(TAG, "Group '" + groupId + "': " + M + " apps, hull boundary=" + B
                    + " verts, centroid=" + c);
        }
    }

    /**
     * Adds a single vertex to a MeshPartBuilder at the given 3D position,
     * with a sphere-radial (outward-from-origin) normal and zero UV.
     * Returns the short vertex index for use in triangle() calls.
     */
    private static short addVertex(MeshPartBuilder pb, Vector3 pos) {
        Vector3 nor = new Vector3(pos).nor();
        return pb.vertex(pos, nor, null, null);
    }

    /**
     * Andrew's Monotone Chain — 2D convex hull algorithm.
     *
     * Computes the convex hull of M 2D points given as parallel float arrays
     * (xs, ys). Returns an array of indices into xs/ys in CCW order.
     *
     * Handles degenerate cases (M==1 → [0], M==2 → [0,1], all-collinear →
     * returns the two extremes). The algorithm is O(M log M).
     *
     * @param xs  X-coordinates (length >= M)
     * @param ys  Y-coordinates (length >= M)
     * @param M   Number of active points
     * @return    Array of indices in CCW convex hull order
     */
    private static int[] convexHull2D(float[] xs, float[] ys, int M) {
        if (M == 0) return new int[0];
        if (M == 1) return new int[]{0};
        if (M == 2) return new int[]{0, 1};

        // Build index array sorted by (x asc, y asc).
        Integer[] idx = new Integer[M];
        for (int i = 0; i < M; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> {
            int c = Float.compare(xs[a], xs[b]);
            return c != 0 ? c : Float.compare(ys[a], ys[b]);
        });

        int[] hull = new int[2 * M];
        int k = 0;

        // Lower hull.
        for (int i = 0; i < M; i++) {
            while (k >= 2 && cross2D(xs, ys, hull[k-2], hull[k-1], idx[i]) <= 0) k--;
            hull[k++] = idx[i];
        }
        // Upper hull.
        for (int i = M - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross2D(xs, ys, hull[k-2], hull[k-1], idx[i]) <= 0) k--;
            hull[k++] = idx[i];
        }

        return Arrays.copyOf(hull, k - 1);
    }

    /** Cross product of vectors (O→A) and (O→B) in 2D (using index arrays). */
    private static float cross2D(float[] xs, float[] ys, int o, int a, int b) {
        return (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o]);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Input Setup — GestureDetector for spin, tap, fling
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Configures input handling with a subclassed GestureDetector for:
     * - **Pan** (one-finger): Converts 2D screen drag into 3D sphere rotation
     * - **Fling**: Applies angular momentum for inertial spinning
     * - **Tap**: Raycasts to detect which app was tapped and launches it
     * - **Pinch / two-finger drag**: Priority rotation channel — uses the
     *   midpoint delta of two pointers to rotate the sphere.  The launcher
     *   ignores two-finger drags on the wallpaper, so this gesture is never
     *   contested.  One-finger pan() is suppressed while pinchActive is true
     *   to prevent double-application.
     *
     * ─── Why subclass GestureDetector? ──────────────────────────────────────
     *
     * GestureDetector.touchCancelled() (verified in libGDX 1.13.0 bytecode)
     * calls only cancel() internally, which clears longPressFired but does NOT
     * clear the internal `pinching` flag.  Separately, reset() clears panning
     * and inTapRectangle but also does NOT clear `pinching`.  Two paths that
     * bypass pinchStop() are therefore:
     *   • touchCancelled (fires on notification-shade pull, system gesture, etc.)
     *   • reset()
     * Without interception our pinchActive would stay true forever after any
     * of these paths, permanently killing one-finger pan.  The subclass below
     * intercepts both touchCancelled and touchDown so the outer pinchActive
     * flag is always consistent with the actual touch state.
     */
    private void setupInput() {
        // Subclass GestureDetector to intercept touchCancelled and touchDown
        // before the gesture listener sees them.  Signature verified against
        // libGDX 1.13.0 decompiled bytecode:
        //   touchCancelled(int screenX, int screenY, int pointer, int button)
        //   touchDown(int screenX, int screenY, int pointer, int button)
        GestureDetector gestureDetector = new GestureDetector(new GestureDetector.GestureAdapter() {

            /**
             * ─── TAP → App Launch (preview mode only) ───────────────────
             *
             * On the real home screen, launching is gated on the
             * {@code android.wallpaper.tap} command (see
             * {@link #onWallpaperTapCommand}).  Launchers send that command
             * only for taps on empty workspace — taps consumed by the app
             * drawer, icon grid, widgets, or search bar never produce a
             * wallpaper tap command.  Allowing GestureDetector tap() to
             * launch on the home screen would let drawer-UI taps
             * accidentally reach sphere apps underneath.
             *
             * In the wallpaper-picker preview there is no launcher and no
             * commands arrive, so the GestureDetector path is the only
             * way to exercise tap-to-launch there.
             */
            @Override
            public boolean tap(float x, float y, int count, int button) {
                // ─── Home screen: command path only ────────────────────
                // On the real home screen the launcher sends wallpaper tap
                // commands; GestureDetector tap() must not launch here.
                if (!isPreviewMode) return false;

                // ─── Preview: run the raycast+launch directly ───────────
                return raycastAndLaunch(x, y);
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
                // ─── Two-finger drag has priority — suppress one-finger pan ─
                // GestureDetector may still fire pan() for the first pointer
                // while a two-finger drag is active.  We must ignore it here
                // so the two code paths never double-apply rotation in the
                // same frame (the pinch() callback handles all rotation while
                // two fingers are down).
                if (pinchActive) return false;

                userInteracting = true;
                // Reset idle spin completely — ramp restarts from zero on next release.
                idleTimer = 0f;
                idleBlend = 0f;

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

                // Kill any existing fling momentum while dragging
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
             * Idle spin will ramp back in (idleBlend → 1) after IDLE_DELAY
             * once the fling decays.
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

            /**
             * ─── PINCH (two-finger drag) → Sphere Rotation ───────────────
             *
             * WHY: The launcher fights one-finger drags on the home screen
             * (swipe-up = app drawer, swipe-left = Discover).  Two-finger
             * drags are ignored by the launcher on its wallpaper surface, so
             * this is the wallpaper's "priority channel" — a dedicated gesture
             * that gives the sphere exclusive control without launcher conflict.
             *
             * libGDX's GestureDetector calls pinch() continuously while two
             * pointers are down and at least one is moving.  We track the
             * midpoint of the two pointers and rotate the sphere by the
             * midpoint delta each call — exactly the same world-space
             * pre-multiplication used by pan() for frame-rate independence.
             *
             * On the FIRST call of a gesture (pinchActive == false) we just
             * record the midpoint and arm the state; no rotation is applied
             * (there is no previous midpoint to delta from yet).
             *
             * No per-call allocations: midpoint arithmetic uses only the four
             * float parameters; no Vector2 objects are created here.
             */
            @Override
            public boolean pinch(Vector2 initialPointer1, Vector2 initialPointer2,
                                 Vector2 pointer1, Vector2 pointer2) {
                // Midpoint of the two current pointer positions (screen pixels).
                float midX = (pointer1.x + pointer2.x) * 0.5f;
                float midY = (pointer1.y + pointer2.y) * 0.5f;

                if (!pinchActive) {
                    // First call of this two-finger gesture — arm state, no rotation.
                    pinchActive = true;
                    lastPinchMidX = midX;
                    lastPinchMidY = midY;
                    return true;
                }

                // Delta since the previous pinch() call (screen pixels).
                float deltaX = midX - lastPinchMidX;
                float deltaY = midY - lastPinchMidY;

                // Rotate sphere using the same sensitivity and world-space
                // pre-multiplication as the one-finger pan() handler so the
                // feel is identical regardless of which gesture is used.
                float angleY = -deltaX * ROTATION_SENSITIVITY;
                float angleX =  deltaY * ROTATION_SENSITIVITY;

                tmpQuat.setFromAxis(Vector3.Y, (float) Math.toDegrees(angleY));
                sphereRotation.mulLeft(tmpQuat);

                tmpQuat.setFromAxis(Vector3.X, (float) Math.toDegrees(angleX));
                sphereRotation.mulLeft(tmpQuat);

                // Mark as interacting: suppresses idle spin and resets its ramp.
                userInteracting = true;
                idleTimer = 0f;
                idleBlend = 0f;

                // Kill any existing fling momentum while dragging.
                angularVelocity.setZero();

                // Advance midpoint for next delta calculation.
                lastPinchMidX = midX;
                lastPinchMidY = midY;

                return true;
            }

            /**
             * ─── PINCH STOP → Disarm two-finger state ────────────────────
             *
             * Called by GestureDetector when a finger lifts during a pinch
             * gesture.  Clearing pinchActive here re-enables one-finger pan()
             * and ensures any subsequent single-finger gesture is processed
             * normally.  userInteracting is also cleared so the idle-spin
             * ramp resumes after the interaction ends (same as panStop).
             */
            @Override
            public void pinchStop() {
                pinchActive = false;
                userInteracting = false;
            }
        }) {
            /**
             * Fix 1a + Fix 2 — override touchDown (pointer 0 = new gesture start).
             *
             * When pointer 0 goes down it signals the start of a new gesture
             * sequence.  Any stale pinchActive from a previous aborted two-finger
             * drag must be cleared now so the very first pinch() of the NEW gesture
             * records a fresh midpoint instead of computing a delta against a
             * potentially stale one hundreds of pixels away (Fix 2 rotation jolt).
             *
             * Delegates to super so GestureDetector's own touchDown logic runs.
             */
            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                if (pointer == 0) {
                    // New gesture starts: clear any stale pinch state so the next
                    // pinch() re-arms from the current midpoint (no rotation jolt).
                    pinchActive = false;
                }
                return super.touchDown(screenX, screenY, pointer, button);
            }

            /**
             * Fix 1a — override touchCancelled to clear pinchActive.
             *
             * libGDX 1.13.0 GestureDetector.touchCancelled() calls only cancel()
             * (which clears longPressFired only) then delegates to InputAdapter —
             * it does NOT clear the internal pinching flag.  On real hardware,
             * touchCancelled fires on: notification-shade pull, system gesture
             * interception (e.g. back/home swipe), and window switches.  Without
             * this override, pinchActive would stay true forever after any such
             * event, making one-finger pan permanently unresponsive.
             *
             * Delegates to super so GestureDetector's own cancel path also runs.
             */
            @Override
            public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
                // Clear our outer pinch state that super cannot reach.
                pinchActive = false;
                userInteracting = false;
                return super.touchCancelled(screenX, screenY, pointer, button);
            }
        };

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

        // ─── Fix 1b: Defensive pinch-state self-heal ─────────────────────
        // If pinchActive is true but the second pointer is no longer down,
        // a touchCancelled or other event was missed (e.g. reset() path in
        // GestureDetector that does not clear its pinching flag).  Clear
        // now so one-finger pan is never permanently blocked.
        if (pinchActive && !Gdx.input.isTouched(1)) {
            pinchActive = false;
            userInteracting = false;
        }

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
    //  Physics Update — Fling friction + friction-free idle spin
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Updates the sphere's rotation state each frame.
     *
     * ─── Two independent rotation contributions ────────────────────────────
     *
     * 1. FLING MOMENTUM (angularVelocity):
     *    Angular velocity set by user flings; decays via exponential friction
     *    (FRICTION^(delta*120)) and snaps to zero below VELOCITY_EPSILON.
     *    Applied via pre-multiplication (world space) each frame.
     *
     * 2. IDLE SPIN (idleBlend):
     *    Completely friction-free constant Y-axis rotation that engages after
     *    IDLE_DELAY seconds of no user interaction. idleBlend ramps from 0→1
     *    over 1.5 seconds (smooth ramp-in, no visible jump) and is reset to 0
     *    immediately when the user touches the screen.
     *    Applied via a direct per-frame angle = IDLE_SPIN_SPEED * rotationSpeedFactor
     *    * idleBlend * delta (radians).
     *
     * Both contributions are applied the same frame — fling decays away naturally
     * while idle ramps in, so the transitions are smooth and continuous.
     *
     * ─── Why not implement idle via angularVelocity + impulse? ────────────
     *
     * Setting angularVelocity.y = IDLE_SPIN_SPEED in the impulse-rearming
     * pattern triggers an immediate impulse that the per-frame friction then
     * decays within ~0.7 s, causing a visible pulse/stop/pulse cycle. Separating
     * idle spin from fling physics entirely eliminates that artifact.
     *
     * ─── Why mulLeft (pre-multiply)? ──────────────────────────────────────
     *
     * The angular velocity vector and idle axis are defined in world space.
     * Pre-multiplying ensures the rotation always acts on world axes, consistent
     * with the pan handler. Post-multiplying would rotate in the sphere's local
     * space, causing the auto-spin axis to drift as orientation accumulates.
     *
     * @param delta Frame time in seconds (1/120 at 120 FPS)
     */
    private void updatePhysics(float delta) {
        // ─── 1. Fling momentum (with friction) ──────────────────────────
        float speed = angularVelocity.len();
        if (speed > VELOCITY_EPSILON) {
            // Apply fling rotation: angle = speed * delta radians this frame.
            float angle = speed * delta;
            tmpVec.set(angularVelocity).nor();
            tmpQuat.setFromAxis(tmpVec, (float) Math.toDegrees(angle));

            // Pre-multiply: apply in WORLD space.
            sphereRotation.mulLeft(tmpQuat);

            // Normalize to prevent floating-point drift.
            sphereRotation.nor();

            // Exponential friction: v *= friction^(delta * 120).
            // The exponent normalizes friction to feel consistent at any frame rate.
            float frictionThisFrame = (float) Math.pow(FRICTION, delta * 120.0);
            angularVelocity.scl(frictionThisFrame);

            // Snap to zero below epsilon to avoid eternal micro-spinning.
            if (angularVelocity.len2() < VELOCITY_EPSILON * VELOCITY_EPSILON) {
                angularVelocity.setZero();
            }
        }

        // ─── 2. Idle spin (friction-free, separate from fling physics) ──
        // Only runs when user is not touching the screen.
        if (!userInteracting) {
            idleTimer += delta;

            if (idleTimer > IDLE_DELAY) {
                // Smooth ramp-in over 1.5 s so there is no visible jump when
                // idle spin first engages (e.g. just after a fling decays).
                idleBlend = Math.min(1f, idleBlend + delta / 1.5f);

                if (idleBlend > 0f) {
                    // Constant Y-axis rotation, scaled by user speed preference.
                    // angle in radians = IDLE_SPIN_SPEED * speedFactor * blend * delta.
                    float idleAngleRad = IDLE_SPIN_SPEED * rotationSpeedFactor * idleBlend * delta;
                    tmpQuat.setFromAxis(Vector3.Y, (float) Math.toDegrees(idleAngleRad));
                    // Pre-multiply: world-space Y so it never drifts.
                    sphereRotation.mulLeft(tmpQuat);
                    sphereRotation.nor();
                }
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
     * something — applying the priority chain:
     *   1. Custom photo (BackgroundStore) if one has been uploaded.
     *   2. System wallpaper mirror (WallpaperManager) to approximate transparency
     *      when no custom photo is set.
     *   3. Procedural gradient (gradientTexture) as the final fallback.
     *
     * backgroundTexture holds whichever of (1) or (2) was loaded by applyConfig();
     * when null, the gradient is used. showBackground=false keeps backgroundTexture
     * null so only the gradient is drawn.
     *
     * Uses SpriteBatch which internally sets up an orthographic projection,
     * unaffected by our 3D PerspectiveCamera. The depth buffer is temporarily
     * disabled so the background is always behind everything.
     *
     * The gradient texture (1×256) is always stretched full-screen — that is
     * correct since it is a uniform gradient with no meaningful aspect ratio.
     * The backgroundTexture (custom photo or system wallpaper) is center-cropped
     * (aspect-fill) so
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
    //  Render Layer 2 — Group Convex-Hull Polygon Patches
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Renders the translucent colored convex-hull polygon patches behind grouped
     * app clusters.
     *
     * Each patch's transform is the sphere rotation matrix scaled by pageVisibility.
     * No per-patch base-orientation matrix is needed because the patch geometry is
     * built in sphere-local coordinates (actual 3D positions) — the rotation
     * matrix alone is sufficient to rotate them with the sphere.
     */
    private void renderGroupBackdrops() {
        // Build the sphere-rotation matrix once per frame (avoids per-patch alloc).
        tmpMat.set(sphereRotation);

        modelBatch.begin(camera);

        for (int i = 0; i < groupBackdrops.size; i++) {
            ModelInstance instance = groupBackdrops.get(i);

            // Compose: sphereRotation, then scale for page visibility.
            // Identity base — geometry is already in sphere-local coordinates.
            instance.transform.set(tmpMat).scl(pageVisibility);

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
        // Track preview mode so tap() knows whether to launch (no command path in preview).
        this.isPreviewMode = isPreview;
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

    // ═══════════════════════════════════════════════════════════════════════
    //  Launch Logic — shared by command path and preview tap path
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Raycasts from the camera through screen point (x, y) and launches the
     * closest intersected app icon, if any.
     *
     * <p>Must be called on the GL thread — it reads {@link #camera},
     * {@link #appNodes}, {@link #nodePositions}, and {@link #sphereRotation}
     * which are all GL-thread-owned.
     *
     * <p>The debounce guard ({@link #LAUNCH_DEBOUNCE_MS}) prevents double-fires
     * that could theoretically occur if the same tap triggers both this method
     * (via {@link #onWallpaperTapCommand}) and the preview GestureDetector path.
     *
     * @param x  Screen-space X coordinate (pixels, origin top-left)
     * @param y  Screen-space Y coordinate (pixels, origin top-left)
     * @return   {@code true} if an app was launched, {@code false} otherwise
     */
    private boolean raycastAndLaunch(float x, float y) {
        if (appNodes == null || appNodes.isEmpty()) return false;

        // ─── Ignore taps when sphere is mostly invisible ──────────────
        // The sphere fully fades to 0 on non-active pages; tapping through
        // it would silently launch apps the user cannot see.
        if (pageVisibility < 0.5f) return false;

        // ─── Debounce: ignore rapid double-fire within 500 ms ─────────
        long now = System.currentTimeMillis();
        if (now - lastLaunchTime < LAUNCH_DEBOUNCE_MS) return false;

        // ─── Cast a pick ray from camera through screen point ─────────
        // camera.getPickRay expects screen coordinates with origin top-left.
        // GestureDetector and wallpaper tap commands both provide surface-
        // relative pixels with the same origin, so no conversion is needed.
        Ray pickRay = camera.getPickRay(x, y);

        int closestIdx = -1;
        float closestDist = Float.MAX_VALUE;

        // ─── Test intersection with each app node ──────────────────────
        // Each node is treated as a sphere with radius = iconSize * 0.6f
        // (slightly larger than the visual for forgiving tap targets).
        float hitRadius = iconSize * 0.6f;

        for (int i = 0; i < appNodes.size(); i++) {
            Vector3 rotatedPos = getRotatedPosition(i);

            if (Intersector.intersectRaySphere(pickRay, rotatedPos, hitRadius, tmpVec)) {
                // Squared distance avoids sqrt per node.
                float dist = pickRay.origin.dst2(tmpVec);
                if (dist < closestDist) {
                    closestDist = dist;
                    closestIdx = i;
                }
            }
        }

        // ─── Launch the tapped app ─────────────────────────────────────
        if (closestIdx >= 0) {
            AppFetcher.AppNode tappedNode = appNodes.get(closestIdx);
            Log.i(TAG, "Launching app: " + tappedNode.appName
                    + " (" + tappedNode.packageName + ")");

            lastLaunchTime = now;
            final String pkg = tappedNode.packageName;
            // AppFetcher.launchApp must run on the UI (main) thread;
            // postRunnable marshals from GL thread to main thread.
            Gdx.app.postRunnable(() -> AppFetcher.launchApp(context, pkg));

            return true;
        }

        return false;
    }

    /**
     * Called from {@code MyWallpaperService.AuraOrbitEngine.onCommand} when
     * the launcher sends an {@code android.wallpaper.tap} command.
     *
     * <p>Launchers send {@code android.wallpaper.tap} exclusively for taps on
     * empty workspace — taps consumed by the app drawer, icon grid, widgets,
     * or the search bar never produce this command.  Gating launching on this
     * command therefore prevents the sphere from launching apps when the user
     * taps on drawer UI layered over the wallpaper.
     *
     * <p>The command arrives on the Android main thread; the raycast must run
     * on the libGDX GL thread.  {@code Gdx.app.postRunnable} performs that
     * marshal safely.
     *
     * <p>Coordinate spaces: the command x/y are surface-relative pixels with
     * top-left origin, identical to the coordinates that GestureDetector passes
     * to {@code tap()} — both are directly suitable for
     * {@code camera.getPickRay(x, y)}.
     *
     * @param x  Surface-relative X coordinate in pixels (top-left origin)
     * @param y  Surface-relative Y coordinate in pixels (top-left origin)
     */
    public void onWallpaperTapCommand(int x, int y) {
        // Marshal from main thread to GL thread for raycast safety.
        final float fx = x;
        final float fy = y;
        Gdx.app.postRunnable(() -> raycastAndLaunch(fx, fy));
    }

    /**
     * Called from MyWallpaperService when wallpaper visibility changes.
     * When not visible, we skip the render loop entirely.
     *
     * Fix 1c: gesture state must not survive visibility edges.  A two-finger
     * drag that was in progress when the wallpaper became invisible will never
     * receive a touchCancelled or pinchStop on some devices (the events are
     * swallowed by the system).  Clearing here prevents pinchActive from
     * getting stuck true across a visibility change.
     */
    public void setVisible(boolean visible) {
        this.isVisible = visible;
        if (!visible) {
            // Gesture state must not survive across visibility edges (Fix 1c).
            pinchActive = false;
            userInteracting = false;
        }
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
        // Live wallpaper is going to background — reduce state.
        // Fix 1c: clear gesture state so pinchActive cannot survive a pause/resume
        // cycle (the paired finger-up events are dropped when the window loses focus).
        pinchActive = false;
        userInteracting = false;
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
