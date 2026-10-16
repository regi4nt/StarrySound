package com.fxsoundlite.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Second home-screen widget, always ONE cell tall: a search pill plus a "Detect song" button (1 cell wide = search only,
 * 2 cells = the two as equal buttons).
 * RemoteViews can't host a real text field, so a tap on the pill just opens the app's Music page (MainActivity.ACTION_OPEN_MUSIC_SEARCH)
 * with the search box focused and the keyboard up, on whichever source was used last;
 * the mic button opens SongDetectActivity directly.
 * It has no state of its own, so it never needs a push update except on resize (the layout
 * drops the hint text on narrow sizes).
 */
class MusicSearchWidgetProvider : AppWidgetProvider() {

    companion object {
        /** Below this width (dp, AppWidgetManager's OPTION_APPWIDGET_MIN_WIDTH units) the hint
         *  text is dropped and the pill shows only the magnifier (4 cells ~ 250dp, 3 ~ 180dp). */
        private const val HINT_MIN_DP = 240
        /** Width breakpoints for the small sizes: 1 cell = search button only, 2 cells = search +
         *  detect buttons, 3 cells = search pill (icon) + detect, 4+ = pill with hint + detect. */
        private const val DUO_MIN_DP = 90
        private const val COMPACT_MIN_DP = 150

        private fun pushUpdate(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
            val minWidthDp = mgr.getAppWidgetOptions(appWidgetId)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val layoutRes = when {
                minWidthDp < DUO_MIN_DP -> R.layout.widget_music_search_mini
                minWidthDp < COMPACT_MIN_DP -> R.layout.widget_music_search_duo
                minWidthDp < HINT_MIN_DP -> R.layout.widget_music_search_compact
                else -> R.layout.widget_music_search
            }

            val views = RemoteViews(context.packageName, layoutRes)
            views.setOnClickPendingIntent(R.id.musicSearchRoot, searchPendingIntent(context, 10, MusicSearchRequest.LAST))
            views.setOnClickPendingIntent(R.id.musicSearchPill, searchPendingIntent(context, 10, MusicSearchRequest.LAST))
            if (layoutRes != R.layout.widget_music_search_mini) { // the 1x1 has no detect button
                views.setOnClickPendingIntent(R.id.musicDetectButton, detectPendingIntent(context))
            }
            mgr.updateAppWidget(appWidgetId, views)
        }

        /** Opens the song detection screen (sing / hum / playing nearby). */
        private fun detectPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 11,
                Intent(context, SongDetectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** Request code 10 is clear of the other widget's 0-6. */
        private fun searchPendingIntent(context: Context, requestCode: Int, source: Int): PendingIntent =
            PendingIntent.getActivity(
                context, requestCode,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_MUSIC_SEARCH)
                    .putExtra(MainActivity.EXTRA_SOURCE, source)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) pushUpdate(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        pushUpdate(context, appWidgetManager, appWidgetId)
    }
}

/** Marker values for MainActivity.EXTRA_SOURCE besides the real Track.SOURCE_* ones. */
object MusicSearchRequest {
    /** Keep whichever source the Music page was last on. */
    const val LAST = -1
}
