package com.metrowin.keyboard.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metrowin.keyboard.R
import com.metrowin.keyboard.gif.GifItem
import com.metrowin.keyboard.gif.GifRepository
import com.metrowin.keyboard.theme.KeyboardColors

/**
 * GIFs from the source the user chose. Shown full size with a row of ready-made searches, or, while the user types a search with
 * the keyboard, as a single strip above the keys.
 */
class GifPanel(context: Context, private val repo: GifRepository, private val images: ImageLoader) : LinearLayout(context) {
    interface Listener {
        fun onGif(item: GifItem)
        fun onBack()
        fun onSearch()
        fun onOpenSettings()
    }

    var listener: Listener? = null
    private var colors: KeyboardColors? = null
    private var strip = false
    private var language = "en"
    private var lastQuery: String? = null
    private var generation = 0

    private val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val back = IconButton(context, null, "ABC")
    private val search = IconButton(context, Icon.SEARCH)
    private val chipsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
    private val chips = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val list = RecyclerView(context)
    private val message = TextView(context)
    private val adapter = Cells()

    init {
        orientation = VERTICAL
        header.addView(back, LayoutParams(dp(64f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        chipsScroll.addView(chips)
        header.addView(chipsScroll, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(search, LayoutParams(dp(52f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f).toInt()))
        list.adapter = adapter
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        message.gravity = Gravity.CENTER; message.textSize = 14f; message.setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(8f).toInt())
        addView(message, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        back.onClick = { listener?.onBack() }
        search.onClick = { listener?.onSearch() }
        message.setOnClickListener { if (repo.problem() != null) listener?.onOpenSettings() }
        setStrip(false)
    }

    fun setColors(c: KeyboardColors) {
        colors = c
        setBackgroundColor(c.background)
        back.colors = c; search.colors = c; back.invalidate(); search.invalidate()
        message.setTextColor(c.subtle)
        rebuildChips()
        adapter.notifyDataSetChanged()
    }

    fun setLanguage(tag: String) { language = tag }

    fun setStrip(on: Boolean) {
        strip = on
        header.visibility = if (on) GONE else VISIBLE
        list.layoutManager = if (on) LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false) else GridLayoutManager(context, 3)
        adapter.notifyDataSetChanged()
    }

    private fun rebuildChips() {
        chips.removeAllViews()
        val c = colors ?: return
        val names = context.resources.getStringArray(R.array.gif_chips)
        // The first chip is trending (an empty search); the rest are searches in the language of the phone.
        for ((i, name) in names.withIndex()) {
            val tv = TextView(context).apply {
                text = name; textSize = 14f; gravity = Gravity.CENTER; setTextColor(c.text)
                setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
                background = GradientDrawable().apply { setColor(c.key); cornerRadius = dp(18f) }
                setOnClickListener { run(if (i == 0) null else name) }
            }
            chips.addView(tv, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34f).toInt()).apply { setMargins(dp(3f).toInt(), 0, dp(3f).toInt(), 0) })
        }
    }

    /** Shows what the source finds for [query] (trending when empty). */
    fun run(query: String?) {
        lastQuery = query
        val problem = repo.problem()
        if (problem != null) {
            adapter.items = emptyList(); adapter.notifyDataSetChanged()
            message.text = context.getString(when (problem) {
                GifRepository.GifProblem.NEEDS_KEY -> R.string.gif_needs_key
                GifRepository.GifProblem.NEEDS_FOLDER -> R.string.gif_needs_folder
                GifRepository.GifProblem.NEEDS_URL -> R.string.gif_needs_url
            })
            message.visibility = VISIBLE
            return
        }
        val mine = ++generation
        message.text = context.getString(R.string.gif_loading); message.visibility = VISIBLE
        repo.load(query, language) { r ->
            if (mine != generation) return@load
            r.onSuccess {
                adapter.items = it; adapter.notifyDataSetChanged()
                message.visibility = if (it.isEmpty()) VISIBLE else GONE
                message.text = context.getString(R.string.gif_none)
                list.scrollToPosition(0)
            }.onFailure {
                adapter.items = emptyList(); adapter.notifyDataSetChanged()
                message.text = context.getString(R.string.gif_error); message.visibility = VISIBLE
            }
        }
    }

    fun reload() = run(lastQuery)

    private inner class Cells : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<GifItem> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val iv = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            iv.layoutParams = RecyclerView.LayoutParams(if (strip) dp(110f).toInt() else ViewGroup.LayoutParams.MATCH_PARENT, if (strip) dp(96f).toInt() else dp(96f).toInt()).apply {
                setMargins(dp(2f).toInt(), dp(2f).toInt(), dp(2f).toInt(), dp(2f).toInt())
            }
            return object : RecyclerView.ViewHolder(iv) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val iv = holder.itemView as ImageView
            val item = items[position]
            iv.setBackgroundColor(colors?.key ?: 0x33FFFFFF)
            iv.setImageBitmap(null)
            iv.tag = item.previewUrl
            images.load(item.previewUrl, 260) { bmp -> if (iv.tag == item.previewUrl) iv.setImageBitmap(bmp) }
            iv.setOnClickListener { listener?.onGif(item) }
        }
    }
}
