package com.fxsoundlite.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Third home-screen widget: a 1x1 shortcut that opens the app straight on its Screensaver page
 * (MainActivity.ACTION_OPEN_SCREENSAVER). No state, so it only needs the click wiring on update.
 */
class ScreensaverWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pi = PendingIntent.getActivity(
            context, 20,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_SCREENSAVER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_screensaver)
            views.setOnClickPendingIntent(R.id.screensaverWidgetRoot, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
