package com.w10m.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * The Start tile grid. Lays tiles out on the W10M small-cell grid, handles edit mode
 * (long press → others shrink/dim, unpin + resize buttons, drag to reorder) and turnstile animations.
 */
@SuppressLint("ViewConstructor")
class StartGrid(context: Context, val look: TileLook) : ViewGroup(context) {

    interface Host {
        fun onTileClick(t: TileView)
        fun onTilesChanged(tiles: List<TileSpec>)
        fun onEditModeChanged(editing: Boolean)
    }

    var host: Host? = null
    var columns = Metro.COLUMNS_DEFAULT
        set(v) { field = v; requestLayout() }
    var topInset = 0
    var bottomInset = 0

    private val tiles = ArrayList<TileView>()
    private var cells: List<Packer.Cell> = emptyList()
    private var cell = 0f
    private val gutter = context.dp(Metro.GUTTER)
    private val side = context.dp(Metro.SIDE_MARGIN)

    // Edit mode
    var selected: TileView? = null
        private set
    val editing get() = selected != null
    private val unpinBtn = CircleButton(context, CircleButton.UNPIN)
    private val resizeBtn = CircleButton(context, CircleButton.RESIZE)

    init {
        clipChildren = false
        clipToPadding = false
        unpinBtn.setOnClickListener { selected?.let { unpin(it) } }
        resizeBtn.setOnClickListener { selected?.let { resize(it) } }
        setOnClickListener { if (editing) exitEdit() }
    }

    val specs: List<TileSpec> get() = tiles.map { it.spec }
    val tileViews: List<TileView> get() = tiles

    fun setTiles(list: List<TileView>) {
        removeAllViews(); tiles.clear()
        list.forEach { addTile(it) }
        addView(unpinBtn); addView(resizeBtn)
        unpinBtn.visibility = GONE; resizeBtn.visibility = GONE
        selected = null
        requestLayout()
    }

