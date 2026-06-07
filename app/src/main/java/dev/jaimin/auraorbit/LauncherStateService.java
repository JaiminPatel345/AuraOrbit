package dev.jaimin.auraorbit;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
 *      reads the launcher's page indicator (content-desc "Page N of M. ") and
 *      publishes the exact 0-based page into {@link LauncherState#page}.
 *      SphereEngine uses this as the highest-priority page source, replacing the
 *      dead-reckoning swipe counter that can drift.
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

    /**
     * Pattern matching One UI page-indicator content descriptions.
     *
     * Observed format:  "Page 1 of 4. "  (note trailing ". ")
     * The pattern is tolerant of any whitespace between words and of the
     * trailing dot-space, matching both "Page 1 of 4." and "Page 1 of 4. ".
     */
    private static final Pattern PAGE_PATTERN =
            Pattern.compile("(?i)page\\s+(\\d+)\\s+of\\s+(\\d+)");

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
         * Current 0-based home-screen page (1-based from the indicator minus 1).
         * Default 0 (first page). Only meaningful when {@link #serviceConnected} is
         * true AND {@link #updatedNanos} is within the freshness window.
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
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  AccessibilityService lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        LauncherState.serviceConnected = true;
        // Reset stale state from any previous session.
        LauncherState.page          = 0;
        LauncherState.pageCount     = 1;
        LauncherState.drawerOpen    = false;
        LauncherState.updatedNanos  = Long.MIN_VALUE / 2;
        Log.i(TAG, "LauncherStateService connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        LauncherState.serviceConnected = false;
        Log.i(TAG, "LauncherStateService unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        LauncherState.serviceConnected = false;
        Log.i(TAG, "LauncherStateService destroyed");
        super.onDestroy();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Event handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
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

        // ─── Scan the accessibility node tree ────────────────────────────────
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            // Window not yet available (e.g. launcher is animating in).
            return;
        }

        try {
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
     *   - page/pageCount come from the home-classified indicator (preferred)
     *     or the drawer indicator if no home indicator was found.
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
                    || containsIgnoreCase(eventClass, "allapps"));

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

        int newPage      = (foundHomePage >= 0)  ? foundHomePage      : LauncherState.page;
        int newPageCount = (foundHomeCount >= 0)  ? foundHomeCount     : LauncherState.pageCount;
        // If no home indicator was found but a drawer indicator was, keep the
        // last known home page (drawerOpen is true, sphere is suppressed anyway).

        LauncherState.drawerOpen   = drawerOpen;
        LauncherState.page         = newPage;
        LauncherState.pageCount    = newPageCount;
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

        // ─── Check this node's contentDescription ────────────────────────────
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            Matcher m = PAGE_PATTERN.matcher(desc);
            if (m.find()) {
                // This node is a page indicator. Parse page and count.
                int indicatorPage  = parseIntSafe(m.group(1), 1);
                int indicatorCount = parseIntSafe(m.group(2), 1);

                // Classify surface: is this a drawer indicator?
                boolean isDrawerIndicator = isDrawerNode(node);

                if (isDrawerIndicator) {
                    // Drawer indicator visible → drawer is open.
                    if (node.isVisibleToUser()) {
                        result.drawerIndicatorVisible = true;
                    }
                } else {
                    // Home-screen indicator: update page (only when visible).
                    if (node.isVisibleToUser() && result.homePage < 0) {
                        // Convert 1-based indicator to 0-based internal page.
                        result.homePage      = Math.max(0, indicatorPage - 1);
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
                if (id.contains("applist") || id.contains("apps")) return true;
            }

            // Walk ancestors.
            current = node.getParent();
            int depth = 0;
            while (current != null && depth < 10) {
                CharSequence resId = current.getViewIdResourceName();
                if (resId != null) {
                    String id = resId.toString().toLowerCase();
                    if (id.contains("applist") || id.contains("apps")) {
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
