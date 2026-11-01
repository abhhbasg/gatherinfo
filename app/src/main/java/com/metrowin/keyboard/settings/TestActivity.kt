package com.metrowin.keyboard.settings

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.metrowin.keyboard.R

/** A plain text box to try the keyboard in, right after turning it on. */
class TestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MetroUi.header(this, getString(R.string.test_title)))
        root.addView(EditText(this).apply {
            setHint(R.string.test_hint)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(pad, pad, pad, pad)
            background = null
            textSize = 20f
            typeface = com.metrowin.keyboard.ui.Fonts.light(this@TestActivity)
            setTextColor(MetroUi.fg(this@TestActivity)); setHintTextColor(MetroUi.subtle(this@TestActivity))
            requestFocus()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        MetroUi.applyInsets(root)    }
}
