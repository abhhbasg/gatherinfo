package com.metrowin.keyboard.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.metrowin.keyboard.R
import com.metrowin.keyboard.theme.KeyboardColors
import com.metrowin.keyboard.tutorial.Tutorial

/** The tutorial inside the keyboard itself: the same pages as the app's, one at a time, so help is one tap away from the toolbox. */
class TutorialPanel(context: Context) : LinearLayout(context) {
    var onBack: (() -> Unit)? = null
    var onSeen: (() -> Unit)? = null
    private var index = 0
    private var colors: KeyboardColors? = null
    private val back = IconButton(context, null, "ABC")
    private val prev = IconButton(context, Icon.CHEVRON_LEFT)
    private val next = IconButton(context, Icon.CHEVRON_RIGHT)
    private val title = TextView(context)
    private val body = TextView(context)
    private val counter = TextView(context)

    init {
        orientation = VERTICAL
        val top = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(back, LayoutParams(dp(64f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        title.typeface = Fonts.light(context); title.textSize = 24f; title.setPadding(dp(8f).toInt(), 0, 0, 0)
        top.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50f).toInt()))
        body.typeface = Fonts.regular(context); body.textSize = 15f; body.setLineSpacing(0f, 1.15f)
        body.setPadding(dp(16f).toInt(), dp(4f).toInt(), dp(16f).toInt(), dp(4f).toInt())
        addView(ScrollView(context).apply { addView(body) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(prev, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        counter.gravity = Gravity.CENTER; counter.textSize = 14f
        bottom.addView(counter, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(next, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f).toInt()))
        back.onClick = { onBack?.invoke() }
        prev.onClick = { go(index - 1) }
        next.onClick = { go(index + 1) }
    }

    fun setColors(c: KeyboardColors) {
        colors = c
        setBackgroundColor(c.background)
        title.setTextColor(c.text); body.setTextColor(c.text); counter.setTextColor(c.subtle)
        listOf(back, prev, next).forEach { it.colors = c; it.invalidate() }
    }

    /** Opens at the first page that is new to this person, or the beginning. */
    fun open(startAt: Int) { go(startAt) }

    private fun go(i: Int) {
        if (i !in Tutorial.pages.indices) return
        index = i
        val page = Tutorial.pages[i]
        title.text = context.getString(page.title).lowercase()
        body.text = context.getString(page.body)
        counter.text = context.getString(R.string.tutorial_counter, i + 1, Tutorial.pages.size)
        prev.alpha = if (i == 0) 0.3f else 1f; next.alpha = if (i == Tutorial.pages.lastIndex) 0.3f else 1f
        if (i == Tutorial.pages.lastIndex) onSeen?.invoke()
    }
}
