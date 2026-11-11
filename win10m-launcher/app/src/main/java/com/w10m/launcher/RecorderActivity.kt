package com.w10m.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/**
 * Voice Recorder in the W10M layout: a large timer above a big round accent record button with a
 * microphone glyph; while recording the button turns into stop and a pause key appears. Recordings
 * are listed below with name, date and length; tap to play, long-press to rename / share / delete.
 */
class RecorderActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var shell: Shell
    private lateinit var timer: TextView
    private lateinit var recButton: RecButton
    private lateinit var pauseButton: GlyphButton
    private lateinit var list: LinearLayout
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var playing: File? = null
    private var current: File? = null
    private var startedAt = 0L
    private var pausedTotal = 0L
    private var pausedAt = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateTimer(); ui.postDelayed(this, 200) }
    }

    private val dir: File get() = File(filesDir, "recordings").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); p = prefs.palette()
        shell = Shell(this, prefs, p)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        root.addView(pageTitle(this, p, getString(R.string.recorder)))

        timer = TextView(this).apply {
            textSize = 46f; typeface = Metro.light(context); setTextColor(p.fg); gravity = Gravity.CENTER; text = clockText(0)
        }
        root.addView(timer, LinearLayout.LayoutParams(-1, dpi(88f)))

        val controls = FrameLayout(this)
        recButton = RecButton(this).apply { setOnClickListener { if (recorder == null) start() else stop() } }
        controls.addView(recButton, FrameLayout.LayoutParams(dpi(96f), dpi(96f), Gravity.CENTER))
        pauseButton = GlyphButton(this, p.fg) { c, pt, cx, cy, u ->
            pt.style = Paint.Style.FILL
            if (pausedAt > 0) {
                val path = android.graphics.Path(); path.moveTo(cx - 5 * u, cy - 8 * u); path.lineTo(cx + 8 * u, cy); path.lineTo(cx - 5 * u, cy + 8 * u); path.close()
                c.drawPath(path, pt)
            } else { c.drawRect(cx - 6 * u, cy - 8 * u, cx - 2 * u, cy + 8 * u, pt); c.drawRect(cx + 2 * u, cy - 8 * u, cx + 6 * u, cy + 8 * u, pt) }
        }.apply { visibility = View.INVISIBLE; setOnClickListener { togglePause() } }
        controls.addView(pauseButton, FrameLayout.LayoutParams(dpi(56f), dpi(56f), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dpi(40f) })
        root.addView(controls, LinearLayout.LayoutParams(-1, dpi(140f)))

        root.addView(TextView(this).apply {
            text = getString(R.string.recordings); textSize = 15f; typeface = Metro.semibold(context); setTextColor(p.fg)
            setPadding(dpi(Metro.PAGE_MARGIN), dpi(8f), dpi(Metro.PAGE_MARGIN), dpi(4f))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list); overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.setContent(root)
        fillList()
    }

    override fun onStop() {
        super.onStop()
        // Keep recording in the background only while the screen is ours; finish cleanly otherwise.
        if (isFinishing) { stop(); stopPlayback() }
    }

    override fun onDestroy() { super.onDestroy(); stop(); stopPlayback(); ui.removeCallbacksAndMessages(null) }

    // ---------------------------------------------------------------- recording

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        stopPlayback()
        val n = (dir.listFiles()?.size ?: 0) + 1
        var f = File(dir, getString(R.string.recording_name, n) + ".m4a")
        var k = n
        while (f.exists()) f = File(dir, getString(R.string.recording_name, ++k) + ".m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000); r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.path)
            r.prepare(); r.start()
            recorder = r; current = f
        } catch (_: Exception) { f.delete(); return }
        startedAt = SystemClock.elapsedRealtime(); pausedTotal = 0; pausedAt = 0
        recButton.recording = true
        pauseButton.visibility = View.VISIBLE; pauseButton.invalidate()
        ui.post(tick)
    }

    private fun togglePause() {
        val r = recorder ?: return
        try {
            if (pausedAt > 0) { r.resume(); pausedTotal += SystemClock.elapsedRealtime() - pausedAt; pausedAt = 0 }
            else { r.pause(); pausedAt = SystemClock.elapsedRealtime() }
        } catch (_: Exception) {}
        pauseButton.invalidate(); recButton.invalidate()
    }

    private fun stop() {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (_: Exception) { current?.delete() }
        r.release()
        ui.removeCallbacks(tick)
        recButton.recording = false
        pauseButton.visibility = View.INVISIBLE
        pausedAt = 0
        timer.text = clockText(0)
        fillList()
    }

    private fun elapsed(): Long {
        val now = if (pausedAt > 0) pausedAt else SystemClock.elapsedRealtime()
        return now - startedAt - pausedTotal
    }

    private fun updateTimer() {
        val r = recorder
        if (r != null) {
            timer.text = clockText(elapsed())
            recButton.level = try { r.maxAmplitude / 32767f } catch (_: Exception) { 0f }
        } else player?.let { timer.text = clockText(it.currentPosition.toLong()) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
    }

    // ---------------------------------------------------------------- list and playback

    private fun duration(f: File): Long = try {
        MediaMetadataRetriever().run { setDataSource(f.path); val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L; release(); d }
    } catch (_: Exception) { 0L }

    private fun fillList() {
        list.removeAllViews()
        val m = dpi(Metro.PAGE_MARGIN)
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) list.addView(TextView(this).apply {
            text = getString(R.string.no_recordings); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium); setPadding(m, dpi(4f), m, 0)
        })
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(m, dpi(6f), m, dpi(6f))
                addView(TextView(context).apply {
                    text = f.nameWithoutExtension; textSize = 20f; typeface = Metro.semilight(context)
                    setTextColor(if (f == playing) p.accent else p.fg); isSingleLine = true
                })
                addView(TextView(context).apply {
                    text = DateUtils.formatDateTime(context, f.lastModified(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL) +
                        "   " + clockText(duration(f))
                    textSize = 12f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
                })
            }
            row.setOnClickListener { if (playing == f) stopPlayback() else play(f) }
            row.setOnLongClickListener {
                MetroMenu.show(row, p, listOf(
                    getString(R.string.share) to { share(f) },
                    getString(R.string.delete) to { if (playing == f) stopPlayback(); f.delete(); fillList() },
                )); true
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun play(f: File) {
        if (recorder != null) return
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(f.path); prepare(); start()
                setOnCompletionListener { stopPlayback() }
            }
            playing = f
            ui.post(tick)
        } catch (_: Exception) { stopPlayback() }
        fillList()
    }

    private fun stopPlayback() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        val was = playing != null
        player = null; playing = null
        if (recorder == null) { ui.removeCallbacks(tick); if (::timer.isInitialized) timer.text = clockText(0) }
        if (was && ::list.isInitialized) fillList()
    }

    private fun share(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!shell.handleBack()) @Suppress("DEPRECATION") super.onBackPressed() }

    /** 96 epx accent circle: microphone glyph at rest, white stop square while recording, with a level ring. */
    private inner class RecButton(c: Context) : View(c) {
        var recording = false
            set(v) { field = v; level = 0f; invalidate() }
        var level = 0f
            set(v) { field = v; invalidate() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val gp = Paint(Paint.ANTI_ALIAS_FLAG)

        init { isClickable = true }

        override fun onDraw(cv: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - dp(6f)
            if (recording && pausedAt == 0L) {
                ring.color = p.accent; ring.alpha = 90; ring.strokeWidth = dp(2f) + level * dp(6f)
                cv.drawCircle(cx, cy, r + ring.strokeWidth / 2, ring)
            }
            fill.color = if (isPressed) p.chromeHigh else p.accent
            cv.drawCircle(cx, cy, r, fill)
            if (recording) {
                fill.color = Color.WHITE
                val s = dp(10f); cv.drawRect(cx - s, cy - s, cx + s, cy + s, fill)
            } else IconPack.glyph("mic")?.draw(cv, cx - dp(16f), cy - dp(16f), dp(32f), gp)
        }

        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    }
}
