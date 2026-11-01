package com.metrowin.keyboard.settings

import android.Manifest
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.metrowin.keyboard.KeyboardApp
import java.util.concurrent.Executors

/** A keyboard cannot show a permission dialog or a file picker, so it opens this invisible screen to do it and closes it at once. */
class HelperActivity : AppCompatActivity() {
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
    private val picture = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) { finish(); return@registerForActivityResult }
        val app = KeyboardApp.of(this)
        Executors.newSingleThreadExecutor().execute {
            runCatching { contentResolver.openInputStream(uri)?.let { app.clipboard.addImage(it, (contentResolver.getType(uri) ?: "image/jpeg").substringAfter('/')) } }
            runOnUiThread { finish() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_MIC -> mic.launch(Manifest.permission.RECORD_AUDIO)
            MODE_CLIP_IMAGE -> picture.launch("image/*")
            else -> finish()
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_MIC = "mic"
        const val MODE_CLIP_IMAGE = "clip_image"
    }
}
