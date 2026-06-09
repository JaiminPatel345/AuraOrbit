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
                    GroupStore.Group group = GroupStore.find(groups, groupName);
                    if (group != null) {
                        try {
                            int color = android.graphics.Color.parseColor(group.color);
                            views.setInt(R.id.widget_icon_ring, "setColorFilter", color);
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
                } else {
                    views.setTextViewText(R.id.widget_label, "All");
                    views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                    views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.WHITE);
                    views.setViewVisibility(R.id.widget_icon_ring, View.VISIBLE);
                }
                views.setViewVisibility(R.id.widget_label, View.VISIBLE);
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
                intent.putExtra("group_deleted", true);
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
                intent.putExtra("group_deleted", true);
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
                GroupStore.Group group = GroupStore.find(groups, groupName);
                if (group != null) {
                    try {
                        int color = android.graphics.Color.parseColor(group.color);
                        views.setInt(R.id.widget_icon_ring, "setColorFilter", color);
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
            } else {
                views.setTextViewText(R.id.widget_label, "All");
                views.setTextColor(R.id.widget_label, android.graphics.Color.WHITE);
                views.setInt(R.id.widget_icon_ring, "setColorFilter", android.graphics.Color.WHITE);
                views.setViewVisibility(R.id.widget_icon_ring, View.VISIBLE);
            }
            views.setViewVisibility(R.id.widget_label, View.VISIBLE);
            
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
