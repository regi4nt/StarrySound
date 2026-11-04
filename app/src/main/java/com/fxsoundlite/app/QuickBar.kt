package com.fxsoundlite.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList

/**
 * The customizable bar on the Quick page: a row of shortcut chips under the page title (chat with the AI, chat with
 * people, the theme catalog, ...). The pencil at the end opens the editor: tick what should show and move it up / down.
 * The choice is saved on the device. To offer one more shortcut, add an [Item] and a branch in [open].
 */
object QuickBar {

    enum class Item(val id: String, val iconRes: Int, val labelRes: Int) {
        CHAT_AI("chat_ai", R.drawable.ic_sparkle, R.string.quickbar_chat_ai),
        CHAT_ROOM("chat_room", R.drawable.ic_chat, R.string.quickbar_chat_room),
        THEME("theme", R.drawable.ic_palette, R.string.quickbar_theme);

        companion object {
            fun fromId(id: String): Item? = entries.firstOrNull { it.id == id }
        }
    }

    private const val PREFS = "quick_bar_prefs"
    private const val KEY = "items"
    private val DEFAULT = listOf(Item.CHAT_AI, Item.CHAT_ROOM, Item.THEME)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What the bar shows, in order. Nothing saved yet: the default three. */
    fun enabled(c: Context): List<Item> {
        val p = prefs(c)
        if (!p.contains(KEY)) return DEFAULT
        return p.getString(KEY, "").orEmpty().split(',').mapNotNull { Item.fromId(it.trim()) }.distinct()
    }

    private fun save(c: Context, items: List<Item>) {
        prefs(c).edit().putString(KEY, items.joinToString(",") { it.id }).apply()
    }

    // ------------------------------------------------------------------ bar

    /** Fills [host] (the bar's row, as wide as the screen) with the chips: they share the width equally. */
    fun render(host: LinearLayout, a: Activity) {
        host.removeAllViews()
        enabled(a).forEach { item ->
            host.addView(chip(a, item.iconRes, a.getString(item.labelRes)) { open(a, item) })
        }
        host.addView(chip(a, R.drawable.ic_edit, null, a.getString(R.string.quickbar_edit)) {
            showEditor(a) { render(host, a) }
        })
    }

    private fun chip(a: Activity, icon: Int, label: String?, desc: String? = label, onClick: () -> Unit): View {
        val dp = a.resources.displayMetrics.density
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.quick_bar_chip_bg)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            isClickable = true
            isFocusable = true
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(a).apply {
            setImageResource(icon)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(0xFFFFFFFF.toInt()))
        }, LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()))
        if (label != null) {
            row.addView(TextView(a).apply {
                text = label
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 1
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (7 * dp).toInt()
            })
        }
        row.layoutParams = if (label == null) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.apply { marginEnd = (8 * dp).toInt() }
        return row
    }

    // ------------------------------------------------------------------ actions

    fun open(a: Activity, item: Item) {
        when (item) {
            Item.CHAT_AI -> a.startActivity(Intent(a, AiChatActivity::class.java))
            Item.CHAT_ROOM -> a.startActivity(Intent(a, RoomChatActivity::class.java))
            Item.THEME -> ThemeCatalog.show(a)
        }
    }

    // ------------------------------------------------------------------ editor

    private fun showEditor(a: Activity, onDone: () -> Unit) {
        val dp = a.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val on = enabled(a)
        // Ticked ones first (in their order), the rest after them.
        val order = ArrayList<Item>(on + Item.entries.filter { it !in on })
        val checked = HashSet<Item>(on)
        val textPrimary = CoverColors.themeColor(a, R.attr.colorTextPrimary)
        val textSecondary = CoverColors.themeColor(a, R.attr.colorTextSecondary)

        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun rebuild() {
            rows.removeAllViews()
            order.forEachIndexed { i, item ->
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(CheckBox(a).apply {
                    text = a.getString(item.labelRes)
                    setTextColor(textPrimary)
                    isChecked = item in checked
                    setOnCheckedChangeListener { _, v -> if (v) checked.add(item) else checked.remove(item) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fun arrow(sym: String, enabled: Boolean, delta: Int) = TextView(a).apply {
                    text = sym
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(if (enabled) textPrimary else textSecondary)
                    alpha = if (enabled) 1f else 0.3f
                    if (enabled) setOnClickListener {
                        val j = i + delta
                        val t = order[i]; order[i] = order[j]; order[j] = t
                        rebuild()
                    }
                }
                row.addView(arrow("▲", i > 0, -1), LinearLayout.LayoutParams(px(44), px(44)))
                row.addView(arrow("▼", i < order.size - 1, +1), LinearLayout.LayoutParams(px(44), px(44)))
                rows.addView(row)
            }
        }
        rebuild()

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(12), px(0))
        }
        box.addView(TextView(a).apply {
            text = a.getString(R.string.quickbar_edit_hint)
            textSize = 13f
            setTextColor(textSecondary)
        })
        box.addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })

        AlertDialog.Builder(a)
            .setTitle(R.string.quickbar_edit_title)
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                save(a, order.filter { it in checked })
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
