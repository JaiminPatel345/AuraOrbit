package dev.jaimin.auraorbit;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

public class WidgetPinnedReceiver extends BroadcastReceiver {
    
    public static final String EXTRA_GROUP_NAME = "extra_group_name";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || context == null) return;
        
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        String groupName = intent.getStringExtra(EXTRA_GROUP_NAME);
        
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID && groupName != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit().putString("widget_group_" + widgetId, groupName).apply();
            
            // Force an update for this specific widget to apply its group styling
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            int[] appWidgetIds = new int[]{widgetId};
            
            new SphereWidgetProvider().onUpdate(context, appWidgetManager, appWidgetIds);
            
            android.widget.Toast.makeText(context, "Group Saved & Widget Pinned!", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
