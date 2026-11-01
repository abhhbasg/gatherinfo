package com.metrowin.keyboard.settings

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.metrowin.keyboard.R

/** A plain text box to try the keyboard in, right after turning it on. */
class TestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.test_title)
        val pad = (resources.displayMetrics.density * 16).toInt()
        setContentView(EditText(this).apply {
            setHint(R.string.test_hint)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(pad, pad, pad, pad)
            background = null
            requestFocus()
        })
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
