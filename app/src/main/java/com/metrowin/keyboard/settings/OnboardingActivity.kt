package com.metrowin.keyboard.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.metrowin.keyboard.K
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.R
import com.metrowin.keyboard.ui.Fonts
import com.metrowin.keyboard.ui.dp

/**
 * The guided set-up shown the first time: turn the keyboard on, choose it, (optionally) allow the microphone, try it. Each step
 * notices by itself when it is done, so the person only has to follow the buttons.
 */
class OnboardingActivity : AppCompatActivity() {
    private enum class Step { WELCOME, ENABLE, SELECT, MIC, TRY, DONE }

    private var step = Step.WELCOME
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var holder: LinearLayout
    private var status: TextView? = null
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { go(Step.TRY) }

    private fun enabled() = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
    private fun active() = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private val poll = object : Runnable {
        override fun run() {
            when {
                step == Step.ENABLE && enabled() -> { markOk(); handler.postDelayed({ go(Step.SELECT) }, 700); return }
                step == Step.SELECT && active() -> { markOk(); handler.postDelayed({ go(Step.MIC) }, 700); return }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shown once: leaving half-way does not bring it back at every start (it stays available in the settings).
        KeyboardApp.of(this).prefs.putBoolean(K.ONBOARDING_DONE, true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(holder) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        MetroUi.applyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (step.ordinal == 0) finish() else go(Step.entries[step.ordinal - 1]) }
        })
        go(Step.WELCOME)
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacksAndMessages(null); super.onPause() }

    private fun markOk() { status?.text = "✓  " + getString(R.string.onb_done_step); status?.setTextColor(MetroUi.accent(this)) }

    private fun go(s: Step) {
        step = s
        handler.removeCallbacksAndMessages(null)
        holder.removeAllViews()
        status = null
        val (title, text) = when (s) {
            Step.WELCOME -> R.string.onb_welcome_title to R.string.onb_welcome_text
            Step.ENABLE -> R.string.onb_enable_title to R.string.onb_enable_text
            Step.SELECT -> R.string.onb_select_title to R.string.onb_select_text
            Step.MIC -> R.string.onb_mic_title to R.string.onb_mic_text
            Step.TRY -> R.string.onb_try_title to R.string.onb_try_text
            Step.DONE -> R.string.onb_finish_title to R.string.onb_finish_text
        }
        holder.addView(MetroUi.header(this, getString(title)))
        val pad = dp(24f).toInt()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(12f).toInt(), pad, pad) }
        body.addView(MetroUi.label(this, "${s.ordinal + 1} / ${Step.entries.size}", 13f, MetroUi.accent(this), Fonts.semibold(this)))
        body.addView(MetroUi.label(this, getString(text), 18f, MetroUi.fg(this), Fonts.light(this)).apply { setLineSpacing(0f, 1.2f); includeFontPadding = true; setPadding(0, dp(8f).toInt(), 0, dp(16f).toInt()) })
        val gap = dp(12f).toInt()
        fun addButton(label: Int, filled: Boolean = true, action: () -> Unit) = body.addView(
            MetroUi.button(this, getString(label), filled, action),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap },
        )
        fun addStatus(idle: Int) { status = MetroUi.label(this, getString(idle), 15f, MetroUi.subtle(this), Fonts.regular(this)).apply { setPadding(0, gap, 0, 0) }; body.addView(status) }
        when (s) {
            Step.WELCOME -> addButton(R.string.onb_start) { go(Step.ENABLE) }
            Step.ENABLE -> {
                if (enabled()) { go(Step.SELECT); return }
                addButton(R.string.onb_open_settings) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                addStatus(R.string.onb_waiting)
                handler.post(poll)
            }
            Step.SELECT -> {
                if (active()) { go(Step.MIC); return }
                addButton(R.string.onb_open_picker) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
                addStatus(R.string.onb_waiting)
                handler.post(poll)
            }
            Step.MIC -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { go(Step.TRY); return }
                addButton(R.string.onb_allow_mic) { mic.launch(Manifest.permission.RECORD_AUDIO) }
                addButton(R.string.onb_skip, filled = false) { go(Step.TRY) }
            }
            Step.TRY -> {
                body.addView(EditText(this).apply {
                    setHint(R.string.test_hint); textSize = 20f; typeface = Fonts.light(this@OnboardingActivity); minLines = 4; gravity = Gravity.TOP
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    setTextColor(MetroUi.fg(this@OnboardingActivity)); setHintTextColor(MetroUi.subtle(this@OnboardingActivity))
                    requestFocus()
                })
                addButton(R.string.onb_next) { go(Step.DONE) }
            }
            Step.DONE -> {
                addButton(R.string.onb_tour) { startActivity(Intent(this, TutorialActivity::class.java).putExtra("start", 0)); finish() }
                addButton(R.string.onb_finish, filled = false) { finish() }
            }
        }
        holder.addView(body)
    }
}
