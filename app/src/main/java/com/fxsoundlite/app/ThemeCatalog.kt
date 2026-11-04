package com.fxsoundlite.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * One catalog for everything that changes the look, opened from the Quick bar ([QuickBar]) as a bottom sheet:
 *  - bundles: a scene theme + the matching Screensaver background in one tap;
 *  - mode (system / light / dark), scene theme, accent colour (the Settings > Appearance choices);
 *  - Screensaver animated background and Screensaver touch effect (the Settings > Screensaver choices).
 * Everything is saved the same way Settings saves it, so the two always agree. Theme and accent changes recreate the
 * screen so they show at once; Screensaver choices are picked up the next time that page is shown.
 */
object ThemeCatalog {

    private fun styleOf(scene: ThemeHelper.Scene) = when (scene) {
        ThemeHelper.Scene.RAIN -> ScreensaverPrefs.STYLE_RAIN
        ThemeHelper.Scene.NIGHT_SKY -> ScreensaverPrefs.STYLE_NIGHT_SKY
        ThemeHelper.Scene.STARFIELD -> ScreensaverPrefs.STYLE_STARFIELD
        ThemeHelper.Scene.WATER -> ScreensaverPrefs.STYLE_WATER
    }

    private fun accentColorRes(accent: ThemeHelper.Accent) = when (accent) {
        ThemeHelper.Accent.DEFAULT -> R.color.accent_default
        ThemeHelper.Accent.RED -> R.color.accent_red
        ThemeHelper.Accent.YELLOW -> R.color.accent_yellow
        ThemeHelper.Accent.BLUE -> R.color.accent_blue
        ThemeHelper.Accent.ORANGE -> R.color.accent_orange
        ThemeHelper.Accent.GREEN -> R.color.accent_green
        ThemeHelper.Accent.PURPLE -> R.color.accent_purple
        ThemeHelper.Accent.RAIN -> R.color.scene_rain_accent
        ThemeHelper.Accent.NIGHT_SKY -> R.color.scene_nightsky_accent
        ThemeHelper.Accent.STARFIELD -> R.color.scene_starfield_accent
        ThemeHelper.Accent.WATER -> R.color.scene_water_accent
    }

    private fun accentLabelRes(accent: ThemeHelper.Accent) = when (accent) {
        ThemeHelper.Accent.DEFAULT -> R.string.accent_default
        ThemeHelper.Accent.RED -> R.string.accent_red
        ThemeHelper.Accent.YELLOW -> R.string.accent_yellow
        ThemeHelper.Accent.BLUE -> R.string.accent_blue
        ThemeHelper.Accent.ORANGE -> R.string.accent_orange
        ThemeHelper.Accent.GREEN -> R.string.accent_green
        ThemeHelper.Accent.PURPLE -> R.string.accent_purple
        ThemeHelper.Accent.RAIN -> R.string.animated_rain
        ThemeHelper.Accent.NIGHT_SKY -> R.string.animated_night_sky
        ThemeHelper.Accent.STARFIELD -> R.string.animated_starfield
        ThemeHelper.Accent.WATER -> R.string.animated_water
    }

