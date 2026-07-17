package dev.jaimin.auraorbit;

import android.service.wallpaper.WallpaperService;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidLiveWallpaperService;

public class MyWallpaperService extends AndroidLiveWallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new MyAndroidWallpaperEngine();
    }

    public class MyAndroidWallpaperEngine extends AndroidWallpaperEngine {
        @Override
        public void onZoomChanged(float zoom) {
            super.onZoomChanged(zoom);
            if (app != null && app.getApplicationListener() instanceof SphereEngine) {
                ((SphereEngine) app.getApplicationListener()).onWallpaperZoom(zoom);
            }
        }
    }

    @Override
    public void onCreateApplication() {
        super.onCreateApplication();
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useGL30 = false;
        config.useCompass = false;
        config.useWakelock = false;
        config.useAccelerometer = false;
        config.getTouchEventsForLiveWallpaper = true;
        
        initialize(new SphereEngine(this), config);
    }
}
