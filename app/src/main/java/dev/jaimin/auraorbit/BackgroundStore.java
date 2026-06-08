package dev.jaimin.auraorbit;

import android.content.Context;
import android.net.Uri;

public class BackgroundStore {
    public static final String PREF_BACKGROUND_VERSION = "bg_ver";
    public static boolean exists(Context c) { return false; }
    public static void clear(Context c) {}
    public static boolean saveFromUri(Context c, Uri u) { return false; }
    public static java.io.File file(Context c) { return null; }
}
