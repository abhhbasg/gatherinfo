package com.w10m.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.graphics.Shader
import android.graphics.drawable.Drawable
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
)

@SuppressLint("ViewConstructor")
class TileView(context: Context, val spec: TileSpec, private val look: TileLook) : View(context) {

    var app: AppEntry? = null
        set(v) { field = v; contentDescription = v?.label; invalidate() }
    var badge: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Top of this tile in screen coordinates, supplied by the grid (used by the picture-on-tiles mode). */
    var screenTop: Float = 0f
    var screenLeft: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()
    private var shader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null
    private val dimPaint = Paint().apply { color = 0x33000000 }

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = context.sp(Metro.TILE_LABEL_SP); typeface = Metro.regular(context)
    }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = context.sp(12f); typeface = Metro.regular(context)
        textAlign = Paint.Align.RIGHT
    }
    private val bigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Metro.light(context) }
    private val midPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Metro.regular(context) }
    private val whiteFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)

    /** Live tile face: 0 = front, 1 = back. */
    private var face = 0
    private var flipping = false

    init {
        cameraDistance = 12000 * resources.displayMetrics.density
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        drawBackground(canvas, w, h)
        when (spec.kind) {
            TileKind.APP -> drawApp(canvas, w, h)
            TileKind.CALENDAR -> drawCalendar(canvas, w, h)
        }
        if (badge > 0) {
            val small = spec.size == TileSize.SMALL
            val r = context.dp(if (small) 4f else 8f); val b = context.dp(if (small) 3f else Metro.TILE_LABEL_BOTTOM)
            canvas.drawText(if (badge > 99) "99+" else badge.toString(), w - r, h - b - badgePaint.descent(), badgePaint)
        }
    }

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
            look.background == Background.BEHIND_TILES && wp != null -> {
                bgPaint.shader = null
                bgPaint.color = look.palette.accent
                bgPaint.alpha = (255 * (100 - look.transparency) / 100f).toInt()
                canvas.drawRect(0f, 0f, w, h, bgPaint)
            }
            else -> {
                bgPaint.shader = null
                bgPaint.color = look.palette.accent
                canvas.drawRect(0f, 0f, w, h, bgPaint)
            }
        }
    }

    private fun drawApp(canvas: Canvas, w: Float, h: Float) {
        val a = app ?: return
        val small = spec.size == TileSize.SMALL
        val base = minOf(w, h)
        val iconSize = base * if (small) Metro.ICON_RATIO_SMALL else Metro.ICON_RATIO_MEDIUM
        val mono = if (look.monoIcons) a.mono else null
        val d: Drawable = mono ?: a.icon
        // Monochrome layers are 108dp canvases with the glyph in the inner 66% — compensate so glyphs match.
        val drawSize = if (mono != null) iconSize * 1.5f else iconSize
        val cx = w / 2f
        // Label sits at the bottom, so the icon centre is nudged up a little on medium/wide tiles (W10M).
        val cy = if (small) h / 2f else h / 2f - context.dp(2f)
        d.setBounds((cx - drawSize / 2).toInt(), (cy - drawSize / 2).toInt(), (cx + drawSize / 2).toInt(), (cy + drawSize / 2).toInt())
        d.colorFilter = if (mono != null) whiteFilter else null
        d.draw(canvas)
        if (!small) drawLabel(canvas, a.label, w, h)
    }

    private fun drawLabel(canvas: Canvas, text: String, w: Float, h: Float) {
        val left = context.dp(Metro.TILE_LABEL_LEFT)
        val maxW = w - left * 2 - if (badge > 0) context.dp(16f) else 0f
        val t = TextUtils.ellipsize(text, labelPaint, maxW, TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, left, h - context.dp(Metro.TILE_LABEL_BOTTOM) - labelPaint.descent(), labelPaint)
    }

    private fun drawCalendar(canvas: Canvas, w: Float, h: Float) {
        val now = Calendar.getInstance()
        val loc = Locale.getDefault()
        val day = now.get(Calendar.DAY_OF_MONTH).toString()
        val weekday = SimpleDateFormat("EEEE", loc).format(now.time)
        val pad = context.dp(Metro.TILE_LABEL_LEFT)
        when (spec.size) {
            TileSize.SMALL -> {
                bigPaint.textSize = h * 0.5f
                bigPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(day, w / 2, h / 2 - (bigPaint.ascent() + bigPaint.descent()) / 2, bigPaint)
            }
            else -> if (face == 0) {
                // Front: big date number + weekday, label "Calendar" bottom-left (W10M calendar tile).
                bigPaint.textAlign = Paint.Align.LEFT
                bigPaint.textSize = context.sp(if (spec.size == TileSize.WIDE) 46f else 40f)
                val top = context.dp(6f) - bigPaint.ascent()
                if (spec.size == TileSize.WIDE) {
                    canvas.drawText(day, pad, top, bigPaint)
                    midPaint.textSize = context.sp(15f)
                    val dx = pad + bigPaint.measureText(day) + context.dp(10f)
                    canvas.drawText(weekday, dx, top, midPaint)
                } else {
                    canvas.drawText(day, pad, top, bigPaint)
                    midPaint.textSize = context.sp(15f)
                    canvas.drawText(weekday, pad, top + context.sp(20f), midPaint)
                }
                drawLabel(canvas, context.getString(R.string.calendar), w, h)
            } else {
                // Back: time + date.
                val time = android.text.format.DateFormat.getTimeFormat(context).format(now.time)
                val date = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(loc, "MMMd"), loc).format(now.time)
                bigPaint.textAlign = Paint.Align.LEFT
                bigPaint.textSize = context.sp(if (spec.size == TileSize.WIDE) 40f else 30f)
                canvas.drawText(time, pad, context.dp(6f) - bigPaint.ascent(), bigPaint)
                midPaint.textSize = context.sp(15f)
                canvas.drawText(date, pad, context.dp(6f) - bigPaint.ascent() + context.sp(22f), midPaint)
                drawLabel(canvas, weekday, w, h)
            }
        }
    }

    // ---------------------------------------------------------------- live flip

    val isLive get() = spec.kind == TileKind.CALENDAR && spec.size != TileSize.SMALL

    /** One live-tile flip around the X axis: 0 → 90°, swap face, −90° → 0. */
    fun flip() {
        if (flipping || !isAttachedToWindow) return
        flipping = true
        pivotX = width / 2f; pivotY = height / 2f
        animate().rotationX(90f).setDuration(250).setInterpolator(android.view.animation.AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    face = 1 - face; invalidate(); rotationX = -90f
                    animate().rotationX(0f).setDuration(250).setInterpolator(DecelerateInterpolator())
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) { flipping = false }
                        })
                }
            })
    }

    fun nextFlipDelay() = 6000L + Random.nextLong(4000)

    // ---------------------------------------------------------------- tilt

    var tiltEnabled = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tiltEnabled && !flipping) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> tilt(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> untilt()
        }
        return super.onTouchEvent(e)
    }

    private fun tilt(x: Float, y: Float) {
        val nx = ((x / width) - 0.5f) * 2f   // -1..1
        val ny = ((y / height) - 0.5f) * 2f
        pivotX = width / 2f; pivotY = height / 2f
        // Pressing the right edge pushes it away: positive rotationY. Scale down slightly for centre presses.
        animate().rotationY(nx * Metro.TILT_MAX_DEG).rotationX(-ny * Metro.TILT_MAX_DEG)
            .scaleX(Metro.TILT_SCALE).scaleY(Metro.TILT_SCALE).setDuration(80).setListener(null)
    }

    fun untilt() {
        animate().rotationX(0f).rotationY(0f).scaleX(1f).scaleY(1f).setDuration(Metro.DUR_FAST).setListener(null)
    }
}
