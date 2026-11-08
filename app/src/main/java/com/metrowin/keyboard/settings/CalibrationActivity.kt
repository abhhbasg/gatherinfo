package com.metrowin.keyboard.settings

import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.metrowin.keyboard.K
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.R
import com.metrowin.keyboard.calibration.Bias
import com.metrowin.keyboard.calibration.Calibrator
import com.metrowin.keyboard.calibration.Metrics
import com.metrowin.keyboard.calibration.Result
import com.metrowin.keyboard.ui.Fonts
import com.metrowin.keyboard.ui.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Types a few fixed sentences on the real keyboard and learns from the taps (see [Calibrator]). What is learned is put to work between
 * one sentence and the next, so the keyboard grows into its final setting while the test runs. If the test is left without applying it,
 * the sizes go back to what they were; nothing leaves the phone.
 */
class CalibrationActivity : AppCompatActivity() {
    private val prefs by lazy { KeyboardApp.of(this).prefs }
    private val land get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val mmPerPx get() = 25.4 / ((resources.displayMetrics.xdpi + resources.displayMetrics.ydpi) / 2.0)
    private lateinit var cal: Calibrator
    private lateinit var body: LinearLayout
    private lateinit var progress: TextView
    private lateinit var sentence: TextView
    private lateinit var hold: TextView
    private lateinit var field: EditText
    private lateinit var finishButton: TextView
    private lateinit var original: Metrics
    private var running = true
    private var applied = false
    private var lastSentence = -1
    private var precisionMm: Double? = null

