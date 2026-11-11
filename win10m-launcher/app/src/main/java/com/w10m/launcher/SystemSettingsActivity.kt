package com.w10m.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings in the W10M layout: "SETTINGS" title, "Find a setting" box, and the category list
 * (glyph, name, one-line summary). Each category opens a page of entries; entries open the matching
 * Android settings screen, and Personalization leads to the launcher's own Start / colour settings.
 */
class SystemSettingsActivity : Activity() {

    /** A target: an Android settings action (with fallbacks) or one of the launcher's own screens. */
    private class Entry(val title: Int, val desc: Int, val actions: List<String> = emptyList(), val own: Class<*>? = null, val pkgUri: Boolean = false)
    private class Category(val glyph: String, val title: Int, val desc: Int, val entries: List<Entry>)

    private val categories = listOf(
        Category("phone", R.string.set_system, R.string.set_system_d, listOf(
            Entry(R.string.set_display, R.string.set_display_d, listOf(Settings.ACTION_DISPLAY_SETTINGS)),
            Entry(R.string.set_notifications, R.string.set_notifications_d, listOf("android.settings.NOTIFICATION_SETTINGS", Settings.ACTION_APPLICATION_SETTINGS)),
            Entry(R.string.set_apps, R.string.set_apps_d, listOf(Settings.ACTION_APPLICATION_SETTINGS)),
            Entry(R.string.set_default_apps, R.string.set_default_apps_d, listOf(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_HOME_SETTINGS)),
            Entry(R.string.set_battery, R.string.set_battery_d, listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS)),
            Entry(R.string.set_storage, R.string.set_storage_d, listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
            Entry(R.string.set_about, R.string.set_about_d, listOf(Settings.ACTION_DEVICE_INFO_SETTINGS)),
        )),
        Category("bluetooth", R.string.set_devices, R.string.set_devices_d, listOf(
            Entry(R.string.qa_bluetooth, R.string.set_bluetooth_d, listOf(Settings.ACTION_BLUETOOTH_SETTINGS)),
            Entry(R.string.set_nfc, R.string.set_nfc_d, listOf(Settings.ACTION_NFC_SETTINGS)),
            Entry(R.string.set_cast, R.string.set_cast_d, listOf(Settings.ACTION_CAST_SETTINGS)),
            Entry(R.string.set_print, R.string.set_print_d, listOf("android.settings.ACTION_PRINT_SETTINGS")),
        )),
        Category("wifi", R.string.set_network, R.string.set_network_d, listOf(
            Entry(R.string.qa_wifi, R.string.set_wifi_d, listOf(Settings.ACTION_WIFI_SETTINGS)),
            Entry(R.string.set_cellular, R.string.set_cellular_d, listOf(Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)),
            Entry(R.string.qa_airplane, R.string.set_airplane_d, listOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS)),
            Entry(R.string.set_data_usage, R.string.set_data_usage_d, listOf(Settings.ACTION_DATA_USAGE_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)),
            Entry(R.string.set_vpn, R.string.set_vpn_d, listOf(Settings.ACTION_VPN_SETTINGS)),
            Entry(R.string.set_hotspot, R.string.set_hotspot_d, listOf("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)),
        )),
        Category("brush", R.string.set_personalization, R.string.set_personalization_d, listOf(
            Entry(R.string.settings_start, R.string.set_start_d, own = SettingsActivity::class.java),
            Entry(R.string.set_colors, R.string.set_colors_d, own = SettingsActivity::class.java),
            Entry(R.string.set_lock, R.string.set_lock_d, listOf(Settings.ACTION_SECURITY_SETTINGS)),
            Entry(R.string.set_sounds, R.string.set_sounds_d, listOf(Settings.ACTION_SOUND_SETTINGS)),
        )),
        Category("people", R.string.set_accounts, R.string.set_accounts_d, listOf(
            Entry(R.string.set_your_accounts, R.string.set_your_accounts_d, listOf(Settings.ACTION_SYNC_SETTINGS)),
            Entry(R.string.set_add_account, R.string.set_add_account_d, listOf(Settings.ACTION_ADD_ACCOUNT)),
        )),
        Category("clock", R.string.set_time, R.string.set_time_d, listOf(
            Entry(R.string.set_date_time, R.string.set_date_time_d, listOf(Settings.ACTION_DATE_SETTINGS)),
            Entry(R.string.set_language, R.string.set_language_d, listOf(Settings.ACTION_LOCALE_SETTINGS)),
            Entry(R.string.set_keyboard, R.string.set_keyboard_d, listOf(Settings.ACTION_INPUT_METHOD_SETTINGS)),
        )),
        Category("star", R.string.set_ease, R.string.set_ease_d, listOf(
            Entry(R.string.set_accessibility, R.string.set_accessibility_d, listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            Entry(R.string.set_captions, R.string.set_captions_d, listOf(Settings.ACTION_CAPTIONING_SETTINGS)),
        )),
        Category("shield", R.string.set_privacy, R.string.set_privacy_d, listOf(
            Entry(R.string.qa_location, R.string.set_location_d, listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
            Entry(R.string.set_permissions, R.string.set_permissions_d, listOf("android.settings.PRIVACY_SETTINGS", Settings.ACTION_APPLICATION_SETTINGS)),
            Entry(R.string.set_this_app, R.string.set_this_app_d, listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), pkgUri = true),
        )),
        Category("download", R.string.set_update, R.string.set_update_d, listOf(
            Entry(R.string.set_phone_update, R.string.set_phone_update_d, listOf("android.settings.SYSTEM_UPDATE_SETTINGS", Settings.ACTION_DEVICE_INFO_SETTINGS)),
            Entry(R.string.set_security, R.string.set_security_d, listOf(Settings.ACTION_SECURITY_SETTINGS)),
            Entry(R.string.set_backup, R.string.set_backup_d, listOf("android.settings.BACKUP_AND_RESET_SETTINGS", Settings.ACTION_PRIVACY_SETTINGS)),
        )),
    )

    private lateinit var p: Palette
    private lateinit var shell: Shell
    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var search: MetroSearchBox
    private var open: Category? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); p = prefs.palette()
        shell = Shell(this, prefs, p)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); isFocusableInTouchMode = true }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dpi(24f)) }
        search = MetroSearchBox(this, p).apply { edit.setHint(R.string.find_setting) }
        search.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (open == null) showHome() }
        })
        scroll = ScrollView(this).apply { addView(col); overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        shell.setContent(root)
        root.requestFocus()
        showHome()
    }

    private fun showHome() {
        open = null
        col.removeAllViews()
        col.addView(pageTitle(this, p, getString(R.string.settings_app)))
        (search.parent as? LinearLayout)?.removeView(search)
        col.addView(search, LinearLayout.LayoutParams(-1, dpi(32f)).apply {
            val m = dpi(Metro.PAGE_MARGIN); setMargins(m, dpi(4f), m, dpi(12f))
        })
        val q = search.edit.text.toString().trim()
        if (q.isEmpty()) {
            for (c in categories) col.addView(row(c.glyph, getString(c.title), getString(c.desc)) { showCategory(c) })
        } else {
            // Search matches entry titles and descriptions across every category.
            val hits = categories.flatMap { c -> c.entries.map { c to it } }.filter { (_, e) ->
                getString(e.title).contains(q, true) || getString(e.desc).contains(q, true)
            }
            if (hits.isEmpty()) col.addView(TextView(this).apply {
                text = getString(R.string.no_setting_found); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
                setPadding(dpi(Metro.PAGE_MARGIN), 0, dpi(Metro.PAGE_MARGIN), 0)
            })
            for ((c, e) in hits) col.addView(row(c.glyph, getString(e.title), getString(c.title)) { launch(e) })
        }
        scroll.scrollTo(0, 0)
    }

    private fun showCategory(c: Category) {
        open = c
        col.removeAllViews()
        col.addView(pageTitle(this, p, getString(c.title)))
        for (e in c.entries) col.addView(row(null, getString(e.title), getString(e.desc)) { launch(e) })
        scroll.scrollTo(0, 0)
        col.post { Motion.drillIn(col) }
    }

    /** Row: optional 24 epx glyph, title 15, summary 12 at 60 %. */
    private fun row(glyph: String?, title: String, desc: String, tap: () -> Unit): View = LinearLayout(this).apply {
        val m = dpi(Metro.PAGE_MARGIN)
        setPadding(m, dpi(10f), m, dpi(10f))
        isClickable = true
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(if (p.dark) 0x22FFFFFF else 0x22000000))
        }
        if (glyph != null) addView(object : View(context) {
            val gp = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) { IconPack.glyph(glyph)?.draw(c, 0f, dp(4f), dp(24f), gp, p.fg) }
        }, LinearLayout.LayoutParams(dpi(24f), dpi(32f)).apply { marginEnd = dpi(16f) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fg) })
            addView(TextView(context).apply { text = desc; textSize = 12f; typeface = Metro.regular(context); setTextColor(p.fgMedium) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { tap() }
    }

    private fun launch(e: Entry) {
        if (e.own != null) { startActivity(Intent(this, e.own)); return }
        for (a in e.actions + Settings.ACTION_SETTINGS) {
            val i = Intent(a).apply { if (e.pkgUri) data = Uri.fromParts("package", packageName, null) }
            try { startActivity(i); return } catch (_: Exception) {}
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            shell.handleBack() -> Unit
            open != null -> { showHome(); Motion.drillBackIn(col) }
            search.edit.text.isNotEmpty() -> search.edit.setText("")
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    companion object {
        fun intent(c: Context) = Intent(c, SystemSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
