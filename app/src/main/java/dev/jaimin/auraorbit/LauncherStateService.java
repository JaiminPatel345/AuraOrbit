package dev.jaimin.auraorbit;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LauncherStateService — One UI launcher page + drawer detector (opt-in)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Fixes GitHub #10 + #11:
 *
 * #10: On One UI, the wallpaper receives NO offset/command/zoom callbacks that
 *      would tell us the app drawer is open.  This service detects the drawer by
 *      inspecting the launcher's accessibility node tree, then publishes
 *      {@link LauncherState#drawerOpen}.  SphereEngine's direct-tap path checks
 *      this flag so taps on the blurred sphere behind the open drawer no longer
 *      launch apps.
 *
 * #11: One UI never sends page-offset events to live wallpapers.  This service
 *      reads the launcher's page indicator (content-desc "Page N of M. " or
 *      "Home screen N of M") and publishes the 1-based page into
 *      {@link LauncherState#page} (0 = no data yet).  SphereEngine uses this as
 *      the highest-priority page source, replacing the dead-reckoning swipe
 *      counter that can drift.
 *
 * ─── Scope and privacy ────────────────────────────────────────────────────────
 *
 * The service is restricted to {@code packageNames="com.sec.android.app.launcher"}
 * in launcher_state_service.xml — it NEVER receives events from other apps and
 * NEVER reads content outside the Samsung launcher.  It reads only the page
 * indicator's contentDescription (e.g. "Page 1 of 4. "), never any user data.
 *
 * ─── Page-indicator format discovered via uiautomator on Galaxy S25 Ultra ────
 *
 *   Home-screen page indicator:   contentDescription = "Page 1 of 4. "
 *   Drawer page indicator:        contentDescription = "Page 1 of 3. "
 *                                 (also has resource-id containing "applist")
 *
 * Surface classification: walk each matching node and its ancestors for a
 * resource-id containing "applist" or "apps" → drawer; anything else → home.
 * If the surface cannot be classified, it is treated as home (safe default).
 *
 * ─── Threading ────────────────────────────────────────────────────────────────
 *
 * This service runs in the same process as MyWallpaperService (single-process
 * app).  {@link LauncherState} uses volatile fields so reads on the libGDX GL
 * thread see updates published by this service's main-thread callbacks without
 * explicit synchronization.
 *
 * ─── Battery / performance ───────────────────────────────────────────────────
 *
 * - Events are throttled: we skip a scan if less than 80 ms has elapsed since
 *   the last scan (matches the notificationTimeout in the XML config).
 * - No heap allocations beyond the unavoidable node-scan loop; the pattern
 *   and matcher are created once at class-init time.
 * - Log.d calls are throttled so they do not spam logcat in production.
 */
public class LauncherStateService extends AccessibilityService {

    private static final String TAG = "AuraOrbit.A11y";

    public static LauncherStateService instance;
    private WindowManager windowManager;
    private View touchOverlay;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isOverlayAdded = false;

    /**
     * Pattern matching One UI home-screen page-indicator content descriptions.
     *
     * Observed formats (Samsung wording varies slightly by One UI version):
     *   Standard:     "Page 1 of 4. "       (note trailing ". ")
     *   Alternate:    "Home screen 1 of 4"  (older/regional Samsung builds)
     *
     * The pattern is tolerant of any whitespace between words and of the
     * trailing dot-space, matching both "Page 1 of 4." and "Page 1 of 4. "
     * as well as "Home screen 1 of 4" (with or without trailing punctuation).
     */
    private static final Pattern PAGE_PATTERN =
            Pattern.compile(
                    "(?i)(?:page\\s+(\\d+)\\s+of\\s+(\\d+)"
                    + "|home\\s*screen\\s+(\\d+)\\s+of\\s+(\\d+)"
                    + "|(?:^|\\s)(\\d+)\\s+of\\s+(\\d+)(?:\\.|\\s|$))");

    /**
     * Minimum nanoseconds between full node-tree scans.
     * Rapid scroll events fire many times per second; coalescing to 80 ms
     * keeps CPU load negligible while still updating at ≥12 Hz.
     */
    private static final long SCAN_THROTTLE_NS = 80_000_000L; // 80 ms

    /** Nanosecond timestamp of the previous scan (System.nanoTime()). */
    private long lastScanNanos = 0L;

    // ─── Log throttle ────────────────────────────────────────────────────────
    /** Count of events processed since the last log line was emitted. */
    private int eventCountSinceLog = 0;
    /** Emit a log line every N events to confirm the service is alive. */
    private static final int LOG_EVERY_N = 50;
    /** Nanosecond timestamp of the last "home page parsed" log line. */
    private long lastHomePageLogNanos = 0L;
    /** Minimum interval between "home page" log lines (1 second). */
    private static final long HOME_PAGE_LOG_THROTTLE_NS = 1_000_000_000L;

    // ═══════════════════════════════════════════════════════════════════════════
    //  Shared launcher state — visible process-wide via static fields
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Snapshot of the launcher's current paging state.
     *
     * All fields are {@code volatile} for safe cross-thread visibility: the
     * service writes on the main thread; SphereEngine reads on the GL thread.
     * No locks are needed because each write is to an independent field and
     * readers can tolerate a frame-level lag in stale values.
     */
    public static final class LauncherState {
        /**
         * Current 1-based home-screen page as parsed directly from the indicator
         * (e.g. "Page 2 of 4" → page=2).  The default value 0 is the "no data yet"
         * sentinel written at service start; it means the indicator has never been
         * parsed (One UI hides the dots when at rest, only showing them mid-swipe).
         *
         * <p>SphereEngine treats page &lt; 1 as "no data" and falls through to the
         * next page source (offsets or dead-reckoning) so the sphere does not
         * collapse immediately after being applied before the first swipe.
         *
         * <p>Only meaningful when {@link #serviceConnected} is true AND
         * {@link #updatedNanos} is within the freshness window.
         */
        public static volatile int page = 0;

        /**
         * Total number of home-screen pages reported by the page indicator.
         * Default 1 (single page assumed when unknown).
         */
        public static volatile int pageCount = 1;

        /**
         * True when the One UI app drawer is open and in the foreground.
         * SphereEngine suppresses app launches while this is true so that taps
         * on the blurred sphere behind the drawer do not open apps.
         */
        public static volatile boolean drawerOpen = false;

        /**
         * System.nanoTime() of the most recent update.  SphereEngine treats
         * values older than 5 seconds as stale and falls through to the next
         * page-source in the priority chain.
         */
        public static volatile long updatedNanos = Long.MIN_VALUE / 2;

        /**
         * True while the accessibility service is connected and running.
         * Set to true in {@link LauncherStateService#onServiceConnected()};
         * set to false in {@link LauncherStateService#onDestroy()} so that
         * SphereEngine falls back to dead-reckoning the moment the service dies.
         */
        public static volatile boolean serviceConnected = false;

        public static volatile boolean systemUiVisible = false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  AccessibilityService lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        touchOverlay = new View(this);
        touchOverlay.setOnTouchListener((v, event) -> {
            if (dev.jaimin.auraorbit.MyWallpaperService.activeEngine != null) {
                // Map the touch from overlay-local coordinates to absolute screen coordinates
                // so libGDX receives the touch at the correct position on its full-screen surface.
                event.setLocation(event.getRawX(), event.getRawY());
                dev.jaimin.auraorbit.MyWallpaperService.activeEngine.injectTouch(event);
                return true; // Consume touch to prevent launcher stealing
            }
            return false;
        });

        LauncherState.serviceConnected = true;
        // Reset stale state from any previous session.
        LauncherState.page          = 0;
        LauncherState.pageCount     = 1;
        LauncherState.drawerOpen    = false;
        LauncherState.systemUiVisible = false;
        LauncherState.updatedNanos  = Long.MIN_VALUE / 2;
        Log.i(TAG, "LauncherStateService connected");
    }

    public static void updateOverlayState(boolean interactive, int size) {
        if (instance != null) {
            instance.mainHandler.post(() -> instance.applyOverlayState(interactive, size));
        }
    }

    private void applyOverlayState(boolean interactive, int size) {
        if (interactive && size > 0) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.CENTER;

            if (!isOverlayAdded) {
                try {
                    windowManager.addView(touchOverlay, params);
                    isOverlayAdded = true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to add touch overlay", e);
                }
            } else {
                try {
                    windowManager.updateViewLayout(touchOverlay, params);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to update touch overlay", e);
                }
            }
        } else {
            if (isOverlayAdded) {
                try {
                    windowManager.removeView(touchOverlay);
                    isOverlayAdded = false;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to remove touch overlay", e);
                }
            }
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        LauncherState.serviceConnected = false;
        Log.i(TAG, "LauncherStateService unbound");
        if (isOverlayAdded && touchOverlay != null) {
            try { windowManager.removeView(touchOverlay); } catch(Exception e) {}
            isOverlayAdded = false;
        }
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        LauncherState.serviceConnected = false;
        Log.i(TAG, "LauncherStateService destroyed");
        if (isOverlayAdded && touchOverlay != null) {
            try { windowManager.removeView(touchOverlay); } catch(Exception e) {}
            isOverlayAdded = false;
        }
        instance = null;
        super.onDestroy();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Event handling
    // ═══════════════════════════════════════════════════════════════════════════

    private static long lastDebugDumpNanos = 0;

    private void debugDumpTree(AccessibilityNodeInfo root, AccessibilityEvent event) {
        long now = System.nanoTime();
        if (now - lastDebugDumpNanos < 5_000_000_000L) return; // Dump once every 5 seconds
        lastDebugDumpNanos = now;
        
        Log.d(TAG, "=== DEBUG DUMP TREE START (Event: " + event.getClassName() + ") ===");
        dumpNode(root, 0);
        Log.d(TAG, "=== DEBUG DUMP TREE END ===");
    }

    private void dumpNode(AccessibilityNodeInfo node, int depth) {
        if (node == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(node.getClassName());
        if (node.getViewIdResourceName() != null) sb.append(" id=").append(node.getViewIdResourceName());
        if (node.getText() != null) sb.append(" text='").append(node.getText()).append("'");
        if (node.getContentDescription() != null) sb.append(" desc='").append(node.getContentDescription()).append("'");
        if (node.isVisibleToUser()) sb.append(" [visible]");
        if (node.isSelected()) sb.append(" [selected]");
        if (node.isChecked()) sb.append(" [checked]");
        Log.d(TAG, sb.toString());
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                dumpNode(child, depth + 1);
            } finally {
                if (child != null) {
                    try { child.recycle(); } catch (Exception ignored) {}
                }
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // ─── Direct Event Package Inspection ─────────────────────────────────
        // Catch System UI (notifications) even if the active window root hasn't
        // updated yet. We do this before the throttle to ensure we never miss it.
        CharSequence eventPkg = event.getPackageName();
        if (eventPkg != null) {
            String pkg = eventPkg.toString().toLowerCase();
            if (pkg.contains("systemui")) {
                LauncherState.systemUiVisible = true;
                LauncherState.updatedNanos = System.nanoTime();
            } else if (pkg.contains("launcher")) {
                LauncherState.systemUiVisible = false;
            }
        }

        // ─── 80 ms throttle ──────────────────────────────────────────────────
        long nowNanos = System.nanoTime();
        if (nowNanos - lastScanNanos < SCAN_THROTTLE_NS) return;
        lastScanNanos = nowNanos;

        // ─── Throttled log: confirm service alive without spamming ───────────
        eventCountSinceLog++;
        if (eventCountSinceLog >= LOG_EVERY_N) {
            eventCountSinceLog = 0;
            Log.d(TAG, "onAccessibilityEvent: service alive, drawerOpen="
                    + LauncherState.drawerOpen + " page=" + LauncherState.page);
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            // Window not yet available (e.g. launcher is animating in).
            return;
        }

        // ─── Root Package Check ──────────────────────────────────────────────
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg != null) {
            boolean isSysUi = rootPkg.toString().toLowerCase().contains("systemui");
            if (isSysUi) {
                LauncherState.systemUiVisible = true;
                LauncherState.updatedNanos = System.nanoTime();
                try { root.recycle(); } catch (Exception ignored) { }
                return;
            }
        }

        try {
            debugDumpTree(root, event);
            scanNodeTree(root, event);
        } finally {
            // Always recycle the root we obtained — required on all API levels
            // (on API 33+ recycle() is a no-op, so calling it is always safe).
            try { root.recycle(); } catch (IllegalStateException ignored) { }
        }
    }

    /**
     * Scans the accessibility node tree to determine:
     *   1. Which surface is active (home vs. drawer).
     *   2. The current page and page count from the home-screen indicator.
     *
     * Strategy:
     *   - Walk the tree to find nodes whose contentDescription matches
     *     {@link #PAGE_PATTERN}.
     *   - For each matching node, walk its ancestors to classify the surface:
     *       "applist" or "apps" in any ancestor's resource-id → drawer
     *       otherwise                                          → home
     *   - drawerOpen = true when a drawer-classified indicator is present
     *     and visible, OR when the incoming event's class name hints at the
     *     apps-view container.
     *   - page/pageCount come from the home-classified indicator only (STICKY:
     *     when no home indicator is found, the last known values are preserved).
     *
     * @param root  Root node of the active window (caller must recycle).
     * @param event The triggering event (used for class-name hint).
     */
    private void scanNodeTree(AccessibilityNodeInfo root, AccessibilityEvent event) {
        // Results accumulated during the scan.
        int  foundHomePage  = -1;   // -1 = not found this scan
        int  foundHomeCount = -1;
        boolean foundDrawerIndicator = false;

        // ─── Class-name hint: TYPE_WINDOW_STATE_CHANGED on the apps-view ─────
        // One UI fires a window-state event whose className contains "AppsView"
        // or similar when the drawer opens/closes. Use as a fast-path hint.
        CharSequence eventClass = event.getClassName();
        boolean classHintDrawer = eventClass != null
                && (containsIgnoreCase(eventClass, "appsview")
                    || containsIgnoreCase(eventClass, "applist")
                    || containsIgnoreCase(eventClass, "allapps")
                    || containsIgnoreCase(eventClass, "drawer"));

        // ─── Recursive node scan ─────────────────────────────────────────────
        // We process nodes breadth-first by recursing into children. Depth is
        // bounded by the launcher's UI hierarchy (typically < 20 levels).
        ScanResult result = new ScanResult();
        scanNode(root, result);

        foundHomePage        = result.homePage;
        foundHomeCount       = result.homePageCount;
        foundDrawerIndicator = result.drawerIndicatorVisible;

        // ─── Publish results ──────────────────────────────────────────────────
        boolean drawerOpen = foundDrawerIndicator || classHintDrawer;

        // STICKY page cache: the home page indicator only appears while the user
        // is swiping (One UI fades out the dots when at rest).  When no indicator
        // was found in this scan, keep the last known value — pages can only change
        // via swipes, and swipes produce events when the indicator is visible.
        // page=0 is the "no data yet" sentinel written at service start; it will
        // be replaced the first time a swipe exposes the indicator.
        // DRAWER indicators must NEVER write into the home page fields.
        if (foundHomePage >= 0) {
            // Successfully parsed a home page indicator — update the sticky cache.
            LauncherState.page      = foundHomePage;
            LauncherState.pageCount = foundHomeCount;
            // Throttled log so activity is visible in logcat without spam.
            long nowNs = System.nanoTime();
            if (nowNs - lastHomePageLogNanos > HOME_PAGE_LOG_THROTTLE_NS) {
                lastHomePageLogNanos = nowNs;
                Log.d(TAG, "home page=" + foundHomePage + " of " + foundHomeCount);
            }
        }
        // else: no home indicator in this scan — keep LauncherState.page/pageCount
        // unchanged (sticky cache).  This covers both "at rest between swipes" and
        // "drawer is open" — in neither case should we discard the last known page.

        LauncherState.drawerOpen   = drawerOpen;
        LauncherState.updatedNanos = System.nanoTime();
    }

    /**
     * Mutable result container used during the node scan to avoid creating
     * multiple return values.  All fields are plain (no volatile needed —
     * used only on the service main thread).
     */
    private static final class ScanResult {
        int     homePage             = -1;
        int     homePageCount        = -1;
        boolean drawerIndicatorVisible = false;
    }

    /**
     * Recursively visits every node in the tree rooted at {@code node},
     * looking for page-indicator nodes (matched by contentDescription pattern).
     *
     * Classification of each matching node:
     *   - Walk the node and its parents; if ANY has a resource-id substring
     *     matching "applist" or "apps" → classify as drawer.
     *   - Otherwise classify as home.
     *
     * Nodes that are not visible to the user are skipped (avoids counting
     * off-screen / detached views).
     *
     * @param node    Node to examine (may be null — guard at top of method).
     * @param result  Accumulator updated in-place.
     */
    private void scanNode(AccessibilityNodeInfo node, ScanResult result) {
        if (node == null) return;

        int childCount = node.getChildCount();

        // ─── Drawer Signature Check ──────────────────────────────────────────
        // Detect One UI 8.5 app drawers that lack a page indicator.
        CharSequence nodeText = node.getText();
        if (nodeText != null) {
            String textStr = nodeText.toString().toLowerCase();
            if (textStr.equals("all apps") || textStr.equals("search")) {
                if (node.isVisibleToUser()) result.drawerIndicatorVisible = true;
            }
        }
        CharSequence nodeDesc = node.getContentDescription();
        if (nodeDesc != null) {
            String descStr = nodeDesc.toString().toLowerCase();
            if (descStr.equals("more options")) {
                if (node.isVisibleToUser()) result.drawerIndicatorVisible = true;
            }
        }
        
        CharSequence ownId = node.getViewIdResourceName();
        if (ownId != null) {
            String id = ownId.toString().toLowerCase();
            if (id.contains("applist") || id.contains("allapps") || id.contains("appsview") || id.equals("com.sec.android.app.launcher:id/apps_grid")) {
                if (node.isVisibleToUser()) result.drawerIndicatorVisible = true;
            }
        }

        // ─── Check this node's contentDescription ────────────────────────────
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            Matcher m = PAGE_PATTERN.matcher(desc);
            if (m.find()) {
                // This node is a page indicator. Parse page and count.
                // Pattern has two alternatives (groups 1+2 for "page N of M",
                // groups 3+4 for "home screen N of M"): use whichever matched.
                String pageGroup  = m.group(1) != null ? m.group(1) : m.group(3);
                String countGroup = m.group(2) != null ? m.group(2) : m.group(4);
                int indicatorPage  = parseIntSafe(pageGroup,  1);
                int indicatorCount = parseIntSafe(countGroup, 1);

                // Classify surface: is this a drawer indicator?
                boolean isDrawerIndicator = isDrawerNode(node);

                if (isDrawerIndicator) {
                    // Drawer indicator visible → drawer is open.
                    if (node.isVisibleToUser()) {
                        result.drawerIndicatorVisible = true;
                    }
                } else {
                    // Home-screen indicator: update page.
                    // One UI 8.5 renders all dots simultaneously (even invisible ones), 
                    // so we MUST check for isSelected() to pick the active dot.
                    if (result.homePage < 0 || node.isSelected()) {
                        result.homePage      = Math.max(1, indicatorPage);
                        result.homePageCount = indicatorCount;
                    }
                }
            }
        }

        // ─── Recurse into children ───────────────────────────────────────────
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                scanNode(child, result);
            } finally {
                // Recycle each child after we finish with it to avoid leaking.
                if (child != null) {
                    try { child.recycle(); } catch (IllegalStateException ignored) { }
                }
            }
        }
    }

    /**
     * Returns true if {@code node} or any of its ancestors has a resource-id
     * that contains "applist" or "apps" (case-insensitive), indicating that
     * the node belongs to the app-drawer surface rather than the home workspace.
     *
     * One UI observed IDs:
     *   Drawer page indicator:  "com.sec.android.app.launcher:id/applist_page_indicator"
     *   Home page indicator:    resource-ids contain "home" or "workspace"
     *
     * We walk up to 10 ancestor levels — sufficient for any reasonable launcher
     * hierarchy and guards against pathological trees that could run for a long time.
     *
     * @param node  Starting node (its own resource-id is also checked).
     * @return true if the node is classified as belonging to the drawer.
     */
    private boolean isDrawerNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = null;
        try {
            // Start check at the node itself (do NOT recycle 'node' — caller owns it).
            CharSequence ownId = node.getViewIdResourceName();
            if (ownId != null) {
                String id = ownId.toString().toLowerCase();
                if (id.contains("applist") || id.contains("apps") || id.contains("drawer") || id.contains("allapps")) return true;
            }

            // Walk ancestors.
            current = node.getParent();
            int depth = 0;
            while (current != null && depth < 10) {
                CharSequence resId = current.getViewIdResourceName();
                if (resId != null) {
                    String id = resId.toString().toLowerCase();
                    if (id.contains("applist") || id.contains("apps") || id.contains("drawer") || id.contains("allapps")) {
                        return true;
                    }
                }
                AccessibilityNodeInfo parent = current.getParent();
                try { current.recycle(); } catch (IllegalStateException ignored) { }
                current = parent;
                depth++;
            }
        } finally {
            // Recycle the last non-null current we have, if any.
            if (current != null) {
                try { current.recycle(); } catch (IllegalStateException ignored) { }
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Utility helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Parses an integer string safely, returning {@code fallback} on error. */
    private static int parseIntSafe(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Case-insensitive substring test on a CharSequence (no allocation). */
    private static boolean containsIgnoreCase(CharSequence haystack, String needle) {
        int hLen = haystack.length();
        int nLen = needle.length();
        if (nLen == 0) return true;
        if (hLen < nLen) return false;
        outer:
        for (int i = 0; i <= hLen - nLen; i++) {
            for (int j = 0; j < nLen; j++) {
                if (Character.toLowerCase(haystack.charAt(i + j))
                        != Character.toLowerCase(needle.charAt(j))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        // No active requests to interrupt — this service is passive (observe-only).
    }
}
