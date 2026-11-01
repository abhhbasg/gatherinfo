package com.metrowin.keyboard.settings

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.R

/** The settings: one list of screens, each a preference fragment, under a big light title like a Windows Phone page. */
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private var titleView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val root = findViewById<LinearLayout>(R.id.settings_root)
        MetroUi.applyInsets(root)
        val header = MetroUi.header(this, getString(R.string.app_name))
        titleView = header.findViewById(R.id.header_title)
        root.addView(header, 0)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, RootFragment()).commit()
            // The first time, a guided set-up comes first.
            if (!KeyboardApp.of(this).prefs.onboardingDone) startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onTitleChanged(title: CharSequence?, color: Int) {
        super.onTitleChanged(title, color)
        titleView?.text = title.toString().lowercase()
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val name = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, name)
        supportFragmentManager.beginTransaction().replace(R.id.settings_container, fragment).addToBackStack(null).commit()
        return true
    }
}