    // The field only gives the keyboard something to type into. The keyboard reports a tap before it types the letter, so emptying the
    // field when the last letter of a sentence is reported would leave that letter behind. It is emptied a moment later, and once more at
    // the first tap of the next sentence in case the letter was slow: by then the old sentence is typed, and the new letter comes after.
    private var clearNext = false
    private val clearField = Runnable { field.text.clear() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        original = metrics()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MetroUi.header(this, getString(R.string.calib_title)))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24f).toInt(), dp(12f).toInt(), dp(24f).toInt(), dp(12f).toInt()) }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        MetroUi.applyInsets(root)
        start(fresh = true)
    }

    // The keyboard reports its taps to the calibrator only while the sentences are on screen.
    override fun onResume() { super.onResume(); Calibrator.current = if (running) cal else null }
    override fun onPause() { Calibrator.current = null; super.onPause() }
    override fun onDestroy() { if (!applied) restore(); super.onDestroy() }

    private fun metrics() = Metrics(if (land) prefs.keyHeightLand else prefs.keyHeight, prefs.keyGap, prefs.keyboardWidth)

    private fun setMetrics(m: Metrics) {
        if (m == metrics()) return
        prefs.sp.edit().putInt(if (land) K.KEY_HEIGHT_LAND else K.KEY_HEIGHT, m.heightPct).putInt(K.KEY_GAP, m.gapDp).putInt(K.KEYBOARD_WIDTH, m.widthPct).apply()
    }

    /** Puts the sizes back to what they were when the screen was opened. */
    private fun restore() = setMetrics(original)

    private fun sentences(): List<String> {
        val lang = (prefs.currentLanguage ?: prefs.languages.first()).substringBefore('-').lowercase()
        val file = if (assets.list("calibration")?.contains("$lang.txt") == true) "$lang.txt" else "en.txt"
        return assets.open("calibration/$file").bufferedReader().readLines().filter { it.isNotBlank() }.shuffled()
    }

    /** [fresh]: a new test from the sizes the keyboard had; otherwise the same test goes on with more sentences. */
    private fun start(fresh: Boolean) {
        running = true
        if (fresh) {
            restore(); lastSentence = -1; precisionMm = null
            cal = Calibrator(sentences())
        } else cal.more(sentences())
        cal.onChange = ::refresh
        Calibrator.current = cal
        body.removeAllViews()
        progress = MetroUi.label(this, "", 14f, MetroUi.subtle(this), Fonts.semibold(this))
        sentence = MetroUi.label(this, "", 26f, MetroUi.fg(this), Fonts.light(this)).apply { setLineSpacing(0f, 1.2f) }
        hold = MetroUi.label(this, "", 14f, MetroUi.accent(this), Fonts.semibold(this))
        field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(); textSize = 18f; typeface = Fonts.light(this@CalibrationActivity)
            setTextColor(MetroUi.fg(this@CalibrationActivity)); setHintTextColor(MetroUi.subtle(this@CalibrationActivity))
            setHint(R.string.calib_hint)
        }
        finishButton = MetroUi.button(this, getString(R.string.calib_finish), filled = false) { showResult() }
        body.addView(progress)
        body.addView(MetroUi.space(this, 12))
        body.addView(sentence)
        body.addView(MetroUi.space(this, 8))
        body.addView(hold)
        body.addView(MetroUi.space(this, 16))
        body.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(MetroUi.space(this, 16))
        body.addView(finishButton)
        clearNext = false
        field.requestFocus()
        refresh()
    }

    private fun refresh() {
        if (cal.done) { showResult(); return }
        if (cal.sentenceNumber != lastSentence) {
            if (lastSentence != -1) { clearNext = true; field.removeCallbacks(clearField); field.postDelayed(clearField, 150); learnSoFar() }
            lastSentence = cal.sentenceNumber
        } else if (clearNext) { field.text.clear(); clearNext = false }
        val pct = if (cal.taps == 0) 0 else (100f * cal.misses / cal.taps).roundToInt()
        val head = precisionMm?.let { getString(R.string.calib_progress_live, cal.sentenceNumber, cal.sentenceCount, pct, it) }
            ?: getString(R.string.calib_progress, cal.sentenceNumber, cal.sentenceCount, pct)
        progress.text = head.uppercase()
        val s = cal.sentence; val i = cal.position
        sentence.text = SpannableString(s).apply {
            setSpan(ForegroundColorSpan(MetroUi.accent(this@CalibrationActivity)), 0, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i < s.length) setSpan(UnderlineSpan(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i + 1 < s.length) setSpan(ForegroundColorSpan(MetroUi.subtle(this@CalibrationActivity)), i + 1, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val h = cal.holdHint()
        hold.text = h?.let { getString(R.string.calib_hold, it.first, it.second) } ?: ""
        hold.visibility = if (h != null) View.VISIBLE else View.INVISIBLE
        finishButton.visibility = if (cal.canFinish) View.VISIBLE else View.GONE
    }

    /** Between two sentences: what has been learned goes to the keyboard at once, so the next sentence is typed with it. */
    private fun learnSoFar() {
        val r = cal.fit(LIVE_MIN) ?: return
        cal.liveBias = r.bias
        precisionMm = r.errAfterPx * mmPerPx
        if (cal.canFinish) setMetrics(Calibrator.advise(metrics(), r))
    }

    private fun showResult() {
        running = false
        Calibrator.current = null
        field.removeCallbacks(clearField)
        body.removeAllViews()
        val r = cal.fit()
        if (r == null) {
            line(getString(R.string.calib_result_few))
            body.addView(MetroUi.space(this, 20))
            body.addView(MetroUi.button(this, getString(R.string.calib_more)) { start(fresh = false) })
            body.addView(MetroUi.space(this, 12))
            body.addView(MetroUi.button(this, getString(R.string.calib_retry), filled = false) { start(fresh = true) })
            return
        }
        val next = Calibrator.advise(metrics(), r)
        line(getString(R.string.calib_result_misses, (r.before * 100).roundToInt(), (r.after * 100).roundToInt()), 22f)
        line(getString(R.string.calib_result_precision, r.errBeforePx * mmPerPx, r.errAfterPx * mmPerPx))
        if (r.bias != null) {
            val dx = r.meanDx * mmPerPx; val dy = r.meanDy * mmPerPx
            line(getString(R.string.calib_result_offset, abs(dx), getString(if (dx >= 0) R.string.calib_right else R.string.calib_left), abs(dy), getString(if (dy >= 0) R.string.calib_down else R.string.calib_up)))
        }
        if (next.heightPct != original.heightPct) line(getString(R.string.calib_result_height, original.heightPct, next.heightPct))
        if (next.gapDp != original.gapDp) line(getString(R.string.calib_result_gap, original.gapDp, next.gapDp))
        if (next.widthPct != original.widthPct) line(getString(R.string.calib_result_width, original.widthPct, next.widthPct))
        val change = r.bias != null || next != original
        if (!change) line(getString(R.string.calib_result_none))
        body.addView(MetroUi.space(this, 20))
        if (change) body.addView(MetroUi.button(this, getString(R.string.calib_apply)) { apply(r, next) })
        body.addView(MetroUi.space(this, 12))
        body.addView(MetroUi.button(this, getString(R.string.calib_more), filled = !change) { start(fresh = false) })
        body.addView(MetroUi.space(this, 12))
        body.addView(MetroUi.button(this, getString(R.string.calib_retry), filled = false) { start(fresh = true) })
    }

    private fun line(text: String, sp: Float = 16f) {
        body.addView(MetroUi.label(this, text, sp, MetroUi.fg(this), Fonts.light(this)).apply { setPadding(0, 0, 0, dp(8f).toInt()) })
    }

    /** One write, so the keyboard refreshes once. A calibration that found nothing to correct removes the old correction. */
    private fun apply(r: Result, next: Metrics) {
        applied = true
        prefs.sp.edit()
            .putString(if (land) K.CALIB_BIAS_LAND else K.CALIB_BIAS, Bias.encode(r.bias))
            .putInt(if (land) K.KEY_HEIGHT_LAND else K.KEY_HEIGHT, next.heightPct)
            .putInt(K.KEY_GAP, next.gapDp).putInt(K.KEYBOARD_WIDTH, next.widthPct)
            // How far the taps still land from the key meant, in percent of a key: the correction of typos weighs the finger by it.
            .putInt(K.CALIB_SIGMA, (100 * ((r.sigmaX / r.keyW + r.sigmaY / r.keyH) / 2)).roundToInt().coerceIn(15, 90))
            .apply()
        Toast.makeText(this, R.string.calib_applied, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object { const val LIVE_MIN = 30 }
}
