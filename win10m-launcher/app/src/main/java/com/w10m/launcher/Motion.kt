package com.w10m.launcher

import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * Page transitions measured frame by frame from Microsoft's own reference animations
 * (windows-dev-docs: motion/images/drill.gif and page-refresh.gif, 20 ms per frame) and the
 * documented easing curves (timing-and-easing.md):
 *
 *  - Decelerate "fast out, slow in"  cubic-bezier(0, 0, 0, 1)  — anything entering.
 *  - Accelerate "slow out, fast in"  cubic-bezier(1, 0, 1, 1)  — anything leaving.
 *  - Drill in:   new page 0.76 → 1.0 scale over ~300 ms; outgoing page fades in 83 ms.
 *  - Drill back: page shrinks 1.0 → 0.87 and fades over ~167 ms; previous page settles 1.2 → 1.0.
 *  - Page refresh: new content rises from +23 % of its height to rest over ~300 ms, fading in 83 ms.
 */
object Motion {
    val DECELERATE = PathInterpolator(0f, 0f, 0f, 1f)
    val ACCELERATE = PathInterpolator(1f, 0f, 1f, 1f)
    const val FASTER = 83L

    /** Scale/translate on the decelerate curve over [dur] while opacity comes up in 83 ms, as in the reference. */
    private fun enter(v: View, dur: Long, vararg props: android.animation.PropertyValuesHolder) {
        v.animate().cancel()
        v.alpha = 0f
        val move = android.animation.ObjectAnimator.ofPropertyValuesHolder(v, *props).apply { duration = dur; interpolator = DECELERATE }
        val fade = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).apply { duration = FASTER }
        android.animation.AnimatorSet().apply { playTogether(move, fade); start() }
    }

    /** Drill into a detail page. */
    fun drillIn(v: View) {
        v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
        enter(v, 300, android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.76f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.76f, 1f))
    }

    /** Leave a detail page backwards; [end] runs once it has gone (e.g. to hide it). */
    fun drillOut(v: View, end: () -> Unit) {
        v.animate().cancel()
        v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
        v.animate().scaleX(0.87f).scaleY(0.87f).alpha(0f).setDuration(Metro.DUR_FAST).setInterpolator(ACCELERATE)
            .withEndAction { v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f; end() }
    }

    /** The page being returned to settles from slightly enlarged. */
    fun drillBackIn(v: View) {
        v.animate().cancel()
        v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
        v.scaleX = 1.2f; v.scaleY = 1.2f; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280).setInterpolator(DECELERATE)
    }

    /** Content swapped in place (pivot tab, refreshed list): slide up and fade in. */
    fun pageRefresh(v: View) {
        val h = if (v.height > 0) v.height else v.resources.displayMetrics.heightPixels / 2
        enter(v, 300, android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, h * 0.23f, 0f))
    }

    /** List items slide in from the right one after another (W10M app list, pivot items). */
    fun staggerIn(group: ViewGroup, fromX: Float, step: Long = 18L) {
        var k = 0
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c.visibility != View.VISIBLE || c.bottom < 0 || c.top > group.height) continue
            c.animate().cancel()
            c.translationX = fromX; c.alpha = 0f
            c.animate().translationX(0f).alpha(1f).setStartDelay(k++ * step).setDuration(Metro.DUR_NORMAL + 100).setInterpolator(DECELERATE)
                .withEndAction { c.animate().startDelay = 0 }
        }
    }

    /**
     * Turnstile feather-out for list rows: each row swings away about the screen's left edge,
     * top rows first, [last] (the tapped row) last. Calls [done] when finished.
     */
    fun turnstileOut(group: ViewGroup, last: View?, done: () -> Unit) {
        val rows = (0 until group.childCount).map { group.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE && it.bottom > 0 && it.top < group.height }
            .sortedWith(compareBy<View> { if (it === last) 1 else 0 }.thenBy { it.top })
        if (rows.isEmpty()) { done(); return }
        val step = 15L
        rows.forEachIndexed { i, v ->
            v.pivotX = -v.left.toFloat(); v.pivotY = v.height / 2f
            v.cameraDistance = 12000 * v.resources.displayMetrics.density
            val delay = if (v === last) rows.size * step + 30 else i * step
            v.animate().rotationY(-80f).alpha(0f).setStartDelay(delay).setDuration(Metro.DUR_NORMAL).setInterpolator(ACCELERATE)
        }
        group.postDelayed({
            rows.forEach { it.animate().cancel(); it.rotationY = 0f; it.alpha = 1f; it.animate().startDelay = 0 }
            done()
        }, rows.size * step + 30 + Metro.DUR_NORMAL)
    }
}
