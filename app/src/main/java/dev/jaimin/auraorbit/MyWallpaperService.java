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
        bypassHiddenApiRestrictions();
        prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
    }

    private void bypassHiddenApiRestrictions() {
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L");
            android.util.Log.d("MyWallpaperService", "Successfully bypassed hidden API restrictions using LSPosed HiddenApiBypass");
        } catch (Throwable e) {
            android.util.Log.e("MyWallpaperService", "Failed to bypass hidden API restrictions using HiddenApiBypass", e);
        }
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

    public void updateOverlay(boolean interactive, int ignoredX, int ignoredY, int ignoredSize) {
        boolean blockEnabled = prefs.getBoolean("pref_block_launcher_gestures", false);
        boolean canDraw = Settings.canDrawOverlays(this);

        if (!blockEnabled || !canDraw || !interactive) {
            removeOverlay();
            return;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            
            // Read latest preferences to compute physical screen size and position
            int radiusPref = prefs.getInt("pref_sphere_radius", 50);
            int iconPref = prefs.getInt("pref_icon_size", 50);
            float scale = prefs.getFloat("pref_sphere_scale", 1.0f);
            String posType = prefs.getString("pref_sphere_position", "center");
            int currentPercent = prefs.getInt("pref_gesture_capture_scale_percent", 100);

            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;

            // Compute actual visual sizes in physical pixels
            float worldRadius = 3.0f + 5.0f * (radiusPref / 100f);
            float worldIconSize = 0.6f + 1.4f * (iconPref / 100f);
            float effRadius = worldRadius + worldIconSize * 0.75f;
            float baseDiameter = (effRadius * 2f * (screenWidth / 16f)) * scale;
            int size = (int) (baseDiameter * (currentPercent / 100f));

            if (size <= 0) {
                removeOverlay();
                return;
            }

            // Position calculations in physical pixels
            int centerX, centerY;
            if ("custom".equals(posType)) {
                float customX = prefs.getFloat("pref_sphere_x", 0f);
                float customY = prefs.getFloat("pref_sphere_y", (screenHeight - screenWidth) / 2f);
                centerX = (int) (customX + (screenWidth * scale) / 2f);
                centerY = (int) (customY + (screenWidth * scale) / 2f);
            } else if ("top".equals(posType)) {
                centerX = screenWidth / 2;
                centerY = (int) (screenHeight * 0.25f);
            } else if ("bottom".equals(posType)) {
                centerX = screenWidth / 2;
                centerY = (int) (screenHeight * 0.75f);
            } else { // "center"
                centerX = screenWidth / 2;
                centerY = screenHeight / 2;
            }

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
                overlayView.postInvalidate();
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
        private final android.graphics.Paint debugPaint = new android.graphics.Paint();
        {
            debugPaint.setColor(0xFFFF0000); // Red color
            debugPaint.setStyle(android.graphics.Paint.Style.STROKE);
            debugPaint.setStrokeWidth(6f);
            debugPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{15f, 15f}, 0f));
        }

        public TouchOverlayView(Context context) {
            super(context);
            // Ensure onDraw is called for debug bounds rendering
            setWillNotDraw(false);

            // Circular Touch Interception Region using reflection to bypass hidden API compile restrictions
            try {
                Class<?> listenerClass = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
                Class<?> insetsClass = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
                java.lang.reflect.Method addListenerMethod = getViewTreeObserver().getClass().getMethod(
                        "addOnComputeInternalInsetsListener", listenerClass);
                
                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{listenerClass},
                        (proxy, method, args1) -> {
                            if ("onComputeInternalInsets".equals(method.getName())) {
                                Object insets = args1[0];
                                
                                // insets.touchableRegion.setEmpty()
                                java.lang.reflect.Field regionField = insetsClass.getField("touchableRegion");
                                android.graphics.Region touchableRegion = (android.graphics.Region) regionField.get(insets);
                                touchableRegion.setEmpty();
                                
                                // insets.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
                                // TOUCHABLE_INSETS_REGION is constant 3
                                java.lang.reflect.Method setInsetsMethod = insetsClass.getMethod("setTouchableInsets", int.class);
                                setInsetsMethod.invoke(insets, 3);
                                
                                // Create circular path and region
                                android.graphics.Path path = new android.graphics.Path();
                                float r = getWidth() / 2f;
                                path.addCircle(r, r, r, android.graphics.Path.Direction.CW);
                                android.graphics.Region region = new android.graphics.Region(0, 0, getWidth(), getHeight());
                                region.setPath(path, region);
                                touchableRegion.set(region);
                            }
                            return null;
                        }
                );
                
                addListenerMethod.invoke(getViewTreeObserver(), listener);
            } catch (Exception e) {
                android.util.Log.e("MyWallpaperService", "Error setting circular touch bounds via reflection", e);
            }
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

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (prefs != null && prefs.getBoolean("pref_debug_gesture_bounds", false)) {
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f - 3f, debugPaint);
            }
        }
    }
}
