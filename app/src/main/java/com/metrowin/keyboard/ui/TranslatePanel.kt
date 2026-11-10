package com.metrowin.keyboard.ui

import com.metrowin.keyboard.layout.KeyboardBuilder
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metrowin.keyboard.Prefs
import com.metrowin.keyboard.R
import com.metrowin.keyboard.theme.KeyboardColors
import com.metrowin.keyboard.translate.Providers
import com.metrowin.keyboard.translate.TranslateLanguages
import com.metrowin.keyboard.translate.Translator

/**
 * Translate what is in the field (the selection, or all of it) or what was last copied, from one language to another, and put the
 * result back, next to the text, or on the clipboard. The text goes to the chosen service only when the panel is opened or the
 * arrow is tapped.
 */
class TranslatePanel(context: Context, private val translator: Translator, private val prefs: Prefs) : LinearLayout(context) {
    interface Listener {
        fun onBack()
        /** The text to translate from the field, and whether it is a selection. */
        fun readField(): Pair<String, Boolean>
        fun readClipboard(): String?
        fun replace(text: String, wasSelection: Boolean)
        fun insert(text: String)
        fun copy(text: String)
        fun openSettings()
    }

    var listener: Listener? = null
    private var colors: KeyboardColors? = null
    private var fromField = true
    private var wasSelection = false
    private var detected: String? = null
    private var picking: Boolean? = null // null: not picking; true: source; false: target

    private val back = IconButton(context, null, KeyboardBuilder.LETTERS_LABEL)
    private val srcBtn = IconButton(context, null, "")
    private val swap = IconButton(context, Icon.SWAP)
    private val tgtBtn = IconButton(context, null, "")
    private val go = IconButton(context, Icon.ARROW_RIGHT)
    private val source = TextView(context)
    private val result = TextView(context)
    private val actions = LinearLayout(context)
    private val fieldBtn = IconButton(context, null, context.getString(R.string.tr_field))
    private val clipBtn = IconButton(context, null, context.getString(R.string.tr_clipboard))
    private val replaceBtn = IconButton(context, null, context.getString(R.string.tr_replace))
    private val insertBtn = IconButton(context, null, context.getString(R.string.tr_insert))
    private val copyBtn = IconButton(context, null, context.getString(R.string.tr_copy))
    private val main = LinearLayout(context)
    private val list = RecyclerView(context)
    private val langs = Languages()