    private fun addTile(t: TileView) {
        tiles += t
        addView(t)
        t.setOnClickListener {
            if (editing) { if (selected !== t) select(t) } else host?.onTileClick(t)
        }
        t.setOnLongClickListener {
            if (!editing) { t.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); enterEdit(t) }
            true
        }
    }

    fun appendTile(t: TileView) {
        removeView(unpinBtn); removeView(resizeBtn)
        addTile(t); addView(unpinBtn); addView(resizeBtn)
        requestLayout(); host?.onTilesChanged(specs)
    }

    // ---------------------------------------------------------------- layout

    fun cellSize(width: Int, cols: Int = columns) = (width - 2 * side - (cols - 1) * gutter) / cols

    private fun span(n: Int) = n * cell + (n - 1) * gutter

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cell = cellSize(w)
        cells = Packer.pack(tiles.map { it.spec }, columns)
        var rows = 0
        tiles.forEachIndexed { i, t ->
            val sz = t.spec.size
            val tw = span(minOf(sz.w, columns)).toInt(); val th = span(sz.h).toInt()
            t.measure(MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(th, MeasureSpec.EXACTLY))
            rows = maxOf(rows, cells[i].row + sz.h)
        }
        val b = context.dpi(32f)
        unpinBtn.measure(MeasureSpec.makeMeasureSpec(b, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b, MeasureSpec.EXACTLY))
        resizeBtn.measure(MeasureSpec.makeMeasureSpec(b, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b, MeasureSpec.EXACTLY))
        val contentH = topInset + context.dp(Metro.TOP_MARGIN) + rows * cell + maxOf(0, rows - 1) * gutter
        // Leave room so the last row can scroll up comfortably (W10M has ~1/3 screen of slack at the bottom).
        val minH = MeasureSpec.getSize(heightMeasureSpec)
        val h = maxOf(contentH + context.dp(96f) + bottomInset, minH.toFloat())
        setMeasuredDimension(w, h.toInt())
    }

    fun cellLeft(c: Int) = side + c * (cell + gutter)
    fun cellTop(r: Int) = topInset + context.dp(Metro.TOP_MARGIN) + r * (cell + gutter)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        tiles.forEachIndexed { i, v ->
            if (v === dragging) return@forEachIndexed
            val x = cellLeft(cells[i].col).toInt(); val y = cellTop(cells[i].row).toInt()
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        }
        dragging?.let { it.layout(it.left, it.top, it.left + it.measuredWidth, it.top + it.measuredHeight) }
        placeButtons()
    }

    private fun placeButtons() {
        val s = selected ?: return
        val b = unpinBtn.measuredWidth
        // Buttons straddle the tile corners like on W10M.
        val ux = (s.left + s.translationX + s.width - b * 0.6f).toInt(); val uy = (s.top + s.translationY - b * 0.4f).toInt()
        unpinBtn.layout(ux, uy, ux + b, uy + b)
        val rx = (s.left + s.translationX + s.width - b * 0.6f).toInt(); val ry = (s.top + s.translationY + s.height - b * 0.6f).toInt()
        resizeBtn.layout(rx, ry, rx + b, ry + b)
        resizeBtn.rotation = when (s.spec.size) { TileSize.MEDIUM -> 0f; TileSize.SMALL -> 180f; TileSize.WIDE -> 90f }
    }

    /** Called by the scroll container; keeps picture-on-tiles in sync. */
    fun onScrolled(scrollY: Int) {
        if (look.background != Background.ON_TILES) return
        look.wallpaperOffsetY = scrollY * 0.18f.coerceAtMost(1f)
        val maxOff = ((look.wallpaper?.height ?: 0) - rootView.height).coerceAtLeast(0).toFloat()
        look.wallpaperOffsetY = look.wallpaperOffsetY.coerceIn(0f, maxOff)
        tiles.forEach { it.screenLeft = it.left.toFloat(); it.screenTop = (it.top - scrollY).toFloat(); it.invalidate() }
    }

    /** Layout positions changed: animate every tile from its old spot to its new one (167 ms). */
    private fun relayoutAnimated() {
        val before = tiles.associateWith { it.left to it.top }
        requestLayout()
        post {
            tiles.forEach { v ->
                if (v === dragging) return@forEach
                val (ol, ot) = before[v] ?: return@forEach
                val dx = (ol - v.left).toFloat(); val dy = (ot - v.top).toFloat()
                if (dx != 0f || dy != 0f) {
                    v.translationX = dx; v.translationY = dy
                    v.animate().translationX(0f).translationY(0f).setDuration(Metro.DUR_FAST)
                        .setInterpolator(DecelerateInterpolator()).withEndAction { placeButtons() }
                }
            }
            placeButtons()
        }
    }

    // ---------------------------------------------------------------- edit mode

    fun enterEdit(t: TileView) {
        host?.onEditModeChanged(true)
        select(t)
    }

    private fun select(t: TileView) {
        selected = t
        tiles.forEach { v ->
            v.tiltEnabled = false
            v.untilt()
            val sel = v === t
            v.animate().scaleX(if (sel) 1f else Metro.EDIT_OTHERS_SCALE).scaleY(if (sel) 1f else Metro.EDIT_OTHERS_SCALE)
                .alpha(if (sel) 1f else Metro.EDIT_OTHERS_ALPHA).rotationX(0f).rotationY(0f).setDuration(Metro.DUR_FAST)
        }
        t.bringToFront(); unpinBtn.bringToFront(); resizeBtn.bringToFront()
        unpinBtn.visibility = VISIBLE; resizeBtn.visibility = VISIBLE
        requestLayout()
    }

    fun exitEdit() {
        if (!editing) return
        selected = null
        unpinBtn.visibility = GONE; resizeBtn.visibility = GONE
        tiles.forEach { v ->
            v.tiltEnabled = true
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Metro.DUR_FAST)
        }
        host?.onEditModeChanged(false)
    }

    private fun unpin(t: TileView) {
        val idx = tiles.indexOf(t)
        tiles.remove(t); removeView(t)
        host?.onTilesChanged(specs)
        val next = tiles.getOrNull(idx) ?: tiles.lastOrNull()
        if (next != null) select(next) else exitEdit()
        relayoutAnimated()
    }

    private fun resize(t: TileView) {
        t.spec.size = t.spec.size.next()
        if (t.spec.size == TileSize.WIDE && columns < 4) t.spec.size = t.spec.size.next()
        host?.onTilesChanged(specs)
        t.invalidate()
        relayoutAnimated()
    }

    // ---------------------------------------------------------------- drag to reorder

    private var dragging: TileView? = null
    private var downX = 0f; private var downY = 0f
    private var grabDx = 0f; private var grabDy = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        val s = selected ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                if (hit(s, e.x, e.y)) parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging == null && hit(s, downX, downY) &&
                (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                startDrag(s); return true
            }
        }
        return false
    }

    private fun hit(v: View, x: Float, y: Float) = x >= v.left && x < v.right && y >= v.top && y < v.bottom

    private fun startDrag(s: TileView) {
        dragging = s
        grabDx = downX - s.left; grabDy = downY - s.top
        parent.requestDisallowInterceptTouchEvent(true)
        unpinBtn.visibility = INVISIBLE; resizeBtn.visibility = INVISIBLE
        s.animate().scaleX(1.05f).scaleY(1.05f).setDuration(Metro.DUR_FAST)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = dragging ?: return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val nl = (e.x - grabDx).toInt(); val nt = (e.y - grabDy).toInt()
                d.layout(nl, nt, nl + d.measuredWidth, nt + d.measuredHeight)
                reorderUnder(d, e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                val ol = d.left; val ot = d.top
                requestLayout()
                post {
                    d.translationX = (ol - d.left).toFloat(); d.translationY = (ot - d.top).toFloat()
                    d.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(Metro.DUR_FAST)
                        .withEndAction { placeButtons(); unpinBtn.visibility = VISIBLE; resizeBtn.visibility = VISIBLE }
                }
                host?.onTilesChanged(specs)
            }
        }
        return true
    }

    private fun reorderUnder(d: TileView, x: Float, y: Float) {
        val target = tiles.firstOrNull { it !== d && hit(it, x, y) } ?: return
        val from = tiles.indexOf(d); val to = tiles.indexOf(target)
        if (from == to) return
        tiles.removeAt(from); tiles.add(to, d)
        relayoutAnimated()
    }

    // ---------------------------------------------------------------- turnstile

    /**
     * Turnstile feather out/in. Tiles rotate about the screen's left edge; rows nearer the top go first,
     * [last] (the tapped tile) goes last. [visibleTop]/[visibleBottom] limit the work to on-screen tiles.
     */
    fun turnstile(out: Boolean, last: View?, visibleTop: Int, visibleBottom: Int, done: () -> Unit) {
        val vis = tiles.filter { it.bottom > visibleTop && it.top < visibleBottom }
        if (vis.isEmpty()) { done(); return }
        val ordered = vis.sortedWith(compareBy<TileView> { if (it === last) 1 else 0 }.thenBy { it.top }.thenBy { it.left })
        val step = 18L
        var maxEnd = 0L
        ordered.forEachIndexed { i, v ->
            v.pivotX = -v.left.toFloat(); v.pivotY = v.height / 2f
            val delay = if (v === last) (ordered.size - 1) * step + 40 else i * step
            val dur = Metro.DUR_NORMAL
            if (out) {
                v.rotationY = 0f; v.alpha = 1f
                v.animate().rotationY(-80f).alpha(0f).setStartDelay(delay).setDuration(dur)
                    .setInterpolator(AccelerateInterpolator(1.6f)).setListener(null)
            } else {
                v.rotationY = 80f; v.alpha = 0f
                v.animate().rotationY(0f).alpha(1f).setStartDelay(delay).setDuration(dur + 80)
                    .setInterpolator(DecelerateInterpolator(2f)).setListener(null)
            }
            maxEnd = maxOf(maxEnd, delay + dur)
        }
        postDelayed({
            tiles.forEach { it.animate().setStartDelay(0) }
            done()
        }, maxEnd)
    }

    fun resetTransforms() = tiles.forEach {
        it.animate().cancel(); it.rotationY = 0f; it.rotationX = 0f; it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f
        it.pivotX = it.width / 2f; it.pivotY = it.height / 2f
    }
}

