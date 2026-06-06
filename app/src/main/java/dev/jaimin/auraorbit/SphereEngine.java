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
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
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
import com.badlogic.gdx.utils.Array;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *    Renders the captured system wallpaper as a full-screen quad behind
 *    the 3D scene. Uses orthographic projection within SpriteBatch.
 *
 * 2. **Group Backdrop Layer** (ModelBatch, 3D)
 *    Renders translucent colored spherical cap meshes behind each app
 *    group cluster. These meshes are parented to the sphere's rotation
 *    transform so they rotate with the apps.
 *
 * 3. **App Icon Layer** (DecalBatch, 3D billboarded)
 *    Renders app icons as 2D textured decals positioned in 3D space on
 *    the sphere surface. Each decal uses lookAt() billboarding to always
 *    face the camera.
 *
 * ─── Physics ────────────────────────────────────────────────────────────────
 *
 * - Rotation uses Quaternions exclusively (no Euler angles → no Gimbal Lock)
 * - Angular velocity with exponential friction for smooth spin deceleration
 * - All math is delta-time dependent for frame-rate independence
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
    private SpriteBatch spriteBatch;       // For 2D background wallpaper
    private DecalBatch decalBatch;         // For 3D billboarded app icons
    private ModelBatch modelBatch;         // For 3D group backdrop meshes

    // ─── Background Wallpaper ───────────────────────────────────────────
    private Texture backgroundTexture;     // System wallpaper as texture
    private boolean keepSystemWallpaper;   // User preference toggle

    // ─── Sphere State ───────────────────────────────────────────────────
    private float sphereRadius;            // Configurable sphere size
    private float iconSize;                // Configurable icon dimensions
    private List<AppFetcher.AppNode> appNodes;  // The loaded app data
    private Array<Decal> decals;           // libGDX decals for each app
    private Vector3[] nodePositions;       // Fibonacci-distributed positions

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
     * each frame via quaternion multiplication.
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
     * Sensitivity multiplier for fling-to-spin. Converts fling velocity
     * (pixels/sec) into angular velocity (radians/sec).
     */
    private static final float FLING_SENSITIVITY = 0.002f;

    /**
     * Default auto-rotation speed when user isn't interacting.
     * A gentle Y-axis spin to keep the wallpaper alive.
     */
    private static final float IDLE_SPIN_SPEED = 0.15f;

    // ─── Group Backdrop Meshes ──────────────────────────────────────────
    private Array<ModelInstance> groupBackdrops;
    private Array<Model> groupModels;  // Must be disposed
    private Array<Vector3> groupCentroids; // Original centroid positions (pre-rotation)

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
    private final Matrix4 tmpMatrix = new Matrix4();

    // ─── Interaction tracking ───────────────────────────────────────────
    private boolean userInteracting = false;
    private float idleTimer = 0f;
    private static final float IDLE_DELAY = 3f; // Seconds before auto-spin resumes

    // ═══════════════════════════════════════════════════════════════════════
    //  Constructor
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * @param context The Android context (WallpaperService). Used for
     *                PackageManager, WallpaperManager, SharedPreferences.
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

        // ─── Read user preferences ──────────────────────────────────────
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        keepSystemWallpaper = prefs.getBoolean("pref_keep_wallpaper", true);
        activePage = prefs.getInt("pref_active_page", 0);

        // Sphere radius: pref value 20–100 mapped to world units 3.0–8.0
        int radiusPref = prefs.getInt("pref_sphere_radius", 50);
        sphereRadius = MathUtils.lerp(3.0f, 8.0f, radiusPref / 100f);

        // Icon size: pref value 20–100 mapped to world units 0.6–2.0
        int iconPref = prefs.getInt("pref_icon_size", 50);
        iconSize = MathUtils.lerp(0.6f, 2.0f, iconPref / 100f);

        Log.i(TAG, "Config — radius: " + sphereRadius + ", iconSize: " + iconSize
                + ", keepWallpaper: " + keepSystemWallpaper + ", activePage: " + activePage);

        // ─── Setup 3D camera ────────────────────────────────────────────
        // PerspectiveCamera with 67° FOV — standard for immersive 3D.
        // Position the camera far enough back that the entire sphere fits
        // in view with some margin for the icons.
        camera = new PerspectiveCamera(67f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.position.set(0f, 0f, sphereRadius * 2.8f);
        camera.lookAt(0f, 0f, 0f);
        camera.near = 0.1f;
        camera.far = 100f;
        camera.update();

        // ─── Initialize rendering systems ───────────────────────────────
        spriteBatch = new SpriteBatch();
        decalBatch = new DecalBatch(new CameraGroupStrategy(camera));
        modelBatch = new ModelBatch();

        // ─── Initialize physics state ───────────────────────────────────
        sphereRotation = new Quaternion().idt(); // Identity = no rotation
        angularVelocity = new Vector3(0f, IDLE_SPIN_SPEED, 0f); // Gentle initial spin

        // ─── Load app data ──────────────────────────────────────────────
        appNodes = AppFetcher.fetchSelectedApps(context);

        // ─── Distribute icons on sphere using Fibonacci algorithm ───────
        distributeNodesOnSphere();

        // ─── Create decals for each app icon ────────────────────────────
        createDecals();

        // ─── Build group backdrop meshes ────────────────────────────────
        buildGroupBackdrops();

        // ─── Load background wallpaper if enabled ───────────────────────
        if (keepSystemWallpaper) {
            backgroundTexture = AppFetcher.fetchSystemWallpaper(context);
        }

        // ─── Setup input handling ───────────────────────────────────────
        setupInput();

        Log.i(TAG, "SphereEngine created with " + appNodes.size() + " apps");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Fibonacci Sphere Distribution Algorithm
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Distributes app nodes evenly on a sphere using the Fibonacci Sphere
     * (also known as the Fibonacci Lattice or Spiral).
     *
     * ─── Why Fibonacci? ──────────────────────────────────────────────────
     *
     * The Fibonacci Sphere produces near-optimal even distribution of N
     * points on a sphere surface. Unlike grid-based approaches (latitude/
     * longitude), it avoids:
     *   - Clustering at the poles
     *   - Uneven spacing at different latitudes
     *   - The need for different algorithms for different N values
     *
     * ─── The Math ────────────────────────────────────────────────────────
     *
     * The golden angle φ = π(3 - √5) ≈ 2.3999... radians ensures each
     * successive point is rotated by the most irrational angle possible,
     * preventing alignment patterns. The y-coordinate is linearly
     * distributed from +1 to -1 (pole to pole), and x/z are computed
     * from the remaining radius at each latitude.
     *
     * ─── Group Clustering ────────────────────────────────────────────────
     *
     * Because appNodes is sorted by group (see AppFetcher.fetchSelectedApps),
     * consecutive Fibonacci indices naturally cluster grouped apps together
     * on the sphere. This gives us spatial coherence for free.
     */
    private void distributeNodesOnSphere() {
        int totalApps = appNodes.size();
        nodePositions = new Vector3[totalApps];

        if (totalApps == 0) return;

        if (totalApps == 1) {
            // Single app — place at front of sphere facing the camera
            nodePositions[0] = new Vector3(0f, 0f, sphereRadius);
            return;
        }

        // ─── Golden Angle ───────────────────────────────────────────────
        // The irrational rotation ensures no two points share the same
        // longitude, creating the characteristic Fibonacci spiral pattern.
        float phi = (float) (Math.PI * (3f - Math.sqrt(5f)));

        for (int i = 0; i < totalApps; i++) {
            // Y coordinate: linear from +1 (north pole) to -1 (south pole)
            float y = 1f - (i / (float) (totalApps - 1)) * 2f;

            // Radius at this latitude (cross-section of the sphere)
            float radiusAtY = (float) Math.sqrt(1 - y * y);

            // Longitude angle — golden angle × index
            float theta = phi * i;

            // Convert spherical to Cartesian, scaled by sphere radius
            float x = (float) (Math.cos(theta) * radiusAtY);
            float z = (float) (Math.sin(theta) * radiusAtY);

            nodePositions[i] = new Vector3(
                    x * sphereRadius,
                    y * sphereRadius,
                    z * sphereRadius
            );
        }

        Log.d(TAG, "Distributed " + totalApps + " nodes via Fibonacci sphere");
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

        for (int i = 0; i < appNodes.size(); i++) {
            AppFetcher.AppNode node = appNodes.get(i);

            if (node.iconRegion == null) continue;

            // Create a 2D decal from the app icon texture
            // hasTransparency=true enables alpha blending for round icons
            Decal decal = Decal.newDecal(iconSize, iconSize, node.iconRegion, true);

            // Position at the Fibonacci-distributed point on the sphere
            decal.setPosition(nodePositions[i].x, nodePositions[i].y, nodePositions[i].z);

            decals.add(decal);
        }

        Log.d(TAG, "Created " + decals.size + " decals");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Group Backdrop Mesh Generation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds translucent colored 3D spherical cap meshes behind each app group.
     *
     * ─── Visual Design ───────────────────────────────────────────────────
     *
     * Each group gets a semi-transparent sphere positioned slightly inside
     * the main sphere radius (at 85% of the radius). This creates a
     * visible colored glow behind the group's app icons.
     *
     * The mesh is attached to the same rotation transform as the app nodes,
     * so it rotates with the sphere. Even when apps are on the "back" of
     * the sphere, the colored glow is visible through the translucent mesh.
     *
     * ─── Mesh Construction ───────────────────────────────────────────────
     *
     * We use libGDX's ModelBuilder to create a low-poly sphere (12×12
     * divisions) with a BlendingAttribute for translucency. The Material
     * uses ColorAttribute.Diffuse with the group's color at 35% opacity.
     */
    private void buildGroupBackdrops() {
        groupBackdrops = new Array<>();
        groupModels = new Array<>();
        groupCentroids = new Array<>();

        if (appNodes == null || appNodes.isEmpty()) return;

        // ─── Identify groups and their centroid positions ────────────────
        Map<String, List<Integer>> groupIndices = new HashMap<>();
        Map<String, String> groupColorMap = new HashMap<>();

        for (int i = 0; i < appNodes.size(); i++) {
            AppFetcher.AppNode node = appNodes.get(i);
            if (node.groupId != null) {
                groupIndices.computeIfAbsent(node.groupId, k -> new ArrayList<>()).add(i);
                if (node.groupColorHex != null) {
                    groupColorMap.put(node.groupId, node.groupColorHex);
                }
            }
        }

        ModelBuilder modelBuilder = new ModelBuilder();

        for (Map.Entry<String, List<Integer>> entry : groupIndices.entrySet()) {
            String groupId = entry.getKey();
            List<Integer> indices = entry.getValue();
            String colorHex = groupColorMap.getOrDefault(groupId, "#FFFFFF");

            if (indices.size() < 2) continue; // Need at least 2 apps for a visible group

            // ─── Calculate centroid of this group's apps ────────────────
            Vector3 centroid = new Vector3();
            for (int idx : indices) {
                centroid.add(nodePositions[idx]);
            }
            centroid.scl(1f / indices.size());

            // ─── Calculate group spread for mesh sizing ─────────────────
            // The backdrop mesh diameter should encompass all apps in the group
            // plus some padding for visual breathing room
            float maxDist = 0f;
            for (int idx : indices) {
                float dist = nodePositions[idx].dst(centroid);
                maxDist = Math.max(maxDist, dist);
            }
            float meshSize = (maxDist + iconSize) * 1.3f; // 30% padding

            // Minimum mesh size so single-app groups are still visible
            meshSize = Math.max(meshSize, iconSize * 2f);

            // ─── Parse the group color ──────────────────────────────────
            Color gdxColor = parseHexColor(colorHex, 0.35f); // 35% opacity

            // ─── Create translucent material ────────────────────────────
            // BlendingAttribute enables GL_SRC_ALPHA/GL_ONE_MINUS_SRC_ALPHA
            // blending so the mesh appears as a colored translucent glow
            Material material = new Material(
                    ColorAttribute.createDiffuse(gdxColor),
                    new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 0.35f)
            );

            // ─── Build the spherical backdrop mesh ──────────────────────
            int attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;

            modelBuilder.begin();
            MeshPartBuilder partBuilder = modelBuilder.part(
                    "group_" + groupId,
                    GL20.GL_TRIANGLES,
                    attributes,
                    material
            );

            // Low-poly sphere (12×12 divisions) for performance
            // Higher divisions aren't needed since the mesh is blurred by translucency
            partBuilder.sphere(meshSize, meshSize, meshSize, 12, 12);

            Model model = modelBuilder.end();
            groupModels.add(model);

            ModelInstance instance = new ModelInstance(model);

            // ─── Position the mesh at the centroid, pushed toward sphere center
            // Position at 85% of the centroid distance to place it slightly
            // behind the app icons, creating a depth-layered effect
            Vector3 meshPos = centroid.cpy().scl(0.85f);
            instance.transform.setToTranslation(meshPos);

            // Store the original centroid for per-frame rotation recalculation
            groupCentroids.add(meshPos.cpy());
            groupBackdrops.add(instance);

            Log.d(TAG, "Group '" + groupId + "': " + indices.size() + " apps, mesh at "
                    + meshPos + ", size: " + meshSize);
        }
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
             */
            @Override
            public boolean tap(float x, float y, int count, int button) {
                if (appNodes == null || appNodes.isEmpty()) return false;

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
             * We apply the rotation as a quaternion multiplication to prevent
             * gimbal lock that would occur with sequential Euler rotations.
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

                // Create rotation quaternions for each axis
                tmpQuat.setFromAxis(Vector3.Y, (float) Math.toDegrees(angleY));
                sphereRotation.mul(tmpQuat);

                tmpQuat.setFromAxis(Vector3.X, (float) Math.toDegrees(angleX));
                sphereRotation.mul(tmpQuat);

                // Kill any existing momentum while dragging
                angularVelocity.setZero();

                return true;
            }

            /**
             * ─── FLING → Momentum Spin ───────────────────────────────────
             *
             * When the user releases a swipe, apply the fling velocity as
             * angular momentum. The friction system in render() will
             * smoothly decelerate the spin.
             */
            @Override
            public boolean fling(float velocityX, float velocityY, int button) {
                userInteracting = false;

                // Map screen-space fling velocity to angular velocity
                // Negative X velocity = positive Y rotation (natural feel)
                angularVelocity.set(
                        velocityY * FLING_SENSITIVITY,   // X axis from vertical fling
                        -velocityX * FLING_SENSITIVITY,  // Y axis from horizontal fling
                        0f                                // No Z rotation from 2D input
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
        // Clear both color and depth buffers.
        // Alpha = 0 for transparent background (system wallpaper shows through
        // if we're not rendering our own background).
        Gdx.gl.glClearColor(0f, 0f, 0f, keepSystemWallpaper ? 1f : 0f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        // ─── Enable depth testing for proper 3D sorting ─────────────────
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        // ─── Layer 1: Background System Wallpaper ───────────────────────
        if (keepSystemWallpaper && backgroundTexture != null) {
            renderBackground();
        }

        // ─── Layer 2: Group Backdrop Meshes ─────────────────────────────
        if (groupBackdrops != null && groupBackdrops.size > 0 && pageVisibility > 0.01f) {
            renderGroupBackdrops();
        }

        // ─── Layer 3: App Icon Decals (Billboarded) ─────────────────────
        if (decals != null && decals.size > 0 && pageVisibility > 0.01f) {
            renderDecals();
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
     * ─── Why Quaternion × Delta Quaternion? ──────────────────────────────
     *
     * Instead of euler.x += omega.x * dt (which gimbal-locks), we create
     * a small delta rotation quaternion from the angular velocity vector
     * and multiply it into the accumulated rotation. This is equivalent
     * to integrating the angular velocity in SO(3) space.
     *
     * @param delta Frame time in seconds (1/120 at 120 FPS)
     */
    private void updatePhysics(float delta) {
        // ─── Idle auto-spin ─────────────────────────────────────────────
        if (!userInteracting) {
            idleTimer += delta;
            if (idleTimer > IDLE_DELAY && angularVelocity.len2() < VELOCITY_EPSILON * VELOCITY_EPSILON) {
                // Resume gentle auto-rotation around Y axis
                angularVelocity.y = IDLE_SPIN_SPEED;
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

            // Multiply into accumulated rotation
            sphereRotation.mul(tmpQuat);

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
     * 0.1 (minimal render, mostly hidden) to save GPU cycles.
     *
     * The lerp speed (8.0) gives a snappy ~150ms transition at 120 FPS.
     */
    private void updatePageVisibility(float delta) {
        // Determine if we're on the active page
        float targetVisibility;

        if (xOffsetStep <= 0f) {
            // Can't determine page (some launchers don't report step) — always visible
            targetVisibility = 1f;
        } else {
            // Calculate current page number from continuous offset
            float currentPage = currentXOffset / xOffsetStep;
            float pageDistance = Math.abs(currentPage - activePage);

            // Full visibility when within 0.3 pages, fading to 10% beyond 1 page
            targetVisibility = MathUtils.clamp(1f - (pageDistance - 0.3f) * 1.4f, 0.1f, 1f);
        }

        // Smooth lerp to target
        pageVisibility = MathUtils.lerp(pageVisibility, targetVisibility, delta * 8f);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Render Layer 1 — Background System Wallpaper
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Renders the captured system wallpaper as a full-screen 2D sprite
     * behind the 3D scene.
     *
     * Uses SpriteBatch which internally sets up an orthographic projection,
     * unaffected by our 3D PerspectiveCamera. The depth buffer is temporarily
     * disabled so the background is always behind everything.
     */
    private void renderBackground() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);

        spriteBatch.begin();
        spriteBatch.draw(backgroundTexture,
                0, 0,
                Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        spriteBatch.end();

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Render Layer 2 — Group Backdrop Meshes
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Renders the translucent colored meshes behind grouped app clusters.
     *
     * Each backdrop mesh is transformed by the sphere's rotation quaternion
     * so it moves in sync with the app icons. The meshes use alpha blending
     * and are rendered before the decals so they appear behind the icons.
     *
     * ModelBatch automatically handles back-to-front sorting for transparent
     * objects via its RenderableSorter.
     */
    private void renderGroupBackdrops() {
        modelBatch.begin(camera);

        for (int i = 0; i < groupBackdrops.size; i++) {
            ModelInstance instance = groupBackdrops.get(i);
            Vector3 centroid = groupCentroids.get(i);

            // ─── Recompute the rotated centroid position ────────────────
            // Apply the sphere's rotation quaternion to the stored centroid
            // so this backdrop orbits with the sphere, not stuck at origin.
            tmpVec.set(centroid);
            sphereRotation.transform(tmpVec);

            // ─── Scale by page visibility for fade effect ───────────────
            tmpVec.scl(pageVisibility);

            // ─── Compose the final transform ────────────────────────────
            // Translation = rotated centroid position
            // Scale = page visibility factor for shrink/grow animation
            instance.transform.idt();
            instance.transform.setToTranslation(tmpVec);
            instance.transform.scl(pageVisibility);

            modelBatch.render(instance);
        }

        modelBatch.end();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Render Layer 3 — Billboarded App Icon Decals
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Renders all app icon decals with billboarding (face-the-camera).
     *
     * For each decal:
     * 1. Compute the rotated position by applying sphereRotation quaternion
     *    to the original Fibonacci position
     * 2. Update the decal's position
     * 3. Call lookAt(camera.position, camera.up) for billboarding
     * 4. Apply page visibility scaling
     * 5. Add to DecalBatch for depth-sorted rendering
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
        for (int i = 0; i < decals.size && i < nodePositions.length; i++) {
            Decal decal = decals.get(i);

            // ─── Apply sphere rotation to this node's position ──────────
            Vector3 rotatedPos = getRotatedPosition(i);

            // ─── Apply page visibility scale ────────────────────────────
            // Scale position toward origin when page visibility < 1
            rotatedPos.scl(pageVisibility);

            // ─── Update decal transform ─────────────────────────────────
            decal.setPosition(rotatedPos.x, rotatedPos.y, rotatedPos.z);

            // Scale decal size with page visibility for a shrink effect
            float scaledSize = iconSize * pageVisibility;
            decal.setDimensions(scaledSize, scaledSize);

            // ─── Billboard: always face the camera ──────────────────────
            decal.lookAt(camera.position, camera.up);

            // ─── Add to batch (CameraGroupStrategy handles depth sort) ──
            decalBatch.add(decal);
        }

        // ─── Flush all decals to GPU in one draw call ───────────────────
        decalBatch.flush();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Utility — Get rotated position of a node
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns the current world-space position of the node at the given index,
     * after applying the sphere's rotation quaternion.
     *
     * The rotation is applied by:
     * 1. Creating a rotation matrix from the quaternion
     * 2. Multiplying the original Fibonacci position by this matrix
     *
     * This preserves the original positions (important for group centroid
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
        // Live wallpaper returning to foreground
        // Reload preferences in case user changed settings
        Log.d(TAG, "Resumed");
        reloadPreferences();
    }

    @Override
    public void dispose() {
        Log.i(TAG, "Disposing SphereEngine...");

        // ─── Dispose rendering systems ──────────────────────────────────
        if (spriteBatch != null) spriteBatch.dispose();
        if (decalBatch != null) decalBatch.dispose();
        if (modelBatch != null) modelBatch.dispose();

        // ─── Dispose textures ───────────────────────────────────────────
        if (backgroundTexture != null) backgroundTexture.dispose();

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

        Log.i(TAG, "SphereEngine disposed");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Preference Hot-Reload
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reloads user preferences and rebuilds the sphere if settings changed.
     * Called on resume() to pick up changes made in LiveWallpaperSettings.
     *
     * Note: Full rebuild (re-fetching app icons from PackageManager) is
     * relatively expensive (~100ms for 30 apps), so we only do it when
     * the app returns from the settings screen.
     */
    private void reloadPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        boolean newKeepWallpaper = prefs.getBoolean("pref_keep_wallpaper", true);
        int newActivePage = prefs.getInt("pref_active_page", 0);
        int newRadiusPref = prefs.getInt("pref_sphere_radius", 50);
        int newIconPref = prefs.getInt("pref_icon_size", 50);

        float newRadius = MathUtils.lerp(3.0f, 8.0f, newRadiusPref / 100f);
        float newIconSize = MathUtils.lerp(0.6f, 2.0f, newIconPref / 100f);

        activePage = newActivePage;

        // ─── Check if we need to rebuild ────────────────────────────────
        boolean needsRebuild = false;

        if (newKeepWallpaper != keepSystemWallpaper) {
            keepSystemWallpaper = newKeepWallpaper;
            // Reload or dispose background texture
            if (keepSystemWallpaper) {
                if (backgroundTexture != null) backgroundTexture.dispose();
                backgroundTexture = AppFetcher.fetchSystemWallpaper(context);
            } else {
                if (backgroundTexture != null) {
                    backgroundTexture.dispose();
                    backgroundTexture = null;
                }
            }
        }

        if (Math.abs(newRadius - sphereRadius) > 0.1f || Math.abs(newIconSize - iconSize) > 0.01f) {
            sphereRadius = newRadius;
            iconSize = newIconSize;
            needsRebuild = true;
        }

        if (needsRebuild) {
            // Rebuild sphere with new dimensions
            Gdx.app.postRunnable(this::rebuildSphere);
        }
    }

    /**
     * Fully rebuilds the sphere — re-fetches apps, redistributes nodes,
     * recreates decals and group meshes. Called when preferences change
     * that affect the sphere's structure or appearance.
     */
    private void rebuildSphere() {
        Log.i(TAG, "Rebuilding sphere...");

        // Dispose old textures
        if (appNodes != null) {
            for (AppFetcher.AppNode node : appNodes) {
                if (node.iconTexture != null) node.iconTexture.dispose();
            }
        }
        if (groupModels != null) {
            for (Model model : groupModels) model.dispose();
        }

        // Re-fetch and rebuild
        appNodes = AppFetcher.fetchSelectedApps(context);
        distributeNodesOnSphere();
        createDecals();
        buildGroupBackdrops();

        // Update camera distance for new radius
        camera.position.set(0f, 0f, sphereRadius * 2.8f);
        camera.update();

        Log.i(TAG, "Sphere rebuilt with " + appNodes.size() + " apps");
    }

    @Override
    public void iconDropped(int x, int y) {
        // Required by AndroidWallpaperListener but not used
    }
}