    init {
        orientation = VERTICAL
        val top = LinearLayout(context).apply { orientation = HORIZONTAL }
        top.addView(back, LayoutParams(dp(56f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(srcBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        top.addView(swap, LayoutParams(dp(44f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(tgtBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        top.addView(go, LayoutParams(dp(56f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f).toInt()))

        main.orientation = VERTICAL
        for (t in listOf(source, result)) { t.textSize = 15f; t.setPadding(dp(14f).toInt(), dp(6f).toInt(), dp(14f).toInt(), dp(6f).toInt()); t.typeface = Fonts.regular(context) }
        main.addView(ScrollView(context).apply { addView(source) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        main.addView(View(context).apply { tag = "rule" }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2))
        main.addView(ScrollView(context).apply { addView(result) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f))
        addView(main, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        list.layoutManager = GridLayoutManager(context, 3)
        list.adapter = langs
        list.visibility = GONE
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        actions.orientation = HORIZONTAL
        for (b in listOf(fieldBtn, clipBtn, replaceBtn, insertBtn, copyBtn)) actions.addView(b, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(actions, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42f).toInt()))

        back.onClick = { if (picking != null) closePicker() else { translator.cancel(); listener?.onBack() } }
        go.onClick = { run() }
        swap.onClick = { swapLanguages() }
        srcBtn.onClick = { openPicker(true) }
        tgtBtn.onClick = { openPicker(false) }
        fieldBtn.onClick = { load(true) }
        clipBtn.onClick = { load(false) }
        replaceBtn.onClick = { result.text.toString().takeIf { it.isNotBlank() && translated }?.let { listener?.replace(it, wasSelection) } }
        insertBtn.onClick = { result.text.toString().takeIf { it.isNotBlank() && translated }?.let { listener?.insert(it) } }
        copyBtn.onClick = { result.text.toString().takeIf { it.isNotBlank() && translated }?.let { listener?.copy(it) } }
        result.setOnClickListener { if (translator.problem() != null) listener?.openSettings() }
    }

    private var translated = false

    fun setColors(c: KeyboardColors) {
        colors = c
        setBackgroundColor(c.background)
        source.setTextColor(c.subtle); result.setTextColor(c.text)
        main.findViewWithTag<View>("rule")?.setBackgroundColor(c.accent)
        listOf(back, srcBtn, swap, tgtBtn, go, fieldBtn, clipBtn, replaceBtn, insertBtn, copyBtn).forEach { it.colors = c; it.invalidate() }
        go.highlighted = true
        langs.notifyDataSetChanged()
    }

    /** Called when the panel is shown: takes the text of the field and translates it. */
    fun open() { closePicker(); load(true) }

    private fun load(field: Boolean) {
        fromField = field
        fieldBtn.highlighted = field; clipBtn.highlighted = !field
        fieldBtn.invalidate(); clipBtn.invalidate()
        val text = if (field) listener?.readField()?.also { wasSelection = it.second }?.first else listener?.readClipboard().also { wasSelection = false }
        source.text = text.orEmpty().ifBlank { context.getString(if (field) R.string.tr_empty_field else R.string.tr_empty_clipboard) }
        result.text = ""; translated = false; detected = null
        refreshLabels()
        if (!text.isNullOrBlank()) run()
    }

    private fun run() {
        val text = source.text.toString()
        val empty = context.getString(R.string.tr_empty_field)
        if (text.isBlank() || text == empty || text == context.getString(R.string.tr_empty_clipboard)) return
        val problem = translator.problem()
        if (problem != null) {
            result.text = context.getString(if (problem == "key") R.string.tr_needs_key else R.string.tr_needs_url); translated = false
            return
        }
        result.text = context.getString(R.string.tr_working); translated = false
        translator.translate(text, prefs.translateSource, prefs.translateTarget) { r ->
            r.onSuccess { t -> result.text = t.text; translated = true; detected = t.detectedLanguage; refreshLabels() }
                .onFailure { result.text = context.getString(R.string.tr_error); translated = false }
        }
    }

    private fun swapLanguages() {
        val src = if (prefs.translateSource == Providers.AUTO) (detected ?: prefs.languages.firstOrNull() ?: "en") else prefs.translateSource
        val tgt = prefs.translateTarget
        prefs.translateSource = tgt; prefs.translateTarget = src
        // Translate the result back: it becomes the new source.
        if (translated) { source.text = result.text; result.text = ""; translated = false }
        refreshLabels(); run()
    }

    private fun refreshLabels() {
        val src = prefs.translateSource
        srcBtn.text = if (src == Providers.AUTO) TranslateLanguages.name(Providers.AUTO) + (detected?.let { " · " + TranslateLanguages.name(it.substringBefore('-')) } ?: "") else TranslateLanguages.name(src)
        tgtBtn.text = TranslateLanguages.name(prefs.translateTarget)
        srcBtn.invalidate(); tgtBtn.invalidate()
    }

    private fun openPicker(forSource: Boolean) {
        picking = forSource
        langs.items = (if (forSource) listOf(Providers.AUTO) else emptyList()) + TranslateLanguages.codes
        langs.notifyDataSetChanged()
        main.visibility = GONE; list.visibility = VISIBLE; actions.visibility = GONE
    }

    private fun closePicker() {
        picking = null
        main.visibility = VISIBLE; list.visibility = GONE; actions.visibility = VISIBLE
    }

    private inner class Languages : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<String> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply { gravity = Gravity.CENTER; textSize = 15f; typeface = Fonts.regular(parent.context); maxLines = 1 }
            tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42f).toInt())
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            val code = items[position]
            tv.text = TranslateLanguages.name(code)
            tv.setTextColor(colors?.text ?: 0xFFFFFFFF.toInt())
            tv.setOnClickListener {
                if (picking == true) prefs.translateSource = code else prefs.translateTarget = code
                closePicker(); refreshLabels(); detected = null; run()
            }
        }
    }
}
