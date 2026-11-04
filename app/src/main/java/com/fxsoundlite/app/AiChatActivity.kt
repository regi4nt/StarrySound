package com.fxsoundlite.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Chat with the AI service chosen in Settings > AI ([AiChat]). The conversation is kept on the device
 * (last [KEEP] messages) until it is cleared with the bin in the header. Opened from the Quick bar ([QuickBar]).
 */
class AiChatActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var dp = 1f
    private fun px(v: Int) = (v * dp).toInt()

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageView
    private lateinit var emptyView: TextView

    private val turns = ArrayList<AiChat.Turn>()
    private var typing: View? = null
    private var busy = false

    private fun color(attr: Int) = CoverColors.themeColor(this, attr)
    private val cAccent get() = color(androidx.appcompat.R.attr.colorAccent)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        loadTurns()
        setContentView(buildFrame())
        AppNavBar.attach(this)
        turns.forEach { addBubble(it.role == "user", it.text, false) }
        updateEmpty()
        scrollToEnd()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildFrame(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.attr.colorBg))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(10), px(8), px(10))
        }
        header.addView(iconButton(R.drawable.ic_arrow_back, getString(R.string.back_button_desc)) { finish() })
        header.addView(TextView(this).apply {
            text = getString(R.string.aichat_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.attr.colorTextPrimary))
            setPadding(px(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(R.drawable.ic_delete, getString(R.string.aichat_clear)) { confirmClear() })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(4), px(12), px(12))
        }
        emptyView = TextView(this).apply {
            text = getString(R.string.aichat_empty)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(color(R.attr.colorTextSecondary))
            setPadding(px(32), px(60), px(32), px(20))
        }
        list.addView(emptyView)
        scroll = ScrollView(this).apply { isFillViewport = true; addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.attr.colorSurface))
            setPadding(px(10), px(8), px(10), px(8))
        }
        input = EditText(this).apply {
            hint = getString(R.string.aichat_hint)
            setHintTextColor(color(R.attr.colorTextSecondary))
            setTextColor(color(R.attr.colorTextPrimary))
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            setBackgroundResource(R.drawable.input_bg)
            setPadding(px(14), px(10), px(14), px(10))
            filters = arrayOf(android.text.InputFilter.LengthFilter(AiChat.MAX_INPUT))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sendBtn = iconButton(R.drawable.ic_send, getString(R.string.aichat_send)) { send() }
        ImageViewCompat.setImageTintList(sendBtn, ColorStateList.valueOf(cAccent))
        bar.addView(sendBtn)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun iconButton(icon: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(icon)
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(R.attr.colorTextSecondary)))
        contentDescription = desc
        setPadding(px(10), px(10), px(10), px(10))
        layoutParams = LinearLayout.LayoutParams(px(44), px(44))
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
    }

    private fun addBubble(mine: Boolean, text: String, error: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(px(14), px(10), px(14), px(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            setTextColor(
                when {
                    error -> color(R.attr.colorDanger)
                    mine -> color(R.attr.colorOnAccent)
                    else -> color(R.attr.colorTextPrimary)
                }
            )
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(if (mine && !error) cAccent else color(R.attr.colorSurfaceRaised))
            }
        }
        list.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = px(8)
        })
        updateEmpty()
        return tv
    }

    private fun updateEmpty() {
        val hasBubbles = list.childCount > 1
        emptyView.visibility = if (hasBubbles) View.GONE else View.VISIBLE
    }

    private fun scrollToEnd() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    // ------------------------------------------------------------------ chatting

    private fun send() {
        val text = input.text.toString().trim().take(AiChat.MAX_INPUT)
        if (text.isEmpty() || busy) return
        if (!AiChat.hasKey(this)) {
            Toast.makeText(this, R.string.ai_err_no_key, Toast.LENGTH_LONG).show()
            return
        }
        input.setText("")
        turns.add(AiChat.Turn("user", text))
        addBubble(true, text, false)
        saveTurns()
        busy = true
        typing = addBubble(false, getString(R.string.aichat_thinking), false)
        scrollToEnd()

        val snapshot = ArrayList(turns)
        io.execute {
            var answer: String? = null
            var error: String? = null
            try {
                answer = AiChat.reply(applicationContext, snapshot)
            } catch (e: AiRecommender.AiException) {
                error = getString(e.resId, e.detail)
            } catch (e: Exception) {
                error = getString(R.string.ai_err_network)
            }
            val finalAnswer = answer
            val finalError = error.orEmpty()
            main.post {
                if (isDestroyed) return@post
                typing?.let { list.removeView(it) }
                typing = null
                busy = false
                if (finalAnswer != null) {
                    turns.add(AiChat.Turn("assistant", finalAnswer))
                    addBubble(false, finalAnswer, false)
                    saveTurns()
                } else {
                    addBubble(false, finalError, true)
                }
                scrollToEnd()
            }
        }
    }

    private fun confirmClear() {
        if (turns.isEmpty() || busy) return
        AlertDialog.Builder(this)
            .setMessage(R.string.aichat_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                turns.clear()
                saveTurns()
                list.removeAllViews()
                list.addView(emptyView)
                updateEmpty()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ storage

    private fun prefs() = getSharedPreferences("ai_chat_prefs", MODE_PRIVATE)

    private fun loadTurns() {
        try {
            val a = JSONArray(prefs().getString(KEY, "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val role = o.optString("r")
                val text = o.optString("t")
                if ((role == "user" || role == "assistant") && text.isNotBlank()) turns.add(AiChat.Turn(role, text))
            }
        } catch (e: Exception) { android.util.Log.d("AiChat", "ignored: $e") }
    }

    private fun saveTurns() {
        while (turns.size > KEEP) turns.removeAt(0)
        val a = JSONArray()
        turns.forEach { a.put(JSONObject().put("r", it.role).put("t", it.text)) }
        prefs().edit().putString(KEY, a.toString()).apply()
    }

    private companion object {
        const val KEY = "turns"
        const val KEEP = 60
    }
}
