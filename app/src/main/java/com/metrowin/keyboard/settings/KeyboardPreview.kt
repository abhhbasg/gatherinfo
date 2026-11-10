package com.metrowin.keyboard.settings

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.R
import com.metrowin.keyboard.layout.KeyboardBuilder
import com.metrowin.keyboard.theme.KeyboardTheme
import com.metrowin.keyboard.ui.KeyboardView
import com.metrowin.keyboard.ui.Slot
import com.metrowin.keyboard.ui.SuggestionBar

/**
 * A live picture of the keyboard in the settings, drawn by the same views the keyboard uses, so what is changed on this screen
 * (height, width, position, spacing, number row, globe key, look) is seen at once. Not touchable: the button below it opens a text box
 * to try the real thing.
 */
class KeyboardPreview(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var keyboard: KeyboardView? = null
    private var bar: SuggestionBar? = null

    init {
        layoutResource = R.layout.pref_keyboard_preview
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val frame = holder.itemView as FrameLayout
        if (frame.childCount == 0) {
            val kb = KeyboardView(context).apply { isEnabled = false }
            val b = SuggestionBar(context)
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(kb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // The preview shows; it does not type.
            column.setOnTouchListener { _, _ -> true }
            frame.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            keyboard = kb; bar = b
        }
        refresh()
    }

    fun refresh() {
        val kb = keyboard ?: return
        val app = KeyboardApp.of(context)
        val p = app.prefs
        val layout = app.layouts.get(p.currentLanguage ?: p.languages.first()) ?: app.layouts.forLanguage(java.util.Locale.getDefault().language)
        val colors = KeyboardTheme.resolve(context, p, KeyboardTheme.isSystemDark(context), null, if (p.usePictureAccent && p.imageBackground) p.pictureAccent else 0)
        kb.setColors(colors)
        kb.setMetrics(p.keyHeight, p.keyGap, p.keyboardWidth, p.keyboardAlign)
        kb.setBehavior(false, p.longPressMs, false, false, p.keyHints, false)
        kb.setPage(KeyboardBuilder.letters(layout, p.numberRow, p.keyHints, p.languages.size > 1 && p.languageKey))
        kb.setLanguageName(layout.name)
        bar?.setColors(colors)
        bar?.setSlots(listOf(Slot("ciao"), Slot("MetroWin", best = true), Slot("tastiera")))
        bar?.setButtons(p.barButtons)
        bar?.setQuickTool(com.metrowin.keyboard.ui.Tool.from(p.quickTool))
        kb.requestLayout()
    }
}
