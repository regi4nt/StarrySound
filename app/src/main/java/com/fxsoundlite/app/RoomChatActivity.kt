package com.fxsoundlite.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.ImageViewCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat between people ([ChatRoom], built like Listen together: Firebase REST + a 6-character room code).
 *  - inside a Listen together room: the chat of that room opens straight away;
 *  - otherwise: make a chat room or join one with its code.
 * Opened from the Quick bar ([QuickBar]).
 */
class RoomChatActivity : AppCompatActivity() {

    private var dp = 1f
    private fun px(v: Int) = (v * dp).toInt()
    private fun color(attr: Int) = CoverColors.themeColor(this, attr)
    private val cAccent get() = color(androidx.appcompat.R.attr.colorAccent)

    private lateinit var body: LinearLayout        // swapped by render()
    private lateinit var titleView: TextView
    private lateinit var leaveBtn: ImageView
    private var scroll: ScrollView? = null
    private var list: LinearLayout? = null
    private var shownCount = -1
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyMode(this)
        ThemeHelper.applyAccent(this)
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        setContentView(buildFrame())
        AppNavBar.attach(this)
    }

    override fun onStart() {
        super.onStart()
        ChatRoom.attachToListen(this) // inside a Listen together room: its chat
        ChatRoom.onChange = { render() }
        ChatRoom.watching = true
        ChatRoom.refresh()
        shownCount = -1
        render()
    }

    override fun onStop() {
        ChatRoom.watching = false
        ChatRoom.onChange = null
        super.onStop()
    }

    // ------------------------------------------------------------------ frame

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
        titleView = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.attr.colorTextPrimary))
            setPadding(px(6), 0, 0, 0)
            setOnClickListener { copyCode() }
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        leaveBtn = iconButton(R.drawable.ic_call_end, getString(R.string.roomchat_leave)) { ChatRoom.leave() }
        ImageViewCompat.setImageTintList(leaveBtn, ColorStateList.valueOf(color(R.attr.colorDanger)))
        header.addView(leaveBtn)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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

    private fun copyCode() {
        if (!ChatRoom.active) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("room", ChatRoom.code))
        Toast.makeText(this, R.string.listen_code_copied, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ states

    private fun render() {
        if (ChatRoom.active) renderRoom() else renderStart()
    }

    /** Not in a chat: make a room or join one. */
    private fun renderStart() {
        shownCount = -1
        scroll = null; list = null
        titleView.text = getString(R.string.roomchat_title)
        leaveBtn.visibility = View.GONE
        body.removeAllViews()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(10), px(20), px(20))
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.roomchat_intro)
            textSize = 14f
            setTextColor(color(R.attr.colorTextSecondary))
        })
        box.addView(Button(this).apply {
            text = getString(R.string.roomchat_create)
            setOnClickListener { doCreate() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(18) })

        val codeInput = EditText(this).apply {
            hint = getString(R.string.roomchat_code_hint)
            setHintTextColor(color(R.attr.colorTextSecondary))
            setTextColor(color(R.attr.colorTextPrimary))
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.input_bg)
            setPadding(px(14), px(12), px(14), px(12))
        }
        box.addView(codeInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(18) })
        box.addView(Button(this).apply {
            text = getString(R.string.roomchat_join)
            setOnClickListener { doJoin(codeInput.text.toString()) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        body.addView(box)
    }

    private fun failText(f: ChatRoom.Fail) = when (f) {
        ChatRoom.Fail.NOT_CONFIGURED -> R.string.listen_err_config
        ChatRoom.Fail.NETWORK -> R.string.listen_err_network
        ChatRoom.Fail.NOT_FOUND -> R.string.listen_err_notfound
        ChatRoom.Fail.BUSY -> R.string.listen_err_busy
    }

    private fun doCreate() {
        if (busy) return
        busy = true
        ChatRoom.create(this) { fail, _ ->
            busy = false
            if (fail != null) Toast.makeText(this, failText(fail), Toast.LENGTH_LONG).show()
        }
    }

    private fun doJoin(code: String) {
        if (busy) return
        busy = true
        ChatRoom.join(this, code) { fail, _ ->
            busy = false
            if (fail != null) Toast.makeText(this, failText(fail), Toast.LENGTH_LONG).show()
        }
    }

    /** In a chat: messages + the input line. */
    private fun renderRoom() {
        val msgs = ChatRoom.messages
        if (list == null) buildRoomViews()
        titleView.text = getString(R.string.roomchat_room_title, ChatRoom.code)
        leaveBtn.visibility = View.VISIBLE
        if (msgs.size == shownCount) return
        val sv = scroll
        val l = list ?: return
        val atBottom = shownCount < 0 || sv == null || sv.scrollY + sv.height >= l.height - px(60)
        shownCount = msgs.size
        l.removeAllViews()
        var previous = ""
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        msgs.forEach { m ->
            val mine = m.uid == ChatRoom.myId
            val who = if (mine) getString(R.string.roomchat_you)
                else m.name.ifBlank { getString(R.string.roomchat_anon, m.uid.take(4)) }
            if (!mine && m.uid != previous) l.addView(TextView(this).apply {
                text = who
                textSize = 12f
                setTextColor(color(R.attr.colorTextSecondary))
                setPadding(px(6), px(8), 0, 0)
            })
            previous = m.uid
            l.addView(bubble(m, mine, if (m.t > 0) fmt.format(Date(m.t)) else ""))
        }
        if (atBottom) sv?.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(m: ChatRoom.Msg, mine: Boolean, time: String): View {
        val tv = TextView(this).apply {
            text = if (time.isEmpty()) m.text else "${m.text}\n$time"
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(px(14), px(9), px(14), px(9))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            setTextColor(if (mine) color(R.attr.colorOnAccent) else color(R.attr.colorTextPrimary))
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(if (mine) cAccent else color(R.attr.colorSurfaceRaised))
            }
        }
        tv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = px(4)
        }
        return tv
    }

    private fun buildRoomViews() {
        body.removeAllViews()
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(4), px(12), px(12))
        }
        val sv = ScrollView(this).apply { isFillViewport = true; addView(l) }
        body.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.attr.colorSurface))
            setPadding(px(10), px(8), px(10), px(8))
        }
        val input = EditText(this).apply {
            hint = getString(R.string.roomchat_hint)
            setHintTextColor(color(R.attr.colorTextSecondary))
            setTextColor(color(R.attr.colorTextPrimary))
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            setBackgroundResource(R.drawable.input_bg)
            setPadding(px(14), px(10), px(14), px(10))
            filters = arrayOf(InputFilter.LengthFilter(ChatRoom.MAX_LEN))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = iconButton(R.drawable.ic_send, getString(R.string.aichat_send)) {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                ChatRoom.send(this, text) { ok ->
                    if (!ok) {
                        Toast.makeText(this, R.string.roomchat_send_failed, Toast.LENGTH_SHORT).show()
                        input.setText(text) // give the text back so nothing is lost
                    }
                }
            }
        }
        ImageViewCompat.setImageTintList(send, ColorStateList.valueOf(cAccent))
        bar.addView(send)
        body.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll = sv
        list = l
        shownCount = -1
    }
}
