package com.metrowin.keyboard.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.R
import com.metrowin.keyboard.tutorial.Tutorial
import com.metrowin.keyboard.ui.Fonts
import com.metrowin.keyboard.ui.dp
import kotlin.math.abs
import kotlin.math.floor

/** The screens of the settings: the home page opens them by class name, and a test loads each one so a typo cannot hide until it is tapped. */
object Home {
    class Screen(val fragment: String, val title: Int, val summary: Int)

    private fun screen(name: String, title: Int, summary: Int) = Screen("com.metrowin.keyboard.settings.${name}Fragment", title, summary)

    val settings = listOf(
        screen("Languages", R.string.screen_languages, R.string.screen_languages_summary),
        screen("Appearance", R.string.screen_appearance, R.string.screen_appearance_summary),
        screen("Size", R.string.screen_size, R.string.screen_size_summary),
        screen("Typing", R.string.screen_typing, R.string.screen_typing_summary),
        screen("Suggestions", R.string.screen_suggestions, R.string.screen_suggestions_summary),
        screen("Feedback", R.string.screen_feedback, R.string.screen_feedback_summary),
        screen("Clipboard", R.string.screen_clipboard, R.string.screen_clipboard_summary),
        screen("Translate", R.string.screen_translate, R.string.screen_translate_summary),
        screen("Gif", R.string.screen_gif, R.string.screen_gif_summary),
        screen("Voice", R.string.screen_voice, R.string.screen_voice_summary),
        screen("Launcher", R.string.screen_launcher, R.string.screen_launcher_summary),
        screen("Backup", R.string.screen_backup, R.string.screen_backup_summary),
        screen("Update", R.string.screen_update, R.string.screen_update_summary),
    )
    val about = listOf(
        screen("About", R.string.screen_about, R.string.screen_about_summary),
        screen("Projects", R.string.screen_projects, R.string.screen_projects_summary),
    )
    val all get() = (settings + about).map { it.fragment }
}

/**
 * The home page, as the settings of Windows Phone and MetroWin Launcher: the name of the app in small capitals over large light titles
 * side by side, the next one peeking in at the edge, and a page for each that slides with the finger. The titles follow the pages and
 * can be touched to swing to one.
 */
class HomeFragment : Fragment() {
    private var enable: Row? = null
    private var select: Row? = null
    private var tutorial: Row? = null
    private var updateTile: View? = null
    private var updateBody: TextView? = null

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val c = requireContext()
        val pages = listOf(startPage(c), settingsPage(c), aboutPage(c))
        return PivotView(c, listOf(R.string.pivot_start, R.string.pivot_settings, R.string.pivot_about).map { getString(it) }, pages)
    }

    private fun open(name: String) = parentFragmentManager.beginTransaction()
        .replace(R.id.settings_container, parentFragmentManager.fragmentFactory.instantiate(requireContext().classLoader, name)).addToBackStack(null).commit()

    private fun startPage(c: Context): View = page(c).apply {
        list.addView(updateTile(c))
        list.addView(calibrationTile(c))
        enable = row(c, R.string.setup_enable_title, R.string.setup_enable_summary) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }.also { addRow(it) }
        select = row(c, R.string.setup_select_title, R.string.setup_select_summary) { c.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }.also { addRow(it) }
        addRow(row(c, R.string.setup_try_title, R.string.setup_try_summary) { startActivity(Intent(c, TestActivity::class.java)) })
        tutorial = row(c, R.string.setup_tutorial_title, R.string.setup_tutorial_summary) { startActivity(Intent(c, TutorialActivity::class.java)) }.also { addRow(it) }
        addRow(row(c, R.string.setup_guide_title, R.string.setup_guide_summary) { startActivity(Intent(c, OnboardingActivity::class.java)) })
    }.scroll

    private fun settingsPage(c: Context): View = page(c).apply {
        for (screen in Home.settings) addRow(row(c, screen.title, screen.summary) { open(screen.fragment) })
    }.scroll

    private fun aboutPage(c: Context): View = page(c).apply {
        for (screen in Home.about) addRow(row(c, screen.title, screen.summary) { open(screen.fragment) })
    }.scroll

    override fun onResume() {
        super.onResume()
        requireActivity().title = ""
        val found = com.metrowin.keyboard.update.Updates.pending(requireContext())
        updateTile?.visibility = if (found != null) View.VISIBLE else View.GONE
        if (found != null) updateBody?.text = getString(R.string.update_tile_body, found.version)
        val pkg = requireContext().packageName
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == pkg }
        val active = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$pkg/") == true
        enable?.summary?.setText(if (enabled) R.string.setup_enabled else R.string.setup_disabled)
        select?.summary?.setText(if (active) R.string.setup_active else R.string.setup_inactive)
        val seen = KeyboardApp.of(requireContext()).prefs.tutorialSeen
        tutorial?.summary?.setText(if (Tutorial.hasNew(seen)) R.string.setup_tutorial_new else R.string.setup_tutorial_summary)
    }

    /** A new version was found: a tile at the top of Start that opens the updates (hidden when there is nothing new). */
    private fun updateTile(c: Context): View {
        val accent = MetroUi.accent(c)
        val on = com.metrowin.keyboard.theme.ColorMath.onColor(accent)
        val body = MetroUi.label(c, "", 14f, on, Fonts.regular(c)).apply { alpha = 0.9f; setPadding(0, c.dp(4f).toInt(), 0, 0) }
        updateBody = body
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(accent)
            setPadding(c.dp(20f).toInt(), c.dp(16f).toInt(), c.dp(20f).toInt(), c.dp(18f).toInt())
            addView(MetroUi.label(c, getString(R.string.update_tile_title), 26f, on, Fonts.light(c)).apply { includeFontPadding = true })
            addView(body)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(c.dp(24f).toInt(), c.dp(8f).toInt(), c.dp(24f).toInt(), c.dp(4f).toInt()) }
            isClickable = true; isFocusable = true
            setOnClickListener { open(Home.all.first { it.endsWith("UpdateFragment") }) }
            visibility = View.GONE
            press()
        }.also { updateTile = it }
    }

    /** The calibration, as a tile in the colour of the app: the thing most worth doing right after turning the keyboard on. */
    private fun calibrationTile(c: Context): View {
        val accent = MetroUi.accent(c)
        val on = com.metrowin.keyboard.theme.ColorMath.onColor(accent)
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(accent)
            setPadding(c.dp(20f).toInt(), c.dp(16f).toInt(), c.dp(20f).toInt(), c.dp(18f).toInt())
            addView(MetroUi.label(c, getString(R.string.tile_calibrate_title), 26f, on, Fonts.light(c)).apply { includeFontPadding = true })
            addView(MetroUi.label(c, getString(R.string.tile_calibrate_body), 14f, on, Fonts.regular(c)).apply { alpha = 0.9f; setPadding(0, c.dp(4f).toInt(), 0, 0) })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(c.dp(24f).toInt(), c.dp(8f).toInt(), c.dp(24f).toInt(), c.dp(12f).toInt()) }
            isClickable = true; isFocusable = true
            setOnClickListener { startActivity(Intent(c, CalibrationActivity::class.java)) }
            press()
        }
    }

    /** A list page: rows one under another in a scroll view. */
    private class Page(val scroll: ScrollView, val list: LinearLayout) { fun addRow(r: Row) = list.addView(r.view) }

    private fun page(c: Context): Page {
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, c.dp(8f).toInt(), 0, c.dp(24f).toInt()) }
        val scroll = ScrollView(c).apply { isFillViewport = false; clipToPadding = false; addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        return Page(scroll, list)
    }

    private inline fun Page.apply(block: Page.() -> Unit): Page { block(); return this }

    private fun row(c: Context, title: Int, summary: Int, onClick: () -> Unit) = Row(c, c.getString(title), if (summary != 0) c.getString(summary) else "", onClick)
}

