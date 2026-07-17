package dev.jaimin.auraorbit;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidLiveWallpaperService;

public class MyWallpaperService extends AndroidLiveWallpaperService {
    private TouchOverlayView overlayView;
    private WindowManager.LayoutParams overlayParams;
    private boolean isOverlayAdded = false;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
    }

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

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (!visible) {
                removeOverlay();
            }
        }

        @Override
        public void onDestroy() {
            removeOverlay();
            super.onDestroy();
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

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    public void updateOverlay(boolean interactive, int centerX, int centerY, int size) {
        boolean blockEnabled = prefs.getBoolean("pref_block_launcher_gestures", false);
        android.util.Log.d("MyWallpaperService", "updateOverlay: blockEnabled=" + blockEnabled + ", interactive=" + interactive + ", size=" + size);

        if (!blockEnabled || !interactive || size <= 0) {
            removeOverlay();
            return;
        }

        boolean canDraw = Settings.canDrawOverlays(this);
        android.util.Log.d("MyWallpaperService", "updateOverlay: canDrawOverlays=" + canDraw);
        if (!canDraw) {
            return;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (overlayView == null) {
                overlayView = new TouchOverlayView(this);
            }

            if (overlayParams == null) {
                overlayParams = new WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                );
                overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
            }

            overlayParams.width = size;
            overlayParams.height = size;
            overlayParams.x = centerX - size / 2;
            overlayParams.y = centerY - size / 2;

            try {
                if (!isOverlayAdded) {
                    android.util.Log.d("MyWallpaperService", "Adding overlay view of size " + size + " at (" + overlayParams.x + "," + overlayParams.y + ")");
                    wm.addView(overlayView, overlayParams);
                    isOverlayAdded = true;
                } else {
                    android.util.Log.d("MyWallpaperService", "Updating overlay view to size " + size + " at (" + overlayParams.x + "," + overlayParams.y + ")");
                    wm.updateViewLayout(overlayView, overlayParams);
                }
            } catch (Exception e) {
                android.util.Log.e("MyWallpaperService", "Error in wm.addView / updateViewLayout", e);
            }
        });
    }

    private void removeOverlay() {
        if (!isOverlayAdded || overlayView == null) return;
        android.util.Log.d("MyWallpaperService", "removeOverlay requested");
        new Handler(Looper.getMainLooper()).post(() -> {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            try {
                if (isOverlayAdded && overlayView != null) {
                    android.util.Log.d("MyWallpaperService", "Removing overlay view from WindowManager");
                    wm.removeView(overlayView);
                    isOverlayAdded = false;
                }
            } catch (Exception e) {
                android.util.Log.e("MyWallpaperService", "Error in wm.removeView", e);
            }
        });
    }

    private class TouchOverlayView extends View {
        public TouchOverlayView(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // Forward touch event to the libGDX GL surface view!
            if (app != null) {
                com.badlogic.gdx.Graphics g = app.getGraphics();
                if (g instanceof com.badlogic.gdx.backends.android.AndroidGraphics) {
                    View v = ((com.badlogic.gdx.backends.android.AndroidGraphics) g).getView();
                    if (v != null) {
                        return v.dispatchTouchEvent(event);
                    }
                }
            }
            return false;
        }
    }
}
