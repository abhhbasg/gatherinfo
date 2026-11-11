package com.w10m.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.Collator
import java.util.Locale

/** One track from the system media library. */
class Track(val id: Long, val title: String, val artist: String, val album: String, val albumId: Long, val duration: Long) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}

/**
 * Process-wide player so music keeps going while the user leaves the Music screen
 * (no foreground service: the system may stop it if it needs the memory).
 */
object Player {
    var queue: List<Track> = emptyList(); private set
    var index = -1; private set
    var shuffle = false
    var repeat = false
    private var mp: MediaPlayer? = null
    val listeners = LinkedHashSet<() -> Unit>()
    private fun changed() = listeners.toList().forEach { it() }

    val current: Track? get() = queue.getOrNull(index)
    val isPlaying: Boolean get() = try { mp?.isPlaying == true } catch (_: Exception) { false }
    val position: Long get() = try { mp?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }

    fun play(c: Context, list: List<Track>, i: Int) { queue = list; load(c.applicationContext, i) }

    private fun load(c: Context, i: Int) {
        mp?.release(); mp = null
        index = i
        val t = current ?: return changed()
        mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            try { setDataSource(c, t.uri); prepare(); start() } catch (_: Exception) {}
            setOnCompletionListener { next(c, auto = true) }
        }
        changed()
    }

    fun toggle() { mp?.let { if (it.isPlaying) it.pause() else it.start() }; changed() }

    fun next(c: Context, auto: Boolean = false) {
        if (queue.isEmpty()) return
        val n = when {
            shuffle -> queue.indices.random()
            index + 1 < queue.size -> index + 1
            repeat || !auto -> 0
            else -> { mp?.seekTo(0); mp?.pause(); changed(); return }
        }
        load(c.applicationContext, n)
    }

    fun prev(c: Context) {
        if (queue.isEmpty()) return
        // Like Groove: restart the song if more than 3 s in, otherwise go to the previous one.
        if (position > 3000) { mp?.seekTo(0); changed(); return }
        load(c.applicationContext, if (index > 0) index - 1 else queue.size - 1)
    }

    fun seek(ms: Long) { try { mp?.seekTo(ms.toInt()) } catch (_: Exception) {}; changed() }
}

/**
 * Music in the W10M Groove layout: Songs / Artists / Albums pivot, now-playing bar at the bottom,
 * and a full now-playing view with big album art, seek bar and the transport row.
 */
class MusicActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var shell: Shell
    private lateinit var pivot: PivotHeader
    private lateinit var list: RecyclerView
    private lateinit var empty: LinearLayout
    private lateinit var bar: NowBar
    private lateinit var full: FullPlayer
    private var tracks: List<Track> = emptyList()
    private var mode = 0
    private var rows: List<Any> = emptyList()       // Track or String (group header)
    private val art = HashMap<Long, Bitmap?>()
    private val ui = Handler(Looper.getMainLooper())
    private val refresh: () -> Unit = { bar.update(); full.update(); list.adapter?.notifyDataSetChanged() }
    private val ticker = object : Runnable { override fun run() { full.updateProgress(); bar.invalidateProgress(); ui.postDelayed(this, 500) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); p = prefs.palette()
        shell = Shell(this, prefs, p)
        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(pageTitle(this, p, getString(R.string.music)))
        pivot = PivotHeader(this, p, listOf(getString(R.string.songs), getString(R.string.artists), getString(R.string.albums))) { mode = it; rebuild(); Motion.pageRefresh(list) }
        col.addView(pivot)
        val body = FrameLayout(this)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context); adapter = Adapter(); itemAnimator = null }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(Metro.PAGE_MARGIN), dpi(8f), dpi(Metro.PAGE_MARGIN), 0) }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        bar = NowBar(this).apply { setOnClickListener { if (Player.current != null) full.show() } }
        col.addView(bar, LinearLayout.LayoutParams(-1, dpi(64f)))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        full = FullPlayer(this)
        root.addView(full, FrameLayout.LayoutParams(-1, -1))
        shell.setContent(root)
        load()
    }

    override fun onStart() { super.onStart(); Player.listeners += refresh; refresh(); ui.post(ticker) }
    override fun onStop() { super.onStop(); Player.listeners -= refresh; ui.removeCallbacks(ticker) }

    private fun perm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun load() {
        empty.removeAllViews()
        if (checkSelfPermission(perm()) != PackageManager.PERMISSION_GRANTED) {
            tracks = emptyList(); rebuild()
            empty.addView(TextView(this).apply {
                text = getString(R.string.music_permission); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
            })
            empty.addView(metroButton(this, p, getString(R.string.grant)) { requestPermissions(arrayOf(perm()), 1) },
                LinearLayout.LayoutParams(-2, dpi(32f)).apply { topMargin = dpi(12f) })
            return
        }
        val out = ArrayList<Track>()
        try {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION),
                MediaStore.Audio.Media.IS_MUSIC + "!=0", null, null)?.use { c ->
                while (c.moveToNext()) out += Track(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty().replace("<unknown>", ""),
                    c.getString(3).orEmpty(), c.getLong(4), c.getLong(5))
            }
        } catch (_: Exception) {}
        val col = Collator.getInstance(Locale.CHINA)
        tracks = out.sortedWith { a, b -> col.compare(a.title, b.title) }
        rebuild()
        if (tracks.isEmpty()) empty.addView(TextView(this).apply {
            text = getString(R.string.no_music); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = load()

    private fun rebuild() {
        val col = Collator.getInstance(Locale.CHINA)
        val unknown = getString(R.string.unknown_artist)
        rows = when (mode) {
            0 -> tracks
            else -> {
                val key: (Track) -> String = if (mode == 1) { t -> t.artist.ifEmpty { unknown } } else { t -> t.album.ifEmpty { unknown } }
                tracks.groupBy(key).toSortedMap { a, b -> col.compare(a, b) }.flatMap { (k, v) -> listOf<Any>(k) + v }
            }
        }
        list.adapter?.notifyDataSetChanged()
    }

    fun albumArt(albumId: Long, size: Int): Bitmap? = art.getOrPut(albumId * 10 + if (size > 200) 1 else 0) {
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
            if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(uri, Size(size, size), null) else null
        } catch (_: Exception) { null }
    }

    private fun queueFor(t: Track): List<Track> = rows.filterIsInstance<Track>().let { if (it.contains(t)) it else tracks }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = SongRow(this@MusicActivity)
            v.layoutParams = RecyclerView.LayoutParams(-1, dpi(if (viewType == 0) 44f else 56f))
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView as SongRow
            when (val r = rows[position]) {
                is String -> { v.header = r; v.track = null; v.setOnClickListener(null); v.isClickable = false }
                is Track -> { v.header = null; v.track = r; v.setOnClickListener { val q = queueFor(r); Player.play(this@MusicActivity, q, q.indexOf(r)) } }
            }
            v.invalidate()
        }
    }

    /** Song: title 15 (accent when playing), "artist · album" 12 at 60 %, length on the right. Header: 20 epx accent. */
    private inner class SongRow(c: Context) : View(c) {
        var header: String? = null
        var track: Track? = null
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(15f); typeface = Metro.regular(c) }
        private val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(12f); typeface = Metro.regular(c) }
        private val hp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(20f); typeface = Metro.semilight(c) }
        override fun onDraw(cv: Canvas) {
            val m = dp(Metro.PAGE_MARGIN)
            header?.let {
                hp.color = p.accent
                val t = TextUtils.ellipsize(it, hp, width - 2 * m, TextUtils.TruncateAt.END)
                cv.drawText(t, 0, t.length, m, height / 2f - (hp.ascent() + hp.descent()) / 2, hp); return
            }
            val t = track ?: return
            val now = Player.current?.id == t.id
            sp.color = p.fgMedium; sp.textAlign = Paint.Align.RIGHT
            val len = clockText(t.duration)
            cv.drawText(len, width - m, dp(10f) - tp.ascent(), sp)
            val room = width - 2 * m - sp.measureText(len) - dp(12f)
            tp.color = if (now) p.accent else p.fg
            val title = TextUtils.ellipsize(t.title, tp, room, TextUtils.TruncateAt.END)
            cv.drawText(title, 0, title.length, m, dp(8f) - tp.ascent(), tp)
            sp.textAlign = Paint.Align.LEFT
            val sub = listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" · ")
            val st = TextUtils.ellipsize(sub, sp, room, TextUtils.TruncateAt.END)
            cv.drawText(st, 0, st.length, m, dp(32f) - sp.ascent(), sp)
        }
    }

    // ---------------------------------------------------------------- transport glyphs

    private fun drawTransport(c: Canvas, pt: Paint, cx: Float, cy: Float, u: Float, kind: Int) {
        val path = Path()
        pt.style = Paint.Style.STROKE
        when (kind) {
            0 -> { // previous: bar + left triangle
                c.drawLine(cx - 7 * u, cy - 7 * u, cx - 7 * u, cy + 7 * u, pt)
                path.moveTo(cx + 7 * u, cy - 7 * u); path.lineTo(cx - 4 * u, cy); path.lineTo(cx + 7 * u, cy + 7 * u); path.close()
            }
            1 -> if (Player.isPlaying) { // pause
                c.drawLine(cx - 4 * u, cy - 8 * u, cx - 4 * u, cy + 8 * u, pt); c.drawLine(cx + 4 * u, cy - 8 * u, cx + 4 * u, cy + 8 * u, pt)
            } else { path.moveTo(cx - 5 * u, cy - 8 * u); path.lineTo(cx + 8 * u, cy); path.lineTo(cx - 5 * u, cy + 8 * u); path.close() }
            2 -> { // next
                c.drawLine(cx + 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u, pt)
                path.moveTo(cx - 7 * u, cy - 7 * u); path.lineTo(cx + 4 * u, cy); path.lineTo(cx - 7 * u, cy + 7 * u); path.close()
            }
            3 -> { // shuffle: two crossing arrows
                c.drawLine(cx - 9 * u, cy - 5 * u, cx + 9 * u, cy + 5 * u, pt); c.drawLine(cx - 9 * u, cy + 5 * u, cx + 9 * u, cy - 5 * u, pt)
                c.drawLine(cx + 5 * u, cy - 6 * u, cx + 9 * u, cy - 5 * u, pt); c.drawLine(cx + 5 * u, cy + 6 * u, cx + 9 * u, cy + 5 * u, pt)
            }
            4 -> { // repeat: loop with arrowhead
                c.drawRect(cx - 9 * u, cy - 5 * u, cx + 9 * u, cy + 5 * u, pt)
                c.drawLine(cx + 3 * u, cy - 8 * u, cx + 6 * u, cy - 5 * u, pt); c.drawLine(cx + 3 * u, cy - 2 * u, cx + 6 * u, cy - 5 * u, pt)
            }
        }
        c.drawPath(path, pt)
    }

    // ---------------------------------------------------------------- now-playing bar

    @SuppressLint("ViewConstructor")
    private inner class NowBar(c: Context) : LinearLayout(c) {
        private val cover = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(p.accent) }
        private val title = TextView(c).apply { textSize = 15f; typeface = Metro.regular(c); setTextColor(p.fg); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        private val artist = TextView(c).apply { textSize = 12f; typeface = Metro.regular(c); setTextColor(p.fgMedium); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        private val play = GlyphButton(c, p.fg) { cv, pt, cx, cy, u -> drawTransport(cv, pt, cx, cy, u, 1) }.apply { setOnClickListener { Player.toggle() } }
        private val progress = View(c).apply { setBackgroundColor(p.accent); pivotX = 0f }

        init {
            orientation = VERTICAL; setBackgroundColor(p.chromeMedium); isClickable = true
            addView(FrameLayout(c).apply { addView(progress, FrameLayout.LayoutParams(-1, -1)) }, LayoutParams(-1, c.dpi(2f)))
            val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(c.dpi(8f), 0, 0, 0) }
            row.addView(cover, LayoutParams(c.dpi(48f), c.dpi(48f)))
            row.addView(LinearLayout(c).apply {
                orientation = VERTICAL; setPadding(c.dpi(12f), 0, c.dpi(8f), 0)
                addView(title); addView(artist)
            }, LayoutParams(0, -2, 1f))
            row.addView(GlyphButton(c, p.fg) { cv, pt, cx, cy, u -> drawTransport(cv, pt, cx, cy, u, 0) }.apply { setOnClickListener { Player.prev(context) } }, LayoutParams(c.dpi(48f), -1))
            row.addView(play, LayoutParams(c.dpi(48f), -1))
            row.addView(GlyphButton(c, p.fg) { cv, pt, cx, cy, u -> drawTransport(cv, pt, cx, cy, u, 2) }.apply { setOnClickListener { Player.next(context) } }, LayoutParams(c.dpi(48f), -1))
            addView(row, LayoutParams(-1, 0, 1f))
        }

        fun update() {
            val t = Player.current
            visibility = if (t == null) GONE else VISIBLE
            t ?: return
            title.text = t.title; artist.text = t.artist.ifEmpty { getString(R.string.unknown_artist) }
            cover.setImageBitmap(albumArt(t.albumId, 128))
            play.invalidate(); invalidateProgress()
        }

        fun invalidateProgress() {
            val t = Player.current ?: return
            progress.scaleX = if (t.duration > 0) (Player.position.toFloat() / t.duration).coerceIn(0f, 1f) else 0f
        }
    }

    // ---------------------------------------------------------------- full now-playing view

    @SuppressLint("ViewConstructor")
    private inner class FullPlayer(c: Context) : LinearLayout(c) {
        private val cover = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(p.accent) }
        private val title = TextView(c).apply { textSize = 24f; typeface = Metro.semilight(c); setTextColor(p.fg); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        private val artist = TextView(c).apply { textSize = 15f; typeface = Metro.regular(c); setTextColor(p.fgMedium); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        private val seek = SeekBar(c)
        private val pos = TextView(c).apply { textSize = 12f; typeface = Metro.regular(c); setTextColor(p.fgMedium) }
        private val len = TextView(c).apply { textSize = 12f; typeface = Metro.regular(c); setTextColor(p.fgMedium); gravity = Gravity.END }
        private val buttons = ArrayList<GlyphButton>()

        init {
            orientation = VERTICAL; setBackgroundColor(p.bg); isClickable = true; visibility = GONE
            val m = c.dpi(Metro.PAGE_MARGIN)
            addView(pageTitle(c, p, getString(R.string.now_playing)))
            addView(FrameLayout(c).apply {
                setPadding(m, c.dpi(8f), m, c.dpi(8f))
                addView(cover, FrameLayout.LayoutParams(-1, -1))
            }, LayoutParams(-1, 0, 1f))
            addView(title, LayoutParams(-1, -2).apply { setMargins(m, c.dpi(8f), m, 0) })
            addView(artist, LayoutParams(-1, -2).apply { setMargins(m, 0, m, c.dpi(8f)) })
            addView(seek, LayoutParams(-1, c.dpi(32f)).apply { setMargins(m, 0, m, 0) })
            addView(LinearLayout(c).apply {
                addView(pos, LayoutParams(0, -2, 1f)); addView(len, LayoutParams(0, -2, 1f))
            }, LayoutParams(-1, -2).apply { setMargins(m, 0, m, c.dpi(8f)) })
            val row = LinearLayout(c).apply { gravity = Gravity.CENTER }
            for (k in listOf(3, 0, 1, 2, 4)) {
                val b = GlyphButton(c, p.fg) { cv, pt, cx, cy, u ->
                    pt.color = if ((k == 3 && Player.shuffle) || (k == 4 && Player.repeat)) p.accent else p.fg
                    drawTransport(cv, pt, cx, cy, if (k == 1) u * 1.4f else u, k)
                }
                b.setOnClickListener {
                    when (k) {
                        0 -> Player.prev(context); 1 -> Player.toggle(); 2 -> Player.next(context)
                        3 -> { Player.shuffle = !Player.shuffle; b.invalidate() }
                        4 -> { Player.repeat = !Player.repeat; b.invalidate() }
                    }
                }
                buttons += b
                row.addView(b, LayoutParams(c.dpi(if (k == 1) 72f else 56f), c.dpi(if (k == 1) 72f else 56f)))
            }
            addView(row, LayoutParams(-1, -2).apply { bottomMargin = c.dpi(16f) })
        }

        fun show() {
            visibility = VISIBLE; translationY = height.toFloat().coerceAtLeast(dp(400f))
            animate().translationY(0f).setDuration(Metro.DUR_NORMAL).withEndAction(null)
            update()
        }

        fun hide() { animate().translationY(height.toFloat()).setDuration(Metro.DUR_FAST).withEndAction { visibility = GONE } }

        fun update() {
            val t = Player.current ?: return
            title.text = t.title; artist.text = listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" · ")
            cover.setImageBitmap(albumArt(t.albumId, 640))
            len.text = clockText(t.duration)
            buttons.forEach { it.invalidate() }
            updateProgress()
        }

        fun updateProgress() {
            val t = Player.current ?: return
            if (!seek.dragging) seek.fraction = if (t.duration > 0) Player.position.toFloat() / t.duration else 0f
            pos.text = clockText(if (seek.dragging) (seek.fraction * t.duration).toLong() else Player.position)
        }
    }

    /** UWP slider look: 2 epx track, accent fill, 8×24 thumb. Drag to seek. */
    private inner class SeekBar(c: Context) : View(c) {
        var fraction = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        var dragging = false
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val tw = dp(8f); val th = dp(24f); val cy = height / 2f
            val x = tw / 2 + (width - tw) * fraction
            pt.color = p.chromeHigh; cv.drawRect(x, cy - dp(1f), width.toFloat(), cy + dp(1f), pt)
            pt.color = p.accent; cv.drawRect(0f, cy - dp(1f), x, cy + dp(1f), pt)
            pt.color = p.fg; cv.drawRoundRect(x - tw / 2, cy - th / 2, x + tw / 2, cy + th / 2, dp(4f), dp(4f), pt)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            fraction = (e.x - dp(4f)) / (width - dp(8f))
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { dragging = true; full.updateProgress() }
                MotionEvent.ACTION_UP -> { dragging = false; Player.current?.let { Player.seek((fraction * it.duration).toLong()) } }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            shell.handleBack() -> Unit
            full.visibility == View.VISIBLE -> full.hide()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}
