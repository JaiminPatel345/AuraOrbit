package dev.jaimin.auraorbit;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IconPackManager {

    private static final String TAG = "IconPackManager";
    public static final String PREF_ICON_PACK = "pref_icon_pack";
    
    private static IconPackManager sInstance;
    private Context mContext;
    private String mCurrentIconPack;
    private Map<String, String> mPackagesDrawables = new HashMap<>();
    private Resources mIconPackRes = null;

    private IconPackManager(Context context) {
        mContext = context.getApplicationContext();
        loadIconPack(androidx.preference.PreferenceManager.getDefaultSharedPreferences(mContext).getString(PREF_ICON_PACK, null));
    }

    public static synchronized IconPackManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new IconPackManager(context);
        }
        return sInstance;
    }

    public static class IconPackInfo {
        public String packageName;
        public String label;
        public Drawable icon;
    }

    public static List<IconPackInfo> getAvailableIconPacks(Context context) {
        PackageManager pm = context.getPackageManager();
        List<IconPackInfo> packs = new ArrayList<>();
        
        String[] intentActions = new String[]{
                "org.adw.launcher.THEMES",
                "com.gau.go.launcherex.theme",
                "com.novalauncher.THEME",
                "com.fede.launcher.THEME_ICONPACK"
        };
        
        List<String> addedPackages = new ArrayList<>();
        
        for (String action : intentActions) {
            Intent intent = new Intent(action);
            List<ResolveInfo> resolves = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA);
            for (ResolveInfo info : resolves) {
                String pkg = info.activityInfo.packageName;
                if (!addedPackages.contains(pkg)) {
                    addedPackages.add(pkg);
                    IconPackInfo packInfo = new IconPackInfo();
                    packInfo.packageName = pkg;
                    packInfo.label = info.loadLabel(pm).toString();
                    packInfo.icon = info.loadIcon(pm);
                    packs.add(packInfo);
                }
            }
        }
        return packs;
    }

    public void loadIconPack(String packageName) {
        mCurrentIconPack = packageName;
        mPackagesDrawables.clear();
        mIconPackRes = null;

        if (packageName == null || packageName.isEmpty()) {
            return;
        }

        try {
            PackageManager pm = mContext.getPackageManager();
            mIconPackRes = pm.getResourcesForApplication(packageName);

            int resId = mIconPackRes.getIdentifier("appfilter", "xml", packageName);
            if (resId > 0) {
                XmlPullParser xpp = mIconPackRes.getXml(resId);
                int eventType = xpp.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && xpp.getName().equals("item")) {
                        String component = xpp.getAttributeValue(null, "component");
                        String drawable = xpp.getAttributeValue(null, "drawable");
                        if (component != null && drawable != null && component.startsWith("ComponentInfo{")) {
                            mPackagesDrawables.put(component, drawable);
                        }
                    }
                    eventType = xpp.next();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load icon pack: " + packageName, e);
        }
    }

    public Drawable getIcon(String componentName) {
        if (mIconPackRes == null || mCurrentIconPack == null) return null;
        
        String drawableName = mPackagesDrawables.get(componentName);
        
        // If it's our app and unthemed, try to use a generic drawer icon
        if (drawableName == null && componentName != null && componentName.contains("dev.jaimin.auraorbit")) {
            String[] fallbacks = {"drawer", "all_apps", "ic_allapps", "sym_def_app_icon", "home", "launcher"};
            for (String fallback : fallbacks) {
                int resId = mIconPackRes.getIdentifier(fallback, "drawable", mCurrentIconPack);
                if (resId > 0) {
                    try {
                        return mIconPackRes.getDrawable(resId, null);
                    } catch (Exception e) {}
                }
            }
        }
        
        if (drawableName != null) {
            int resId = mIconPackRes.getIdentifier(drawableName, "drawable", mCurrentIconPack);
            if (resId > 0) {
                try {
                    return mIconPackRes.getDrawable(resId, null);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
