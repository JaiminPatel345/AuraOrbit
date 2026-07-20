package dev.jaimin.auraorbit;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LauncherStateService extends AccessibilityService {
    private static final String TAG = "LauncherStateService";

    private static final Pattern PAGE_PATTERN = Pattern.compile(
            "(?i)(?:page\\s+(\\d+)\\s+of\\s+(\\d+)|home\\s*screen\\s+(\\d+)\\s+of\\s+(\\d+))");

    public static final class LauncherState {
        public static volatile boolean serviceConnected = false;
        public static volatile long updatedNanos = 0;
        public static volatile int page = 0;
        public static volatile int pageCount = 0;
        public static volatile boolean drawerOpen = false;
        public static volatile boolean systemUiVisible = false;
    }

    public static void updateOverlayState(boolean interactive, int size) {
        // Reserved for future two-way IPC if needed
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        LauncherState.serviceConnected = true;
        LauncherState.updatedNanos = System.nanoTime();
        Log.d(TAG, "LauncherStateService connected successfully");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence packageNameSeq = event.getPackageName();
        CharSequence classNameSeq = event.getClassName();
        String packageName = packageNameSeq != null ? packageNameSeq.toString() : "";
        String className = classNameSeq != null ? classNameSeq.toString() : "";

        // Check for Recents / Recent Apps / App Drawer / System UI window changes
        boolean isRecentsOrDrawer = false;
        boolean isExternalApp = false;

        String lowerPkg = packageName.toLowerCase();
        String lowerCls = className.toLowerCase();

        if (lowerCls.contains("recents") || lowerCls.contains("overview")
                || lowerCls.contains("allapps") || lowerCls.contains("appspicker")
                || lowerPkg.contains("systemui") || lowerCls.contains("taskswitcher")) {
            isRecentsOrDrawer = true;
        }

        // If package is not launcher package and not empty
        if (!packageName.isEmpty() && !lowerPkg.contains("launcher") && !lowerPkg.contains("home")
                && !lowerPkg.contains("aurorbit") && !lowerPkg.contains("systemui")) {
            isExternalApp = true;
        }

        LauncherState.drawerOpen = isRecentsOrDrawer;
        LauncherState.systemUiVisible = isExternalApp || isRecentsOrDrawer;
        LauncherState.updatedNanos = System.nanoTime();

        // Scan window for page indicators
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try {
                scanNodeTree(root);
            } catch (Exception e) {
                // Ignore transient node traversal exceptions
            } finally {
                root.recycle();
            }
        }
    }

    private void scanNodeTree(AccessibilityNodeInfo node) {
        if (node == null) return;

        CharSequence descSeq = node.getContentDescription();
        if (descSeq != null) {
            String desc = descSeq.toString();
            Matcher m = PAGE_PATTERN.matcher(desc);
            if (m.find()) {
                String pageGroup = m.group(1) != null ? m.group(1) : m.group(3);
                String countGroup = m.group(2) != null ? m.group(2) : m.group(4);
                try {
                    int p = Integer.parseInt(pageGroup);
                    int c = Integer.parseInt(countGroup);
                    if (p >= 1) {
                        LauncherState.page = p;
                        LauncherState.pageCount = c;
                        LauncherState.updatedNanos = System.nanoTime();
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                scanNodeTree(child);
                child.recycle();
            }
        }
    }

    @Override
    public void onInterrupt() {
        LauncherState.serviceConnected = false;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        LauncherState.serviceConnected = false;
        return super.onUnbind(intent);
    }
}
