package com.metrowin.keyboard.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.metrowin.keyboard.R
import com.metrowin.keyboard.ui.Fonts
import com.metrowin.keyboard.ui.dp

/**
 * The same author's apps: the open source ones with their source, the others with their store page. Each has its icon, a few lines about
 * it and a strip of screenshots, so a glance says what it is.
 */
class ProjectsFragment : Fragment() {
    private class Project(val icon: Int, val title: Int, val text: Int, val shots: List<Int>, val shotHeightDp: Float, val button: Int, val url: String)

    private val open = listOf(
        Project(
            R.mipmap.ic_launcher, R.string.project_keyboard_title, R.string.project_keyboard_desc,
            listOf(R.drawable.proj_keyboard_1, R.drawable.proj_keyboard_2, R.drawable.proj_keyboard_3), 84f,
            R.string.project_open_github, "https://github.com/developerkenshi-coder/MetroWin-Keyboard",
        ),
        Project(
            R.drawable.proj_launcher_icon, R.string.project_launcher_title, R.string.project_launcher_desc,
            listOf(R.drawable.proj_launcher_1, R.drawable.proj_launcher_2, R.drawable.proj_launcher_3, R.drawable.proj_launcher_4), 260f,
            R.string.project_open_github, "https://github.com/developerkenshi-coder/MetroWinLauncher",
        ),
    )
    private val closed = listOf(
        Project(
            R.drawable.proj_spesatracker_icon, R.string.project_spesatracker_title, R.string.project_spesatracker_desc, emptyList(), 0f,
            R.string.project_open_play, "https://play.google.com/store/apps/details?id=com.spesatracker.app",
        ),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val c = requireContext()
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, c.dp(8f).toInt(), 0, c.dp(32f).toInt()) }
        section(c, list, R.string.cat_foss, open)
        section(c, list, R.string.cat_nonfoss, closed)
        return ScrollView(c).apply { addView(list) }
    }

    override fun onResume() { super.onResume(); requireActivity().setTitle(R.string.screen_projects) }

    private fun section(c: Context, list: LinearLayout, title: Int, projects: List<Project>) {
        list.addView(MetroUi.label(c, getString(title).uppercase(), 13f, MetroUi.accent(c), Fonts.semibold(c)).apply { setPadding(c.dp(24f).toInt(), c.dp(16f).toInt(), c.dp(24f).toInt(), c.dp(8f).toInt()) })
        for (p in projects) list.addView(block(c, p))
    }

    private fun block(c: Context, p: Project): View {
        val pad = c.dp(24f).toInt()
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, c.dp(8f).toInt(), 0, c.dp(20f).toInt()) }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(pad, 0, pad, 0) }
        head.addView(ImageView(c).apply { setImageResource(p.icon); contentDescription = null }, LinearLayout.LayoutParams(c.dp(48f).toInt(), c.dp(48f).toInt()))
        head.addView(MetroUi.label(c, getString(p.title), 26f, MetroUi.fg(c), Fonts.light(c)).apply { includeFontPadding = true; setPadding(c.dp(14f).toInt(), 0, 0, 0) })
        box.addView(head)
        box.addView(MetroUi.label(c, getString(p.text), 14f, MetroUi.subtle(c), Fonts.regular(c)).apply { includeFontPadding = true; setLineSpacing(0f, 1.15f); setPadding(pad, c.dp(8f).toInt(), pad, c.dp(10f).toInt()) })
        if (p.shots.isNotEmpty()) {
            val strip = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad, 0, pad, 0) }
            for (id in p.shots) strip.addView(
                ImageView(c).apply { setImageResource(id); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = null },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, c.dp(p.shotHeightDp).toInt()).apply { rightMargin = c.dp(8f).toInt() },
            )
            box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
        box.addView(MetroUi.button(c, getString(p.button), filled = false) { view(c, p.url) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(pad, c.dp(12f).toInt(), pad, 0) })
        return box
    }

    private fun view(c: Context, url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { Toast.makeText(c, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }
}