/** White circular edit button with an MDL2-style glyph (unpin / resize arrow). */
@SuppressLint("ViewConstructor")
class CircleButton(context: Context, private val kind: Int) : View(context) {
    companion object { const val UNPIN = 0; const val RESIZE = 1 }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = context.dp(1.5f)
        strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
    }
    private val path = Path()

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val r = w / 2
        c.drawCircle(r, r, r, fill)
        val u = w / 32f
        path.reset()
        if (kind == UNPIN) {
            // Pin with a slash (MDL2 "Unpin").
            path.moveTo(12 * u, 9 * u); path.lineTo(20 * u, 9 * u)
            path.moveTo(13 * u, 9 * u); path.lineTo(13 * u, 15 * u); path.lineTo(11 * u, 18 * u); path.lineTo(21 * u, 18 * u)
            path.lineTo(19 * u, 15 * u); path.lineTo(19 * u, 9 * u)
            path.moveTo(16 * u, 18 * u); path.lineTo(16 * u, 24 * u)
            path.moveTo(9 * u, 9 * u); path.lineTo(23 * u, 23 * u)
        } else {
            // Diagonal arrow pointing down-right (rotated per target size).
            path.moveTo(10 * u, 10 * u); path.lineTo(22 * u, 22 * u)
            path.moveTo(22 * u, 14 * u); path.lineTo(22 * u, 22 * u); path.lineTo(14 * u, 22 * u)
        }
        c.drawPath(path, stroke)
    }
}

