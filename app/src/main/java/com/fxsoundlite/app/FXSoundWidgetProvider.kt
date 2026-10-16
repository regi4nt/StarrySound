package com.fxsoundlite.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast

/**
 * Home-screen widget, always ONE cell tall: a round on/off button, the current preset (tap to
 * cycle) with an ON/OFF status line, and on wide sizes previous / next preset buttons. What it
 * shows depends on its width only (see the table in the companion object).
 *
 * Reading state here (AudioEffectManager(context).isEnabled / prefs) is
 * always safe from a bare Context: the constructor only touches
 * SharedPreferences, it never calls initialize() -- that's the one call
 * that actually creates AudioEffect instances (see AudioEffectManager's
 * class doc on why only ONE such instance, owned by EffectService, should
 * ever exist). Any actual audio change the widget triggers is therefore
 * always forwarded to EffectService instead of touching effects directly
 * here (see ACTION_TOGGLE / ACTION_SET_PRESET there).
 */
class FXSoundWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_POWER = "com.fxsoundlite.app.widget.ACTION_TOGGLE_POWER"
        const val ACTION_CYCLE_PRESET = "com.fxsoundlite.app.widget.ACTION_CYCLE_PRESET"

        /** Sent by the launcher after the user confirms a widget requested via
         *  AppWidgetManager.requestPinAppWidget() from SettingsActivity. */
        const val ACTION_WIDGET_PINNED = "com.fxsoundlite.app.widget.ACTION_WIDGET_PINNED"

        /** Width breakpoints (dp, AppWidgetManager's OPTION_APPWIDGET_MIN_WIDTH units; hosts report
         *  roughly 40-75dp for one cell, 110-150dp for two, 180dp+ for three):
         *
         *   1 col     -> widget_fxsound_power     (on/off button only)
         *   2 cols    -> widget_fxsound_row       (on/off, preset)
         *   3 cols    -> widget_fxsound_row_wide  (on/off, preset + next arrow)
         *   4+ cols   -> widget_fxsound_row_xwide (on/off, preset, previous, next)
         *
         *  The widget is one cell tall (maxResizeHeight in the provider XML). */
        private const val MULTI_COL_MIN_DP = 90
        private const val WIDE_MIN_DP = 150
        private const val ROW_XWIDE_MIN_DP = 240

        /** Called from AudioEffectManager itself whenever isEnabled or the
         *  active preset changes, from ANY trigger (app UI, the widget, or
         *  state restored at boot) -- so every widget instance on the home
         *  screen always reflects reality, not just right after being tapped. */
        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, FXSoundWidgetProvider::class.java))
            for (id in ids) pushUpdate(context, mgr, id)
        }

        /** Picks which widget layout fits the width the host has actually given this instance. */
        private fun resolveLayoutRes(minWidthDp: Int): Int = when {
            minWidthDp < MULTI_COL_MIN_DP -> R.layout.widget_fxsound_power
            minWidthDp < WIDE_MIN_DP -> R.layout.widget_fxsound_row
            minWidthDp < ROW_XWIDE_MIN_DP -> R.layout.widget_fxsound_row_wide
            else -> R.layout.widget_fxsound_row_xwide
        }

        private fun pushUpdate(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
            val em = AudioEffectManager(context) // prefs read only -- see class doc above
            val enabled = em.isEnabled
            val presetName = em.prefs.getString("last_preset", "Default") ?: "Default"

            // Options are populated by the host as soon as the widget exists
            // (at the very first onUpdate, not just after a later resize), so
            // this reflects the real current size on every call, not just
            // the default size the manifest asks the host to drop it at.
            val options = mgr.getAppWidgetOptions(appWidgetId)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            val layoutRes = resolveLayoutRes(minWidthDp)

            val views = RemoteViews(context.packageName, layoutRes)
            val powerBg = if (enabled) R.drawable.widget_power_bg_on else R.drawable.widget_power_bg_off
            views.setInt(R.id.widgetPowerButton, "setBackgroundResource", powerBg)
            views.setOnClickPendingIntent(R.id.widgetPowerButton, togglePendingIntent(context))

            // Every layout has the on/off button and a card root that opens the app.
            views.setOnClickPendingIntent(R.id.widgetCompactRoot, openAppPendingIntent(context))

            // Every layout except the 1x1 shows the preset block (name + ON/OFF status).
            if (layoutRes != R.layout.widget_fxsound_power) {
                views.setTextViewText(R.id.widgetPresetName, presetName)
                views.setTextViewText(R.id.widgetStatusText,
                    context.getString(if (enabled) R.string.widget_status_on else R.string.widget_status_off))
                views.setTextColor(R.id.widgetStatusText, if (enabled) 0xFF7FB2FF.toInt() else 0xFF8A91A0.toInt())
                views.setOnClickPendingIntent(R.id.widgetPresetRow, cyclePresetPendingIntent(context))
            }

            // Previous / next preset buttons (widest layout): same EffectService actions as the
            // notification's buttons. Like there, they only act while effects are on, so they are
            // dimmed while off.
            if (layoutRes == R.layout.widget_fxsound_row_xwide) {
                val alpha = if (enabled) 255 else 90
                views.setInt(R.id.widgetPrevPreset, "setImageAlpha", alpha)
                views.setInt(R.id.widgetNextPreset, "setImageAlpha", alpha)
                views.setOnClickPendingIntent(R.id.widgetPrevPreset,
                    stepPresetPendingIntent(context, 5, EffectService.ACTION_PREV_PRESET))
                views.setOnClickPendingIntent(R.id.widgetNextPreset,
                    stepPresetPendingIntent(context, 6, EffectService.ACTION_NEXT_PRESET))
            }

            mgr.updateAppWidget(appWidgetId, views)
        }

        private fun openAppPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )

        private fun togglePendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 1,
                Intent(context, FXSoundWidgetProvider::class.java).setAction(ACTION_TOGGLE_POWER),
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )

        private fun cyclePresetPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 2,
                Intent(context, FXSoundWidgetProvider::class.java).setAction(ACTION_CYCLE_PRESET),
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )

        private fun stepPresetPendingIntent(context: Context, requestCode: Int, action: String): PendingIntent =
            PendingIntent.getForegroundService(
                context, requestCode,
                Intent(context, EffectService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )

        private fun immutableFlag(): Int = PendingIntent.FLAG_IMMUTABLE

        /** Next preset in Presets.ALL after `current`, wrapping around -- lets
         *  a single tap step through every preset (including "Auto") without
         *  needing to open the app. */
        private fun nextPresetName(current: String): String {
            val list = Presets.ALL
            val idx = list.indexOfFirst { it.name == current }
            val next = if (idx == -1) 0 else (idx + 1) % list.size
            return list[next].name
        }

        private fun startEffectService(context: Context, intent: Intent) {
            context.startForegroundService(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) pushUpdate(context, appWidgetManager, id)
    }

    /** Called by the host whenever this instance is resized (dragged bigger
     *  or smaller), with the new size already available via
     *  appWidgetManager.getAppWidgetOptions(). Re-running pushUpdate here
     *  re-picks the layout for the new size, so resizing a widget narrower
     *  or wider swaps layouts live instead of keeping whatever was
     *  first inflated at drop time. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        pushUpdate(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_POWER -> {
                val svcIntent = Intent(context, EffectService::class.java)
                    .setAction(EffectService.ACTION_TOGGLE)
                startEffectService(context, svcIntent)
            }
            ACTION_CYCLE_PRESET -> {
                val current = AudioEffectManager(context).prefs.getString("last_preset", "Default") ?: "Default"
                val svcIntent = Intent(context, EffectService::class.java)
                    .setAction(EffectService.ACTION_SET_PRESET)
                    .putExtra(EffectService.EXTRA_PRESET_NAME, nextPresetName(current))
                startEffectService(context, svcIntent)
            }
            ACTION_WIDGET_PINNED -> {
                Toast.makeText(context, R.string.widget_pinned, Toast.LENGTH_SHORT).show()
                updateAllWidgets(context)
            }
            else -> super.onReceive(context, intent) // APPWIDGET_UPDATE / ENABLED / DELETED / etc.
        }
    }
}
