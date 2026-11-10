package com.metrowin.keyboard.ui

import com.metrowin.keyboard.layout.KeyboardBuilder
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metrowin.keyboard.clipboard.ClipItem
import com.metrowin.keyboard.clipboard.ClipboardStore
import com.metrowin.keyboard.theme.KeyboardColors

/** The saved clipboard: tap to paste, pin to keep, cross to remove. Pictures show as thumbnails. */
class ClipboardPanel(context: Context, private val store: ClipboardStore, private val images: ImageLoader) : LinearLayout(context) {
    interface Listener {
        fun onPaste(item: ClipItem)
        fun onAddImage()
        fun onBack()
    }

    var listener: Listener? = null
    private var colors: KeyboardColors? = null
    private val list = RecyclerView(context)
    private val adapter = Rows()
    private val empty = TextView(context)
    private val back = IconButton(context, null, KeyboardBuilder.LETTERS_LABEL)
    private val add = IconButton(context, Icon.IMAGE)
    private val clear = IconButton(context, Icon.TRASH)
    private val title = TextView(context)
    private val changedListener: () -> Unit = { post { refresh() } }

    init {
        orientation = VERTICAL
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(back, LayoutParams(dp(64f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        title.textSize = 15f; title.gravity = Gravity.CENTER_VERTICAL
        top.addView(title, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        top.addView(add, LayoutParams(dp(52f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(clear, LayoutParams(dp(52f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f).toInt()))
        val frame = FrameLayout(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        frame.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty.gravity = Gravity.CENTER; empty.textSize = 14f
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(frame, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        back.onClick = { listener?.onBack() }
        add.onClick = { listener?.onAddImage() }
        clear.onClick = { store.clear(includePinned = false) }
        title.setPadding(dp(8f).toInt(), 0, 0, 0)
        title.text = context.getString(com.metrowin.keyboard.R.string.clipboard_title)
        empty.text = context.getString(com.metrowin.keyboard.R.string.clipboard_empty)
    }

    fun setColors(c: KeyboardColors) {
        colors = c
        setBackgroundColor(c.background)
        title.setTextColor(c.text); empty.setTextColor(c.subtle)
        listOf(back, add, clear).forEach { it.colors = c; it.invalidate() }
        adapter.notifyDataSetChanged()
    }

    fun setImagesEnabled(on: Boolean) { add.visibility = if (on) VISIBLE else GONE }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); store.addListener(changedListener); refresh() }
    override fun onDetachedFromWindow() { store.removeListener(changedListener); super.onDetachedFromWindow() }

    fun refresh() {
        adapter.items = store.all()
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.items.isEmpty()) VISIBLE else GONE
    }

    private inner class Row(val card: LinearLayout, val text: TextView, val thumb: ImageView, val pin: IconButton, val remove: IconButton) : RecyclerView.ViewHolder(card)

    private inner class Rows : RecyclerView.Adapter<Row>() {
        var items: List<ClipItem> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val ctx = parent.context
            val card = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            card.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(6f).toInt(), dp(3f).toInt(), dp(6f).toInt(), dp(3f).toInt())
            }
            val thumb = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val text = TextView(ctx).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 14f; setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt()) }
            val pin = IconButton(ctx, Icon.PIN)
            val remove = IconButton(ctx, Icon.CLOSE)
            card.addView(thumb, LayoutParams(dp(64f).toInt(), dp(56f).toInt()))
            card.addView(text, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(pin, LayoutParams(dp(40f).toInt(), dp(44f).toInt()))
            card.addView(remove, LayoutParams(dp(40f).toInt(), dp(44f).toInt()))
            return Row(card, text, thumb, pin, remove)
        }

        override fun onBindViewHolder(h: Row, position: Int) {
            val item = items[position]
            val c = colors
            h.card.background = GradientDrawable().apply {
                setColor(c?.key ?: 0x33FFFFFF); cornerRadius = dp(c?.cornerDp ?: 4f).coerceAtLeast(dp(4f))
                if (item.pinned && c != null) setStroke(dp(2f).toInt(), c.accent)
            }
            h.text.setTextColor(c?.text ?: 0xFFFFFFFF.toInt())
            h.pin.colors = c; h.remove.colors = c
            h.pin.highlighted = item.pinned
            h.pin.invalidate(); h.remove.invalidate()
            if (item.image) {
                h.thumb.visibility = View.VISIBLE
                h.text.text = ""
                h.thumb.setImageBitmap(null)
                val file = store.imageFile(item)
                if (file != null) images.load(file.toURI().toString(), 220) { bmp: Bitmap? -> if (h.bindingAdapterPosition == position || h.bindingAdapterPosition == RecyclerView.NO_POSITION) h.thumb.setImageBitmap(bmp) }
            } else {
                h.thumb.visibility = View.GONE
                h.text.text = item.text
            }
            h.card.setOnClickListener { listener?.onPaste(item) }
            h.pin.onClick = { store.togglePin(item.id) }
            h.remove.onClick = { store.remove(item.id) }
        }
    }
}
