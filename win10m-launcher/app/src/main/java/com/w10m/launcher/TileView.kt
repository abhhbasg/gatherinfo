package com.w10m.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/** Shared look settings for all tiles on the Start screen. */
class TileLook(
    var palette: Palette,
    var background: Background,
    var transparency: Int,
    var monoIcons: Boolean,
    /** Bitmap scaled to cover the screen (for ON_TILES), plus current parallax offset. */
    var wallpaper: Bitmap? = null,
    var wallpaperOffsetY: Float = 0f,
    /** Draw icon-pack glyphs instead of the apps' own icons. */
    var iconPack: Boolean = true,
    /** Brand apps get their brand colour as tile colour (as third-party tiles did on W10M). */
    var brandColors: Boolean = true,
)

/** Content sources for live tiles that are not tied to one app's notifications. */
object Live {
    /** Recent photos for the Photos tile slideshow (empty until the photos permission is granted). */
    @Volatile var photos: List<Bitmap> = emptyList()
}

@SuppressLint("ViewConstructor")
class TileView(context: Context, val spec: TileSpec, private val look: TileLook) : View(context) {

    var app: AppEntry? = null
        set(v) { field = v; contentDescription = v?.label ?: spec.label; invalidate() }
    var badge: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Latest notification of this tile's app: becomes a live face. */
    var note: Note? = null
        set(v) { if (field !== v) { field = v; if (v == null && face == FACE_NOTE) face = FACE_MAIN; invalidate() } }
    /** Top-left of this tile in screen coordinates, supplied by the grid (picture-on-tiles mode). */
    var screenTop: Float = 0f
    var screenLeft: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()
    private var shader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null
    private val dimPaint = Paint().apply { color = 0x33000000 }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = context.sp(Metro.TILE_LABEL_SP); typeface = Metro.regular(context)
    }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = context.sp(12f); typeface = Metro.regular(context)
        textAlign = Paint.Align.RIGHT
    }
    private val bigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Metro.light(context) }
    private val midPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Metro.regular(context) }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); typeface = Metro.regular(context); textSize = context.sp(12f)
    }
    private val whiteFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)

    // Live faces: the tile cycles through whatever faces currently have content.
    private var face = FACE_MAIN
    private var prevFace = FACE_MAIN
    private var slide = 1f
    private var anim: ValueAnimator? = null

    init {
        cameraDistance = 12000 * resources.displayMetrics.density
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
    }

    private val glyph: Glyph?
        get() = when (spec.kind) {
            TileKind.WEB -> IconPack.glyph("browser")
            else -> if (look.iconPack) app?.glyph else null
        }

    private val isPhotosTile get() = app?.glyph?.name.let { it == "photos" || it == "googlephotos" }

    // ---------------------------------------------------------------- faces

    private fun faces(): List<Int> {
        if (spec.size == TileSize.SMALL) return listOf(FACE_MAIN)
        val f = ArrayList<Int>(4)
        f += FACE_MAIN
        when (spec.kind) {
            TileKind.CALENDAR -> f += FACE_BACK
            TileKind.APP -> {
                if (note != null) f += FACE_NOTE
                if (isPhotosTile) for (i in Live.photos.indices) f += FACE_PHOTO + i
            }
            TileKind.WEB -> Unit
        }
        return f
    }

    /** Advances to the next live face with the W10M slide-up transition. Returns false if the tile is static. */
    fun advance(): Boolean {
        val f = faces()
        if (f.size < 2 || !isAttachedToWindow || width == 0) {
            if (face != FACE_MAIN && face !in f) { face = FACE_MAIN; invalidate() }
            return false
        }
        val next = f[(f.indexOf(face).coerceAtLeast(0) + 1) % f.size]
        if (next == face) return false
        prevFace = face; face = next
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener { slide = it.animatedValue as Float; invalidate() }
            start()
        }
        return true
    }

    fun nextLiveDelay() = 5000L + Random.nextLong(5000)

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        drawBackground(canvas, w, h)
        if (slide < 1f) {
            canvas.save()
            canvas.clipRect(0f, 0f, w, h)
            canvas.translate(0f, -h * slide)
            drawFace(canvas, prevFace, w, h)
            canvas.translate(0f, h)
            drawFace(canvas, face, w, h)
            canvas.restore()
        } else drawFace(canvas, face, w, h)
        if (badge > 0) {
            val small = spec.size == TileSize.SMALL
            val r = context.dp(if (small) 4f else 8f); val b = context.dp(if (small) 3f else Metro.TILE_LABEL_BOTTOM)
            canvas.drawText(if (badge > 99) "99+" else badge.toString(), w - r, h - b - badgePaint.descent(), badgePaint)
        }
    }

    private fun tileColor() = IconPack.tileColor(glyph, look.palette.accent, look.brandColors)

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        val wp = look.wallpaper
        when {
            look.background == Background.ON_TILES && wp != null -> {
                if (shaderBitmap !== wp) {
                    shader = BitmapShader(wp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shaderBitmap = wp
                }
                shaderMatrix.setTranslate(-screenLeft, -screenTop + look.wallpaperOffsetY)
                shader!!.setLocalMatrix(shaderMatrix)
                bgPaint.shader = shader
                bgPaint.alpha = 255
                canvas.drawRect(0f, 0f, w, h, bgPaint)
                canvas.drawRect(0f, 0f, w, h, dimPaint)
            }
            else -> {
                bgPaint.shader = null
                bgPaint.color = tileColor()
                if (look.background == Background.BEHIND_TILES) bgPaint.alpha = (255 * (100 - look.transparency) / 100f).toInt()
                canvas.drawRect(0f, 0f, w, h, bgPaint)
            }
        }
    }

    private fun drawFace(canvas: Canvas, f: Int, w: Float, h: Float) {
        when {
            f >= FACE_PHOTO -> drawPhoto(canvas, f - FACE_PHOTO, w, h)
            f == FACE_NOTE -> drawNote(canvas, w, h)
            spec.kind == TileKind.CALENDAR -> drawCalendar(canvas, f, w, h)
            else -> drawIcon(canvas, w, h)
        }
    }

    private fun title() = app?.label ?: spec.label ?: ""

    private fun drawIcon(canvas: Canvas, w: Float, h: Float) {
        val small = spec.size == TileSize.SMALL
        val base = minOf(w, h)
        val iconSize = base * if (small) Metro.ICON_RATIO_SMALL else Metro.ICON_RATIO_MEDIUM
        val cx = w / 2f
        // The label sits at the bottom, so the icon centre is nudged up a little on medium/wide tiles.
        val cy = if (small) h / 2f else h / 2f - context.dp(2f)
        val g = glyph
        if (g != null) {
            // Glyphs are drawn a little smaller than full-colour icons: W10M glyphs fill ~36% of a medium tile.
            val s = iconSize * 0.84f
            g.draw(canvas, cx - s / 2, cy - s / 2, s, glyphPaint)
        } else app?.let { a ->
            val mono = if (look.monoIcons) a.mono else null
            val d: Drawable = mono ?: a.icon
            // Monochrome layers are 108dp canvases with the glyph in the inner 66% — compensate.
            val ds = if (mono != null) iconSize * 1.5f else iconSize
            d.setBounds((cx - ds / 2).toInt(), (cy - ds / 2).toInt(), (cx + ds / 2).toInt(), (cy + ds / 2).toInt())
            d.colorFilter = if (mono != null) whiteFilter else null
            d.draw(canvas)
        }
        if (!small) drawLabel(canvas, title(), w, h)
    }

    private fun drawLabel(canvas: Canvas, text: String, w: Float, h: Float) {
        val left = context.dp(Metro.TILE_LABEL_LEFT)
        val maxW = w - left * 2 - if (badge > 0) context.dp(16f) else 0f
        val t = TextUtils.ellipsize(text, labelPaint, maxW, TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, left, h - context.dp(Metro.TILE_LABEL_BOTTOM) - labelPaint.descent(), labelPaint)
    }

    /** Notification face: title (15) + wrapped body (12), app name bottom-left — the W10M adaptive tile layout. */
    private fun drawNote(canvas: Canvas, w: Float, h: Float) {
        val n = note ?: return drawIcon(canvas, w, h)
        val pad = context.dp(Metro.TILE_LABEL_LEFT)
        val tw = (w - 2 * pad).toInt()
        midPaint.textSize = context.sp(15f)
        var y = context.dp(6f)
        if (n.title.isNotEmpty()) {
            val t = TextUtils.ellipsize(n.title, midPaint, tw.toFloat(), TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, pad, y - midPaint.ascent(), midPaint)
            y += midPaint.descent() - midPaint.ascent()
        }
        val lineH = bodyPaint.descent() - bodyPaint.ascent()
        val room = h - y - context.dp(Metro.TILE_LABEL_BOTTOM) - lineH - context.dp(4f)
        val lines = (room / lineH).toInt()
        if (n.text.isNotEmpty() && lines > 0 && tw > 0) {
            val sl = StaticLayout.Builder.obtain(n.text, 0, n.text.length, bodyPaint, tw)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
            canvas.save(); canvas.translate(pad, y); sl.draw(canvas); canvas.restore()
        }
        drawLabel(canvas, title(), w, h)
    }

    /** Photos live tile: centre-cropped photo filling the tile, app name on top. */
    private fun drawPhoto(canvas: Canvas, i: Int, w: Float, h: Float) {
        val b = Live.photos.getOrNull(i) ?: return drawIcon(canvas, w, h)
        val sc = maxOf(w / b.width, h / b.height)
        val sw = (w / sc).toInt(); val sh = (h / sc).toInt()
        src.set((b.width - sw) / 2, (b.height - sh) / 2, (b.width + sw) / 2, (b.height + sh) / 2)
        dst.set(0f, 0f, w, h)
        canvas.drawBitmap(b, src, dst, photoPaint)
        drawLabel(canvas, title(), w, h)
    }

    private fun drawCalendar(canvas: Canvas, f: Int, w: Float, h: Float) {
        val now = Calendar.getInstance()
        val loc = Locale.getDefault()
        val day = now.get(Calendar.DAY_OF_MONTH).toString()
        val weekday = SimpleDateFormat("EEEE", loc).format(now.time)
        val pad = context.dp(Metro.TILE_LABEL_LEFT)
        if (spec.size == TileSize.SMALL) {
            bigPaint.textSize = h * 0.5f
            bigPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(day, w / 2, h / 2 - (bigPaint.ascent() + bigPaint.descent()) / 2, bigPaint)
            return
        }
        bigPaint.textAlign = Paint.Align.LEFT
        midPaint.textSize = context.sp(15f)
        if (f == FACE_MAIN) {
            // Front: big date number + weekday, "Calendar" bottom-left.
            bigPaint.textSize = context.sp(if (spec.size == TileSize.WIDE) 46f else 40f)
            val top = context.dp(6f) - bigPaint.ascent()
            canvas.drawText(day, pad, top, bigPaint)
            if (spec.size == TileSize.WIDE) canvas.drawText(weekday, pad + bigPaint.measureText(day) + context.dp(10f), top, midPaint)
            else canvas.drawText(weekday, pad, top + context.sp(20f), midPaint)
            drawLabel(canvas, context.getString(R.string.calendar), w, h)
        } else {
            // Back: time + date.
            val time = android.text.format.DateFormat.getTimeFormat(context).format(now.time)
            val date = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(loc, "MMMd"), loc).format(now.time)
            bigPaint.textSize = context.sp(if (spec.size == TileSize.WIDE) 40f else 30f)
            canvas.drawText(time, pad, context.dp(6f) - bigPaint.ascent(), bigPaint)
            canvas.drawText(date, pad, context.dp(6f) - bigPaint.ascent() + context.sp(22f), midPaint)
            drawLabel(canvas, weekday, w, h)
        }
    }

    // ---------------------------------------------------------------- tilt

    var tiltEnabled = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tiltEnabled) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> tilt(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> untilt()
        }
        return super.onTouchEvent(e)
    }

    private fun tilt(x: Float, y: Float) {
        val nx = ((x / width) - 0.5f) * 2f   // -1..1
        val ny = ((y / height) - 0.5f) * 2f
        pivotX = width / 2f; pivotY = height / 2f
        animate().rotationY(nx * Metro.TILT_MAX_DEG).rotationX(-ny * Metro.TILT_MAX_DEG)
            .scaleX(Metro.TILT_SCALE).scaleY(Metro.TILT_SCALE).setDuration(80).setListener(null)
    }

    fun untilt() {
        animate().rotationX(0f).rotationY(0f).scaleX(1f).scaleY(1f).setDuration(Metro.DUR_FAST).setListener(null)
    }

    companion object {
        const val FACE_MAIN = 0
        const val FACE_BACK = 1
        const val FACE_NOTE = 2
        const val FACE_PHOTO = 100
    }
}
