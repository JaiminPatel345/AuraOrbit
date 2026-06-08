package dev.jaimin.auraorbit;

public class LauncherStateService {
    public static class LauncherState {
        public static boolean serviceConnected = false;
        public static long updatedNanos = 0;
        public static int page = 0;
        public static int pageCount = 0;
        public static boolean drawerOpen = false;
        public static boolean systemUiVisible = false;
    }
    public static void updateOverlayState(boolean b, int i) {}
}
