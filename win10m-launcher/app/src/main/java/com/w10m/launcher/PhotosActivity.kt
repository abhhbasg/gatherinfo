package com.w10m.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Photos in the Windows 10 (Mobile) Photos layout, measured from Microsoft's own screenshot
 * (windows-dev-docs semantic-zoom-photos.png, 100 % scale):
 *  - 40 epx header bar in ChromeMedium: hamburger, "COLLECTION" in 12 epx SemiBold caps, commands on the right
 *  - month group headers: 15 epx Regular in the accent colour, in a 40 epx band
 *  - square thumbnails ~161 epx with 2 epx gaps (2 columns at phone width)
 *  - tapping a month header zooms out to the month list (38 epx rows, accent text)
 * plus Albums (folders) and a full-screen viewer with swipe, pinch / double-tap zoom and a command bar.
 */
class PhotosActivity : Activity() {

    class Photo(val id: Long, val date: Long, val bucket: String, val video: Boolean) {
        val uri: Uri get() = ContentUris.withAppendedId(
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    private sealed class Cell { class Header(val label: String, val key: String) : Cell(); class Item(val photo: Photo, val index: Int) : Cell() }

    private lateinit var p: Palette
    private lateinit var shell: Shell
    private lateinit var title: TextView
    private lateinit var grid: RecyclerView
    private lateinit var zoomOut: RecyclerView
    private lateinit var empty: LinearLayout
    private lateinit var pane: LinearLayout
    private lateinit var viewer: Viewer
    private var photos: List<Photo> = emptyList()
    private var current: List<Photo> = emptyList()     // what the grid shows (all, or one album)
    private var cells: List<Cell> = emptyList()
    private var mode = 0                                // 0 collection, 1 albums, 2 inside an album
    private var album: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<Long, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(k: Long, b: Bitmap) = b.byteCount }
    private val loading = HashSet<Long>()
    private val gap by lazy { dpi(2f) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); p = prefs.palette()
        shell = Shell(this, prefs, p)
        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Header bar.
        val header = LinearLayout(this).apply { setBackgroundColor(p.chromeMedium); gravity = Gravity.CENTER_VERTICAL }
        header.addView(GlyphButton(this, p.fg) { c, pt, cx, cy, u -> for (i in -1..1) c.drawLine(cx - 8 * u, cy + i * 5 * u, cx + 8 * u, cy + i * 5 * u, pt) }
            .apply { setOnClickListener { togglePane() } }, LinearLayout.LayoutParams(dpi(48f), -1))
        title = TextView(this).apply { textSize = 12f; typeface = Metro.semibold(context); setTextColor(p.fg); isAllCaps = true; setPadding(dpi(8f), 0, 0, 0) }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(GlyphButton(this, p.fg) { c, pt, cx, cy, u ->
            c.drawArc(cx - 8 * u, cy - 8 * u, cx + 8 * u, cy + 8 * u, -60f, 300f, false, pt)
            c.drawLine(cx + 4 * u, cy - 10 * u, cx + 4 * u, cy - 6.9f * u, pt); c.drawLine(cx + 4 * u, cy - 6.9f * u, cx + 7.5f * u, cy - 6.9f * u, pt)
        }.apply { setOnClickListener { load() } }, LinearLayout.LayoutParams(dpi(48f), -1))
        col.addView(header, LinearLayout.LayoutParams(-1, dpi(40f)))

