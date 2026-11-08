package com.metrowin.keyboard.settings

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.R
import com.metrowin.keyboard.ui.Fonts
import com.metrowin.keyboard.ui.dp
import com.metrowin.keyboard.update.NotFound
import com.metrowin.keyboard.update.Repo
import com.metrowin.keyboard.update.UpdateScheduler
import com.metrowin.keyboard.update.Updates
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * The updates: a switch for the 12-hour check, the state of the last one, what is new in the version found (the changelog) and the button
 * that downloads it and opens the installer. It only ever looks at this keyboard's own repository (see [Repo]).
 */
class UpdateFragment : Fragment() {
    private val io = Executors.newSingleThreadExecutor()
    private val prefs get() = KeyboardApp.of(requireContext()).prefs
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var notesLabel: TextView
    private lateinit var notes: TextView
    private lateinit var check: TextView
    private lateinit var install: TextView
    private var busy = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val c = requireContext()
        val pad = c.dp(24f).toInt()
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, c.dp(8f).toInt(), 0, c.dp(32f).toInt()) }

        // The switch for the 12-hour check.
        val switch = SwitchCompat(c).apply { isChecked = prefs.updateAuto; setOnCheckedChangeListener { _, on -> prefs.putBoolean(com.metrowin.keyboard.K.UPDATE_AUTO, on); UpdateScheduler.sync(c.applicationContext) } }
        val text = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(MetroUi.label(c, getString(R.string.update_auto_title), 22f, MetroUi.fg(c), Fonts.light(c)).apply { includeFontPadding = true })
            addView(MetroUi.label(c, getString(R.string.update_auto_summary), 14f, MetroUi.subtle(c), Fonts.regular(c)).apply { includeFontPadding = true })
        }
        list.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(pad, c.dp(8f).toInt(), pad, c.dp(12f).toInt())
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(switch)
            setOnClickListener { switch.toggle() }
        })

        status = MetroUi.label(c, "", 18f, MetroUi.fg(c), Fonts.light(c)).apply { includeFontPadding = true; setLineSpacing(0f, 1.15f); setPadding(pad, c.dp(8f).toInt(), pad, c.dp(8f).toInt()) }
        progress = MetroUi.label(c, "", 14f, MetroUi.accent(c), Fonts.semibold(c)).apply { includeFontPadding = true; setPadding(pad, 0, pad, c.dp(8f).toInt()) }
        list.addView(status); list.addView(progress)

        check = MetroUi.button(c, getString(R.string.update_check_now), filled = false) { look() }
        install = MetroUi.button(c, getString(R.string.update_install)) { download() }
        val params = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(pad, c.dp(6f).toInt(), pad, c.dp(6f).toInt()) } }
        list.addView(install, params()); list.addView(check, params())

        notesLabel = MetroUi.label(c, getString(R.string.update_notes_title).uppercase(), 13f, MetroUi.accent(c), Fonts.semibold(c)).apply { setPadding(pad, c.dp(20f).toInt(), pad, c.dp(8f).toInt()) }
        notes = MetroUi.label(c, "", 15f, MetroUi.fg(c), Fonts.regular(c)).apply { includeFontPadding = true; setLineSpacing(0f, 1.2f); setPadding(pad, 0, pad, 0); setTextIsSelectable(true) }
        list.addView(notesLabel); list.addView(notes)

        list.addView(MetroUi.label(c, getString(R.string.update_privacy, Repo.OWNER + "/" + Repo.NAME), 12f, MetroUi.subtle(c), Fonts.regular(c)).apply { includeFontPadding = true; setPadding(pad, c.dp(24f).toInt(), pad, 0) })
        return ScrollView(c).apply { addView(list) }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.screen_update)
        show()
        if (prefs.updateAuto && System.currentTimeMillis() - prefs.updateLast > Updates.EVERY_MS) look()
    }

    private fun ui(block: () -> Unit) { view?.post { if (isAdded) block() } }

    private fun show() {
        val c = requireContext()
        val r = Updates.pending(c)
        val last = prefs.updateLast
        val lastText = if (last == 0L) getString(R.string.update_never) else getString(R.string.update_last, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)))
        status.text = if (r != null) {
            val size = if (r.apkSize > 0) " · " + Formatter.formatShortFileSize(c, r.apkSize) else ""
            getString(R.string.update_status_new, r.version, Updates.installed(c)) + "\n" + r.date + size + "\n" + lastText
        } else getString(R.string.update_status_latest, Updates.installed(c)) + "\n" + lastText
        val has = r != null
        install.visibility = if (has && !busy) View.VISIBLE else View.GONE
        notesLabel.visibility = if (has) View.VISIBLE else View.GONE
        notes.visibility = if (has) View.VISIBLE else View.GONE
        notes.text = r?.notes.orEmpty().ifBlank { getString(R.string.update_no_notes) }
        check.alpha = if (busy) 0.4f else 1f; check.isEnabled = !busy
    }

    private fun look() {
        if (busy) return
        busy = true; progress.text = getString(R.string.update_checking); show()
        val app = requireContext().applicationContext
        io.execute {
            val failed = runCatching { Updates.check(app) }.isFailure
            ui { busy = false; progress.text = if (failed) getString(R.string.update_failed) else ""; show() }
        }
    }

    private fun download() {
        val r = Updates.pending(requireContext()) ?: return
        if (busy) return
        busy = true; show()
        val app = requireContext().applicationContext
        io.execute {
            try {
                val file = Updates.download(app, r) { done ->
                    val pct = if (r.apkSize > 0) (100 * done / r.apkSize).toInt().coerceIn(0, 100) else 0
                    ui { progress.text = getString(R.string.update_downloading, pct) }
                }
                ui { busy = false; progress.text = ""; show(); if (!Updates.install(app, file)) progress.text = getString(R.string.update_permission) }
            } catch (e: IOException) {
                val text = when { e is NotFound -> R.string.update_missing; e.message?.contains("match") == true -> R.string.update_bad_file; else -> R.string.update_failed }
                ui { busy = false; progress.text = getString(text); show() }
            }
        }
    }
}