/** One entry of a list: a large light title over a small grey line; it tilts a little under the finger, as on Windows Phone. */
class Row(c: Context, title: String, summary: String, onClick: () -> Unit) {
    val summary: TextView = MetroUi.label(c, summary, 14f, MetroUi.subtle(c), Fonts.regular(c)).apply { includeFontPadding = true }
    val view = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(c.dp(24f).toInt(), c.dp(10f).toInt(), c.dp(24f).toInt(), c.dp(10f).toInt())
        addView(MetroUi.label(c, title, 24f, MetroUi.fg(c), Fonts.light(c)).apply { includeFontPadding = true })
        addView(this@Row.summary)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        press()
    }
}

/** The press of Windows Phone: the entry shrinks a little while it is held. */
@SuppressLint("ClickableViewAccessibility")
fun View.press() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        false
    }
}

/**
 * The pivot: the titles in a row, the current one in full colour and the others fainter, sliding with the pages. The pages are a
 * horizontal list that snaps to one page at a time.
 */
class PivotView(c: Context, private val titles: List<String>, private val pages: List<View>) : LinearLayout(c) {
    // The titles are as wide as they need to be, however narrow the screen: the ones past the edge are clipped, not wrapped.
    private val row = object : LinearLayout(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec)
    }.apply { orientation = HORIZONTAL }
    private val labels = titles.mapIndexed { i, t ->
        MetroUi.label(c, t.lowercase(), 40f, MetroUi.fg(c), Fonts.light(c)).apply {
            includeFontPadding = true; setSingleLine()
            setPadding(0, 0, c.dp(24f).toInt(), 0)
            setOnClickListener { pager.smoothScrollToPosition(i) }
            // A title is a tab: TalkBack says so, says which one is selected, and treats it as a heading.
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            androidx.core.view.ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.roleDescription = c.getString(R.string.pivot_tab)
                    info.isSelected = i == current
                }
            })
        }
    }
    private var current = 0
    private val pager = RecyclerView(c)
    private val layoutManager = LinearLayoutManager(c, RecyclerView.HORIZONTAL, false)

    init {
        orientation = VERTICAL
        labels.forEach { row.addView(it) }
        val clip = FrameLayout(c).apply { clipChildren = true; setPadding(c.dp(24f).toInt(), 0, 0, 0); addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        addView(clip, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pager.layoutManager = layoutManager
        pager.setItemViewCacheSize(pages.size)
        PagerSnapHelper().attachToRecyclerView(pager)
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = pages.size
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = pages[viewType]
                v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = follow()
        })
        addView(pager, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        row.post { follow() }
    }

    /** Slides the titles by the distance the pages have moved, and fades the ones that are not current. */
    private fun follow() {
        val first = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val v = layoutManager.findViewByPosition(first)
        val fraction = if (v == null || width == 0) 0f else (-v.left.toFloat() / width).coerceIn(0f, 1f)
        val position = first + fraction
        val lower = floor(position).toInt().coerceIn(0, titles.lastIndex)
        val upper = (lower + 1).coerceAtMost(titles.lastIndex)
        fun left(i: Int) = labels.take(i).sumOf { it.width }.toFloat()
        row.translationX = -(left(lower) + (left(upper) - left(lower)) * (position - lower))
        labels.forEachIndexed { i, l -> l.alpha = 1f - 0.55f * abs(position - i).coerceIn(0f, 1f) }
        val now = Math.round(position)
        if (now != current) { current = now; labels.forEach { it.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED) } }
    }
}
