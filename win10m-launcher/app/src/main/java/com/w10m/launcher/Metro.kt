package com.w10m.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/** Design tokens from research/W10M_START_SPEC.md. All sizes are epx (== dp). */
object Metro {
    // --- Start grid -------------------------------------------------------
    const val GUTTER = 4f
    const val SIDE_MARGIN = 8f
    const val TOP_MARGIN = 8f
    const val COLUMNS_DEFAULT = 6
    const val COLUMNS_MORE = 8

    // --- Tile content -----------------------------------------------------
    const val TILE_LABEL_SP = 12f
    const val TILE_LABEL_LEFT = 8f
    const val TILE_LABEL_BOTTOM = 6f
    const val ICON_RATIO_MEDIUM = 0.43f
    const val ICON_RATIO_SMALL = 0.5f

    // --- Motion -----------------------------------------------------------
    const val DUR_FAST = 167L
    const val DUR_NORMAL = 250L
    const val DUR_SLOW = 333L
    const val TILT_MAX_DEG = 10f
    const val TILT_SCALE = 0.97f
    const val EDIT_OTHERS_SCALE = 0.9f
    const val EDIT_OTHERS_ALPHA = 0.6f

    // --- App list ---------------------------------------------------------
    const val PAGE_MARGIN = 12f
    const val LIST_ROW_H = 52f
    const val LIST_ICON = 40f
    const val LIST_HEADER_H = 48f

    // --- Accent palette (Windows 10 / W10M, 48 colours) ----------------------
    val ACCENTS = intArrayOf(
        0xFFB900, 0xFF8C00, 0xF7630C, 0xCA5010, 0xDA3B01, 0xEF6950,
        0xD13438, 0xFF4343, 0xE74856, 0xE81123, 0xEA005E, 0xC30052,
        0xE3008C, 0xBF0077, 0xC239B3, 0x9A0089, 0x0078D7, 0x0063B1,
        0x8E8CD8, 0x6B69D6, 0x8764B8, 0x744DA9, 0xB146C2, 0x881798,
        0x0099BC, 0x2D7D9A, 0x00B7C3, 0x038387, 0x00B294, 0x018574,
        0x00CC6A, 0x10893E, 0x7A7574, 0x5D5A58, 0x68768A, 0x515C6B,
        0x567C73, 0x486860, 0x498205, 0x107C10, 0x767676, 0x4C4A48,
        0x69797E, 0x4A5459, 0x647C64, 0x525E54, 0x847545, 0x7E735F,
    ).map { it or 0xFF000000.toInt() }.toIntArray()
    val DEFAULT_ACCENT = 0xFF0078D7.toInt()

    // --- Fonts --------------------------------------------------------------
    private var fonts: Array<Typeface>? = null
    fun light(c: Context) = font(c, 0)
    fun semilight(c: Context) = font(c, 1)
    fun regular(c: Context) = font(c, 2)
    fun semibold(c: Context) = font(c, 3)
    private fun font(c: Context, i: Int): Typeface {
        val f = fonts ?: arrayOf(
            R.font.selawik_light, R.font.selawik_semilight,
            R.font.selawik_regular, R.font.selawik_semibold,
        ).map { ResourcesCompat.getFont(c, it) ?: Typeface.DEFAULT }.toTypedArray().also { fonts = it }
        return f[i]
    }
}

/** Theme brushes, dark or light. */
class Palette(val dark: Boolean, val accent: Int) {
    val bg = if (dark) Color.BLACK else Color.WHITE
    val fg = if (dark) Color.WHITE else Color.BLACK
    val fgMedium = if (dark) 0x99FFFFFF.toInt() else 0x99000000.toInt()
    val fgLow = if (dark) 0x4CFFFFFF else 0x4C000000
    val chromeMedium = if (dark) 0xFF1F1F1F.toInt() else 0xFFE6E6E6.toInt()
    val chromeHigh = if (dark) 0xFF767676.toInt() else 0xFFCCCCCC.toInt()
    val flyout = if (dark) 0xFF2B2B2B.toInt() else 0xFFF2F2F2.toInt()
    val inputBorder = if (dark) 0x66FFFFFF else 0x66000000
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = Math.round(v * resources.displayMetrics.density)
fun Context.sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
