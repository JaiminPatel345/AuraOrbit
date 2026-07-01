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
        java.util.List<GroupStore.Group> groups = GroupStore.load(prefs);

        for (int appWidgetId : appWidgetIds) {
            String groupName = prefs.getString("widget_group_" + appWidgetId, null);
            
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_sphere);
            views.setViewVisibility(R.id.widget_icon_container, visibility);
            
            if (visibility == View.VISIBLE) {
                if (groupName != null) {
                    views.setTextViewText(R.id.widget_label, groupName);
                    views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                    boolean hideLogo = prefs.getBoolean("pref_widget_hide_logo_" + groupName, false);
                    boolean transparent = prefs.getBoolean("pref_widget_transparent_" + groupName, false);
                    if (transparent || hideLogo) {
                        views.setInt(R.id.widget_icon_container, "setBackgroundColor", android.graphics.Color.TRANSPARENT);
                    } else {
                        views.setInt(R.id.widget_icon_container, "setBackgroundResource", R.drawable.rounded_bg_solid);
                    }
                    if (hideLogo) {
                        views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                        views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                    } else if (WidgetLogoStore.exists(context, groupName)) {
                        views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                        views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.VISIBLE);
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(WidgetLogoStore.file(context, groupName).getAbsolutePath());
                        if (bitmap != null) {
                            views.setImageViewBitmap(R.id.widget_custom_logo, bitmap);
                        }
                    } else {
                        views.setViewVisibility(R.id.widget_icon_planet, View.VISIBLE);
                        views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                        GroupStore.Group group = GroupStore.find(groups, groupName);
                        if (group != null) {
                            boolean useThemeColor = prefs.getBoolean("pref_widget_use_theme_color_" + groupName, true);
                            try {
                                if (useThemeColor) {
                                    views.setInt(R.id.widget_icon_ring, "setColorFilter", context.getColor(R.color.widget_theme_color));
                                } else {
                                    views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.parseColor(group.color));
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
                            // don't set intent so it doesn't open deleted group
                        }
                    }
                } else {
                    String widgetName = prefs.getString("pref_widget_name", "All");
                    boolean transparent = prefs.getBoolean("pref_widget_transparent", false);
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
                        views.setTextViewText(R.id.widget_label, widgetName);
                        views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                        views.setViewVisibility(R.id.widget_label, View.VISIBLE);
                    }
                }
                
                if (groupName != null) {
                    boolean hideGroupText = prefs.getBoolean("pref_widget_hide_text_" + groupName, false);
                    if (hideGroupText) {
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
            if (groupName != null) {
                intent.putExtra("group_name", groupName);
            }
            if (groupName != null && GroupStore.find(groups, groupName) == null) {
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
        java.util.List<GroupStore.Group> groups = GroupStore.load(prefs);

        for (int appWidgetId : appWidgetIds) {
            String groupName = prefs.getString("widget_group_" + appWidgetId, null);

            Intent intent = new Intent(context, SphereModeActivity.class);
            // Set flags to clear any previous instance of the activity
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (groupName != null) {
                intent.putExtra("group_name", groupName);
            }
            if (groupName != null && GroupStore.find(groups, groupName) == null) {
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
            
            if (groupName != null) {
                views.setTextViewText(R.id.widget_label, groupName);
                views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                boolean hideLogo = prefs.getBoolean("pref_widget_hide_logo_" + groupName, false);
                boolean transparent = prefs.getBoolean("pref_widget_transparent_" + groupName, false);
                if (transparent || hideLogo) {
                    views.setInt(R.id.widget_icon_container, "setBackgroundColor", android.graphics.Color.TRANSPARENT);
                } else {
                    views.setInt(R.id.widget_icon_container, "setBackgroundResource", R.drawable.rounded_bg_solid);
                }
                if (hideLogo) {
                    views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                    views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                    views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                } else if (WidgetLogoStore.exists(context, groupName)) {
                    views.setViewVisibility(R.id.widget_icon_planet, View.GONE);
                    views.setViewVisibility(R.id.widget_icon_ring, View.GONE);
                    views.setViewVisibility(R.id.widget_custom_logo, View.VISIBLE);
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(WidgetLogoStore.file(context, groupName).getAbsolutePath());
                    if (bitmap != null) {
                        views.setImageViewBitmap(R.id.widget_custom_logo, bitmap);
                    }
                } else {
                    views.setViewVisibility(R.id.widget_icon_planet, View.VISIBLE);
                    views.setViewVisibility(R.id.widget_custom_logo, View.GONE);
                    GroupStore.Group group = GroupStore.find(groups, groupName);
                    if (group != null) {
                        boolean useThemeColor = prefs.getBoolean("pref_widget_use_theme_color_" + groupName, true);
                        try {
                            if (useThemeColor) {
                                views.setInt(R.id.widget_icon_ring, "setColorFilter", context.getColor(R.color.widget_theme_color));
                            } else {
                                views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.parseColor(group.color));
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
                boolean hideGroupText = prefs.getBoolean("pref_widget_hide_text_" + groupName, false);
                if (hideGroupText) {
                    views.setViewVisibility(R.id.widget_label, View.GONE);
                    views.setTextViewText(R.id.widget_label, "");
                } else {
                    views.setTextViewText(R.id.widget_label, groupName);
                    views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                    views.setViewVisibility(R.id.widget_label, View.VISIBLE);
                }
            } else {
                String widgetName = prefs.getString("pref_widget_name", "All");
                boolean transparent = prefs.getBoolean("pref_widget_transparent", false);
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
                    views.setTextViewText(R.id.widget_label, widgetName);
                    views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                    views.setViewVisibility(R.id.widget_label, View.VISIBLE);
                }
            }
            
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }

    public static void updateAllWidgets(Context context) {
        Intent intent = new Intent(context, SphereWidgetProvider.class);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        android.content.ComponentName thisWidget = new android.content.ComponentName(context, SphereWidgetProvider.class);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
        context.sendBroadcast(intent);
    }
}