        val body = FrameLayout(this)
        grid = RecyclerView(this).apply { itemAnimator = null; setPadding(dpi(Metro.PAGE_MARGIN), 0, dpi(Metro.PAGE_MARGIN) - gap, dpi(12f)); clipToPadding = false }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        zoomOut = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context); visibility = View.GONE; setBackgroundColor(p.bg) }
        body.addView(zoomOut, FrameLayout.LayoutParams(-1, -1))
        empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(Metro.PAGE_MARGIN), dpi(12f), dpi(Metro.PAGE_MARGIN), 0) }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Hamburger pane: Collection / Albums.
        pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(p.chromeMedium); visibility = View.GONE; isClickable = true
            setPadding(0, dpi(40f), 0, 0); elevation = dpi(8f).toFloat()
        }
        listOf(R.string.collection to "photos", R.string.photo_albums to "folder").forEachIndexed { i, (res, g) ->
            val gp = Paint(Paint.ANTI_ALIAS_FLAG)
            pane.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(object : View(context) { override fun onDraw(c: Canvas) { IconPack.glyph(g)?.draw(c, dp(14f), height / 2f - dp(10f), dp(20f), gp, p.fg) } },
                    LinearLayout.LayoutParams(dpi(48f), -1))
                addView(TextView(context).apply { text = getString(res); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fg) })
                setOnClickListener { togglePane(); if (i == 0) showCollection() else showAlbums() }
            }, LinearLayout.LayoutParams(-1, dpi(48f)))
        }
        root.addView(pane, FrameLayout.LayoutParams(dpi(260f), -1))

        viewer = Viewer(this)
        root.addView(viewer, FrameLayout.LayoutParams(-1, -1))
        shell.setContent(root)
        load()
    }

    override fun onDestroy() { super.onDestroy(); pool.shutdownNow() }

    private fun togglePane() {
        if (pane.visibility == View.VISIBLE) {
            pane.animate().translationX(-pane.width.toFloat()).setDuration(Metro.DUR_FAST).setInterpolator(Motion.ACCELERATE).withEndAction { pane.visibility = View.GONE }
        } else {
            pane.visibility = View.VISIBLE; pane.translationX = -dp(260f)
            pane.animate().translationX(0f).setDuration(Metro.DUR_NORMAL).setInterpolator(Motion.DECELERATE).withEndAction(null)
        }
    }

    // ---------------------------------------------------------------- data

    private fun perms(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun load() {
        empty.removeAllViews()
        if (checkSelfPermission(perms()[0]) != PackageManager.PERMISSION_GRANTED) {
            photos = emptyList(); showCollection()
            empty.addView(TextView(this).apply { text = getString(R.string.photos_permission); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium) })
            empty.addView(metroButton(this, p, getString(R.string.grant)) { requestPermissions(perms(), 1) },
                LinearLayout.LayoutParams(-2, dpi(32f)).apply { topMargin = dpi(12f) })
            return
        }
        val out = ArrayList<Photo>()
        fun query(uri: Uri, video: Boolean) = try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, "datetaken", MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val taken = c.getLong(2)
                    out += Photo(c.getLong(0), if (taken > 0) taken else c.getLong(1) * 1000, c.getString(3).orEmpty(), video)
                }
            }
        } catch (_: Exception) {}
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        photos = out.sortedByDescending { it.date }
        when (mode) { 1 -> showAlbums(); 2 -> showAlbum(album.orEmpty()); else -> showCollection() }
        if (photos.isEmpty()) empty.addView(TextView(this).apply { text = getString(R.string.no_photos); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium) })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = load()

    private fun monthLabel(t: Long): String {
        val loc = Locale.getDefault()
        return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(loc, "yMMMM"), loc).format(Date(t))
    }

    // ---------------------------------------------------------------- collection grid

    private fun columns(): Int {
        val w = resources.displayMetrics.widthPixels - 2 * dpi(Metro.PAGE_MARGIN) + gap
        return maxOf(2, Math.round(w / (dp(161f) + gap)))
    }

    private fun showCollection() {
        mode = 0; album = null; title.text = getString(R.string.collection)
        current = photos
        val out = ArrayList<Cell>()
        var last = ""
        photos.forEachIndexed { i, ph ->
            val m = monthLabel(ph.date)
            if (m != last) { out += Cell.Header(m, m); last = m }
            out += Cell.Item(ph, i)
        }
        cells = out
        bindGrid()
    }

    private fun showAlbum(name: String) {
        mode = 2; album = name; title.text = name
        current = photos.filter { it.bucket == name }
        cells = current.mapIndexed { i, ph -> Cell.Item(ph, i) }
        bindGrid()
    }

    private fun bindGrid() {
        val cols = columns()
        grid.layoutManager = GridLayoutManager(this, cols).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(pos: Int) = if (cells[pos] is Cell.Header) cols else 1 }
        }
        grid.adapter = GridAdapter(cols)
        zoomOut.visibility = View.GONE
        Motion.pageRefresh(grid)
    }

    private inner class GridAdapter(val cols: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = cells.size
        override fun getItemViewType(position: Int) = if (cells[position] is Cell.Header) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v: View = if (viewType == 0) TextView(this@PhotosActivity).apply {
                textSize = 15f; typeface = Metro.regular(context); setTextColor(p.accent); gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, dpi(40f))
            } else Thumb(this@PhotosActivity).apply {
                val size = (parent.width - parent.paddingLeft - parent.paddingRight) / cols
                layoutParams = RecyclerView.LayoutParams(-1, size)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val c = cells[position]) {
                is Cell.Header -> (h.itemView as TextView).apply { text = c.label; setOnClickListener { showMonths() } }
                is Cell.Item -> (h.itemView as Thumb).apply { photo = c.photo; setOnClickListener { viewer.open(current, c.index, this) }; invalidate() }
            }
        }
    }

    /** Square thumbnail; the right / bottom 2 epx stay empty to form the grid gap. */
    private inner class Thumb(c: Context) : View(c) {
        var photo: Photo? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val ph = Paint().apply { color = p.chromeMedium }
        private val play = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        private val src = android.graphics.Rect(); private val dst = RectF()
        init { isClickable = true }
        override fun onDraw(cv: Canvas) {
            val w = width - gap.toFloat(); val h = height - gap.toFloat()
            val x = photo ?: return
            val b = thumb(x) { if (photo === x) invalidate() }
            if (b == null) { cv.drawRect(0f, 0f, w, h, ph); return }
            val s = minOf(b.width, b.height)
            src.set((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2)
            dst.set(0f, 0f, w, h); cv.drawBitmap(b, src, dst, paint)
            if (x.video) {
                val path = android.graphics.Path(); val cx = w / 2; val cy = h / 2; val u = dp(1f)
                path.moveTo(cx - 7 * u, cy - 10 * u); path.lineTo(cx + 11 * u, cy); path.lineTo(cx - 7 * u, cy + 10 * u); path.close()
                cv.drawPath(path, play)
            }
        }
    }

    /** Cached square-ish thumbnail; loads in the background and calls [ready] when it arrives. */
    fun thumb(x: Photo, ready: () -> Unit): Bitmap? {
        thumbs.get(x.id)?.let { return it }
        if (loading.add(x.id)) pool.execute {
            val b = try {
                if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(x.uri, Size(320, 320), null)
                else if (x.video) @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(contentResolver, x.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                else contentResolver.openInputStream(x.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 }) }
            } catch (_: Exception) { null }
            ui.post { loading.remove(x.id); if (b != null) { thumbs.put(x.id, b); ready() } }
        }
        return null
    }

    // ---------------------------------------------------------------- semantic zoom (month list)

    private fun showMonths() {
        val months = cells.filterIsInstance<Cell.Header>()
        zoomOut.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = months.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(TextView(this@PhotosActivity).apply {
                textSize = 15f; typeface = Metro.regular(context); setTextColor(p.accent); gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(Metro.PAGE_MARGIN), 0, dpi(Metro.PAGE_MARGIN), 0)
                layoutParams = RecyclerView.LayoutParams(-1, dpi(38f))
            }) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                (h.itemView as TextView).apply {
                    text = months[position].label
                    setOnClickListener {
                        val i = cells.indexOf(months[position])
                        (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(i, 0)
                        hideMonths(true)
                    }
                }
            }
        }
        // Semantic zoom out: the grid shrinks away, the month list settles from slightly enlarged.
        grid.animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(Metro.DUR_FAST).setInterpolator(Motion.ACCELERATE)
        zoomOut.visibility = View.VISIBLE
        Motion.drillBackIn(zoomOut)
    }

    private fun hideMonths(zoomIn: Boolean) {
        zoomOut.animate().scaleX(1.15f).scaleY(1.15f).alpha(0f).setDuration(Metro.DUR_FAST).setInterpolator(Motion.ACCELERATE)
            .withEndAction { zoomOut.visibility = View.GONE; zoomOut.scaleX = 1f; zoomOut.scaleY = 1f; zoomOut.alpha = 1f }
        grid.scaleX = if (zoomIn) 0.85f else 1f; grid.scaleY = grid.scaleX
        grid.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).setInterpolator(Motion.DECELERATE)
    }

    // ---------------------------------------------------------------- albums

    private fun showAlbums() {
        mode = 1; title.text = getString(R.string.photo_albums)
        val groups = photos.groupBy { it.bucket.ifEmpty { "—" } }.entries.sortedByDescending { it.value.first().date }
        val cols = 2
        grid.layoutManager = GridLayoutManager(this, cols)
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = groups.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val size = (parent.width - parent.paddingLeft - parent.paddingRight) / cols
                return object : RecyclerView.ViewHolder(AlbumCell(this@PhotosActivity).apply { layoutParams = RecyclerView.LayoutParams(-1, size + dpi(48f)) }) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val g = groups[position]
                (h.itemView as AlbumCell).apply { name = g.key; count = g.value.size; cover = g.value.first(); setOnClickListener { showAlbum(g.key) }; invalidate() }
            }
        }
        zoomOut.visibility = View.GONE
        Motion.pageRefresh(grid)
    }

    /** Album: square cover, then name (15) and item count (12, 60 %). */
    private inner class AlbumCell(c: Context) : View(c) {
        var name = ""; var count = 0; var cover: Photo? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val ph = Paint().apply { color = p.chromeMedium }
        private val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(15f); typeface = Metro.regular(c) }
        private val cp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(12f); typeface = Metro.regular(c) }
        private val src = android.graphics.Rect(); private val dst = RectF()
        init { isClickable = true }
        override fun onDraw(cv: Canvas) {
            val w = width - gap.toFloat(); val s = w
            val x = cover
            val b = x?.let { thumb(it) { invalidate() } }
            if (b == null) cv.drawRect(0f, 0f, w, s, ph) else {
                val m = minOf(b.width, b.height)
                src.set((b.width - m) / 2, (b.height - m) / 2, (b.width + m) / 2, (b.height + m) / 2); dst.set(0f, 0f, w, s)
                cv.drawBitmap(b, src, dst, paint)
            }
            np.color = p.fg; cp.color = p.fgMedium
            val t = TextUtils.ellipsize(name, np, w, TextUtils.TruncateAt.END)
            cv.drawText(t, 0, t.length, 0f, s + dp(6f) - np.ascent(), np)
            cv.drawText(count.toString(), 0f, s + dp(26f) - cp.ascent(), cp)
        }
    }

    // ---------------------------------------------------------------- viewer

    /** Full-screen black viewer: horizontal paging, pinch / double-tap zoom, tap toggles the command bar. */
    @SuppressLint("ViewConstructor")
    private inner class Viewer(c: Context) : FrameLayout(c) {
        private val pager = RecyclerView(c)
        private val bar = LinearLayout(c)
        private var items: List<Photo> = emptyList()
        private var barShown = true

        init {
            setBackgroundColor(android.graphics.Color.BLACK); visibility = GONE; isClickable = true
            pager.layoutManager = LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false)
            PagerSnapHelper().attachToRecyclerView(pager)
            pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = items.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(ZoomImage(c).apply { layoutParams = RecyclerView.LayoutParams(-1, -1) }) {}
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) { (h.itemView as ZoomImage).show(items[position]) }
            }
            addView(pager, LayoutParams(-1, -1))
            bar.setBackgroundColor(0xCC1F1F1F.toInt()); bar.gravity = Gravity.CENTER_HORIZONTAL
            fun cmd(glyph: (Canvas, Paint, Float, Float, Float) -> Unit, tap: () -> Unit) =
                bar.addView(GlyphButton(c, android.graphics.Color.WHITE, glyph).apply { setOnClickListener { tap() } }, LinearLayout.LayoutParams(dpi(64f), -1))
            // Share: box with an arrow leaving it.
            cmd({ cv, pt, cx, cy, u ->
                cv.drawLine(cx - 8 * u, cy - 2 * u, cx - 8 * u, cy + 8 * u, pt); cv.drawLine(cx - 8 * u, cy + 8 * u, cx + 8 * u, cy + 8 * u, pt)
                cv.drawLine(cx + 8 * u, cy + 8 * u, cx + 8 * u, cy - 2 * u, pt)
                cv.drawLine(cx, cy + 3 * u, cx, cy - 9 * u, pt); cv.drawLine(cx - 4 * u, cy - 5 * u, cx, cy - 9 * u, pt); cv.drawLine(cx, cy - 9 * u, cx + 4 * u, cy - 5 * u, pt)
            }) { cur()?.let { x -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (x.video) "video/*" else "image/*")
                .putExtra(Intent.EXTRA_STREAM, x.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)) } }
            // Edit / open with.
            cmd({ cv, pt, cx, cy, u ->
                cv.drawLine(cx - 7 * u, cy + 7 * u, cx + 6 * u, cy - 6 * u, pt); cv.drawLine(cx + 3 * u, cy - 9 * u, cx + 9 * u, cy - 3 * u, pt)
                cv.drawLine(cx - 7 * u, cy + 7 * u, cx - 9 * u, cy + 9 * u, pt)
            }) { cur()?.let { x -> try { startActivity(Intent(if (x.video) Intent.ACTION_VIEW else Intent.ACTION_EDIT).setDataAndType(x.uri, if (x.video) "video/*" else "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) } catch (_: Exception) {} } }
            // Delete: bin.
            cmd({ cv, pt, cx, cy, u ->
                cv.drawRect(cx - 6 * u, cy - 5 * u, cx + 6 * u, cy + 9 * u, pt); cv.drawLine(cx - 9 * u, cy - 5 * u, cx + 9 * u, cy - 5 * u, pt)
                cv.drawLine(cx - 3 * u, cy - 8 * u, cx + 3 * u, cy - 8 * u, pt)
            }) { cur()?.let { delete(it) } }
            addView(bar, LayoutParams(-1, dpi(48f), Gravity.BOTTOM))
        }

        fun cur(): Photo? = items.getOrNull((pager.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition().coerceAtLeast(0))

        fun toggleBar() {
            barShown = !barShown
            bar.animate().translationY(if (barShown) 0f else bar.height.toFloat()).setDuration(Metro.DUR_FAST)
                .setInterpolator(if (barShown) Motion.DECELERATE else Motion.ACCELERATE)
        }

        fun open(list: List<Photo>, index: Int, from: View) {
            items = list
            pager.adapter?.notifyDataSetChanged()
            pager.scrollToPosition(index)
            visibility = VISIBLE
            barShown = true; bar.translationY = 0f
            // Connected-style zoom from the tapped thumbnail.
            val loc = IntArray(2); from.getLocationInWindow(loc); val me = IntArray(2); getLocationInWindow(me)
            pivotX = loc[0] - me[0] + from.width / 2f; pivotY = loc[1] - me[1] + from.height / 2f
            scaleX = from.width.toFloat() / width.coerceAtLeast(1); scaleY = scaleX; alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).setInterpolator(Motion.DECELERATE).withEndAction(null)
        }

        fun close() {
            animate().scaleX(0.87f).scaleY(0.87f).alpha(0f).setDuration(Metro.DUR_FAST).setInterpolator(Motion.ACCELERATE)
                .withEndAction { visibility = GONE; scaleX = 1f; scaleY = 1f; alpha = 1f }
        }

        fun removeCurrent(x: Photo) {
            val i = items.indexOf(x); if (i < 0) return
            items = items - x; pager.adapter?.notifyItemRemoved(i)
            if (items.isEmpty()) close()
        }
    }

    private fun delete(x: Photo) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(x.uri))
                pendingDelete = x
                @Suppress("DEPRECATION") startIntentSenderForResult(pi.intentSender, REQ_DELETE, null, 0, 0, 0)
            } catch (_: Exception) {}
        } else try { contentResolver.delete(x.uri, null, null); afterDelete(x) } catch (_: Exception) {}
    }

    private var pendingDelete: Photo? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE && resultCode == RESULT_OK) pendingDelete?.let { afterDelete(it) }
        pendingDelete = null
    }

    private fun afterDelete(x: Photo) { viewer.removeCurrent(x); photos = photos - x; thumbs.remove(x.id); load() }

    /** Pinch / double-tap zoomable image; single tap toggles the command bar. Hands horizontal drags to the pager unless zoomed. */
    private inner class ZoomImage(c: Context) : View(c) {
        private var bmp: Bitmap? = null
        private var photo: Photo? = null
        private val m = Matrix()
        private var scale = 1f
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = c.sp(15f); typeface = Metro.regular(c); textAlign = Paint.Align.CENTER }
        private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val f = (scale * d.scaleFactor).coerceIn(1f, 6f) / scale
                scale *= f; m.postScale(f, f, d.focusX, d.focusY); fix(); invalidate(); return true
            }
        })
        private val gest = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val x = photo
                if (x != null && x.video) try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(x.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Exception) {}
                else viewer.toggleBar()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (scale > 1.01f) 1f else 2.5f
                val f = target / scale; scale = target; m.postScale(f, f, e.x, e.y); fix(); invalidate(); return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale <= 1.01f) return false
                m.postTranslate(-dx, -dy); fix(); invalidate(); return true
            }
        })

        fun show(x: Photo) {
            photo = x; bmp = thumb(x) { if (photo === x) { bmp = thumbs.get(x.id); reset() } }
            reset()
            // Then the full-resolution image, downsampled to the screen.
            pool.execute {
                val full = try { decodeFull(x) } catch (_: Throwable) { null }
                ui.post { if (photo === x && full != null) { bmp = full; reset() } }
            }
        }

        private fun decodeFull(x: Photo): Bitmap? {
            if (x.video) return null
            val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
            return if (Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, x.uri)) { d, info, _ ->
                val s = info.size; val k = maxOf(s.width, s.height).toFloat() / maxSide
                if (k > 1) d.setTargetSize((s.width / k).toInt(), (s.height / k).toInt())
            } else contentResolver.openInputStream(x.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
        }

        private fun reset() {
            val b = bmp ?: run { invalidate(); return }
            if (width == 0) { post { reset() }; return }
            val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            m.reset(); m.postScale(s, s); m.postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2)
            scale = 1f; invalidate()
        }

        /** Keep the image covering the view when zoomed, centred when it is smaller. */
        private fun fix() {
            val b = bmp ?: return
            val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()); m.mapRect(r)
            var dx = 0f; var dy = 0f
            if (r.width() <= width) dx = width / 2f - r.centerX() else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
            if (r.height() <= height) dy = height / 2f - r.centerY() else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
            m.postTranslate(dx, dy)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = reset()

        override fun onDraw(c: Canvas) {
            val b = bmp ?: return
            c.drawBitmap(b, m, paint)
            if (photo?.video == true) c.drawText("▶", width / 2f, height / 2f, tp)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(scale > 1.01f || e.pointerCount > 1)
            scaleDet.onTouchEvent(e); gest.onTouchEvent(e)
            return true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            shell.handleBack() -> Unit
            viewer.visibility == View.VISIBLE -> viewer.close()
            pane.visibility == View.VISIBLE -> togglePane()
            zoomOut.visibility == View.VISIBLE -> hideMonths(false)
            mode == 2 -> showAlbums()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    companion object { private const val REQ_DELETE = 21 }
}