    fun show(a: Activity) {
        val dp = a.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val dialog = BottomSheetDialog(a)
        val textPrimary = CoverColors.themeColor(a, R.attr.colorTextPrimary)
        val textSecondary = CoverColors.themeColor(a, R.attr.colorTextSecondary)

        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CoverColors.themeColor(a, R.attr.colorSurface))
            setPadding(px(18), px(16), px(18), px(24))
        }

        fun title(text: String, sub: String? = null) {
            root.addView(TextView(a).apply {
                this.text = text
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(textPrimary)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(18) })
            if (sub != null) root.addView(TextView(a).apply {
                this.text = sub
                textSize = 12f
                setTextColor(textSecondary)
            })
        }

        /** A horizontally scrolling row; returns the row so chips can be added. */
        fun row(): LinearLayout {
            val inner = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            root.addView(HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false
                addView(inner)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
            return inner
        }

        /** A chip; [group] keeps the "selected" look on exactly one chip of its row. */
        fun chip(host: LinearLayout, label: String, dot: Int?, selected: Boolean, group: MutableList<TextView>?, onClick: () -> Unit) {
            val tv = TextView(a).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.chip_bg)
                isSelected = selected
                setTextColor(ContextCompat.getColorStateList(a, R.color.chip_text_color))
                setPadding(px(14), 0, px(14), 0)
                isClickable = true
                isFocusable = true
                if (dot != null) {
                    val d = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ContextCompat.getColor(a, dot))
                        setStroke(px(1), 0x66FFFFFF)
                        setSize(px(14), px(14))
                    }
                    setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
                    compoundDrawablePadding = px(8)
                }
            }
            group?.add(tv)
            tv.setOnClickListener {
                group?.forEach { it.isSelected = false }
                tv.isSelected = true
                onClick()
            }
            host.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(38)).apply { marginEnd = px(8) })
        }

        /** Re-applies the theme overlays by recreating the screen under the sheet. */
        fun applyTheme() {
            dialog.dismiss()
            a.recreate()
        }

        root.addView(TextView(a).apply {
            text = a.getString(R.string.theme_catalog_title)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textPrimary)
        })

        // 1. Bundles: scene theme + Screensaver background together.
        title(a.getString(R.string.theme_catalog_bundle), a.getString(R.string.theme_catalog_bundle_sub))
        val bundleRow = row()
        val scene = ThemeHelper.currentScene(a)
        ThemeHelper.Scene.entries.forEach { s ->
            val bundleActive = scene == s && ScreensaverPrefs.mediaType(a) == ScreensaverPrefs.TYPE_ANIMATED &&
                ScreensaverPrefs.animatedStyle(a) == styleOf(s)
            chip(bundleRow, a.getString(s.labelRes), s.bgRes, bundleActive, null) {
                ThemeHelper.setScene(a, s)                        // palette + its own accent
                ScreensaverPrefs.setAnimated(a, styleOf(s))       // matching Screensaver background
                ScreensaverPrefs.setTouchFx(a, ScreensaverTouchFx.Mode.AUTO) // touch effect follows the background
                applyTheme()
            }
        }

        // 2. Mode
        title(a.getString(R.string.theme_mode_label))
        val modeRow = row()
        val modeGroup = ArrayList<TextView>()
        val mode = ThemeHelper.currentMode(a)
        chip(modeRow, a.getString(R.string.theme_system_default), null, mode == ThemeHelper.MODE_SYSTEM, modeGroup) {
            ThemeHelper.setMode(a, ThemeHelper.MODE_SYSTEM); dialog.dismiss()
        }
        chip(modeRow, a.getString(R.string.theme_light), null, mode == ThemeHelper.MODE_LIGHT, modeGroup) {
            ThemeHelper.setMode(a, ThemeHelper.MODE_LIGHT); dialog.dismiss()
        }
        chip(modeRow, a.getString(R.string.theme_dark), null, mode == ThemeHelper.MODE_DARK, modeGroup) {
            ThemeHelper.setMode(a, ThemeHelper.MODE_DARK); dialog.dismiss()
        }

        // 3. Scene theme
        title(a.getString(R.string.theme_catalog_scene))
        val sceneRow = row()
        val sceneGroup = ArrayList<TextView>()
        chip(sceneRow, a.getString(R.string.scene_none), null, scene == null, sceneGroup) {
            ThemeHelper.setScene(a, null); applyTheme()
        }
        ThemeHelper.Scene.entries.forEach { s ->
            chip(sceneRow, a.getString(s.labelRes), s.bgRes, scene == s, sceneGroup) {
                ThemeHelper.setScene(a, s); applyTheme()
            }
        }

        // 4. Accent colour
        title(a.getString(R.string.theme_catalog_accent))
        val accentRow = row()
        val currentAccent = ThemeHelper.currentAccent(a)
        val checks = ArrayList<View>()
        ThemeHelper.Accent.entries.forEach { accent ->
            val cell = FrameLayout(a)
            val circle = View(a).apply {
                setBackgroundResource(R.drawable.accent_swatch_bg)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(a, accentColorRes(accent)))
            }
            cell.addView(circle, FrameLayout.LayoutParams(px(40), px(40)))
            val check = ImageView(a).apply {
                setImageResource(R.drawable.ic_check)
                visibility = if (accent == currentAccent) View.VISIBLE else View.GONE
            }
            checks.add(check)
            cell.addView(check, FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER).apply { })
            cell.contentDescription = a.getString(accentLabelRes(accent))
            cell.isClickable = true
            cell.isFocusable = true
            cell.setOnClickListener {
                checks.forEach { it.visibility = View.GONE }
                check.visibility = View.VISIBLE
                ThemeHelper.setAccent(a, accent)
                applyTheme()
            }
            accentRow.addView(cell, LinearLayout.LayoutParams(px(40), px(40)).apply { marginEnd = px(12) })
        }

        // 5. Screensaver background
        title(a.getString(R.string.theme_catalog_screensaver))
        val ssRow = row()
        val ssGroup = ArrayList<TextView>()
        val animated = ScreensaverPrefs.mediaType(a) == ScreensaverPrefs.TYPE_ANIMATED
        val ssStyle = ScreensaverPrefs.animatedStyle(a)
        ThemeHelper.Scene.entries.forEach { s ->
            chip(ssRow, a.getString(s.labelRes), null, animated && ssStyle == styleOf(s), ssGroup) {
                ScreensaverPrefs.setAnimated(a, styleOf(s))
            }
        }

        // 6. Screensaver touch effect
        title(a.getString(R.string.theme_catalog_touch))
        val fxRow = row()
        val fxGroup = ArrayList<TextView>()
        val fx = ScreensaverPrefs.touchFxChoice(a)
        ScreensaverTouchFx.Mode.entries.forEach { m ->
            chip(fxRow, a.getString(m.labelRes), null, fx == m, fxGroup) {
                ScreensaverPrefs.setTouchFx(a, m)
            }
        }

        dialog.setContentView(ScrollView(a).apply { addView(root) })
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }
}
