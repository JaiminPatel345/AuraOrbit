package dev.jaimin.auraorbit;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

public class SphereWidgetProvider extends AppWidgetProvider {
    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if ("dev.jaimin.auraorbit.WIDGET_HIDE".equals(action)) {
            updateWidgetVisibility(context, View.INVISIBLE);
        } else if ("dev.jaimin.auraorbit.WIDGET_SHOW".equals(action)) {
            updateWidgetVisibility(context, View.VISIBLE);
        }
    }

    private void updateWidgetVisibility(Context context, int visibility) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        android.content.ComponentName thisWidget = new android.content.ComponentName(context, SphereWidgetProvider.class);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        java.util.List<WidgetStore.Widget> widgets = WidgetStore.load(prefs);

        for (int appWidgetId : appWidgetIds) {
            String widgetName = prefs.getString("widget_group_" + appWidgetId, null);
            
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_sphere);
            views.setViewVisibility(R.id.widget_icon_container, visibility);
            
            if (visibility == View.VISIBLE) {
                if (widgetName != null) {
                    views.setTextViewText(R.id.widget_label, widgetName);
                    views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                    boolean hideLogo = prefs.getBoolean("pref_widget_hide_logo_" + widgetName, false);
                    boolean transparent = prefs.getBoolean("pref_widget_transparent_" + widgetName, true);
                    if (transparent || hideLogo) {
                        views.setInt(R.id.widget_icon_container, "setBackgroundColor", android.graphics.Color.TRANSPARENT);
                    } else {
                        views.setInt(R.id.widget_icon_container, "setBackgroundResource", R.drawable.rounded_bg_solid);
                    }
                    if (hideLogo) {
                        views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                        views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                    } else if (WidgetLogoStore.exists(context, widgetName)) {
                        views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                        views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.VISIBLE);
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(WidgetLogoStore.file(context, widgetName).getAbsolutePath());
                        if (bitmap != null) {
                            views.setImageViewBitmap(R.id.widget_custom_logo, bitmap);
                        }
                    } else {
                        views.setViewVisibility(R.id.widget_icon_planet, View.VISIBLE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                        WidgetStore.Widget widget = WidgetStore.find(widgets, widgetName);
                        if (widget != null) {
                            boolean useThemeColor = prefs.getBoolean("pref_widget_use_theme_color_" + widgetName, true);
                            try {
                                if (useThemeColor) {
                                    views.setInt(R.id.widget_icon_ring, "setColorFilter", context.getColor(R.color.widget_theme_color));
                                } else {
                                    views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.parseColor(widget.color));
                                }
                                views.setViewVisibility(R.id.widget_icon_ring, View.VISIBLE);
                            } catch (Exception e) {
                                views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.WHITE);
                                views.setViewVisibility(R.id.widget_icon_ring, View.VISIBLE);
                            }
                        } else {
                            views.setTextViewText(R.id.widget_label, "Deleted");
                            views.setTextColor(R.id.widget_label, android.graphics.Color.RED);
                            views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.RED);
                            views.setViewVisibility(R.id.widget_icon_ring, View.VISIBLE);
                            // don't set intent so it doesn't open deleted widget
                        }
                    }
                } else {
                    String defaultWidgetName = prefs.getString("pref_widget_name", "All");
                    boolean transparent = prefs.getBoolean("pref_widget_transparent", true);
                    boolean hideText = prefs.getBoolean("pref_widget_hide_text", false);

                    if (transparent) {
                        views.setInt(R.id.widget_icon_container, "setBackgroundColor", android.graphics.Color.TRANSPARENT);
                    } else {
                        views.setInt(R.id.widget_icon_container, "setBackgroundResource", R.drawable.rounded_bg_solid);
                    }

                    if (prefs.getBoolean("pref_widget_hide_logo", false)) {
                        views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                        views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                    } else if (WidgetLogoStore.exists(context)) {
                        views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                        views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.VISIBLE);
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(WidgetLogoStore.file(context).getAbsolutePath());
                        if (bitmap != null) {
                            views.setImageViewBitmap(R.id.widget_custom_logo, bitmap);
                        }
                    } else {
                        views.setViewVisibility(R.id.widget_icon_planet, View.VISIBLE);
                        views.setViewVisibility(R.id.widget_icon_ring, View.VISIBLE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                        String orbitColor = prefs.getString("pref_widget_orbit_color", "#FFFFFF");
                        boolean useThemeColor = prefs.getBoolean("pref_widget_use_theme_color", true);
                        try {
                            if (useThemeColor) {
                                views.setInt(R.id.widget_icon_ring, "setColorFilter", context.getColor(R.color.widget_theme_color));
                            } else {
                                views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.parseColor(orbitColor));
                            }
                        } catch (Exception e) {
                            views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.WHITE);
                        }
                    }

                    if (hideText) {
                        views.setViewVisibility(R.id.widget_label, View.GONE);
                        views.setTextViewText(R.id.widget_label, "");
                    } else {
                        views.setTextViewText(R.id.widget_label, defaultWidgetName);
                        views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                        views.setViewVisibility(R.id.widget_label, View.VISIBLE);
                    }
                }
                
                if (widgetName != null) {
                    boolean hideWidgetText = prefs.getBoolean("pref_widget_hide_text_" + widgetName, false);
                    if (hideWidgetText) {
                        views.setViewVisibility(R.id.widget_label, View.GONE);
                        views.setTextViewText(R.id.widget_label, "");
                    } else {
                        views.setViewVisibility(R.id.widget_label, View.VISIBLE);
                    }
                }
            } else {
                views.setViewVisibility(R.id.widget_label, View.GONE);
                views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
            }
            
            Intent intent = new Intent(context, SphereModeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (widgetName != null) {
                intent.putExtra("widget_name", widgetName);
            }
            if (widgetName != null && WidgetStore.find(widgets, widgetName) == null) {
                intent = new Intent(context, DeletedWidgetActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, appWidgetId, intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent);
            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        java.util.List<WidgetStore.Widget> widgets = WidgetStore.load(prefs);

        for (int appWidgetId : appWidgetIds) {
            String widgetName = prefs.getString("widget_group_" + appWidgetId, null);

            Intent intent = new Intent(context, SphereModeActivity.class);
            // Set flags to clear any previous instance of the activity
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (widgetName != null) {
                intent.putExtra("widget_name", widgetName);
            }
            if (widgetName != null && WidgetStore.find(widgets, widgetName) == null) {
                intent = new Intent(context, DeletedWidgetActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            }
            
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, 
                    appWidgetId, 
                    intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_sphere);
            
            if (widgetName != null) {
                views.setTextViewText(R.id.widget_label, widgetName);
                views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                boolean hideLogo = prefs.getBoolean("pref_widget_hide_logo_" + widgetName, false);
                boolean transparent = prefs.getBoolean("pref_widget_transparent_" + widgetName, true);
                if (transparent || hideLogo) {
                    views.setInt(R.id.widget_icon_container, "setBackgroundColor", android.graphics.Color.TRANSPARENT);
                } else {
                    views.setInt(R.id.widget_icon_container, "setBackgroundResource", R.drawable.rounded_bg_solid);
                }
                if (hideLogo) {
                    views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                    views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                    views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                } else if (WidgetLogoStore.exists(context, widgetName)) {
                    views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                    views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                    views.setViewVisibility(R.id.widget_custom_logo, View.VISIBLE);
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(WidgetLogoStore.file(context, widgetName).getAbsolutePath());
                    if (bitmap != null) {
                        views.setImageViewBitmap(R.id.widget_custom_logo, bitmap);
                    }
                } else {
                    IconPackManager iconPackManager = IconPackManager.getInstance(context);
                    android.graphics.drawable.Drawable themedIcon = iconPackManager.getIcon("ComponentInfo{dev.jaimin.auraorbit/dev.jaimin.auraorbit.LiveWallpaperSettings}");
                    if (themedIcon == null) {
                        themedIcon = iconPackManager.getIcon("ComponentInfo{dev.jaimin.auraorbit/dev.jaimin.auraorbit.SphereModeActivity}");
                    }
                    
                    if (themedIcon != null) {
                        views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                        views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.VISIBLE);
                        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(themedIcon.getIntrinsicWidth() > 0 ? themedIcon.getIntrinsicWidth() : 192, themedIcon.getIntrinsicHeight() > 0 ? themedIcon.getIntrinsicHeight() : 192, android.graphics.Bitmap.Config.ARGB_8888);
                        android.graphics.Canvas canvas = new android.graphics.Canvas(b);
                        themedIcon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                        themedIcon.draw(canvas);
                        views.setImageViewBitmap(R.id.widget_custom_logo, b);
                    } else {
                        views.setViewVisibility(R.id.widget_icon_planet, View.VISIBLE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                    }
                    WidgetStore.Widget widget = WidgetStore.find(widgets, widgetName);
                    if (widget != null) {
                        boolean useThemeColor = prefs.getBoolean("pref_widget_use_theme_color_" + widgetName, true);
                        try {
                            if (useThemeColor) {
                                views.setInt(R.id.widget_icon_ring, "setColorFilter", context.getColor(R.color.widget_theme_color));
                            } else {
                                views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.parseColor(widget.color));
                            }
                            views.setViewVisibility(R.id.widget_icon_ring, View.VISIBLE);
                        } catch (Exception e) {
                            views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.WHITE);
                            views.setViewVisibility(R.id.widget_icon_ring, View.VISIBLE);
                        }
                    } else {
                        views.setTextViewText(R.id.widget_label, "Deleted");
                        views.setTextColor(R.id.widget_label, android.graphics.Color.RED);
                        views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.RED);
                        views.setViewVisibility(R.id.widget_icon_ring, View.VISIBLE);
                    }
                }
                boolean hideWidgetText = prefs.getBoolean("pref_widget_hide_text_" + widgetName, false);
                if (hideWidgetText) {
                    views.setViewVisibility(R.id.widget_label, View.GONE);
                    views.setTextViewText(R.id.widget_label, "");
                } else {
                    views.setTextViewText(R.id.widget_label, widgetName);
                    views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                    views.setViewVisibility(R.id.widget_label, View.VISIBLE);
                }
            } else {
                String defaultWidgetName = prefs.getString("pref_widget_name", "All");
                boolean transparent = prefs.getBoolean("pref_widget_transparent", true);
                boolean hideText = prefs.getBoolean("pref_widget_hide_text", false);

                if (transparent) {
                    views.setInt(R.id.widget_icon_container, "setBackgroundColor", android.graphics.Color.TRANSPARENT);
                } else {
                    views.setInt(R.id.widget_icon_container, "setBackgroundResource", R.drawable.rounded_bg_solid);
                }

                if (prefs.getBoolean("pref_widget_hide_logo", false)) {
                    views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                    views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                    views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                } else if (WidgetLogoStore.exists(context)) {
                    views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                    views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                    views.setViewVisibility(R.id.widget_custom_logo, View.VISIBLE);
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(WidgetLogoStore.file(context).getAbsolutePath());
                    if (bitmap != null) {
                        views.setImageViewBitmap(R.id.widget_custom_logo, bitmap);
                    }
                } else {
                    IconPackManager iconPackManager = IconPackManager.getInstance(context);
                    android.graphics.drawable.Drawable themedIcon = iconPackManager.getIcon("ComponentInfo{dev.jaimin.auraorbit/dev.jaimin.auraorbit.LiveWallpaperSettings}");
                    if (themedIcon == null) {
                        themedIcon = iconPackManager.getIcon("ComponentInfo{dev.jaimin.auraorbit/dev.jaimin.auraorbit.SphereModeActivity}");
                    }
                    
                    if (themedIcon != null) {
                        views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                        views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.VISIBLE);
                        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(themedIcon.getIntrinsicWidth() > 0 ? themedIcon.getIntrinsicWidth() : 192, themedIcon.getIntrinsicHeight() > 0 ? themedIcon.getIntrinsicHeight() : 192, android.graphics.Bitmap.Config.ARGB_8888);
                        android.graphics.Canvas canvas = new android.graphics.Canvas(b);
                        themedIcon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                        themedIcon.draw(canvas);
                        views.setImageViewBitmap(R.id.widget_custom_logo, b);
                    } else {
                        views.setViewVisibility(R.id.widget_icon_planet, View.VISIBLE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                    }
                    views.setViewVisibility(R.id.widget_icon_ring, View.VISIBLE);
                    String orbitColor = prefs.getString("pref_widget_orbit_color", "#FFFFFF");
                    boolean useThemeColor = prefs.getBoolean("pref_widget_use_theme_color", true);
                    try {
                        if (useThemeColor) {
                            views.setInt(R.id.widget_icon_ring, "setColorFilter", context.getColor(R.color.widget_theme_color));
                        } else {
                            views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.parseColor(orbitColor));
                        }
                    } catch (Exception e) {
                        views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.WHITE);
                    }
                }

                if (hideText) {
                    views.setViewVisibility(R.id.widget_label, View.GONE);
                    views.setTextViewText(R.id.widget_label, "");
                } else {
                    views.setTextViewText(R.id.widget_label, defaultWidgetName);
                    views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                    views.setViewVisibility(R.id.widget_label, View.VISIBLE);
                }
            }
            
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }

    public static void updateAllWidgets(Context context) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        android.content.ComponentName thisWidget = new android.content.ComponentName(context, SphereWidgetProvider.class);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
        new SphereWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds);
    }
}
