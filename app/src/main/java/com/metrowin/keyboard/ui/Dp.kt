package com.metrowin.keyboard.ui

import android.content.Context
import android.view.View

fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun View.dp(v: Float) = context.dp(v)
fun View.dp(v: Int) = context.dp(v.toFloat())
