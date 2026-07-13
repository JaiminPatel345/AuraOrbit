package dev.jaimin.auraorbit;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidLiveWallpaperService;

public class MyWallpaperService extends AndroidLiveWallpaperService {
    @Override
    public void onCreateApplication() {
        super.onCreateApplication();
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useCompass = false;
        config.useAccelerometer = false;
        config.r = 8; config.g = 8; config.b = 8; config.a = 8;
        config.depth = 16;
        
        // Pass null for pinnedGroupName to indicate this is the Permanent Sphere wallpaper.
        SphereEngine engine = new SphereEngine(this, false, null);
        initialize(engine, config);
    }
}
