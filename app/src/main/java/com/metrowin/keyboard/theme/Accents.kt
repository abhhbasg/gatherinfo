package com.metrowin.keyboard.theme

/** The 20 accent colours of Windows Phone 8, in the order of its colour picker; the same list and order as MetroWin Launcher. */
object Accents {
    val metro: IntArray = intArrayOf(
        0xFFA4C400.toInt(), 0xFF60A917.toInt(), 0xFF008A00.toInt(), 0xFF00ABA9.toInt(), 0xFF1BA1E2.toInt(),
        0xFF0050EF.toInt(), 0xFF6A00FF.toInt(), 0xFFAA00FF.toInt(), 0xFFF472D0.toInt(), 0xFFD80073.toInt(),
        0xFFA20025.toInt(), 0xFFE51400.toInt(), 0xFFFA6800.toInt(), 0xFFF0A30A.toInt(), 0xFFD8C100.toInt(),
        0xFF825A2C.toInt(), 0xFF6D8764.toInt(), 0xFF647687.toInt(), 0xFF76608A.toInt(), 0xFF7A3B3F.toInt(),
    )
    const val DEFAULT_INDEX = 4 // cyan, the default of Windows Phone and of the launcher
    val names = listOf(
        "lime", "green", "emerald", "teal", "cyan", "cobalt", "indigo", "violet", "pink", "magenta",
        "crimson", "red", "orange", "amber", "yellow", "brown", "olive", "steel", "mauve", "sienna",
    )
}
