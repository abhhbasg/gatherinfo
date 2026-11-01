package com.metrowin.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metrowin.keyboard.emoji.EmojiCategory
import com.metrowin.keyboard.emoji.EmojiData
import com.metrowin.keyboard.theme.ColorMath
import com.metrowin.keyboard.theme.KeyboardColors

/** A flat button with an icon or a text, painted in the colours of the theme. */
class IconButton(context: Context, private val icon: Icon?, var text: String? = null) : View(context) {
    var colors: KeyboardColors? = null
    var highlighted = false
    var onClick: (() -> Unit)? = null
    var onRepeat: (() -> Unit)? = null
    private var down = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val repeat = object : Runnable { override fun run() { if (down) { onRepeat?.invoke(); postDelayed(this, 50) } } }

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        if (highlighted) { paint.color = ColorMath.withAlpha(c.accent, 0x55); canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
        if (down) { paint.color = ColorMath.withAlpha(c.text, 0x22); canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
        paint.color = if (highlighted) c.accent else c.text
        if (icon != null) Icons.draw(canvas, icon, width / 2f, height / 2f, minOf(width, height) * 0.46f, paint)
        else text?.let { t ->
            paint.typeface = Fonts.regular(context)
            paint.textSize = height * 0.40f
            // Long labels shrink to fit the button.
            val room = width * 0.92f
            val w = paint.measureText(t)
            if (w > room) paint.textSize *= room / w
            val m = paint.fontMetrics
            canvas.drawText(t, width / 2f, height / 2f - (m.ascent + m.descent) / 2f, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate(); if (onRepeat != null) { onRepeat?.invoke(); postDelayed(repeat, 380) } }
            MotionEvent.ACTION_UP -> { val was = down; down = false; removeCallbacks(repeat); invalidate(); if (was && onRepeat == null && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) onClick?.invoke() }
            MotionEvent.ACTION_CANCEL -> { down = false; removeCallbacks(repeat); invalidate() }
        }
        return true
    }
}

/** Emoji by category, the classic text emoticons and the kaomoji, with the recent ones first. */
class EmojiPanel(context: Context) : LinearLayout(context) {
    interface Listener {
        fun onEmoji(text: String)
        fun onBackspace()
        fun onBack()
    }

    var listener: Listener? = null
    private var colors: KeyboardColors? = null
    private var recent: List<String> = emptyList()
    private var current = EmojiCategory.SMILEYS
    private var data: Map<EmojiCategory, List<String>> = emptyMap()
    private val grid = RecyclerView(context)
    private val adapter = Cells()
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val tabViews = HashMap<EmojiCategory, IconButton>()
    private val back = IconButton(context, null, "ABC")
    private val delete = IconButton(context, Icon.BACKSPACE)
    private var popup: PopupWindow? = null

    init {
        orientation = VERTICAL
        grid.adapter = adapter
        grid.layoutManager = GridLayoutManager(context, 8)
        grid.setHasFixedSize(true)
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar.addView(back, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))
        bar.addView(tabs, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 9f))
        bar.addView(delete, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f))
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f).toInt()))
        back.onClick = { listener?.onBack() }
        delete.onRepeat = { listener?.onBackspace() }
    }

    fun setColors(c: KeyboardColors) {
        colors = c
        setBackgroundColor(c.background)
        back.colors = c; delete.colors = c
        tabViews.values.forEach { it.colors = c; it.invalidate() }
        back.invalidate(); delete.invalidate(); adapter.notifyDataSetChanged()
    }

    /** Called each time the panel is shown, so recent emoji and the tab list are current. */
    fun open(recentEmoji: List<String>) {
        recent = recentEmoji
        data = EmojiData.categories(recent).filter { it.key != EmojiCategory.RECENT || recent.isNotEmpty() }
        if (current !in data) current = data.keys.first { it != EmojiCategory.RECENT }
        if (recent.isNotEmpty() && current == EmojiCategory.SMILEYS) current = EmojiCategory.RECENT
        rebuildTabs()
        show(current)
    }

    private fun rebuildTabs() {
        tabs.removeAllViews(); tabViews.clear()
        for (cat in data.keys) {
            val b = IconButton(context, null, cat.tab)
            b.colors = colors
            b.onClick = { show(cat) }
            tabs.addView(b, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            tabViews[cat] = b
        }
    }

    private fun show(cat: EmojiCategory) {
        current = cat
        tabViews.forEach { (k, v) -> v.highlighted = k == cat; v.invalidate() }
        (grid.layoutManager as GridLayoutManager).spanCount = cat.columns
        adapter.items = data[cat].orEmpty(); adapter.big = cat.big
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
    }

    fun dismissPopup() { popup?.dismiss(); popup = null }

    private fun showTones(anchor: View, emoji: String) {
        val tones = EmojiData.skinTones(emoji)
        if (tones.isEmpty()) return
        val fillColor = colors?.popup ?: 0xFF333333.toInt()
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply { setColor(fillColor); cornerRadius = dp(10f) }
            setPadding(dp(6f).toInt(), dp(4f).toInt(), dp(6f).toInt(), dp(4f).toInt())
        }
        val pop = PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        for (t in tones) {
            row.addView(TextView(context).apply {
                text = t; textSize = 26f; gravity = Gravity.CENTER; setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
                setOnClickListener { listener?.onEmoji(t); pop.dismiss() }
            })
        }
        popup?.dismiss()
        popup = pop
        pop.isOutsideTouchable = true
        row.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        pop.showAsDropDown(anchor, -(row.measuredWidth - anchor.width) / 2, -anchor.height - row.measuredHeight)
    }

    private inner class Cells : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<String> = emptyList()
        var big = true
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply { gravity = Gravity.CENTER }
            tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f).toInt())
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            val e = items[position]
            tv.text = e
            tv.textSize = if (big) 26f else 15f
            tv.setTextColor(colors?.text ?: 0xFFFFFFFF.toInt())
            tv.setOnClickListener { listener?.onEmoji(e) }
            tv.setOnLongClickListener { if (big) { showTones(tv, e); true } else false }
        }
    }
}
