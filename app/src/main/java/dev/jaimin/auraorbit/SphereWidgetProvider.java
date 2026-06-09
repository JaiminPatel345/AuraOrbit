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

        for (int appWidgetId : appWidgetIds) {
            String groupName = prefs.getString("widget_group_" + appWidgetId, null);
            
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_sphere);
            views.setViewVisibility(R.id.widget_icon, visibility);
            
            if (groupName != null && visibility == View.VISIBLE) {
                views.setTextViewText(R.id.widget_label, groupName);
                views.setViewVisibility(R.id.widget_label, View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.widget_label, View.GONE);
            }
            
            Intent intent = new Intent(context, SphereModeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (groupName != null) {
                intent.putExtra("group_name", groupName);
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

        for (int appWidgetId : appWidgetIds) {
            String groupName = prefs.getString("widget_group_" + appWidgetId, null);

            Intent intent = new Intent(context, SphereModeActivity.class);
            // Set flags to clear any previous instance of the activity
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (groupName != null) {
                intent.putExtra("group_name", groupName);
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
                views.setViewVisibility(R.id.widget_label, View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.widget_label, View.GONE);
            }
            
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }
}
